package app.chattyx.automation;

import app.chattyx.connections.ChannelRegistry;
import app.chattyx.conversations.ConversationService;
import app.chattyx.generation.ReplyModel;
import app.chattyx.media.MediaService;
import app.chattyx.memory.MemoryService;
import app.chattyx.personas.SettingsService;
import app.chattyx.shared.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AutomationWorker {

  private final Db db;
  private final SettingsService settings;
  private final ReplyModel model;
  private final MemoryService memory;
  private final MediaService media;
  private final ConversationService chats;
  private final ChannelRegistry channels;
  private final Clock clock;
  private final Events events;
  private final TransactionTemplate tx;
  private final boolean enabled;
  private final Semaphore slots = new Semaphore(10);
  private final Set<UUID> active = ConcurrentHashMap.newKeySet();

  public AutomationWorker(
    Db db,
    SettingsService settings,
    ReplyModel model,
    MemoryService memory,
    MediaService media,
    ConversationService chats,
    ChannelRegistry channels,
    Clock clock,
    Events events,
    org.springframework.transaction.PlatformTransactionManager tm,
    @Value("${chatty.workers-enabled}") boolean enabled
  ) {
    this.db = db;
    this.settings = settings;
    this.model = model;
    this.memory = memory;
    this.media = media;
    this.chats = chats;
    this.channels = channels;
    this.clock = clock;
    this.events = events;
    this.tx = new TransactionTemplate(tm);
    this.enabled = enabled;
  }

  @Scheduled(fixedDelay = 500)
  public void tick() {
    if (!enabled || !settings.read("global").path("enabled").asBoolean()) return;
    for (UUID chat : db.jdbc.queryForList(
      "SELECT conversation_id FROM automation_job WHERE state='WAITING' AND due_at<=? ORDER BY due_at LIMIT 10",
      UUID.class,
      Timestamp.from(clock.instant())
    )) {
      if (!slots.tryAcquire()) return;
      if (!active.add(chat)) {
        slots.release();
        continue;
      }
      Thread.ofVirtual()
        .name("reply-worker")
        .start(() -> {
          try {
            runOne(chat);
          } finally {
            active.remove(chat);
            slots.release();
          }
        });
    }
  }

  public void runOne(UUID chat) {
    UUID token = UUID.randomUUID();
    var snapshot = tx.execute(s -> {
      var c = db.one(
        "SELECT c.*,n.status AS connection_status,n.enabled AS connection_enabled FROM conversation c JOIN connection n ON n.id=c.connection_id WHERE c.id=? FOR UPDATE OF c",
        chat
      );
      var jobs = db.list(
        "SELECT * FROM automation_job WHERE conversation_id=? AND state='WAITING' AND due_at<=? FOR UPDATE",
        chat,
        Timestamp.from(clock.instant())
      );
      if (jobs.isEmpty()) return null;
      long version = ((Number) c.get("version")).longValue();
      if (
        !BurstPolicy.current(
          ((Number) jobs.getFirst().get("contextVersion")).longValue(),
          version,
          c.get("mode").toString(),
          Boolean.TRUE.equals(c.get("selected")),
          Boolean.TRUE.equals(c.get("imported")),
          settings.read("global").path("enabled").asBoolean(),
          c.get("connectionStatus").equals("READY") && Boolean.TRUE.equals(c.get("connectionEnabled"))
        )
      ) return null;
      if (!settings.allowedNow(settings.effective(chat), clock.instant())) return null;
      var stale = db.jdbc.queryForObject(
        "SELECT count(*) FROM message WHERE conversation_id=? AND NOT handled AND NOT deleted AND sent_at<?",
        Integer.class,
        chat,
        Timestamp.from(clock.instant().minusSeconds(3600))
      );
      if (stale > 0) {
        chats.attention(chat, "OLD_INCOMING");
        return null;
      }
      if (
        db.jdbc.queryForObject(
          "SELECT count(*) FROM message WHERE conversation_id=? AND NOT handled AND NOT deleted",
          Integer.class,
          chat
        ) == 0
      ) {
        db.jdbc.update("DELETE FROM automation_job WHERE conversation_id=?", chat);
        return null;
      }
      db.jdbc.update(
        "UPDATE automation_job SET state='GENERATING',lease_token=?,lease_until=?,attempts=attempts+1 WHERE conversation_id=?",
        token,
        Timestamp.from(clock.instant().plusSeconds(120)),
        chat
      );
      db.jdbc.update("UPDATE conversation SET status='GENERATING' WHERE id=?", chat);
      return c;
    });
    if (snapshot == null) return;
    events.changed();
    UUID connection = UUID.fromString(snapshot.get("connectionId").toString());
    String external = snapshot.get("externalId").toString();
    try {
      media.prepare(chat);
      var config = settings.effective(chat);
      var messages = contextMessages(chat);
      if (messages.isEmpty()) throw new ApiException(409, "NO_USABLE_CONTEXT");
      long included = messages
        .stream()
        .filter(m -> Boolean.TRUE.equals(m.get("new")))
        .count();
      int pending = db.jdbc.queryForObject(
        "SELECT count(*) FROM message WHERE conversation_id=? AND NOT handled AND NOT deleted",
        Integer.class,
        chat
      );
      if (included < pending) throw new ApiException(409, "CONTEXT_LIMIT_REQUIRES_OWNER");
      var adapter = channels.forConnection(connection);
      var latest = db.jdbc.queryForList(
        "SELECT external_id FROM message WHERE conversation_id=? AND direction='IN' AND NOT handled AND NOT deleted ORDER BY sent_at DESC LIMIT 1",
        String.class,
        chat
      );
      if (!latest.isEmpty()) adapter.markRead(connection, external, latest.getFirst());
      adapter.setTyping(connection, external, true);
      var reply = model.generate(
        new ReplyModel.Context(
          chat,
          config,
          snapshot.get("summary").toString(),
          memory.list(chat),
          messages,
          media.images(chat)
        )
      );
      tx.executeWithoutResult(s -> {
        var current = db.one("SELECT version,mode FROM conversation WHERE id=? FOR UPDATE", chat);
        var jobs = db.jdbc.queryForList(
          "SELECT lease_token FROM automation_job WHERE conversation_id=?",
          UUID.class,
          chat
        );
        if (
          jobs.isEmpty() ||
          !token.equals(jobs.getFirst()) ||
          !current.get("version").equals(snapshot.get("version")) ||
          !current.get("mode").equals("AUTO")
        ) return;
        if (!reply.action().equals("skip")) {
          UUID out = UUID.randomUUID();
          int low = Math.max(0, config.path("delayMinSeconds").asInt(2)),
            high = Math.max(low, config.path("delayMaxSeconds").asInt(6));
          int delay = ThreadLocalRandom.current().nextInt(low, high + 1);
          db.jdbc.update(
            "INSERT INTO outbound(id,conversation_id,request_key,context_version,body,source,status,due_at,reason) VALUES (?,?,?,?,?,'AUTOMATION',?,?,?)",
            out,
            chat,
            "generation:" + token,
            ((Number) snapshot.get("version")).longValue(),
            reply.text(),
            reply.action().equals("handoff") ? "DRAFT" : "READY",
            Timestamp.from(clock.instant().plusSeconds(delay)),
            reply.reason()
          );
        }
        db.jdbc.update("UPDATE message SET handled=true WHERE conversation_id=? AND NOT handled", chat);
        db.jdbc.update("DELETE FROM automation_job WHERE conversation_id=? AND lease_token=?", chat, token);
        if (reply.action().equals("handoff")) db.jdbc.update(
          "UPDATE conversation SET mode='PAUSED',status='ATTENTION',needs_attention='DRAFT_READY' WHERE id=?",
          chat
        );
        else db.jdbc.update(
          "UPDATE conversation SET status=? WHERE id=?",
          reply.action().equals("skip") ? "IDLE" : "READY",
          chat
        );
      });
    } catch (Exception e) {
      tx.executeWithoutResult(s -> {
        db.one("SELECT id FROM conversation WHERE id=? FOR UPDATE", chat);
        if (
          db.jdbc.queryForObject(
            "SELECT count(*) FROM automation_job WHERE conversation_id=? AND lease_token=?",
            Integer.class,
            chat,
            token
          ) > 0
        ) {
          if (e instanceof ApiException a && a.code().equals("MODEL_BUSY")) {
            db.jdbc.update("UPDATE automation_job SET state='WAITING',lease_token=NULL,lease_until=NULL,due_at=? WHERE conversation_id=? AND lease_token=?",
                Timestamp.from(clock.instant().plusSeconds(3)), chat, token);
            db.jdbc.update("UPDATE conversation SET status='WAITING' WHERE id=?", chat);
          } else chats.attention(chat, e instanceof ApiException a ? a.code() : "GENERATION_FAILED");
        }
      });
    } finally {
      try {
        channels.forConnection(connection).setTyping(connection, external, false);
      } catch (Exception ignored) {}
      events.changed();
    }
  }

  public List<Map<String, Object>> contextMessages(UUID chat) {
    var rows = db.list(
      "SELECT m.id::text AS id,m.source,m.body,m.sent_at,m.handled,COALESCE((SELECT string_agg(transcript,' ') FROM attachment a WHERE a.message_id=m.id AND a.status='READY'),'') AS transcript FROM message m WHERE m.conversation_id=? AND NOT m.deleted AND NOT m.ephemeral AND NOT EXISTS (SELECT 1 FROM memory_tombstone t WHERE t.source_id=m.id AND t.conversation_id=m.conversation_id) ORDER BY m.sent_at DESC LIMIT 50",
      chat
    );
    List<Map<String, Object>> result = new ArrayList<>();
    int chars = 0;
    for (var row : rows) {
      String body = row.get("body").toString();
      String transcript = row.get("transcript").toString();
      String text = body + (transcript.isBlank() ? "" : "\n[Voice transcript] " + transcript);
      if (text.length() > 6000) text = text.substring(0, 6000);
      if (chars + text.length() > 24000) break;
      chars += text.length();
      result.add(
        Map.of(
          "id",
          row.get("id"),
          "source",
          row.get("source"),
          "text",
          text,
          "time",
          row.get("sentAt"),
          "new",
          !Boolean.TRUE.equals(row.get("handled"))
        )
      );
    }
    Collections.reverse(result);
    return result;
  }
}

package app.chattyx.conversations;

import app.chattyx.connections.MessagingAdapter.*;
import app.chattyx.personas.SettingsService;
import app.chattyx.shared.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationService {

  private final Db db;
  private final SettingsService settings;
  private final Clock clock;
  private final Events events;

  public ConversationService(Db db, SettingsService settings, Clock clock, Events events) {
    this.db = db;
    this.settings = settings;
    this.clock = clock;
    this.events = events;
  }

  public Map<String, Object> get(UUID id) {
    return db.one("SELECT c.*,n.status AS connection_status,n.enabled AS connection_enabled FROM conversation c JOIN connection n ON n.id=c.connection_id WHERE c.id=?", id);
  }

  public List<Map<String, Object>> list() {
    return db.list(
      "SELECT c.*,n.name AS account_name,n.adapter,n.status AS connection_status,n.enabled AS connection_enabled, (SELECT body FROM message WHERE conversation_id=c.id AND NOT deleted ORDER BY sent_at DESC LIMIT 1) AS preview FROM conversation c JOIN connection n ON n.id=c.connection_id ORDER BY c.last_activity DESC"
    );
  }

  public List<Map<String, Object>> messages(UUID id, String cursor, String search) {
    get(id);
    Timestamp before = Timestamp.from(Instant.parse("9999-01-01T00:00:00Z"));
    UUID beforeId = new UUID(-1L,-1L);
    if(cursor!=null && !cursor.isBlank()) {
      try {
        String[] parts = new String(Base64.getUrlDecoder().decode(cursor),java.nio.charset.StandardCharsets.UTF_8).split("\\|",-1);
        if(parts.length!=2)throw new IllegalArgumentException();
        before=Timestamp.from(Instant.parse(parts[0]));beforeId=UUID.fromString(parts[1]);
      } catch(IllegalArgumentException error){throw new ApiException(400,"INVALID_CURSOR");}
    }
    return db.list(
      "SELECT m.*,COALESCE((SELECT jsonb_agg(jsonb_build_object('id',a.id,'kind',a.kind,'status',a.status,'transcript',a.transcript,'mimeType',a.mime_type)) FROM attachment a WHERE a.message_id=m.id),'[]'::jsonb) AS attachments FROM message m WHERE conversation_id=? AND NOT deleted AND (?='' OR to_tsvector('simple',body) @@ plainto_tsquery('simple',?)) AND (sent_at,id)<(?,?) ORDER BY sent_at DESC,id DESC LIMIT 50",
      id,
      search,
      search,
      before,
      beforeId
    );
  }

  @Transactional
  public void discover(Chat event) {
    if (!event.eligible()) return;
    db.jdbc.update(
      "INSERT INTO conversation(id,connection_id,external_id,title) VALUES (?,?,?,?) ON CONFLICT(connection_id,external_id) DO UPDATE SET title=excluded.title",
      UUID.randomUUID(),
      event.connectionId(),
      event.chatId(),
      event.title()
    );
    events.changed();
  }

  @Transactional
  public void receive(Incoming message) {
    var rows = db.jdbc.queryForList(
      "SELECT id FROM conversation WHERE connection_id=? AND external_id=?",
      UUID.class,
      message.connectionId(),
      message.chatId()
    );
    if (rows.isEmpty()) return;
    UUID id = rows.getFirst();
    var chat = db.one("SELECT * FROM conversation WHERE id=? FOR UPDATE", id);
    if (!Boolean.TRUE.equals(chat.get("selected"))) return;
    boolean owned = false;
    if (message.outgoing()) {
      var own = db.jdbc.queryForList(
        "SELECT id FROM outbound WHERE conversation_id=? AND ((external_id=? OR temporary_id=?) OR (sending_id=? AND status IN ('SENDING','SUBMITTED'))) LIMIT 1",
        UUID.class,
        id,
        message.messageId(),
        message.messageId(),
        message.sendingId()
      );
      owned = !own.isEmpty();
      if (owned && message.sendingId() != null) db.jdbc.update(
        "UPDATE outbound SET temporary_id=COALESCE(temporary_id,?) WHERE id=?",
        message.messageId(),
        own.getFirst()
      );
    }
    UUID mid = UUID.randomUUID();
    String source = message.outgoing() ? (owned ? "AUTOMATION" : "TELEGRAM_OWNER") : "CONTACT";
    int inserted = db.jdbc.update(
      "INSERT INTO message(id,conversation_id,external_id,direction,source,kind,body,sent_at,historical,handled,ephemeral,reply_to) VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(conversation_id,external_id) DO NOTHING",
      mid,
      id,
      message.messageId(),
      message.outgoing() ? "OUT" : "IN",
      source,
      message.media() == null ? "TEXT" : message.media().kind(),
      message.text(),
      Timestamp.from(message.time()),
      message.historical(),
      message.historical() || message.outgoing(),
      message.ephemeral(),
      message.replyTo()
    );
    if (inserted == 0) {
      // A live event can arrive after its history page. Promote it only inside this
      // import window; replaying older history must never reopen an old question.
      if (message.historical() || chat.get("importRunId") == null || chat.get("importStartedAt") == null ||
          message.time().isBefore(Instant.parse(chat.get("importStartedAt").toString()))) return;
      int promoted = db.jdbc.update(
          "UPDATE message SET historical=false,handled=? WHERE conversation_id=? AND external_id=? AND historical AND NOT deleted",
          message.outgoing(), id, message.messageId());
      if (promoted == 0) return;
      db.jdbc.update("UPDATE attachment SET status='PENDING' WHERE status='HISTORICAL' AND message_id IN (SELECT id FROM message WHERE conversation_id=? AND external_id=?)", id, message.messageId());
    }
    if (inserted > 0 && message.media() != null) {
      var a = message.media();
      db.jdbc.update(
        "INSERT INTO attachment(id,message_id,external_file_id,kind,mime_type,size_bytes,duration_seconds,status) VALUES (?,?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        mid,
        a.fileId(),
        a.kind(),
        a.mime(),
        a.size(),
        a.duration(),
        message.historical() ? "HISTORICAL" : "PENDING"
      );
    }
    db.jdbc.update(
      "UPDATE conversation SET last_activity=GREATEST(last_activity,?) WHERE id=?",
      Timestamp.from(message.time()),
      id
    );
    if (message.historical()) return;
    if (message.outgoing()) {
      if (!owned) pause(id, "MANUAL_TAKEOVER");
      events.changed();
      return;
    }
    invalidate(id);
    if (message.ephemeral()) {
      attention(id, "EPHEMERAL_MESSAGE");
      return;
    }
    if (message.time().isBefore(clock.instant().minusSeconds(3600))) {
      attention(id, "OLD_INCOMING");
      return;
    }
    if (!chat.get("mode").equals("AUTO")) return;
    schedule(id);
    events.changed();
  }

  public void invalidate(UUID id) {
    db.jdbc.update("UPDATE conversation SET version=version+1 WHERE id=?", id);
    db.jdbc.update(
      "UPDATE automation_job SET context_version=(SELECT version FROM conversation WHERE id=?),state='WAITING',lease_token=NULL,lease_until=NULL WHERE conversation_id=?",
      id,
      id
    );
    db.jdbc.update(
      "UPDATE outbound SET status=CASE WHEN status='DRAFT' THEN 'STALE' ELSE 'CANCELLED' END WHERE conversation_id=? AND status IN ('READY','DRAFT') AND source='AUTOMATION'",
      id
    );
  }

  public void schedule(UUID id) {
    var cfg = settings.effective(id);
    Instant now = clock.instant();
    int quiet = Math.max(1, cfg.path("quietSeconds").asInt(8)),
      max = Math.max(quiet, cfg.path("maxBurstSeconds").asInt(30));
    long version = db.jdbc.queryForObject("SELECT version FROM conversation WHERE id=?", Long.class, id);
    db.jdbc.update(
      "INSERT INTO automation_job(conversation_id,context_version,first_at,due_at) VALUES (?,?,?,?) ON CONFLICT(conversation_id) DO UPDATE SET context_version=excluded.context_version,due_at=LEAST(excluded.due_at,automation_job.first_at+(? * interval '1 second')),state='WAITING',lease_token=NULL,lease_until=NULL",
      id,
      version,
      Timestamp.from(now),
      Timestamp.from(now.plusSeconds(quiet)),
      max
    );
    db.jdbc.update("UPDATE conversation SET status='WAITING',needs_attention=NULL WHERE id=?", id);
  }

  @Transactional
  public void control(UUID id, String action) {
    db.one("SELECT id FROM conversation WHERE id=? FOR UPDATE", id);
    switch (action) {
      case "pause" -> pause(id, "OWNER_PAUSED");
      case "resume" -> {
        var row = get(id);
        if (
          !Boolean.TRUE.equals(row.get("selected")) || !Boolean.TRUE.equals(row.get("imported"))
        ) throw new ApiException(409, "IMPORT_REQUIRED");
        invalidate(id);
        db.jdbc.update(
          "UPDATE conversation SET mode='AUTO',status='IDLE',needs_attention=NULL WHERE id=?",
          id
        );
        db.jdbc.update("UPDATE message SET handled=true WHERE conversation_id=? AND NOT handled", id);
        db.jdbc.update("DELETE FROM automation_job WHERE conversation_id=?", id);
      }
      default -> throw new ApiException(400, "INVALID_ACTION");
    }
    events.changed();
  }

  public void pause(UUID id, String reason) {
    invalidate(id);
    db.jdbc.update(
      "UPDATE conversation SET mode='PAUSED',status='PAUSED',needs_attention=? WHERE id=?",
      reason,
      id
    );
    db.jdbc.update("DELETE FROM automation_job WHERE conversation_id=?", id);
  }

  public void attention(UUID id, String reason) {
    pause(id, reason);
    db.jdbc.update("UPDATE conversation SET status='ATTENTION' WHERE id=?", id);
    events.changed();
  }

  @Transactional
  public void edit(UUID connection, String chat, String message, String body) {
    var rows = db.jdbc.queryForList(
      "SELECT c.id FROM conversation c JOIN message m ON m.conversation_id=c.id WHERE c.connection_id=? AND c.external_id=? AND m.external_id=?",
      UUID.class,
      connection,
      chat,
      message
    );
    if (rows.isEmpty()) return;
    UUID id = rows.getFirst();
    db.one("SELECT id FROM conversation WHERE id=? FOR UPDATE", id);
    db.jdbc.update(
      "UPDATE message SET body=?,revision=revision+1 WHERE conversation_id=? AND external_id=?",
      body,
      id,
      message
    );
    invalidateSources(id, message);
    invalidate(id);
    if (
      get(id).get("mode").equals("AUTO") &&
      db.jdbc.queryForObject(
        "SELECT count(*) FROM message WHERE conversation_id=? AND NOT handled AND NOT deleted",
        Integer.class,
        id
      ) > 0
    ) schedule(id);
    events.changed();
  }

  @Transactional
  public void delete(UUID connection, String chat, List<String> messages) {
    for (String external : messages) {
      var rows = db.jdbc.queryForList(
        "SELECT id FROM conversation WHERE connection_id=? AND external_id=?",
        UUID.class,
        connection,
        chat
      );
      if (rows.isEmpty()) continue;
      UUID id = rows.getFirst();
      db.one("SELECT id FROM conversation WHERE id=? FOR UPDATE", id);
      invalidateSources(id, external);
      db.jdbc.update(
        "UPDATE message SET deleted=true,body='',handled=true WHERE conversation_id=? AND external_id=?",
        id,
        external
      );
      db.jdbc.update(
        "UPDATE attachment SET status='DELETED',transcript=NULL WHERE message_id IN (SELECT id FROM message WHERE conversation_id=? AND external_id=?)",
        id,
        external
      );
      invalidate(id);
      if (get(id).get("mode").equals("AUTO")) schedule(id);
    }
    events.changed();
  }

  private void invalidateSources(UUID id, String external) {
    db.jdbc.update(
      "DELETE FROM memory_fact WHERE NOT pinned AND id IN (SELECT f.fact_id FROM fact_source f JOIN message m ON m.id=f.message_id WHERE m.conversation_id=? AND m.external_id=?)",
      id,
      external
    );
    db.jdbc.update(
      "DELETE FROM fact_source WHERE message_id IN (SELECT id FROM message WHERE conversation_id=? AND external_id=?)",
      id,
      external
    );
    db.jdbc.update("UPDATE conversation SET summary='',summary_version=summary_version+1 WHERE id=?", id);
  }
}

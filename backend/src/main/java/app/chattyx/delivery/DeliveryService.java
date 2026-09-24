package app.chattyx.delivery;

import app.chattyx.connections.*;
import app.chattyx.conversations.ConversationService;
import app.chattyx.personas.SettingsService;
import app.chattyx.operations.BudgetService;
import app.chattyx.shared.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DeliveryService {

  private final Db db;
  private final ConversationService chats;
  private final SettingsService settings;
  private final ChannelRegistry channels;
  private final Clock clock;
  private final Events events;
  private final TransactionTemplate tx;
  private final boolean enabled;
  private final AtomicBoolean busy = new AtomicBoolean();
  private final BudgetService budget;

  public DeliveryService(
    Db db,
    ConversationService chats,
    SettingsService settings,
    ChannelRegistry channels,
    Clock clock,
    Events events,
    BudgetService budget,
    org.springframework.transaction.PlatformTransactionManager tm,
    @Value("${chatty.workers-enabled}") boolean enabled
  ) {
    this.db = db;
    this.chats = chats;
    this.settings = settings;
    this.channels = channels;
    this.clock = clock;
    this.events = events;
    this.budget = budget;
    tx = new TransactionTemplate(tm);
    this.enabled = enabled;
  }

  public Map<String, Object> manual(UUID chat, String text, String key) {
    if (
      text == null || text.isBlank() || text.length() > 4000 || key == null || key.length() > 100
    ) throw new ApiException(400, "INVALID_MESSAGE");
    return tx.execute(s -> {
      db.one("SELECT id FROM conversation WHERE id=? FOR UPDATE", chat);
      var existing = db.list("SELECT * FROM outbound WHERE request_key=?", key);
      if (!existing.isEmpty()) {
        var row = existing.getFirst();
        if (
          !row.get("conversationId").equals(chat.toString()) || !row.get("body").equals(text)
        ) throw new ApiException(409, "IDEMPOTENCY_KEY_CONFLICT");
        return row;
      }
      chats.pause(chat, "MANUAL_TAKEOVER");
      long version = ((Number) chats.get(chat).get("version")).longValue();
      UUID id = UUID.randomUUID();
      db.jdbc.update(
        "INSERT INTO outbound(id,conversation_id,request_key,context_version,body,source,status,due_at) VALUES (?,?,?,?,?,'WEB_OWNER','READY',?)",
        id,
        chat,
        key,
        version,
        text,
        Timestamp.from(clock.instant())
      );
      events.changed();
      return db.one("SELECT * FROM outbound WHERE id=?", id);
    });
  }

  public void draft(UUID chat, UUID id, String text, long version, String key) {
    tx.executeWithoutResult(s -> {
      db.one("SELECT id FROM conversation WHERE id=? FOR UPDATE", chat);
      var row = db.one(
        "SELECT * FROM outbound WHERE id=? AND conversation_id=? AND status IN ('DRAFT','STALE')",
        id,
        chat
      );
      if (((Number) chats.get(chat).get("version")).longValue() != version) throw new ApiException(
        409,
        "CONTEXT_CHANGED"
      );
      manual(chat, text, key);
      db.jdbc.update("UPDATE outbound SET status='CANCELLED' WHERE id=?", id);
    });
  }

  @Scheduled(fixedDelay = 500)
  public void tick() {
    if (!enabled || !busy.compareAndSet(false, true)) return;
    try {
      for (var row : db.list(
        "SELECT id,conversation_id FROM outbound WHERE status='READY' AND due_at<=? ORDER BY due_at LIMIT 10",
        Timestamp.from(clock.instant())
      ))
        send(
          UUID.fromString(row.get("id").toString()),
          UUID.fromString(row.get("conversationId").toString())
        );
    } finally {
      busy.set(false);
    }
  }

  public void send(UUID id, UUID chat) {
    var claimed = tx.execute(s -> {
      var c = db.one(
        "SELECT c.*,n.status AS connection_status,n.enabled AS connection_enabled FROM conversation c JOIN connection n ON n.id=c.connection_id WHERE c.id=? FOR UPDATE OF c",
        chat
      );
      var rows = db.list("SELECT * FROM outbound WHERE id=? AND status='READY' AND due_at<=? FOR UPDATE", id, Timestamp.from(clock.instant()));
      if (rows.isEmpty()) return null;
      var out = rows.getFirst();
      boolean auto = out.get("source").equals("AUTOMATION");
      boolean current =
        ((Number) out.get("contextVersion")).longValue() == ((Number) c.get("version")).longValue();
      if (
        auto &&
        (!current ||
          !c.get("mode").equals("AUTO") ||
          !Boolean.TRUE.equals(c.get("selected")) ||
          !Boolean.TRUE.equals(c.get("imported")) ||
          !settings.read("global").path("enabled").asBoolean() ||
          !Boolean.TRUE.equals(c.get("connectionEnabled")))
      ) {
        db.jdbc.update("UPDATE outbound SET status='CANCELLED' WHERE id=?", id);
        return null;
      }
      if (!c.get("connectionStatus").equals("READY")) return null;
      if(auto && budget.used().compareTo(new java.math.BigDecimal(settings.read("global").path("monthlyBudgetUsd").asText("30")))>0){chats.attention(chat,"BUDGET_EXHAUSTED");return null;}
      if (auto && !settings.allowedNow(settings.effective(chat), clock.instant())) return null;
      int sendingId = java.util.concurrent.ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
      db.jdbc.update(
        "UPDATE outbound SET status='SENDING',sending_id=?,attempts=attempts+1,updated_at=? WHERE id=?",
        sendingId,
        Timestamp.from(clock.instant()),
        id
      );
      out.put("sendingId", sendingId);
      out.put("connectionId", c.get("connectionId"));
      out.put("chatId", c.get("externalId"));
      return out;
    });
    if (claimed == null) return;
    try {
      UUID connection = UUID.fromString(claimed.get("connectionId").toString());
      var receipt = channels
        .forConnection(connection)
        .sendText(
          new MessagingAdapter.Outgoing(
            id,
            connection,
            claimed.get("chatId").toString(),
            claimed.get("body").toString(),
            ((Number) claimed.get("sendingId")).intValue()
          )
        );
      tx.executeWithoutResult(s -> {
        db.one("SELECT id FROM conversation WHERE id=? FOR UPDATE", chat);
        db.jdbc.update(
          "UPDATE outbound SET temporary_id=?,external_id=COALESCE(external_id,?),status=CASE WHEN status IN ('SENT','FAILED') THEN status ELSE ? END,updated_at=? WHERE id=?",
          receipt.temporaryId(),
          receipt.finalId(),
          receipt.confirmed() ? "SENT" : "SUBMITTED",
          Timestamp.from(clock.instant()),
          id
        );
        if (receipt.confirmed()) recordSent(id, receipt.finalId());
        for (var pending : db.list(
          "SELECT * FROM delivery_receipt WHERE connection_id=? AND chat_id=? AND temporary_id=?",
          connection,
          claimed.get("chatId"),
          receipt.temporaryId()
        ))
          confirmation(
            new MessagingAdapter.Delivered(
              connection,
              claimed.get("chatId").toString(),
              receipt.temporaryId(),
              pending.get("messageId").toString(),
              Boolean.TRUE.equals(pending.get("success")),
              pending.get("category").toString()
            )
          );
      });
    } catch (RateLimited e) {
      tx.executeWithoutResult(s -> {
        db.one("SELECT id FROM conversation WHERE id=? FOR UPDATE", chat);
        int attempts = db.jdbc.queryForObject("SELECT attempts FROM outbound WHERE id=?", Integer.class, id);
        if (attempts < 3) db.jdbc.update(
          "UPDATE outbound SET status='READY',reason='TELEGRAM_RATE_LIMIT',due_at=? WHERE id=? AND status='SENDING'",
          Timestamp.from(clock.instant().plusSeconds(e.retryAfterSeconds())),
          id
        );
        else {
          db.jdbc.update(
            "UPDATE outbound SET status='FAILED',reason='TELEGRAM_RATE_LIMIT' WHERE id=? AND status='SENDING'",
            id
          );
          chats.attention(chat, "TELEGRAM_RATE_LIMIT");
        }
      });
    } catch (ApiException e) {
      if (e.code().equals("TELEGRAM_REQUEST_REJECTED")) {
        db.jdbc.update(
          "UPDATE outbound SET status='FAILED',reason=? WHERE id=? AND status<>'SENT'",
          e.code(),
          id
        );
      } else if (e.code().equals("TELEGRAM_RATE_LIMIT")) {
        // Do not invent a FloodWait duration; require explicit recovery until adapter provides it.
        db.jdbc.update(
          "UPDATE outbound SET status='FAILED',reason='TELEGRAM_RATE_LIMIT' WHERE id=? AND status<>'SENT'",
          id
        );
      } else db.jdbc.update(
        "UPDATE outbound SET status='UNKNOWN',reason='DELIVERY_UNCONFIRMED' WHERE id=? AND status<>'SENT'",
        id
      );
      tx.executeWithoutResult(s -> chats.attention(chat, "DELIVERY_REQUIRES_REVIEW"));
    } catch (Exception e) {
      db.jdbc.update(
        "UPDATE outbound SET status='UNKNOWN',reason='DELIVERY_UNCONFIRMED' WHERE id=? AND status<>'SENT'",
        id
      );
      tx.executeWithoutResult(s -> chats.attention(chat, "DELIVERY_REQUIRES_REVIEW"));
    }
    events.changed();
  }

  public void confirmation(MessagingAdapter.Delivered result) {
    tx.executeWithoutResult(s -> {
      db.jdbc.update(
        "INSERT INTO delivery_receipt(connection_id,chat_id,temporary_id,message_id,success,category) VALUES (?,?,?,?,?,?) ON CONFLICT(connection_id,chat_id,temporary_id) DO UPDATE SET message_id=excluded.message_id,success=excluded.success,category=excluded.category",
        result.connectionId(),
        result.chatId(),
        result.temporaryId(),
        result.messageId(),
        result.success(),
        result.category()
      );
      var rows = db.list(
        "SELECT o.id,o.conversation_id FROM outbound o JOIN conversation c ON c.id=o.conversation_id WHERE c.connection_id=? AND c.external_id=? AND (o.temporary_id=? OR o.external_id=?)",
        result.connectionId(),
        result.chatId(),
        result.temporaryId(),
        result.messageId()
      );
      for (var row : rows) {
        UUID id = UUID.fromString(row.get("id").toString()),
          chat = UUID.fromString(row.get("conversationId").toString());
        db.one("SELECT id FROM conversation WHERE id=? FOR UPDATE", chat);
        db.jdbc.update(
          "UPDATE outbound SET status=?,external_id=?,reason=?,updated_at=now() WHERE id=?",
          result.success() ? "SENT" : "FAILED",
          result.messageId(),
          result.category(),
          id
        );
        if (result.success()) recordSent(id, result.messageId());
        else chats.attention(chat, result.category());
        db.jdbc.update(
          "DELETE FROM delivery_receipt WHERE connection_id=? AND chat_id=? AND temporary_id=?",
          result.connectionId(),
          result.chatId(),
          result.temporaryId()
        );
      }
    });
    events.changed();
  }

  private void recordSent(UUID id, String external) {
    var out = db.one("SELECT * FROM outbound WHERE id=?", id);
    UUID chat = UUID.fromString(out.get("conversationId").toString());
    String temporary = Objects.toString(out.get("temporaryId"), "");
    db.jdbc.update(
      "DELETE FROM message WHERE conversation_id=? AND external_id=? AND external_id<>?",
      chat,
      temporary,
      external
    );
    db.jdbc.update(
      "INSERT INTO message(id,conversation_id,external_id,direction,source,body,sent_at,handled,delivery_status) VALUES (?,?,?,'OUT',?,?,?,true,'SENT') ON CONFLICT(conversation_id,external_id) DO UPDATE SET source=excluded.source,delivery_status='SENT'",
      UUID.randomUUID(),
      chat,
      external,
      out.get("source"),
      out.get("body"),
      Timestamp.from(clock.instant())
    );
    db.jdbc.update(
      "UPDATE conversation SET status=CASE WHEN mode='AUTO' THEN 'IDLE' ELSE 'PAUSED' END,last_activity=? WHERE id=?",
      Timestamp.from(clock.instant()),
      chat
    );
    db.jdbc.update(
      "INSERT INTO memory_job(conversation_id,due_at) VALUES (?,now()+interval '30 seconds') ON CONFLICT(conversation_id) DO UPDATE SET revision=memory_job.revision+1,due_at=excluded.due_at",
      chat
    );
  }

  public void recover() {
    db.jdbc.update(
      "UPDATE outbound SET status='UNKNOWN',reason='PROCESS_RESTARTED' WHERE status IN ('SENDING','SUBMITTED')"
    );
    db.jdbc.update(
      "UPDATE conversation SET mode='PAUSED',status='ATTENTION',needs_attention='DELIVERY_REQUIRES_REVIEW',version=version+1 WHERE id IN (SELECT conversation_id FROM outbound WHERE status='UNKNOWN')"
    );
  }

  public void reconcile(UUID id) {
    var out = db.one(
      "SELECT o.*,c.connection_id,c.external_id AS chat_id FROM outbound o JOIN conversation c ON c.id=o.conversation_id WHERE o.id=? AND o.status='UNKNOWN'",
      id
    );
    UUID connection = UUID.fromString(out.get("connectionId").toString());
    var result = channels
      .forConnection(connection)
      .reconcile(connection, out.get("chatId").toString(), (String) out.get("temporaryId"));
    if (result.isEmpty() || !result.get().confirmed()) throw new ApiException(409, "DELIVERY_STILL_UNKNOWN");
    confirmation(
      new MessagingAdapter.Delivered(
        connection,
        out.get("chatId").toString(),
        (String) out.get("temporaryId"),
        result.get().finalId(),
        true,
        "RECONCILED"
      )
    );
  }
}

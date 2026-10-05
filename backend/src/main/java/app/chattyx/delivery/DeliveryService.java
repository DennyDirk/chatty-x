package app.chattyx.delivery;

import app.chattyx.connections.*;
import app.chattyx.conversations.ConversationService;
import app.chattyx.operations.BudgetService;
import app.chattyx.personas.SettingsService;
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

  public boolean enabled() {
    return enabled;
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
      if (!enabled) throw new ApiException(409, "PROCESSING_DISABLED");
      if (!"READY".equals(chats.get(chat).get("connectionStatus"))) {
        throw new ApiException(409, "TELEGRAM_NOT_CONNECTED");
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
      var rows = db.list(
        "SELECT * FROM outbound WHERE id=? AND status='READY' AND due_at<=? FOR UPDATE",
        id,
        Timestamp.from(clock.instant())
      );
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
      if (
        auto &&
        !settings.localModel() &&
        budget
          .used()
          .compareTo(
            new java.math.BigDecimal(settings.read("global").path("monthlyBudgetUsd").asText("30"))
          ) >= 0
      ) {
        chats.attention(chat, "BUDGET_EXHAUSTED");
        return null;
      }
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
        String previous = db.jdbc.queryForObject("SELECT status FROM outbound WHERE id=?", String.class, id);
        String next = receipt.confirmed()
          ? "SENT"
          : previous.equals("SENT") || previous.equals("FAILED")
            ? previous
            : "SUBMITTED";
        db.jdbc.update(
          "UPDATE outbound SET temporary_id=?,external_id=CASE WHEN ? THEN ? ELSE COALESCE(external_id,?) END,status=?,updated_at=? WHERE id=?",
          receipt.temporaryId(),
          receipt.confirmed(),
          receipt.finalId(),
          receipt.finalId(),
          next,
          Timestamp.from(clock.instant()),
          id
        );
        if (receipt.confirmed() && !previous.equals("SENT")) recordSent(id, receipt.finalId());
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
          int changed = db.jdbc.update(
            "UPDATE outbound SET status='FAILED',reason='TELEGRAM_RATE_LIMIT' WHERE id=? AND status='SENDING'",
            id
          );
          if (changed > 0) chats.attention(chat, "TELEGRAM_RATE_LIMIT");
        }
      });
    } catch (ApiException e) {
      boolean rejected =
        e.code().equals("TELEGRAM_REQUEST_REJECTED") || e.code().equals("TELEGRAM_RATE_LIMIT");
      // A rate-limit error without a server deadline is not safe to retry automatically.
      recordFailure(id, chat, rejected ? "FAILED" : "UNKNOWN", rejected ? e.code() : "DELIVERY_UNCONFIRMED");
    } catch (Exception e) {
      recordFailure(id, chat, "UNKNOWN", "DELIVERY_UNCONFIRMED");
    }
    events.changed();
  }

  private void recordFailure(UUID id, UUID chat, String status, String reason) {
    tx.executeWithoutResult(s -> {
      db.one("SELECT id FROM conversation WHERE id=? FOR UPDATE", chat);
      int changed = db.jdbc.update(
        "UPDATE outbound SET status=?,reason=?,updated_at=? WHERE id=? AND status IN ('SENDING','SUBMITTED','UNKNOWN')",
        status,
        reason,
        Timestamp.from(clock.instant()),
        id
      );
      if (changed > 0) chats.attention(chat, "DELIVERY_REQUIRES_REVIEW");
    });
  }

  public void confirmation(MessagingAdapter.Delivered result) {
    tx.executeWithoutResult(s -> {
      // Use the same lock order as send(): conversation, then delivery receipt.
      var conversations = db.list(
        "SELECT id FROM conversation WHERE connection_id=? AND external_id=? FOR UPDATE",
        result.connectionId(),
        result.chatId()
      );
      if (conversations.isEmpty()) return;
      UUID chat = UUID.fromString(conversations.getFirst().get("id").toString());
      db.jdbc.update(
        "INSERT INTO delivery_receipt(connection_id,chat_id,temporary_id,message_id,success,category) VALUES (?,?,?,?,?,?) ON CONFLICT(connection_id,chat_id,temporary_id) DO UPDATE SET message_id=excluded.message_id,success=excluded.success,category=excluded.category WHERE NOT delivery_receipt.success AND excluded.success",
        result.connectionId(),
        result.chatId(),
        result.temporaryId(),
        result.messageId(),
        result.success(),
        result.category()
      );
      var receipt = db.one(
        "SELECT * FROM delivery_receipt WHERE connection_id=? AND chat_id=? AND temporary_id=?",
        result.connectionId(),
        result.chatId(),
        result.temporaryId()
      );
      boolean success = Boolean.TRUE.equals(receipt.get("success"));
      String messageId = receipt.get("messageId").toString();
      String category = receipt.get("category").toString();
      var rows = db.list(
        "SELECT id,status FROM outbound WHERE conversation_id=? AND (temporary_id=? OR external_id=?)",
        chat,
        result.temporaryId(),
        messageId
      );
      for (var row : rows) {
        UUID id = UUID.fromString(row.get("id").toString());
        String previous = row.get("status").toString();
        if (
          (success && !previous.equals("SENT")) ||
          (!success && Set.of("SENDING", "SUBMITTED", "UNKNOWN").contains(previous))
        ) {
          db.jdbc.update(
            "UPDATE outbound SET status=?,external_id=?,reason=?,updated_at=? WHERE id=?",
            success ? "SENT" : "FAILED",
            messageId,
            category,
            Timestamp.from(clock.instant()),
            id
          );
          if (success) recordSent(id, messageId);
          else chats.attention(chat, category);
        }
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
      "UPDATE conversation SET status=CASE WHEN version=? AND status='READY' THEN CASE WHEN mode='AUTO' THEN 'IDLE' ELSE 'PAUSED' END ELSE status END,last_activity=GREATEST(last_activity,?) WHERE id=?",
      out.get("contextVersion"),
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

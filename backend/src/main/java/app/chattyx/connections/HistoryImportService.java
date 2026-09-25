package app.chattyx.connections;

import app.chattyx.conversations.ConversationService;
import app.chattyx.shared.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Imports bounded history while keeping the conversation under owner control. */
@Service
public class HistoryImportService {

  private final Db db;
  private final ChannelRegistry channels;
  private final ConversationService chats;
  private final Events events;
  private final Clock clock;
  private final TransactionTemplate tx;

  public HistoryImportService(
    Db db,
    ChannelRegistry channels,
    ConversationService chats,
    Events events,
    Clock clock,
    PlatformTransactionManager tm
  ) {
    this.db = db;
    this.channels = channels;
    this.chats = chats;
    this.events = events;
    this.clock = clock;
    this.tx = new TransactionTemplate(tm);
  }

  public CompletableFuture<Void> start(UUID id) {
    Instant started = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    UUID run = tx.execute(s -> {
      var row = db.one("SELECT * FROM conversation WHERE id=? FOR UPDATE", id);
      if (!Boolean.TRUE.equals(row.get("selected"))) throw new ApiException(409, "CHAT_NOT_SELECTED");
      if (row.get("importRunId") != null) return null;
      var connection = db.one(
        "SELECT status FROM connection WHERE id=?",
        UUID.fromString(row.get("connectionId").toString())
      );
      if (!"READY".equals(connection.get("status"))) throw new ApiException(409, "TELEGRAM_NOT_CONNECTED");
      UUID token = UUID.randomUUID();
      chats.pause(id, "HISTORY_IMPORT");
      db.jdbc.update(
        "UPDATE conversation SET import_run_id=?,import_started_at=?,status='IMPORTING',imported=false,import_cancelled=false,import_count=0,needs_attention=NULL WHERE id=?",
        token,
        java.sql.Timestamp.from(started),
        id
      );
      return token;
    });
    if (run == null) return CompletableFuture.completedFuture(null);
    events.changed();
    Instant cutoff = started.minus(Duration.ofDays(30));
    return CompletableFuture.runAsync(
      () -> execute(id, run, cutoff),
      task -> Thread.ofVirtual().name("history-import").start(task)
    );
  }

  public void cancel(UUID id) {
    tx.executeWithoutResult(s -> {
      var row = db.one("SELECT * FROM conversation WHERE id=? FOR UPDATE", id);
      if (row.get("importRunId") == null) return;
      chats.pause(id, "IMPORT_CANCELLED");
      db.jdbc.update(
        "UPDATE conversation SET import_run_id=NULL,import_cancelled=true,imported=false WHERE id=?",
        id
      );
    });
    events.changed();
  }

  @EventListener(ApplicationReadyEvent.class)
  public void recoverInterrupted() {
    tx.executeWithoutResult(s -> {
      for (UUID id : db.jdbc.queryForList(
        "SELECT id FROM conversation WHERE import_run_id IS NOT NULL OR status='IMPORTING' FOR UPDATE",
        UUID.class
      )) {
        chats.pause(id, "IMPORT_INTERRUPTED");
        db.jdbc.update(
          "UPDATE conversation SET import_run_id=NULL,imported=false,status='ATTENTION' WHERE id=?",
          id
        );
      }
    });
  }

  private boolean current(Map<String, Object> row, UUID run) {
    return run.toString().equals(row.get("importRunId")) && Boolean.TRUE.equals(row.get("selected"));
  }

  private void execute(UUID id, UUID run, Instant cutoff) {
    try {
      var row = chats.get(id);
      UUID connection = UUID.fromString(row.get("connectionId").toString());
      String chat = row.get("externalId").toString();
      var adapter = channels.forConnection(connection);
      if (!adapter.capabilities().history()) throw new ApiException(409, "HISTORY_UNSUPPORTED");
      Set<String> seen = new HashSet<>();
      Set<String> cursors = new HashSet<>();
      String cursor = null;
      while (seen.size() < 1000 && current(chats.get(id), run)) {
        // No database lock is held across the provider request.
        var page = adapter.fetchHistory(connection, chat, cursor, Math.min(100, 1000 - seen.size()));
        int before = seen.size();
        Boolean finished = tx.execute(s -> {
          if (!current(db.one("SELECT * FROM conversation WHERE id=? FOR UPDATE", id), run)) return true;
          boolean older = false;
          for (var message : page.messages()) {
            if (
              !message.connectionId().equals(connection) || !message.chatId().equals(chat)
            ) throw new ApiException(502, "INVALID_HISTORY_SCOPE");
            if (message.time().isBefore(cutoff)) {
              older = true;
              break;
            }
            if (seen.size() >= 1000) break;
            if (!seen.add(message.messageId())) continue;
            // History is a property of this operation, not a provider-controlled automation flag.
            chats.receive(
              new MessagingAdapter.Incoming(
                connection,
                chat,
                message.messageId(),
                message.text(),
                message.time(),
                message.outgoing(),
                true,
                message.ephemeral(),
                message.replyTo(),
                message.media(),
                message.sendingId()
              )
            );
          }
          db.jdbc.update("UPDATE conversation SET import_count=? WHERE id=?", seen.size(), id);
          return older || seen.size() >= 1000 || page.nextCursor() == null;
        });
        events.changed();
        if (Boolean.TRUE.equals(finished)) break;
        if (seen.size() == before || !cursors.add(page.nextCursor())) throw new ApiException(
          502,
          "HISTORY_NO_PROGRESS"
        );
        cursor = page.nextCursor();
      }
      tx.executeWithoutResult(s -> {
        var latest = db.one("SELECT * FROM conversation WHERE id=? FOR UPDATE", id);
        if (!current(latest, run)) return;
        db.jdbc.update(
          "UPDATE conversation SET import_run_id=NULL,imported=true,status=CASE WHEN status='IMPORTING' THEN 'PAUSED' ELSE status END WHERE id=?",
          id
        );
        db.jdbc.update(
          "INSERT INTO memory_job(conversation_id) VALUES (?) ON CONFLICT(conversation_id) DO UPDATE SET revision=memory_job.revision+1,due_at=now()",
          id
        );
      });
    } catch (Exception error) {
      db.jdbc.update(
        "UPDATE conversation SET import_run_id=NULL,imported=false,status='ATTENTION',needs_attention='IMPORT_FAILED' WHERE id=? AND import_run_id=?",
        id,
        run
      );
    } finally {
      events.changed();
    }
  }
}

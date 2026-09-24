package app.chattyx.connections;

import app.chattyx.conversations.ConversationService;
import app.chattyx.shared.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ConnectionService {

  private final Db db;
  private final ChannelRegistry channels;
  private final ConversationService chats;
  private final Events events;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final Set<UUID> imports = ConcurrentHashMap.newKeySet();

  public ConnectionService(
    Db db,
    ChannelRegistry channels,
    ConversationService chats,
    Events events,
    Clock clock,
    org.springframework.transaction.PlatformTransactionManager tm
  ) {
    this.db = db;
    this.channels = channels;
    this.chats = chats;
    this.events = events;
    this.clock = clock;
    tx = new TransactionTemplate(tm);
  }

  public List<Map<String, Object>> list() {
    return db.list("SELECT id,name,adapter,status,enabled,created_at FROM connection ORDER BY created_at");
  }

  public UUID create(String name, String adapter) {
    if (name == null || name.isBlank() || name.length() > 80) throw new ApiException(400, "INVALID_NAME");
    channels.byName(adapter);
    return tx.execute(s -> {
      db.jdbc.execute("SELECT pg_advisory_xact_lock(7261024)");
      if (
        db.jdbc.queryForObject("SELECT count(*) FROM connection", Integer.class) >= 3
      ) throw new ApiException(409, "ACCOUNT_LIMIT");
      UUID id = UUID.randomUUID();
      db.jdbc.update("INSERT INTO connection(id,name,adapter) VALUES (?,?,?)", id, name, adapter);
      return id;
    });
  }

  public MessagingAdapter.Authorization authorize(UUID id, String method) {
    return channels.forConnection(id).beginAuthorization(id, method);
  }

  public MessagingAdapter.Authorization authState(UUID id) {
    var adapter = channels.forConnection(id);
    return adapter instanceof TelegramAdapter t
      ? t.authorization(id)
      : new MessagingAdapter.Authorization(db.one("SELECT status FROM connection WHERE id=?",id).get("status").toString(), Map.of("adapter",adapter.name()));
  }

  public void disconnect(UUID id, boolean revoke) {
    tx.executeWithoutResult(s -> {
      for (UUID chat : db.jdbc.queryForList(
        "SELECT id FROM conversation WHERE connection_id=? FOR UPDATE",
        UUID.class,
        id
      ))
        chats.pause(chat, "ACCOUNT_DISCONNECTED");
      db.jdbc.update("UPDATE connection SET enabled=false WHERE id=?", id);
    });
    channels.forConnection(id).disconnect(id, revoke);
    db.jdbc.update("UPDATE connection SET status=? WHERE id=?", revoke ? "REVOKED" : "DISCONNECTED", id);
    events.changed();
  }

  public void enable(UUID id, boolean value) {
    tx.executeWithoutResult(s -> {
      db.one("SELECT id FROM connection WHERE id=? FOR UPDATE", id);
      db.jdbc.update("UPDATE connection SET enabled=? WHERE id=?", value, id);
      if (!value) for (UUID chat : db.jdbc.queryForList(
        "SELECT id FROM conversation WHERE connection_id=? FOR UPDATE",
        UUID.class,
        id
      ))
        chats.pause(chat, "ACCOUNT_PAUSED");
    });
    events.changed();
  }

  public void select(UUID id, boolean selected) {
    tx.executeWithoutResult(s -> {
      db.jdbc.execute("SELECT pg_advisory_xact_lock(7261025)");
      var chat = db.one("SELECT * FROM conversation WHERE id=? FOR UPDATE", id);
      if (
        selected &&
        !Boolean.TRUE.equals(chat.get("selected")) &&
        db.jdbc.queryForObject("SELECT count(*) FROM conversation WHERE selected", Integer.class) >= 100
      ) throw new ApiException(409, "CHAT_LIMIT");
      chats.pause(id, "OWNER_PAUSED");
      db.jdbc.update("UPDATE conversation SET selected=?,import_cancelled=false WHERE id=?", selected, id);
    });
    if (selected) importHistory(id);
    events.changed();
  }

  public void cancelImport(UUID id) {
    db.jdbc.update("UPDATE conversation SET import_cancelled=true WHERE id=?", id);
  }

  public void importHistory(UUID id) {
    if (!Boolean.TRUE.equals(chats.get(id).get("selected"))) throw new ApiException(409, "CHAT_NOT_SELECTED");
    db.jdbc.update("UPDATE conversation SET import_cancelled=false WHERE id=?", id);
    if (!imports.add(id)) return;
    Thread.ofVirtual()
      .name("history-import")
      .start(() -> {
        try {
          var row = chats.get(id);
          UUID connection = UUID.fromString(row.get("connectionId").toString());
          String external = row.get("externalId").toString();
          var adapter = channels.forConnection(connection);
          String cursor = null;
          int count = 0;
          Instant cutoff = clock.instant().minus(Duration.ofDays(30));
          db.jdbc.update(
            "UPDATE conversation SET status='IMPORTING',imported=false,import_count=0 WHERE id=?",
            id
          );
          while (count < 1000) {
            var current = chats.get(id);
            if (
              Boolean.TRUE.equals(current.get("importCancelled")) ||
              !Boolean.TRUE.equals(current.get("selected"))
            ) break;
            var page = adapter.fetchHistory(connection, external, cursor, Math.min(100, 1000 - count));
            if (page.messages().isEmpty()) break;
            boolean older = false;
            for (var message : page.messages()) {
              if (message.time().isBefore(cutoff)) {
                older = true;
                break;
              }
              chats.receive(message);
              count++;
            }
            db.jdbc.update("UPDATE conversation SET import_count=? WHERE id=?", count, id);
            events.changed();
            if (older || page.nextCursor() == null || page.nextCursor().equals(cursor)) break;
            cursor = page.nextCursor();
          }
          db.jdbc.update(
            "UPDATE conversation SET imported=selected AND NOT import_cancelled,status='PAUSED' WHERE id=?",
            id
          );
          db.jdbc.update(
            "INSERT INTO memory_job(conversation_id) VALUES (?) ON CONFLICT(conversation_id) DO UPDATE SET revision=memory_job.revision+1,due_at=now()",
            id
          );
        } catch (Exception e) {
          db.jdbc.update(
            "UPDATE conversation SET status='ATTENTION',needs_attention='IMPORT_FAILED' WHERE id=?",
            id
          );
        } finally {
          imports.remove(id);
          events.changed();
        }
      });
  }
}

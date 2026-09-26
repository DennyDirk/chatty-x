package app.chattyx.connections;

import app.chattyx.conversations.ConversationService;
import app.chattyx.shared.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ConnectionService {

  private final Db db;
  private final ChannelRegistry channels;
  private final ConversationService chats;
  private final Events events;
  private final HistoryImportService history;
  private final TransactionTemplate tx;

  public ConnectionService(
    Db db,
    ChannelRegistry channels,
    ConversationService chats,
    Events events,
    HistoryImportService history,
    org.springframework.transaction.PlatformTransactionManager tm
  ) {
    this.db = db;
    this.channels = channels;
    this.chats = chats;
    this.events = events;
    this.history = history;
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
      : new MessagingAdapter.Authorization(
          db.one("SELECT status FROM connection WHERE id=?", id).get("status").toString(),
          Map.of("adapter", adapter.name())
        );
  }

  public void disconnect(UUID id, boolean revoke) {
    tx.executeWithoutResult(s -> {
      for (UUID chat : db.jdbc.queryForList(
        "SELECT id FROM conversation WHERE connection_id=? FOR UPDATE",
        UUID.class,
        id
      )) {
        history.cancel(chat);
        chats.pause(chat, "ACCOUNT_DISCONNECTED");
      }
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
        !"READY".equals(
          db
            .one(
              "SELECT status FROM connection WHERE id=?",
              UUID.fromString(chat.get("connectionId").toString())
            )
            .get("status")
        )
      ) {
        throw new ApiException(409, "TELEGRAM_NOT_CONNECTED");
      }
      if (
        selected &&
        !Boolean.TRUE.equals(chat.get("selected")) &&
        db.jdbc.queryForObject("SELECT count(*) FROM conversation WHERE selected", Integer.class) >= 100
      ) throw new ApiException(409, "CHAT_LIMIT");
      chats.pause(id, "OWNER_PAUSED");
      db.jdbc.update("UPDATE conversation SET selected=? WHERE id=?", selected, id);
      if (!selected) history.cancel(id);
    });
    if (selected) importHistory(id);
    events.changed();
  }

  public void cancelImport(UUID id) {
    history.cancel(id);
  }

  public void importHistory(UUID id) {
    history.start(id);
  }
}

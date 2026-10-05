package app.chattyx.connections;

import app.chattyx.conversations.ConversationService;
import app.chattyx.delivery.DeliveryService;
import app.chattyx.shared.*;
import java.util.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class ChannelEvents {

  private final ChannelRegistry channels;
  private final ConversationService chats;
  private final DeliveryService delivery;
  private final Db db;
  private final Events events;

  public ChannelEvents(
    ChannelRegistry channels,
    ConversationService chats,
    DeliveryService delivery,
    Db db,
    Events events
  ) {
    this.channels = channels;
    this.chats = chats;
    this.delivery = delivery;
    this.db = db;
    this.events = events;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void start() {
    channels.all().forEach(a -> a.events(this::accept));
    delivery.recover();
    db.jdbc.update(
      "UPDATE automation_job SET state='WAITING',lease_token=NULL,lease_until=NULL WHERE state='GENERATING'"
    );
    for (var row : db.list(
      "SELECT id,adapter FROM connection WHERE status NOT IN ('DISCONNECTED','REVOKED')"
    ))
      try {
        channels
          .byName(row.get("adapter").toString())
          .beginAuthorization(UUID.fromString(row.get("id").toString()), "restore");
      } catch (Exception e) {
        db.jdbc.update(
          "UPDATE connection SET status='ERROR' WHERE id=?",
          UUID.fromString(row.get("id").toString())
        );
      }
  }

  public void accept(MessagingAdapter.Event event) {
    switch (event) {
      case MessagingAdapter.NewMessage e -> chats.receive(e.message());
      case MessagingAdapter.Chat e -> chats.discover(e);
      case MessagingAdapter.ChatActivity e -> chats.activity(e);
      case MessagingAdapter.Edited e -> chats.edit(e.connectionId(), e.chatId(), e.messageId(), e.text());
      case MessagingAdapter.Deleted e -> chats.delete(e.connectionId(), e.chatId(), e.messageIds());
      case MessagingAdapter.Delivered e -> delivery.confirmation(e);
      case MessagingAdapter.State e -> {
        db.jdbc.update("UPDATE connection SET status=? WHERE id=?", e.status(), e.connectionId());
        events.changed();
      }
    }
  }
}

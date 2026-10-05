package app.chattyx.connections;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

public interface MessagingAdapter {
  record Capabilities(
    boolean history,
    boolean photos,
    boolean voice,
    boolean typing,
    boolean confirmations
  ) {}

  record Authorization(String step, Map<String, String> fields) {}

  record Media(String fileId, String kind, String mime, long size, int duration) {}

  record Incoming(
    UUID connectionId,
    String chatId,
    String messageId,
    String text,
    Instant time,
    boolean outgoing,
    boolean historical,
    boolean ephemeral,
    String replyTo,
    Media media,
    Integer sendingId
  ) {}

  record Outgoing(UUID operationId, UUID connectionId, String chatId, String text, int sendingId) {}

  record Receipt(String temporaryId, String finalId, boolean confirmed) {}

  record History(List<Incoming> messages, String nextCursor) {}

  record Snapshot(List<Incoming> messages, List<String> missingIds) {}

  sealed interface Event permits NewMessage, Edited, Deleted, Chat, ChatActivity, State, Delivered {}

  record NewMessage(Incoming message) implements Event {}

  record Edited(UUID connectionId, String chatId, String messageId, String text) implements Event {}

  record Deleted(UUID connectionId, String chatId, List<String> messageIds) implements Event {}

  record Chat(
    UUID connectionId,
    String chatId,
    String title,
    boolean eligible,
    Instant lastMessageAt
  ) implements Event {
    public Chat(UUID connectionId, String chatId, String title, boolean eligible) {
      this(connectionId, chatId, title, eligible, null);
    }
  }

  record ChatActivity(UUID connectionId, String chatId, Instant lastMessageAt) implements Event {}

  record State(UUID connectionId, String status, String step, Map<String, String> fields) implements Event {}

  record Delivered(
    UUID connectionId,
    String chatId,
    String temporaryId,
    String messageId,
    boolean success,
    String category
  ) implements Event {}

  String name();
  Capabilities capabilities();
  void events(Consumer<Event> listener);
  Authorization beginAuthorization(UUID connectionId, String method);
  Authorization submitAuthorization(UUID connectionId, String step, String value);
  void disconnect(UUID connectionId, boolean revoke);
  void discover(UUID connectionId);
  History fetchHistory(UUID connectionId, String chatId, String cursor, int limit);
  Receipt sendText(Outgoing message);
  void markRead(UUID connectionId, String chatId, String messageId);
  void setTyping(UUID connectionId, String chatId, boolean active);
  Path download(UUID connectionId, String fileId, long maxBytes);

  default void releaseFile(UUID connectionId, String fileId) {}

  default Optional<Snapshot> fetchMessages(UUID connectionId, String chatId, List<String> ids) {
    return Optional.empty();
  }

  Optional<Receipt> reconcile(UUID connectionId, String chatId, String temporaryId);
}

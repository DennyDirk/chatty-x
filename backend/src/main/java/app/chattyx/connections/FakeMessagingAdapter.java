package app.chattyx.connections;

import app.chattyx.shared.ApiException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "chatty.mode", havingValue = "demo")
public class FakeMessagingAdapter implements MessagingAdapter {

  private Consumer<Event> listener = e -> {};
  private final Map<UUID, Receipt> sent = new ConcurrentHashMap<>();

  public String name() {
    return "fake";
  }

  public Capabilities capabilities() {
    return new Capabilities(true, true, true, true, true);
  }

  public void events(Consumer<Event> listener) {
    this.listener = listener;
  }

  public Authorization beginAuthorization(UUID id, String method) {
    listener.accept(new State(id, "READY", "READY", Map.of()));
    discover(id);
    return new Authorization("READY", Map.of());
  }

  public Authorization submitAuthorization(UUID id, String step, String value) {
    return new Authorization("READY", Map.of());
  }

  public void disconnect(UUID id, boolean revoke) {
    listener.accept(new State(id, "DISCONNECTED", "DISCONNECTED", Map.of()));
  }

  public void discover(UUID id) {
    listener.accept(new Chat(id, "1001", "Аня · тестовый чат", true));
    listener.accept(new Chat(id, "1002", "Марк · тестовый чат", true));
  }

  public History fetchHistory(UUID id, String chat, String cursor, int limit) {
    return new History(List.of(), null);
  }

  public Receipt sendText(Outgoing message) {
    return sent.computeIfAbsent(message.operationId(), id -> new Receipt("fake-" + id, "fake-" + id, true));
  }

  public void markRead(UUID id, String chat, String message) {}

  public void setTyping(UUID id, String chat, boolean active) {}

  public Path download(UUID id, String fileId, long maxBytes) {
    throw new ApiException(400, "FAKE_MEDIA_REQUIRES_UPLOAD");
  }

  public Optional<Receipt> reconcile(UUID id, String chat, String temporaryId) {
    return sent
      .values()
      .stream()
      .filter(x -> x.temporaryId().equals(temporaryId))
      .findFirst();
  }

  public void inject(UUID id, String chat, String text, boolean outgoing) {
    listener.accept(
      new NewMessage(
        new Incoming(
          id,
          chat,
          UUID.randomUUID().toString(),
          text,
          Instant.now(),
          outgoing,
          false,
          false,
          null,
          null,
          null
        )
      )
    );
  }
}

package app.chattyx.connections;

import app.chattyx.shared.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Loads the official JsonClient JNI wrapper built from the pinned TDLib source. */
final class TdTransport implements AutoCloseable {

  private Method send, receive, create;
  private volatile boolean running = true;
  private final Map<String, CompletableFuture<JsonNode>> requests = new ConcurrentHashMap<>();
  private final Map<Integer, Consumer<JsonNode>> listeners = new ConcurrentHashMap<>();
  private final Map<Integer, ExecutorService> ordered = new ConcurrentHashMap<>();

  TdTransport() {
    try {
      Class<?> client = Class.forName("org.drinkless.tdlib.JsonClient");
      create = client.getMethod("createClientId");
      send = client.getMethod("send", int.class, String.class);
      receive = client.getMethod("receive", double.class);
      client
        .getMethod("execute", String.class)
        .invoke(null, "{\"@type\":\"setLogVerbosityLevel\",\"new_verbosity_level\":0}");
      Thread.ofPlatform().daemon().name("tdlib-receiver").start(this::loop);
    } catch (Throwable e) {
      throw new ApiException(503, "TDLIB_NATIVE_UNAVAILABLE");
    }
  }

  int create(Consumer<JsonNode> listener) {
    try {
      int id = (int) create.invoke(null);
      listeners.put(id, listener);
      ordered.put(
        id,
        Executors.newSingleThreadExecutor(
          Thread.ofVirtual()
            .name("tdlib-events-" + id)
            .factory()
        )
      );
      return id;
    } catch (Exception e) {
      throw new ApiException(503, "TDLIB_NATIVE_UNAVAILABLE");
    }
  }

  CompletableFuture<JsonNode> async(int id, ObjectNode request) {
    String correlation = UUID.randomUUID().toString();
    request.put("@extra", correlation);
    var future = new CompletableFuture<JsonNode>();
    requests.put(correlation, future);
    future.orTimeout(45, TimeUnit.SECONDS).whenComplete((v, e) -> requests.remove(correlation));
    try {
      send.invoke(null, id, Json.write(request));
    } catch (Exception e) {
      requests.remove(correlation);
      future.completeExceptionally(new ApiException(503, "TELEGRAM_UNAVAILABLE"));
    }
    return future;
  }

  JsonNode call(int id, ObjectNode request) {
    try {
      var result = async(id, request).get(46, TimeUnit.SECONDS);
      if (result.path("@type").asText().equals("error")) {
        String message = result.path("message").asText();
        if (result.path("code").asInt() == 429 || message.contains("FLOOD_WAIT")) {
          var limit = RateLimited.parse(message);
          if (limit != null) throw limit;
          throw new ApiException(429, "TELEGRAM_RATE_LIMIT");
        }
        throw new ApiException(502, "TELEGRAM_REQUEST_REJECTED");
      }
      return result;
    } catch (RateLimited e) {
      throw e;
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new ApiException(504, "TELEGRAM_RESULT_UNKNOWN");
    }
  }

  private void loop() {
    while (running)
      try {
        String raw = (String) receive.invoke(null, 1.0);
        if (raw == null) continue;
        var event = Json.read(raw);
        String key = event.path("@extra").asText();
        if (!key.isEmpty()) {
          var f = requests.remove(key);
          if (f != null) f.complete(event);
        } else {
          int id = event.path("@client_id").asInt();
          var listener = listeners.get(id);
          var executor = ordered.get(id);
          if (listener != null && executor != null) executor.submit(() -> {
            try {
              listener.accept(event);
            } catch (Exception e) {
              org.slf4j.LoggerFactory.getLogger(getClass()).warn(
                "telegram_event_failed type={}",
                e.getClass().getSimpleName()
              );
            }
          });
        }
      } catch (Exception e) {
        if (running) org.slf4j.LoggerFactory.getLogger(getClass()).warn("telegram_receiver_failed");
      }
  }

  public void close() {
    running = false;
    ordered.values().forEach(ExecutorService::shutdownNow);
    requests.values().forEach(f -> f.cancel(true));
  }

  void release(int id) {
    listeners.remove(id);
    var executor = ordered.remove(id);
    if (executor != null) executor.shutdown();
  }
}

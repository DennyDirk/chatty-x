package app.chattyx.generation;

import app.chattyx.operations.BudgetService;
import app.chattyx.shared.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OllamaClient implements StructuredModel {

  private final BudgetService budget;
  private final URI endpoint;
  private final Duration timeout;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final Semaphore slot = new Semaphore(1);

  public OllamaClient(
    BudgetService budget,
    @Value("${chatty.ollama-chat-url:http://localhost:11434/api/chat}") String endpoint,
    @Value("${chatty.ollama-timeout-seconds:90}") int timeoutSeconds
  ) {
    this.budget = budget;
    this.endpoint = URI.create(endpoint);
    this.timeout = Duration.ofSeconds(Math.clamp(timeoutSeconds, 1, 90));
    if (
      !Set.of("http", "https").contains(this.endpoint.getScheme()) ||
      this.endpoint.getHost() == null ||
      this.endpoint.getUserInfo() != null
    ) throw new IllegalArgumentException("Invalid Ollama endpoint");
  }

  public JsonNode structured(
    UUID conversation,
    String kind,
    String model,
    String prompt,
    String instructions,
    JsonNode data,
    List<String> images,
    JsonNode schema
  ) {
    if (!model.equals("qwen3:8b")) throw new ApiException(400, "LOCAL_MODEL_UNSUPPORTED");
    if (!images.isEmpty()) throw new ApiException(409, "LOCAL_MEDIA_UNSUPPORTED");
    var context = LocalModelContext.fit(kind, instructions, data, schema);
    if (!slot.tryAcquire()) throw new ApiException(409, "MODEL_BUSY");
    try {
      var body = Json.object().put("model", model).put("stream", false).put("think", false);
      body.set("format", schema);
      body.set(
        "options",
        Json.object()
          .put("num_ctx", LocalModelContext.CONTEXT_TOKENS)
          .put("num_predict", LocalModelContext.OUTPUT_TOKENS)
          .put("temperature", kind.equals("memory") ? 0 : 0.7)
      );
      body.set(
        "messages",
        Json.MAPPER.createArrayNode()
          .add(
            Json.object()
              .put("role", "system")
              .put("content", instructions + "\nJSON schema: " + Json.write(schema))
          )
          .add(Json.object().put("role", "user").put("content", Json.write(context)))
      );
      var request = HttpRequest.newBuilder(endpoint)
        .timeout(timeout)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
        .build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) throw new ApiException(
        502,
        response.statusCode() == 404 ? "LOCAL_MODEL_NOT_FOUND" : "LOCAL_MODEL_UNAVAILABLE"
      );
      var result = Json.read(response.body());
      if (
        result == null ||
        !result.path("done").asBoolean() ||
        !result.path("done_reason").asText().equals("stop")
      ) throw new ApiException(502, "MODEL_INCOMPLETE_RESPONSE");
      var output = Json.read(result.path("message").path("content").asText());
      ModelSchema.validate(output, schema);
      budget.recordLocal(
        conversation,
        kind,
        model,
        prompt,
        Math.max(0, result.path("prompt_eval_count").asLong()),
        Math.max(0, result.path("eval_count").asLong())
      );
      return output;
    } catch (ApiException e) {
      throw e;
    } catch (HttpTimeoutException e) {
      throw new ApiException(504, "LOCAL_MODEL_TIMEOUT");
    } catch (IllegalArgumentException e) {
      throw new ApiException(502, "INVALID_MODEL_REPLY");
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new ApiException(502, "LOCAL_MODEL_UNAVAILABLE");
    } finally {
      slot.release();
    }
  }
}

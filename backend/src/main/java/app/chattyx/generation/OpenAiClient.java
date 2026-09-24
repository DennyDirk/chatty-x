package app.chattyx.generation;

import app.chattyx.operations.BudgetService;
import app.chattyx.personas.SettingsService;
import app.chattyx.shared.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OpenAiClient {

  private final SettingsService settings;
  private final BudgetService budget;
  private final URI endpoint;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  public OpenAiClient(
    SettingsService settings,
    BudgetService budget,
    @Value("${chatty.openai-url:https://api.openai.com/v1/responses}") String endpoint
  ) {
    this.settings = settings;
    this.budget = budget;
    this.endpoint = URI.create(endpoint);
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
    if (!Set.of("gpt-5.4", "gpt-5.4-mini").contains(model)) throw new ApiException(
      400,
      "MODEL_PRICING_NOT_CONFIGURED"
    );
    String key = settings.credential("openai").orElseThrow(() -> new ApiException(409, "MODEL_KEY_REQUIRED"));
    UUID reservation = budget.reserve(
      conversation,
      kind,
      model,
      prompt,
      new BigDecimal(model.equals("gpt-5.4") ? "0.40" : "0.12")
    );
    var body = Json.object()
      .put("model", model)
      .put("store", false)
      .put("max_output_tokens", 2000)
      .put("instructions", instructions);
    body.set("reasoning", Json.object().put("effort", "none"));
    var content = Json.MAPPER.createArrayNode().add(
      Json.object().put("type", "input_text").put("text", Json.write(data))
    );
    images
      .stream()
      .limit(4)
      .forEach(image ->
        content.add(Json.object().put("type", "input_image").put("image_url", image).put("detail", "low"))
      );
    body.set(
      "input",
      Json.MAPPER.createArrayNode().add(Json.object().put("role", "user").set("content", content))
    );
    var format = Json.object().put("type", "json_schema").put("name", kind).put("strict", true);
    format.set("schema", schema);
    body.set("text", Json.object().set("format", format));
    try {
      var request = HttpRequest.newBuilder(endpoint)
        .timeout(Duration.ofSeconds(45))
        .header("Authorization", "Bearer " + key)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
        .build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        if (response.statusCode() >= 400 && response.statusCode() < 500) budget.release(reservation);
        else budget.unknown(reservation);
        throw new ApiException(502, response.statusCode() == 429 ? "MODEL_RATE_LIMIT" : "MODEL_UNAVAILABLE");
      }
      var result = Json.read(response.body());
      long input = result.path("usage").path("input_tokens").asLong(),
        output = result.path("usage").path("output_tokens").asLong();
      double inPrice = model.equals("gpt-5.4") ? 2.5 : .75,
        outPrice = model.equals("gpt-5.4") ? 15 : 4.5;
      budget.complete(
        reservation,
        input,
        output,
        BigDecimal.valueOf((input * inPrice + output * outPrice) / 1000000)
      );
      StringBuilder text = new StringBuilder();
      for (var item : result.path("output"))
        for (var part : item.path("content"))
          if (part.path("type").asText().equals("output_text")) text.append(part.path("text").asText());
      if (
        !result.path("status").asText("completed").equals("completed") || text.isEmpty()
      ) throw new ApiException(502, "MODEL_INCOMPLETE_RESPONSE");
      return Json.read(text.toString());
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      budget.unknown(reservation);
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new ApiException(502, "MODEL_REQUEST_FAILED");
    }
  }

  public static ObjectNode schema(Map<String, JsonNode> properties) {
    var schema = Json.object().put("type", "object").put("additionalProperties", false);
    var props = Json.object();
    var required = Json.MAPPER.createArrayNode();
    properties.forEach((k, v) -> {
      props.set(k, v);
      required.add(k);
    });
    schema.set("properties", props);
    schema.set("required", required);
    return schema;
  }

  public static ObjectNode stringSchema() {
    return Json.object().put("type", "string");
  }
}

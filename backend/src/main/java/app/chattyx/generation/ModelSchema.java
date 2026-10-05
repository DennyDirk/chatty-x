package app.chattyx.generation;

import app.chattyx.shared.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;

public final class ModelSchema {

  private ModelSchema() {}

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

  // Validate the deliberately small schema subset used by reply and memory contracts.
  public static void validate(JsonNode value, JsonNode schema) {
    if (value == null) invalid();
    switch (schema.path("type").asText()) {
      case "string" -> {
        if (!value.isTextual()) invalid();
      }
      case "array" -> {
        if (!value.isArray()) invalid();
        for (var item : value) validate(item, schema.path("items"));
      }
      case "object" -> {
        if (!value.isObject()) invalid();
        for (var required : schema.path("required")) if (!value.has(required.asText())) invalid();
        value.fields().forEachRemaining(entry -> {
          var property = schema.path("properties").get(entry.getKey());
          if (property == null) invalid();
          validate(entry.getValue(), property);
        });
      }
      default -> invalid();
    }
    if (schema.has("enum")) {
      boolean found = false;
      for (var allowed : schema.path("enum")) if (allowed.equals(value)) found = true;
      if (!found) invalid();
    }
  }

  private static void invalid() {
    throw new ApiException(502, "INVALID_MODEL_REPLY");
  }
}

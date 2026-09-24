package app.chattyx.shared;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class Json {

  public static final ObjectMapper MAPPER = new ObjectMapper();

  private Json() {}

  public static JsonNode read(String v) {
    try {
      return MAPPER.readTree(v);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid JSON");
    }
  }

  public static String write(Object v) {
    try {
      return MAPPER.writeValueAsString(v);
    } catch (Exception e) {
      throw new IllegalArgumentException("Cannot encode JSON");
    }
  }

  public static ObjectNode object() {
    return MAPPER.createObjectNode();
  }

  public static ObjectNode object(String type) {
    return object().put("@type", type);
  }
}

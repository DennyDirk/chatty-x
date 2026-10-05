package app.chattyx.generation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

public interface StructuredModel {
  JsonNode structured(
    UUID conversation,
    String kind,
    String model,
    String prompt,
    String instructions,
    JsonNode data,
    List<String> images,
    JsonNode schema
  );
}

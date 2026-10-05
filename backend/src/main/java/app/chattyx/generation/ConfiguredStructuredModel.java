package app.chattyx.generation;

import app.chattyx.personas.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Service
@Primary
public class ConfiguredStructuredModel implements StructuredModel {

  private final SettingsService settings;
  private final OpenAiClient openai;
  private final OllamaClient ollama;

  public ConfiguredStructuredModel(SettingsService settings, OpenAiClient openai, OllamaClient ollama) {
    this.settings = settings;
    this.openai = openai;
    this.ollama = ollama;
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
    if (settings.localModel()) return ollama.structured(
      conversation,
      kind,
      "qwen3:8b",
      prompt,
      instructions,
      data,
      images,
      schema
    );
    return openai.structured(conversation, kind, model, prompt, instructions, data, images, schema);
  }
}

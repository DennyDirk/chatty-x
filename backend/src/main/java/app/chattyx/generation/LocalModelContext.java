package app.chattyx.generation;

import app.chattyx.shared.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;

final class LocalModelContext {

  static final int CONTEXT_TOKENS = 4096;
  static final int OUTPUT_TOKENS = 768;
  private static final Set<String> PROFILE_KEYS = Set.of(
    "name",
    "biography",
    "tone",
    "examples",
    "avoidExamples",
    "emoji",
    "slang",
    "profanity",
    "maxReplyChars",
    "identityReaction",
    "commitments",
    "addressing",
    "warmth",
    "humor",
    "boldness",
    "letterCase",
    "punctuation",
    "followUpQuestions",
    "boundaries"
  );

  static JsonNode fit(String kind, String instructions, JsonNode original, JsonNode schema) {
    ObjectNode data = original.deepCopy();
    if (data.get("profile") instanceof ObjectNode profile) profile.retain(PROFILE_KEYS);
    String factsKey = kind.equals("memory") ? "existingFacts" : "facts";
    if (data.get(factsKey) instanceof ArrayNode facts) {
      for (var fact : facts)
        if (fact instanceof ObjectNode object) object.retain(
          "subject",
          "content",
          "pinned",
          "factKey",
          "conflict"
        );
    }
    if (data.get("messages") instanceof ArrayNode messages) {
      for (var message : messages)
        if (message instanceof ObjectNode object) object.retain("id", "source", "text", "body", "new");
    }
    // Qwen3 uses byte-level BPE: UTF-8 bytes give a conservative token bound.
    // Reserve space for chat template tokens and output; never truncate system rules/new input.
    while (
      bytes(instructions) + bytes(Json.write(schema)) + bytes(Json.write(data)) >
      CONTEXT_TOKENS - OUTPUT_TOKENS - 256
    ) {
      if (data.get(factsKey) instanceof ArrayNode facts && !facts.isEmpty()) {
        facts.remove(facts.size() - 1);
      } else if (data.has(kind.equals("memory") ? "previousSummary" : "summary")) {
        data.remove(kind.equals("memory") ? "previousSummary" : "summary");
      } else if (data.get("messages") instanceof ArrayNode messages && removable(messages, kind) >= 0) {
        messages.remove(removable(messages, kind));
      } else throw new ApiException(409, "CONTEXT_LIMIT_REQUIRES_OWNER");
    }
    return data;
  }

  private static int removable(ArrayNode messages, String kind) {
    // Memory query is newest first; reply history is chronological.
    if (kind.equals("memory")) return messages.size() > 1 ? messages.size() - 1 : -1;
    for (int i = 0; i < messages.size(); i++) if (
      messages.get(i).has("new") && !messages.get(i).path("new").asBoolean()
    ) return i;
    return -1;
  }

  private static int bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length;
  }
}

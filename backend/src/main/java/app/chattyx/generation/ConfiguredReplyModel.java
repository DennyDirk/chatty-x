package app.chattyx.generation;

import app.chattyx.shared.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
public class ConfiguredReplyModel implements ReplyModel {
  private final StructuredModel client;
  private final boolean demo;
  private final String prompt;

  public ConfiguredReplyModel(StructuredModel client, @Value("${chatty.mode}") String mode)
      throws java.io.IOException {
    this.client = client;
    this.demo = mode.equals("demo");
    this.prompt = new ClassPathResource("prompts/reply-v2.txt").getContentAsString(StandardCharsets.UTF_8);
  }

  public Reply generate(Context context) {
    if (context.messages().isEmpty()) return new Reply("skip", "", "NO_INPUT");
    if (demo) return new Reply("reply", "Слышу тебя 🙂 Расскажешь чуть подробнее?", "DEMO_MODEL");
    var data = Json.object();
    data.set("profile", context.settings());
    data.put("summary", context.summary());
    data.set("facts", Json.MAPPER.valueToTree(context.facts()));
    data.set("messages", Json.MAPPER.valueToTree(context.messages()));
    var actionSchema = ModelSchema.stringSchema();
    actionSchema.set("enum", Json.MAPPER.valueToTree(List.of("reply", "skip", "handoff")));
    var schema = ModelSchema.schema(Map.of("action", actionSchema, "text", ModelSchema.stringSchema(), "reason", ModelSchema.stringSchema()));
    var result = client.structured(context.conversationId(), "reply",
        context.settings().path("replyModel").asText("gpt-5.4"), "reply-v2", prompt, data, context.imageDataUrls(), schema);
    ModelSchema.validate(result, schema);
    String action = result.path("action").asText();
    String text = result.path("text").asText();
    String reason = result.path("reason").asText();
    if (text.length() > context.settings().path("maxReplyChars").asInt(800) || action.equals("reply") && text.isBlank())
      throw new ApiException(502, "INVALID_MODEL_REPLY");
    return new Reply(action, text, reason.length() > 100 ? "MODEL_DECISION" : reason);
  }
}

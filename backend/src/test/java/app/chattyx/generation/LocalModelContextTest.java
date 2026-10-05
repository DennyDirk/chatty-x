package app.chattyx.generation;

import static org.assertj.core.api.Assertions.*;

import app.chattyx.shared.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocalModelContextTest {

  @Test
  void removesOldHistoryButPreservesWholeBurstAndRules() {
    var data = Json.object();
    data.set("profile", Json.object().put("biography", "Do not invent details").put("monthlyBudgetUsd", 30));
    data.set(
      "messages",
      Json.MAPPER.createArrayNode()
        .add(Json.object().put("text", "x".repeat(4000)).put("new", false))
        .add(Json.object().put("text", "hey").put("new", true))
        .add(Json.object().put("text", "finished the project").put("new", true))
    );
    var result = LocalModelContext.fit("reply", "rules", data, ModelSchema.schema(Map.of()));
    assertThat(result.path("messages")).hasSize(2);
    assertThat(result.path("profile").path("biography").asText()).isEqualTo("Do not invent details");
    assertThat(result.path("profile").has("monthlyBudgetUsd")).isFalse();
    assertThat(data.path("messages")).hasSize(3);
  }

  @Test
  void refusesOversizedNewInputRatherThanSilentlyDroppingIt() {
    var data = Json.object().set(
      "messages",
      Json.MAPPER.createArrayNode().add(Json.object().put("text", "x".repeat(4000)).put("new", true))
    );
    assertThatThrownBy(() ->
      LocalModelContext.fit("reply", "rules", data, ModelSchema.schema(Map.of()))
    ).isInstanceOfSatisfying(ApiException.class, e ->
      assertThat(e.code()).isEqualTo("CONTEXT_LIMIT_REQUIRES_OWNER")
    );
  }

  @Test
  void memoryKeepsNewestSourcesInsteadOfOldest() {
    var data = Json.object().set(
      "messages",
      Json.MAPPER.createArrayNode()
        .add(Json.object().put("id", "new").put("body", "Lives in York"))
        .add(Json.object().put("id", "old").put("body", "x".repeat(4000)))
    );
    var result = LocalModelContext.fit("memory", "rules", data, ModelSchema.schema(Map.of()));
    assertThat(result.path("messages")).hasSize(1);
    assertThat(result.path("messages").get(0).path("id").asText()).isEqualTo("new");
  }
}

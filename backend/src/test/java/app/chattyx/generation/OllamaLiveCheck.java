package app.chattyx.generation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import app.chattyx.operations.BudgetService;
import app.chattyx.shared.Json;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** Opt-in only: mvn -Dtest=OllamaLiveCheck -Dollama.smoke.url=... test. No real conversations. */
class OllamaLiveCheck {

  @Test
  void syntheticEnglishReplyAndMemory() throws Exception {
    String endpoint = System.getProperty("ollama.smoke.url");
    assertThat(endpoint).as("Explicit local Ollama URL is required").isNotBlank();
    var client = new OllamaClient(mock(BudgetService.class), endpoint, 90);
    var reply = new ConfiguredReplyModel(
      (chat, kind, model, prompt, rules, data, images, schema) ->
        client.structured(chat, kind, "qwen3:8b", prompt, rules, data, images, schema),
      "production"
    );
    String sql = new ClassPathResource("db/migration/V1__initial.sql").getContentAsString(
      StandardCharsets.UTF_8
    );
    int start = sql.indexOf("'global','") + "'global','".length();
    var settings = (com.fasterxml.jackson.databind.node.ObjectNode) Json.read(
      sql.substring(start, sql.indexOf("');", start))
    );
    String style = new ClassPathResource("db/migration/V5__style_details.sql").getContentAsString(
      StandardCharsets.UTF_8
    );
    var styles = Json.read(style.substring(style.indexOf("'{") + 1, style.indexOf("}'::") + 1));
    settings.setAll((com.fasterxml.jackson.databind.node.ObjectNode) styles);
    long time = System.nanoTime();
    var result = reply.generate(
      new ReplyModel.Context(
        null,
        settings,
        "",
        List.of(),
        List.of(
          Map.of("source", "CONTACT", "text", "Hey", "new", true),
          Map.of(
            "source",
            "CONTACT",
            "text",
            "Finally finished that project. Completely exhausted lol",
            "new",
            true
          )
        ),
        List.of()
      )
    );
    assertThat(result.action()).isEqualTo("reply");
    assertThat(result.text()).isNotBlank().hasSizeLessThan(801);
    assertThat(result.text()).doesNotContainPattern("[А-Яа-яЁё]");
    System.out.println(
      "SYNTHETIC reply seconds=" + (System.nanoTime() - time) / 1_000_000_000.0 + " text=" + result.text()
    );
    var schema = app.chattyx.memory.MemoryService.extractionSchema();
    String source = "00000000-0000-0000-0000-000000000001";
    var data = Json.object().set(
      "messages",
      Json.MAPPER.createArrayNode().add(
        Json.object().put("id", source).put("source", "CONTACT").put("body", "I moved to York last week.")
      )
    );
    time = System.nanoTime();
    var memory = client.structured(
      null,
      "memory",
      "qwen3:8b",
      "memory-v2",
      app.chattyx.memory.MemoryService.INSTRUCTIONS,
      data,
      List.of(),
      schema
    );
    assertThat(memory.path("facts")).isNotEmpty();
    assertThat(memory.toString()).contains("York", source);
    assertThat(memory.path("facts").get(0).path("subject").asText()).isEqualTo("contact");
    System.out.println(
      "SYNTHETIC memory seconds=" + (System.nanoTime() - time) / 1_000_000_000.0 + " result=" + memory
    );
  }
}

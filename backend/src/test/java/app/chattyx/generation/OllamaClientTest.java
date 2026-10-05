package app.chattyx.generation;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import app.chattyx.operations.BudgetService;
import app.chattyx.shared.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class OllamaClientTest {

  WireMockServer server;
  BudgetService budget;
  OllamaClient client;
  JsonNode schema = ModelSchema.schema(Map.of("text", ModelSchema.stringSchema()));

  @BeforeEach
  void setup() {
    server = new WireMockServer(0);
    server.start();
    budget = mock(BudgetService.class);
    client = new OllamaClient(budget, server.baseUrl() + "/api/chat", 5);
  }

  @AfterEach
  void stop() {
    server.stop();
  }

  JsonNode request() {
    return client.structured(
      null,
      "reply",
      "qwen3:8b",
      "reply-v1",
      "Use untrusted data only.",
      Json.object().put("text", "Synthetic hello"),
      List.of(),
      schema
    );
  }

  String response(String content) {
    return Json.write(
      Json.object()
        .put("done", true)
        .put("done_reason", "stop")
        .put("prompt_eval_count", 20)
        .put("eval_count", 8)
        .set("message", Json.object().put("content", content))
    );
  }

  void fails(String code) {
    assertThatThrownBy(this::request).isInstanceOfSatisfying(ApiException.class, error ->
      assertThat(error.code()).isEqualTo(code)
    );
  }

  @Test
  void localStructuredRequestRecordsZeroCostWithoutCloudCredentials() {
    server.stubFor(post(anyUrl()).willReturn(okJson(response("{\"text\":\"hey!\"}"))));
    assertThat(request().path("text").asText()).isEqualTo("hey!");
    server.verify(
      postRequestedFor(urlEqualTo("/api/chat"))
        .withoutHeader("Authorization")
        .withRequestBody(matchingJsonPath("$.think", equalTo("false")))
        .withRequestBody(matchingJsonPath("$.stream", equalTo("false")))
        .withRequestBody(matchingJsonPath("$.format.type", equalTo("object")))
        .withRequestBody(matchingJsonPath("$.options.num_ctx", equalTo("4096")))
    );
    verify(budget).recordLocal(null, "reply", "qwen3:8b", "reply-v1", 20, 8);
    verifyNoMoreInteractions(budget);
  }

  @Test
  void missingModelAndServerFailureHaveActionableErrors() {
    server.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(404)));
    fails("LOCAL_MODEL_NOT_FOUND");
    server.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(503)));
    fails("LOCAL_MODEL_UNAVAILABLE");
    verifyNoInteractions(budget);
  }

  @Test
  void timeoutReleasesSlotAndDoesNotCreateCloudReservation() {
    client = new OllamaClient(budget, server.baseUrl() + "/api/chat", 1);
    server.stubFor(post(anyUrl()).willReturn(okJson(response("{\"text\":\"hi\"}")).withFixedDelay(1500)));
    fails("LOCAL_MODEL_TIMEOUT");
    server.stubFor(post(anyUrl()).willReturn(okJson(response("{\"text\":\"hi\"}"))));
    assertThat(request().path("text").asText()).isEqualTo("hi");
  }

  @Test
  void malformedOrWrongSchemaAndIncompleteOutputCannotBecomeReplies() {
    for (String content : List.of(
      "broken",
      "null",
      "{\"text\":5}",
      "{}",
      "{\"text\":\"hi\",\"extra\":true}"
    )) {
      server.stubFor(post(anyUrl()).willReturn(okJson(response(content))));
      fails("INVALID_MODEL_REPLY");
    }
    server.stubFor(
      post(anyUrl()).willReturn(okJson(response("{\"text\":\"hi\"}").replace("\"stop\"", "\"length\"")))
    );
    fails("MODEL_INCOMPLETE_RESPONSE");
    verifyNoInteractions(budget);
  }

  @Test
  void replyAndMemoryShareOneSlotWithoutWaitingForLeases() throws Exception {
    client = new OllamaClient(budget, server.baseUrl() + "/api/chat", 5);
    var started = new CountDownLatch(1);
    server.addMockServiceRequestListener((request, response) -> started.countDown());
    server.stubFor(post(anyUrl()).willReturn(okJson(response("{\"text\":\"hi\"}")).withFixedDelay(500)));
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = pool.submit(this::request);
      assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(() ->
        client.structured(
          null,
          "memory",
          "qwen3:8b",
          "memory-v1",
          "Extract facts",
          Json.object(),
          List.of(),
          schema
        )
      ).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("MODEL_BUSY"));
      first.get(3, TimeUnit.SECONDS);
    }
    server.verify(1, postRequestedFor(anyUrl()));
  }

  @Test
  void imagesNeverReachLocalTextModel() {
    assertThatThrownBy(() ->
      client.structured(
        null,
        "reply",
        "qwen3:8b",
        "reply-v1",
        "rules",
        Json.object(),
        List.of("synthetic-image"),
        schema
      )
    ).isInstanceOfSatisfying(ApiException.class, error ->
      assertThat(error.code()).isEqualTo("LOCAL_MEDIA_UNSUPPORTED")
    );
    server.verify(0, postRequestedFor(anyUrl()));
  }
}

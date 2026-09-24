package app.chattyx.generation;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import app.chattyx.operations.BudgetService;
import app.chattyx.personas.SettingsService;
import app.chattyx.shared.*;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.*;
import org.junit.jupiter.api.*;

class OpenAiClientTest {

  WireMockServer server;
  BudgetService budget;
  OpenAiClient client;
  UUID reservation;

  @BeforeEach
  void setup() {
    server = new WireMockServer(0);
    server.start();
    var settings = mock(SettingsService.class);
    budget = mock(BudgetService.class);
    reservation = UUID.randomUUID();
    when(settings.credential("openai")).thenReturn(Optional.of("synthetic-api-key"));
    when(budget.reserve(any(), anyString(), anyString(), anyString(), any())).thenReturn(reservation);
    client = new OpenAiClient(settings, budget, server.baseUrl() + "/v1/responses");
  }

  @AfterEach
  void stop() {
    server.stop();
  }

  Object request() {
    return client.structured(
      UUID.randomUUID(),
      "reply",
      "gpt-5.4",
      "reply-v1",
      "system rule",
      Json.object().put("text", "untrusted input"),
      List.of(),
      OpenAiClient.schema(Map.of("action", OpenAiClient.stringSchema()))
    );
  }

  @Test
  void localContextUsesStructuredOutputWithoutRemoteStorage() {
    server.stubFor(
      post(urlEqualTo("/v1/responses")).willReturn(
        okJson(
          "{\"status\":\"completed\",\"usage\":{\"input_tokens\":100,\"output_tokens\":20},\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"{\\\"action\\\":\\\"skip\\\"}\"}]}]}"
        )
      )
    );
    assertThat(request().toString()).contains("skip");
    server.verify(
      postRequestedFor(urlEqualTo("/v1/responses"))
        .withRequestBody(matchingJsonPath("$.store", equalTo("false")))
        .withRequestBody(matchingJsonPath("$.text.format.strict", equalTo("true")))
    );
    verify(budget).complete(eq(reservation), eq(100L), eq(20L), any());
  }

  @Test
  void explicitRejectionReleasesReservation() {
    server.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(429)));
    assertThatThrownBy(this::request).isInstanceOf(ApiException.class);
    verify(budget).release(reservation);
    verify(budget, never()).complete(any(), anyLong(), anyLong(), any());
  }

  @Test
  void serverFailureRetainsUnknownCost() {
    server.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(503)));
    assertThatThrownBy(this::request).isInstanceOf(ApiException.class);
    verify(budget).unknown(reservation);
    verify(budget, never()).release(any());
  }

  @Test
  void malformedOutputCannotBecomeAReply() {
    server.stubFor(
      post(anyUrl()).willReturn(
        okJson(
          "{\"status\":\"completed\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1},\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"not json\"}]}]}"
        )
      )
    );
    assertThatThrownBy(this::request).isInstanceOf(ApiException.class);
    verify(budget).complete(eq(reservation), eq(1L), eq(1L), any());
  }
}

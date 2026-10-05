package app.chattyx.connections;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import app.chattyx.identity.SecretBox;
import app.chattyx.shared.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;

class TelegramAuthorizationTest {

  @TempDir
  Path directory;

  final UUID account = UUID.randomUUID();
  final TdTransport transport = mock(TdTransport.class);
  final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  final List<Consumer<JsonNode>> clients = new ArrayList<>();
  final List<MessagingAdapter.Event> events = new ArrayList<>();
  TelegramAdapter adapter;

  @BeforeEach
  void setup() {
    when(transport.create(any())).thenAnswer(call -> {
      clients.add(call.getArgument(0));
      return clients.size();
    });
    when(transport.async(anyInt(), any())).thenAnswer(call ->
      CompletableFuture.completedFuture(Json.object("ok"))
    );
    when(transport.call(anyInt(), any())).thenReturn(Json.object("ok"));
    adapter = new TelegramAdapter(
      new Db(jdbc),
      new SecretBox("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="),
      12345,
      "synthetic-api-hash",
      directory.toString(),
      () -> transport
    );
    adapter.events(events::add);
  }

  void state(int client, String type) {
    state(client, Json.object(type));
  }

  void state(int client, ObjectNode state) {
    clients.get(client - 1).accept(Json.object("updateAuthorizationState").set("authorization_state", state));
  }

  @Test
  void qrWaitsForNativeStateAndRefreshesLinkWithoutAnotherLoginRequest() {
    assertThat(adapter.beginAuthorization(account, "qr").step()).isEqualTo("INITIALIZING");
    state(1, "authorizationStateWaitPhoneNumber");
    assertThat(adapter.authorization(account).step()).isEqualTo("QR_PENDING");
    state(
      1,
      Json.object("authorizationStateWaitOtherDeviceConfirmation").put(
        "link",
        "tg://login?token=synthetic-one"
      )
    );
    adapter.beginAuthorization(account, "qr");
    state(
      1,
      Json.object("authorizationStateWaitOtherDeviceConfirmation").put(
        "link",
        "tg://login?token=synthetic-two"
      )
    );
    assertThat(adapter.authorization(account).fields()).containsEntry(
      "link",
      "tg://login?token=synthetic-two"
    );
    verify(transport, times(1)).async(
      eq(1),
      argThat(q -> q.path("@type").asText().equals("requestQrCodeAuthentication"))
    );
    assertThat(
      events
        .stream()
        .filter(MessagingAdapter.State.class::isInstance)
        .map(MessagingAdapter.State.class::cast)
        .map(MessagingAdapter.State::fields)
    ).allMatch(Map::isEmpty);
  }

  @Test
  void discoveryCarriesLastMessageDateInsteadOfDiscoveryTime() {
    adapter.beginAuthorization(account,"phone");
    var chat=Json.object("chat").put("id",123).put("title","Synthetic chat");
    chat.set("type",Json.object("chatTypePrivate").put("user_id",77));
    chat.set("last_message",Json.object("message").put("date",1700000000));
    clients.getFirst().accept(Json.object("updateNewChat").set("chat",chat));
    assertThat(events).contains(new MessagingAdapter.Chat(account,"123","Synthetic chat",true,java.time.Instant.ofEpochSecond(1700000000)));
  }

  @Test
  void lastMessageUpdatesCarryOnlyDateAndHandleUnknownLastMessage() {
    adapter.beginAuthorization(account,"phone");
    var update=Json.object("updateChatLastMessage").put("chat_id",123);
    update.set("last_message",Json.object("message").put("date",1700000001).put("body","Do not store preview"));
    clients.getFirst().accept(update);
    assertThat(events).contains(new MessagingAdapter.ChatActivity(account,"123",java.time.Instant.ofEpochSecond(1700000001)));
    update.putNull("last_message");clients.getFirst().accept(update);
    assertThat(events).contains(new MessagingAdapter.ChatActivity(account,"123",null));
    assertThat(events.toString()).doesNotContain("Do not store preview");
  }

  @Test
  void switchingQrToPhoneWaitsForCloseAndIgnoresOldClientEvents() {
    adapter.beginAuthorization(account, "qr");
    state(1, "authorizationStateWaitPhoneNumber");
    state(
      1,
      Json.object("authorizationStateWaitOtherDeviceConfirmation").put("link", "tg://login?token=synthetic")
    );
    assertThat(adapter.beginAuthorization(account, "phone").step()).isEqualTo("CLOSING");
    assertThat(clients).hasSize(1);
    state(1, "authorizationStateClosing");
    state(1, "authorizationStateClosed");
    assertThat(clients).hasSize(2);
    verify(transport).release(1);
    state(2, "authorizationStateWaitPhoneNumber");
    state(1, "authorizationStateClosed");
    state(1, "authorizationStateReady");
    assertThat(adapter.authorization(account).step()).isEqualTo("PHONE");
    adapter.submitAuthorization(account, "PHONE", "+15550000000");
    verify(transport).call(eq(2), argThat(q -> q.path("phone_number").asText().equals("+15550000000")));
  }

  @ParameterizedTest
  @CsvSource({
    "authorizationStateWaitPhoneNumber,PHONE,setAuthenticationPhoneNumber,phone_number",
    "authorizationStateWaitCode,CODE,checkAuthenticationCode,code",
    "authorizationStateWaitPassword,PASSWORD,checkAuthenticationPassword,password",
    "authorizationStateWaitEmailAddress,EMAIL,setAuthenticationEmailAddress,email_address",
    "authorizationStateWaitEmailCode,EMAIL_CODE,checkAuthenticationEmailCode,code",
  })
  void challengeIsSubmittedOnlyForItsCurrentStep(
    String nativeStep,
    String step,
    String requestType,
    String field
  ) {
    adapter.beginAuthorization(account, "phone");
    state(1, nativeStep);
    adapter.submitAuthorization(account, step, "synthetic-value");
    verify(transport).call(
      eq(1),
      argThat(
        q ->
          q.path("@type").asText().equals(requestType) &&
          (step.equals("EMAIL_CODE") ? q.path(field).path("code") : q.path(field))
            .asText()
            .equals("synthetic-value")
      )
    );
    assertThat(adapter.authorization(account).fields()).isEmpty();
    assertThat(events.toString()).doesNotContain("synthetic-value");
  }

  @Test
  void staleChallengeNeverReachesTelegram() {
    adapter.beginAuthorization(account, "phone");
    state(1, "authorizationStateWaitCode");
    state(1, "authorizationStateWaitPassword");
    assertThatThrownBy(() -> adapter.submitAuthorization(account, "CODE", "12345")).isInstanceOfSatisfying(
      ApiException.class,
      e -> assertThat(e.code()).isEqualTo("TELEGRAM_AUTH_STEP_CHANGED")
    );
    verify(transport, never()).call(anyInt(), any());
  }

  @Test
  void rejectedCodePreservesCurrentChallengeForRetry() {
    adapter.beginAuthorization(account, "phone");
    state(1, "authorizationStateWaitCode");
    when(transport.call(anyInt(), any())).thenThrow(new ApiException(502, "TELEGRAM_REQUEST_REJECTED"));
    assertThatThrownBy(() -> adapter.submitAuthorization(account, "CODE", "12345")).isInstanceOf(
      ApiException.class
    );
    assertThat(adapter.authorization(account).step()).isEqualTo("CODE");
  }

  @Test
  void initializationFailureIsVisibleWithoutRawProviderDetails() {
    when(
      transport.async(anyInt(), argThat(q -> q.path("@type").asText().equals("setTdlibParameters")))
    ).thenReturn(
      CompletableFuture.completedFuture(
        Json.object("error").put("code", 400).put("message", "secret-provider-detail")
      )
    );
    adapter.beginAuthorization(account, "phone");
    state(1, "authorizationStateWaitTdlibParameters");
    assertThat(adapter.authorization(account).step()).isEqualTo("ERROR");
    assertThat(adapter.authorization(account).fields()).containsEntry("code", "TELEGRAM_AUTH_REQUEST_FAILED");
    assertThat(events.toString()).doesNotContain("secret-provider-detail");
  }

  @Test
  void duplicateParameterUpdatesDoNotCreateTwoDatabaseKeys() {
    adapter.beginAuthorization(account, "phone");
    state(1, "authorizationStateWaitTdlibParameters");
    state(1, "authorizationStateWaitTdlibParameters");
    verify(transport, times(1)).async(
      eq(1),
      argThat(
        q ->
          q.path("@type").asText().equals("setTdlibParameters") &&
          q
            .path("database_directory")
            .asText()
            .equals(directory.resolve("telegram").resolve(account.toString()).toString()) &&
          !q.path("use_secret_chats").asBoolean()
      )
    );
  }

  @Test
  void delayedErrorFromOldClientCannotBreakNewLogin() {
    var pending = new CompletableFuture<JsonNode>();
    when(
      transport.async(eq(1), argThat(q -> q.path("@type").asText().equals("requestQrCodeAuthentication")))
    ).thenReturn(pending);
    adapter.beginAuthorization(account, "qr");
    state(1, "authorizationStateWaitPhoneNumber");
    adapter.beginAuthorization(account, "phone");
    state(1, "authorizationStateClosed");
    state(2, "authorizationStateWaitPhoneNumber");
    pending.completeExceptionally(new IllegalStateException("synthetic-timeout"));
    assertThat(adapter.authorization(account).step()).isEqualTo("PHONE");
  }

  @Test
  void readyRequiresIdentityAndDoesNotEnableAutomation() {
    adapter.beginAuthorization(account, "restore");
    when(transport.async(eq(1), argThat(q -> q.path("@type").asText().equals("getMe")))).thenReturn(
      CompletableFuture.completedFuture(Json.object("user").put("id", 42))
    );
    state(1, "authorizationStateReady");
    assertThat(adapter.authorization(account).step()).isEqualTo("READY");
    verify(jdbc).update("UPDATE connection SET self_id=? WHERE id=?", "42", account);
    verifyNoMoreInteractions(jdbc);
    adapter.beginAuthorization(account, "qr");
    assertThat(clients).hasSize(1);
  }

  @Test
  void invalidIdentityDoesNotReportReady() {
    adapter.beginAuthorization(account, "phone");
    state(1, "authorizationStateReady");
    assertThat(adapter.authorization(account).step()).isEqualTo("ERROR");
  }

  @Test
  void disconnectDuringIdentityLookupCannotRestoreReady() {
    adapter.beginAuthorization(account, "phone");
    var pending = new CompletableFuture<JsonNode>();
    when(transport.async(eq(1), argThat(q -> q.path("@type").asText().equals("getMe")))).thenReturn(pending);
    state(1, "authorizationStateReady");
    adapter.disconnect(account, false);
    pending.complete(Json.object("user").put("id", 42));
    assertThat(adapter.authorization(account).step()).isEqualTo("CLOSING");
    verifyNoInteractions(jdbc);
  }

  @Test
  void failedDisconnectDoesNotRestoreReadyOrOpenAnotherClient() {
    adapter.beginAuthorization(account, "phone");
    when(transport.call(eq(1), any())).thenThrow(new ApiException(504, "TELEGRAM_RESULT_UNKNOWN"));
    assertThatThrownBy(() -> adapter.disconnect(account, false)).isInstanceOf(ApiException.class);
    assertThat(adapter.authorization(account).step()).isEqualTo("ERROR");
    assertThat(adapter.beginAuthorization(account, "phone").step()).isEqualTo("CLOSING");
    assertThat(clients).hasSize(1);
    state(1, "authorizationStateClosed");
    assertThat(clients).hasSize(2);
  }

  @Test
  void loggingOutRetainsClientUntilClosedAndAllowsFreshAuthorizationAfterwards() {
    adapter.beginAuthorization(account, "phone");
    adapter.disconnect(account, true);
    state(1, "authorizationStateLoggingOut");
    assertThatThrownBy(() -> adapter.beginAuthorization(account, "phone")).isInstanceOf(ApiException.class);
    state(1, "authorizationStateClosed");
    adapter.beginAuthorization(account, "phone");
    assertThat(clients).hasSize(2);
    verify(transport).call(eq(1), argThat(q -> q.path("@type").asText().equals("logOut")));
  }

  @Test
  void independentAccountsKeepIndependentLoginMethods() {
    UUID second = UUID.randomUUID();
    adapter.beginAuthorization(account, "qr");
    adapter.beginAuthorization(second, "phone");
    state(1, "authorizationStateWaitPhoneNumber");
    state(2, "authorizationStateWaitPhoneNumber");
    assertThat(adapter.authorization(account).step()).isEqualTo("QR_PENDING");
    assertThat(adapter.authorization(second).step()).isEqualTo("PHONE");
  }

  @Test
  void unsupportedNativeStepIsExplicitAndCannotAcceptAChallenge() {
    adapter.beginAuthorization(account, "phone");
    state(1, "authorizationStateWaitRegistration");
    assertThat(adapter.authorization(account).step()).isEqualTo("UNSUPPORTED_STEP");
    assertThatThrownBy(() -> adapter.submitAuthorization(account, "PHONE", "+15550000000")).isInstanceOf(
      ApiException.class
    );
    verify(transport, never()).call(anyInt(), any());
  }

  @Test
  void invalidMethodAndMissingCredentialsFailBeforeCreatingNativeClient() {
    assertThatThrownBy(() -> adapter.beginAuthorization(account, "unknown")).isInstanceOf(ApiException.class);
    var missing = new TelegramAdapter(
      new Db(jdbc),
      new SecretBox("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="),
      0,
      "",
      directory.toString(),
      () -> transport
    );
    assertThatThrownBy(() -> missing.beginAuthorization(account, "phone")).isInstanceOf(ApiException.class);
    verifyNoInteractions(transport);
  }
}

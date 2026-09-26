package app.chattyx.connections;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import app.chattyx.conversations.ConversationService;
import app.chattyx.shared.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(
  properties = {
    "chatty.mode=demo",
    "chatty.workers-enabled=false",
    "chatty.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "chatty.bootstrap-token=synthetic-bootstrap-token-for-tests-only",
  }
)
@Import(HistoryImportIT.TimeConfiguration.class)
class HistoryImportIT {

  static final Instant NOW = Instant.parse("2026-09-25T12:00:00Z");

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @TestConfiguration
  static class TimeConfiguration {

    @Bean
    @Primary
    Clock importClock() {
      return Clock.fixed(NOW.plusMillis(750), ZoneOffset.UTC);
    }
  }

  @Autowired
  Db db;

  @Autowired
  ConnectionService connections;

  @Autowired
  ConversationService chats;

  @Autowired
  HistoryImportService history;

  @MockitoSpyBean
  FakeMessagingAdapter fake;

  UUID account;
  UUID chat;

  @BeforeEach
  void setup() {
    db.jdbc.execute("TRUNCATE connection CASCADE");
    account = connections.create("Synthetic import account", "fake");
    connections.authorize(account, "test");
    chat = db.jdbc.queryForObject(
      "SELECT id FROM conversation WHERE connection_id=? AND external_id='1001'",
      UUID.class,
      account
    );
    db.jdbc.update("UPDATE conversation SET selected=true,imported=true,mode='AUTO' WHERE id=?", chat);
  }

  MessagingAdapter.Incoming message(String id, Instant at) {
    // Deliberately not historical: the importing application must enforce that property itself.
    return new MessagingAdapter.Incoming(
      account,
      "1001",
      id,
      "Synthetic " + id,
      at,
      false,
      false,
      false,
      null,
      null,
      null
    );
  }

  int count(String table) {
    return db.jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
  }

  void complete() throws Exception {
    history.start(chat).get(10, TimeUnit.SECONDS);
  }

  void assertPaused() {
    assertThat(chats.get(chat))
      .containsEntry("mode", "PAUSED")
      .containsEntry("imported", true)
      .containsEntry("importRunId", null);
    assertThat(count("automation_job")).isZero();
    verify(fake, never()).sendText(any());
  }

  @Test
  void repeatedImportDeduplicatesAndNeverSchedulesOldReplies() throws Exception {
    doReturn(
      new MessagingAdapter.History(
        List.of(message("a", NOW.minusSeconds(60)), message("a", NOW.minusSeconds(60))),
        "a"
      )
    )
      .when(fake)
      .fetchHistory(eq(account), eq("1001"), isNull(), anyInt());
    doReturn(new MessagingAdapter.History(List.of(message("b", NOW.minusSeconds(120))), null))
      .when(fake)
      .fetchHistory(eq(account), eq("1001"), eq("a"), anyInt());
    complete();
    complete();
    assertThat(count("message")).isEqualTo(2);
    assertThat(chats.get(chat)).containsEntry("importCount", 2);
    assertThat(
      db.jdbc.queryForObject("SELECT count(*) FROM message WHERE historical AND handled", Integer.class)
    ).isEqualTo(2);
    assertPaused();
  }

  @Test
  void selectingChatWhileAuthorizationIsPendingDoesNotPartiallySelectIt() {
    db.jdbc.update("UPDATE connection SET status='AUTHORIZING' WHERE id=?", account);
    db.jdbc.update("UPDATE conversation SET selected=false,imported=false,mode='PAUSED' WHERE id=?", chat);
    var before = chats.get(chat);
    assertThatThrownBy(() -> connections.select(chat, true)).isInstanceOfSatisfying(
      ApiException.class,
      error -> assertThat(error.code()).isEqualTo("TELEGRAM_NOT_CONNECTED")
    );
    assertThat(chats.get(chat))
      .containsEntry("selected", false)
      .containsEntry("importRunId", null)
      .containsEntry("version", before.get("version"))
      .containsEntry("connectionStatus", "AUTHORIZING");
    assertThat(
      chats
        .list()
        .stream()
        .filter(row -> row.get("id").equals(chat.toString()))
        .findFirst()
        .orElseThrow()
    ).containsEntry("connectionStatus", "AUTHORIZING");
    verify(fake, never()).fetchHistory(any(), anyString(), any(), anyInt());
  }

  @Test
  void disconnectedImportCanBeRetriedOnSameAccountAfterAuthorization() throws Exception {
    db.jdbc.update("UPDATE connection SET status='DISCONNECTED',enabled=false WHERE id=?", account);
    db.jdbc.update("UPDATE conversation SET imported=false,mode='PAUSED' WHERE id=?", chat);
    assertThatThrownBy(() -> history.start(chat)).isInstanceOf(ApiException.class);
    assertThat(chats.get(chat)).containsEntry("importRunId", null).containsEntry("mode", "PAUSED");
    connections.authorize(account, "test");
    complete();
    assertPaused();
    assertThat(count("connection")).isEqualTo(1);
    assertThat(db.one("SELECT enabled FROM connection WHERE id=?", account)).containsEntry("enabled", false);
  }

  @Test
  void importHonorsThirtyDayCutoffIncludingBoundary() throws Exception {
    Instant cutoff = NOW.minus(Duration.ofDays(30));
    doReturn(
      new MessagingAdapter.History(
        List.of(message("new", NOW), message("boundary", cutoff), message("too-old", cutoff.minusSeconds(1))),
        "too-old"
      )
    )
      .when(fake)
      .fetchHistory(any(), anyString(), any(), anyInt());
    complete();
    assertThat(count("message")).isEqualTo(2);
    verify(fake, times(1)).fetchHistory(any(), anyString(), any(), anyInt());
    assertPaused();
  }

  @Test
  void capsHistoryAtOneThousandEvenIfProviderOverfillsPage() throws Exception {
    var messages = new ArrayList<MessagingAdapter.Incoming>();
    for (int n = 0; n < 1005; n++) messages.add(message("limit-" + n, NOW.minusSeconds(n)));
    doReturn(new MessagingAdapter.History(messages, "more"))
      .when(fake)
      .fetchHistory(any(), anyString(), any(), anyInt());
    complete();
    assertThat(count("message")).isEqualTo(1000);
    assertThat(chats.get(chat)).containsEntry("importCount", 1000);
    assertPaused();
  }

  @Test
  void liveMessageDuringImportKeepsItsOriginAndManualPause() throws Exception {
    doAnswer(call -> {
      chats.receive(message("live", NOW));
      chats.receive(
        new MessagingAdapter.Incoming(
          account,
          "1001",
          "owner",
          "Synthetic owner",
          NOW,
          true,
          false,
          false,
          null,
          null,
          null
        )
      );
      return new MessagingAdapter.History(
        List.of(message("live", NOW), message("old", NOW.minusSeconds(60))),
        null
      );
    })
      .when(fake)
      .fetchHistory(any(), anyString(), any(), anyInt());
    complete();
    assertThat(count("message")).isEqualTo(3);
    assertThat(db.one("SELECT historical,handled FROM message WHERE external_id='live'"))
      .containsEntry("historical", false)
      .containsEntry("handled", false);
    assertThat(chats.get(chat)).containsEntry("needsAttention", "MANUAL_TAKEOVER");
    assertPaused();
  }

  @Test
  void cancelledPageCannotAffectImmediatelyRestartedImport() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(call -> {
      entered.countDown();
      if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test deadline");
      return new MessagingAdapter.History(List.of(message("cancelled", NOW)), null);
    })
      .when(fake)
      .fetchHistory(any(), anyString(), any(), anyInt());
    var first = history.start(chat);
    try {
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      history.start(chat).get(5, TimeUnit.SECONDS); // Duplicate start is a no-op, not a new task.
      verify(fake, times(1)).fetchHistory(any(), anyString(), any(), anyInt());
      history.cancel(chat);
      assertThat(chats.get(chat)).containsEntry("imported", false).containsEntry("importCancelled", true);
      assertThatThrownBy(() -> chats.control(chat, "resume")).isInstanceOf(ApiException.class);
      doReturn(new MessagingAdapter.History(List.of(message("fresh", NOW)), null))
        .when(fake)
        .fetchHistory(any(), anyString(), any(), anyInt());
      complete();
    } finally {
      release.countDown();
      first.get(10, TimeUnit.SECONDS);
    }
    assertThat(count("message")).isEqualTo(1);
    assertThat(db.jdbc.queryForObject("SELECT external_id FROM message", String.class)).isEqualTo("fresh");
    assertPaused();
  }

  @Test
  void liveEventAfterHistoryPagePreservesOriginWithoutReopeningOldMessages() throws Exception {
    doReturn(
      new MessagingAdapter.History(
        List.of(message("live-later", NOW), message("old", NOW.minusSeconds(60))),
        "old"
      )
    )
      .when(fake)
      .fetchHistory(any(), anyString(), isNull(), anyInt());
    doAnswer(call -> {
      chats.receive(message("live-later", NOW));
      chats.receive(message("old", NOW.minusSeconds(60)));
      return new MessagingAdapter.History(List.of(), null);
    })
      .when(fake)
      .fetchHistory(any(), anyString(), eq("old"), anyInt());
    complete();
    assertThat(count("message")).isEqualTo(2);
    assertThat(db.one("SELECT historical,handled FROM message WHERE external_id='live-later'"))
      .containsEntry("historical", false)
      .containsEntry("handled", false);
    assertThat(db.one("SELECT historical,handled FROM message WHERE external_id='old'"))
      .containsEntry("historical", true)
      .containsEntry("handled", true);
    assertPaused();
  }

  @Test
  void deselectionDiscardsPageAndDoesNotScheduleMemory() throws Exception {
    doAnswer(call -> {
      connections.select(chat, false);
      return new MessagingAdapter.History(List.of(message("ignored", NOW)), null);
    })
      .when(fake)
      .fetchHistory(any(), anyString(), any(), anyInt());
    complete();
    assertThat(count("message")).isZero();
    assertThat(count("memory_job")).isZero();
    assertThat(chats.get(chat)).containsEntry("selected", false).containsEntry("imported", false);
  }

  @Test
  void failureRetainsCommittedPagesAndCanBeRetriedWithoutDuplicates() throws Exception {
    doReturn(new MessagingAdapter.History(List.of(message("first", NOW)), "first"))
      .when(fake)
      .fetchHistory(any(), anyString(), isNull(), anyInt());
    doThrow(new ApiException(503, "SYNTHETIC_PROVIDER_FAILURE"))
      .when(fake)
      .fetchHistory(any(), anyString(), eq("first"), anyInt());
    complete();
    assertThat(chats.get(chat))
      .containsEntry("imported", false)
      .containsEntry("needsAttention", "IMPORT_FAILED");
    assertThat(count("message")).isEqualTo(1);
    assertThat(count("memory_job")).isZero();
    doReturn(new MessagingAdapter.History(List.of(message("second", NOW.minusSeconds(60))), null))
      .when(fake)
      .fetchHistory(any(), anyString(), eq("first"), anyInt());
    complete();
    assertThat(count("message")).isEqualTo(2);
    assertPaused();
  }

  @Test
  void repeatedCursorIsFailureInsteadOfFalseCompletion() throws Exception {
    doReturn(new MessagingAdapter.History(List.of(message("same", NOW)), "same"))
      .when(fake)
      .fetchHistory(any(), anyString(), any(), anyInt());
    complete();
    assertThat(chats.get(chat))
      .containsEntry("imported", false)
      .containsEntry("needsAttention", "IMPORT_FAILED");
    assertThat(count("message")).isEqualTo(1);
  }

  @Test
  void pageFromAnotherChatRollsBackWithoutLeakingMessages() throws Exception {
    var foreign = new MessagingAdapter.Incoming(
      account,
      "1002",
      "foreign",
      "Synthetic private text",
      NOW,
      false,
      true,
      false,
      null,
      null,
      null
    );
    doReturn(new MessagingAdapter.History(List.of(message("valid", NOW), foreign), null))
      .when(fake)
      .fetchHistory(any(), anyString(), any(), anyInt());
    complete();
    assertThat(count("message")).isZero();
    assertThat(count("memory_job")).isZero();
    assertThat(chats.get(chat))
      .containsEntry("imported", false)
      .containsEntry("needsAttention", "IMPORT_FAILED");
  }

  @Test
  void interruptedImportRequiresExplicitRetryAndStaysPaused() throws Exception {
    db.jdbc.update(
      "UPDATE conversation SET status='IMPORTING',import_run_id=?,imported=false WHERE id=?",
      UUID.randomUUID(),
      chat
    );
    history.recoverInterrupted();
    history.recoverInterrupted();
    assertThat(chats.get(chat))
      .containsEntry("mode", "PAUSED")
      .containsEntry("imported", false)
      .containsEntry("importRunId", null)
      .containsEntry("needsAttention", "IMPORT_INTERRUPTED");
    complete();
    assertPaused();
  }

  @Test
  void startingImportCancelsAlreadyPreparedAutomaticReplyAndBlocksResume() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    db.jdbc.update(
      "INSERT INTO automation_job(conversation_id,context_version,first_at,due_at) VALUES (?,0,now(),now())",
      chat
    );
    db.jdbc.update(
      "INSERT INTO outbound(id,conversation_id,request_key,context_version,body,source,status,due_at) VALUES (?,?,?,0,'Synthetic','AUTOMATION','READY',now())",
      UUID.randomUUID(),
      chat,
      UUID.randomUUID().toString()
    );
    doAnswer(call -> {
      entered.countDown();
      if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test deadline");
      return new MessagingAdapter.History(List.of(), null);
    })
      .when(fake)
      .fetchHistory(any(), anyString(), any(), anyInt());
    var run = history.start(chat);
    try {
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(chats.get(chat)).containsEntry("mode", "PAUSED").containsEntry("imported", false);
      assertThatThrownBy(() -> chats.control(chat, "resume")).isInstanceOf(ApiException.class);
      assertThat(db.jdbc.queryForObject("SELECT status FROM outbound", String.class)).isEqualTo("CANCELLED");
    } finally {
      release.countDown();
      run.get(10, TimeUnit.SECONDS);
    }
    assertPaused();
  }
}

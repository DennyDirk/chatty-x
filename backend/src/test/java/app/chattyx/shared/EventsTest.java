package app.chattyx.shared;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class EventsTest {

  Events events;
  SseEmitter emitter;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setup() {
    events = new Events();
    emitter = mock(SseEmitter.class);
    ((List<SseEmitter>) ReflectionTestUtils.getField(events, "clients")).add(emitter);
    TransactionSynchronizationManager.initSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(true);
  }

  @AfterEach
  void cleanup() {
    TransactionSynchronizationManager.clearSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(false);
  }

  @Test
  void oneRefreshAfterCommitNeverBeforeIt() throws Exception {
    events.changed();
    events.changed();
    verifyNoInteractions(emitter);
    var synchronizations = TransactionSynchronizationManager.getSynchronizations();
    assertThat(synchronizations).hasSize(1);
    synchronizations.forEach(TransactionSynchronization::afterCommit);
    verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
  }

  @Test
  void rollbackDoesNotPublishRefresh() {
    events.changed();
    TransactionSynchronizationManager.getSynchronizations().forEach(s ->
      s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK)
    );
    verifyNoInteractions(emitter);
  }
}

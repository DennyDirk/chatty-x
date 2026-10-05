package app.chattyx.shared;

import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class Events {

  private final CopyOnWriteArrayList<SseEmitter> clients = new CopyOnWriteArrayList<>();

  public SseEmitter subscribe() {
    var e = new SseEmitter(60_000L);
    clients.add(e);
    e.onCompletion(() -> clients.remove(e));
    e.onTimeout(() -> {
      clients.remove(e);
      e.complete();
    });
    e.onError(x -> clients.remove(e));
    try {
      e.send(SseEmitter.event().name("refresh").data("ready"));
    } catch (Exception x) {
      clients.remove(e);
    }
    return e;
  }

  public void changed() {
    if (
      TransactionSynchronizationManager.isActualTransactionActive() &&
      TransactionSynchronizationManager.isSynchronizationActive()
    ) {
      if (
        TransactionSynchronizationManager.getSynchronizations()
          .stream()
          .noneMatch(RefreshAfterCommit.class::isInstance)
      ) TransactionSynchronizationManager.registerSynchronization(new RefreshAfterCommit());
      return;
    }
    broadcast();
  }

  private class RefreshAfterCommit implements TransactionSynchronization {

    @Override
    public void afterCommit() {
      broadcast();
    }
  }

  private void broadcast() {
    for (var e : clients)
      try {
        e.send(SseEmitter.event().name("refresh").data("changed"));
      } catch (Exception x) {
        clients.remove(e);
        e.complete();
      }
  }
}

package app.chattyx.shared;

import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;
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
    for (var e : clients)
      try {
        e.send(SseEmitter.event().name("refresh").data("changed"));
      } catch (Exception x) {
        clients.remove(e);
        e.complete();
      }
  }
}

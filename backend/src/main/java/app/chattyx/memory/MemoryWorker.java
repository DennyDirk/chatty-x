package app.chattyx.memory;

import app.chattyx.shared.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MemoryWorker {

  private final Db db;
  private final MemoryService memory;
  private final Events events;
  private final boolean enabled;
  private final AtomicBoolean running = new AtomicBoolean();

  public MemoryWorker(
    Db db,
    MemoryService memory,
    Events events,
    @Value("${chatty.workers-enabled}") boolean enabled
  ) {
    this.db = db;
    this.memory = memory;
    this.events = events;
    this.enabled = enabled;
  }

  @Scheduled(fixedDelay = 5000)
  public void tick() {
    if (!enabled || !running.compareAndSet(false, true)) return;
    Thread.ofVirtual()
      .name("memory-worker")
      .start(() -> {
        try {
          var jobs = db.list(
            "SELECT j.* FROM memory_job j WHERE due_at<=now() AND NOT EXISTS (SELECT 1 FROM automation_job a WHERE a.conversation_id=j.conversation_id) AND NOT EXISTS (SELECT 1 FROM outbound o WHERE o.conversation_id=j.conversation_id AND o.status IN ('READY','SENDING','SUBMITTED','DRAFT')) ORDER BY due_at LIMIT 1"
          );
          for (var job : jobs) {
            UUID chat = UUID.fromString(job.get("conversationId").toString());
            long revision = ((Number) job.get("revision")).longValue();
            try {
              if (memory.refresh(chat)) db.jdbc.update(
                "DELETE FROM memory_job WHERE conversation_id=? AND revision=?",
                chat,
                revision
              );
              else db.jdbc.update(
                "UPDATE memory_job SET due_at=now()+interval '30 seconds' WHERE conversation_id=?",
                chat
              );
            } catch (Exception error) {
              String category = error instanceof ApiException api ? api.code() : "MEMORY_UNAVAILABLE";
              db.jdbc.update(
                "UPDATE memory_job SET attempts=attempts+1,error_category=?,due_at=now()+interval '1 hour' WHERE conversation_id=?",
                category,
                chat
              );
            }
            events.changed();
          }
        } finally {
          running.set(false);
        }
      });
  }
}

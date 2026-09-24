package app.chattyx.operations;
import app.chattyx.shared.Events;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/conversations")
public class DataController {
  private final RetentionService retention;private final Events events;
  public DataController(RetentionService retention,Events events){this.retention=retention;this.events=events;}
  @DeleteMapping("/{id}/local-data") public void erase(@PathVariable UUID id){retention.eraseConversation(id);events.changed();}
}

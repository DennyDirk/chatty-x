package app.chattyx.operations;

import app.chattyx.shared.Db;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class HealthController {

  private final Db db;

  public HealthController(Db db) {
    this.db = db;
  }

  @GetMapping("/health")
  public Object health() {
    db.jdbc.queryForObject("SELECT 1", Integer.class);
    return Map.of("status", "ok");
  }
}

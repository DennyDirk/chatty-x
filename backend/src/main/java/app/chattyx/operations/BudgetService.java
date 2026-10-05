package app.chattyx.operations;

import app.chattyx.personas.SettingsService;
import app.chattyx.shared.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BudgetService {

  private final Db db;
  private final SettingsService settings;
  private final Clock clock;

  public BudgetService(Db db, SettingsService settings, Clock clock) {
    this.db = db;
    this.settings = settings;
    this.clock = clock;
  }

  public BigDecimal used() {
    return db.jdbc.queryForObject(
      "SELECT COALESCE(sum(COALESCE(actual_usd,reserved_usd)),0) FROM usage_entry WHERE created_at>=? AND state<>'RELEASED'",
      BigDecimal.class,
      Timestamp.from(
        clock
          .instant()
          .atZone(ZoneOffset.UTC)
          .withDayOfMonth(1)
          .toLocalDate()
          .atStartOfDay(ZoneOffset.UTC)
          .toInstant()
      )
    );
  }

  @Transactional
  public UUID reserve(UUID conversation, String kind, String model, String prompt, BigDecimal estimate) {
    db.jdbc.execute("SELECT pg_advisory_xact_lock(7261023)");
    BigDecimal limit = new BigDecimal(settings.read("global").path("monthlyBudgetUsd").asText("30"));
    if (estimate.signum() < 0 || used().add(estimate).compareTo(limit) > 0) throw new ApiException(
      409,
      "BUDGET_EXHAUSTED"
    );
    UUID id = UUID.randomUUID();
    db.jdbc.update(
      "INSERT INTO usage_entry(id,conversation_id,kind,model,prompt_version,state,reserved_usd,created_at) VALUES (?,?,?,?,?,'RESERVED',?,?)",
      id,
      conversation,
      kind,
      model,
      prompt,
      estimate,
      Timestamp.from(clock.instant())
    );
    return id;
  }

  public void complete(UUID id, long input, long output, BigDecimal actual) {
    db.jdbc.update(
      "UPDATE usage_entry SET state='COMPLETED',actual_usd=?,input_tokens=?,output_tokens=?,completed_at=? WHERE id=?",
      actual,
      input,
      output,
      Timestamp.from(clock.instant()),
      id
    );
  }

  public void recordLocal(UUID conversation, String kind, String model, String prompt, long input, long output) {
    db.jdbc.update(
      "INSERT INTO usage_entry(id,conversation_id,kind,model,prompt_version,state,reserved_usd,actual_usd,input_tokens,output_tokens,created_at,completed_at) VALUES (?,?,?,?,?,'COMPLETED',0,0,?,?,?,?)",
      UUID.randomUUID(), conversation, kind, "ollama/" + model, prompt, input, output,
      Timestamp.from(clock.instant()), Timestamp.from(clock.instant()));
  }

  public void unknown(UUID id) {
    db.jdbc.update("UPDATE usage_entry SET state='UNKNOWN' WHERE id=? AND state='RESERVED'", id);
  }

  public void release(UUID id) {
    db.jdbc.update(
      "UPDATE usage_entry SET state='RELEASED',completed_at=? WHERE id=?",
      Timestamp.from(clock.instant()),
      id
    );
  }

  public Map<String, Object> overview() {
    double limit = settings.read("global").path("monthlyBudgetUsd").asDouble(30);
    double spent = used().doubleValue();
    return Map.of(
      "limitUsd",
      limit,
      "usedUsd",
      spent,
      "percent",
      limit == 0 ? 100 : Math.min(100, (spent / limit) * 100),
      "entries",
      db.list("SELECT * FROM usage_entry ORDER BY created_at DESC LIMIT 100")
    );
  }
}

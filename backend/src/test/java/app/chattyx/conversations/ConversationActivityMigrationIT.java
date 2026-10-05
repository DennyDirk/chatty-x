package app.chattyx.conversations;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ConversationActivityMigrationIT {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6");

  @Test
  void upgradeRepairsDiscoveryDatesWithoutDeletingMessagesOrEnablingChats() {
    var source = new DriverManagerDataSource(
      postgres.getJdbcUrl(),
      postgres.getUsername(),
      postgres.getPassword()
    );
    Flyway.configure().dataSource(source).target("7").load().migrate();
    var db = new JdbcTemplate(source);
    UUID connection = UUID.randomUUID(),
      withHistory = UUID.randomUUID(),
      empty = UUID.randomUUID();
    db.update("INSERT INTO connection(id,name,adapter) VALUES (?,'Synthetic','fake')", connection);
    db.update(
      "INSERT INTO conversation(id,connection_id,external_id,title) VALUES (?,?,'one','History'),(?,?,'two','Empty')",
      withHistory,
      connection,
      empty,
      connection
    );
    Instant old = Instant.parse("2026-01-01T10:00:00Z");
    db.update(
      "INSERT INTO message(id,conversation_id,external_id,direction,source,sent_at) VALUES (?,?,'one','IN','CONTACT',?)",
      UUID.randomUUID(),
      withHistory,
      Timestamp.from(old)
    );
    db.update(
      "INSERT INTO message(id,conversation_id,external_id,direction,source,sent_at,deleted) VALUES (?,?,'deleted','IN','CONTACT',now(),true)",
      UUID.randomUUID(),
      withHistory
    );
    assertThat(
      db
        .queryForObject("SELECT last_activity FROM conversation WHERE id=?", Timestamp.class, withHistory)
        .toInstant()
    ).isAfter(old);
    var flyway = Flyway.configure().dataSource(source).load();
    assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
    assertThat(
      db
        .queryForObject("SELECT last_activity FROM conversation WHERE id=?", Timestamp.class, withHistory)
        .toInstant()
    ).isEqualTo(old);
    assertThat(
      db.queryForObject("SELECT last_activity FROM conversation WHERE id=?", Timestamp.class, empty)
    ).isNull();
    assertThat(db.queryForObject("SELECT count(*) FROM message", Integer.class)).isEqualTo(2);
    assertThat(
      db.queryForObject("SELECT count(*) FROM conversation WHERE selected OR mode<>'PAUSED'", Integer.class)
    ).isZero();
    assertThat(flyway.migrate().migrationsExecuted).isZero();
  }
}

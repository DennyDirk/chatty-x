package app.chattyx.shared;

import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class Db {

  public final JdbcTemplate jdbc;

  public Db(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Map<String, Object>> list(String sql, Object... args) {
    return jdbc.queryForList(sql, args).stream().map(this::normalize).toList();
  }

  public Map<String, Object> one(String sql, Object... args) {
    var rows = list(sql, args);
    if (rows.isEmpty()) throw new ApiException(404, "NOT_FOUND");
    return rows.getFirst();
  }

  private Map<String, Object> normalize(Map<String, Object> row) {
    var result = new LinkedHashMap<String, Object>();
    row.forEach((key, value) -> {
      StringBuilder name = new StringBuilder();
      boolean upper = false;
      for (char c : key.toCharArray()) {
        if (c == '_') upper = true;
        else {
          name.append(upper ? Character.toUpperCase(c) : c);
          upper = false;
        }
      }
      if (value instanceof UUID) value = value.toString();
      else if (value instanceof Timestamp t) value = t.toInstant().toString();
      else if (value != null && value.getClass().getName().equals("org.postgresql.util.PGobject")) value =
        Json.MAPPER.convertValue(Json.read(value.toString()), Object.class);
      result.put(name.toString(), value);
    });
    return result;
  }

  public void audit(String category, Object id) {
    jdbc.update(
      "INSERT INTO audit_event(category,entity_id) VALUES (?,?)",
      category,
      id == null ? null : id.toString()
    );
  }
}

package app.chattyx.personas;

import app.chattyx.identity.SecretBox;
import app.chattyx.shared.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettingsService {

  private final Db db;
  private final SecretBox box;

  public SettingsService(Db db, SecretBox box) {
    this.db = db;
    this.box = box;
  }

  public ObjectNode effective(UUID conversation) {
    var result = read("global");
    if (conversation != null) {
      UUID connection = db.jdbc.queryForObject(
        "SELECT connection_id FROM conversation WHERE id=?",
        UUID.class,
        conversation
      );
      merge(result, read("connection:" + connection));
      merge(result, read("conversation:" + conversation));
    }
    return result;
  }

  public ObjectNode read(String scope) {
    var rows = db.jdbc.queryForList("SELECT body::text FROM app_settings WHERE scope=?", String.class, scope);
    return rows.isEmpty() ? Json.object() : (ObjectNode) Json.read(rows.getFirst());
  }

  public Map<String, Object> view(String scope) {
    validateScope(scope);
    var rows = db.list("SELECT version,body FROM app_settings WHERE scope=?", scope);
    var result=new LinkedHashMap<String,Object>(rows.isEmpty()?Map.of("version",0,"body",Map.of()):rows.getFirst());
    ObjectNode resolved=read("global");Map<String,String> sources=new LinkedHashMap<>();resolved.fieldNames().forEachRemaining(key->sources.put(key,"global"));
    if(scope.startsWith("conversation:")){
      UUID id=UUID.fromString(scope.substring(13));UUID connection=db.jdbc.queryForObject("SELECT connection_id FROM conversation WHERE id=?",UUID.class,id);
      var account=read("connection:"+connection);merge(resolved,account);account.fieldNames().forEachRemaining(key->sources.put(key,"connection"));
    }
    if(!scope.equals("global")){var local=read(scope);merge(resolved,local);local.fieldNames().forEachRemaining(key->sources.put(key,scope.startsWith("conversation:")?"conversation":"connection"));}
    result.put("effective",Json.MAPPER.convertValue(resolved,Object.class));result.put("sources",sources);return result;
  }

  @Transactional
  public void save(String scope, long version, Map<String, Object> body) {
    validateScope(scope);
    if (body == null) throw new ApiException(400, "INVALID_SETTINGS");
    ObjectNode value = Json.MAPPER.valueToTree(body);
    validate(value);
    if (scope.equals("global")) {
      ObjectNode complete = read("global");
      merge(complete, value);
      value = complete;
    }
    db.jdbc.update("INSERT INTO app_settings(scope) VALUES (?) ON CONFLICT DO NOTHING", scope);
    int updated = db.jdbc.update(
      "UPDATE app_settings SET body=?::jsonb,version=version+1 WHERE scope=? AND version=?",
      Json.write(value),
      scope,
      version
    );
    if (updated != 1) throw new ApiException(409, "SETTINGS_CHANGED");
    String condition = "true";
    Object[] args = {};
    if (scope.startsWith("conversation:")) {
      condition = "id=?";
      args = new Object[] { UUID.fromString(scope.substring(13)) };
    } else if (scope.startsWith("connection:")) {
      condition = "connection_id=?";
      args = new Object[] { UUID.fromString(scope.substring(11)) };
    }
    db.jdbc.update("UPDATE conversation SET version=version+1 WHERE " + condition, args);
    db.jdbc.update(
      "UPDATE outbound SET status=CASE WHEN status='DRAFT' THEN 'STALE' ELSE 'CANCELLED' END WHERE source='AUTOMATION' AND status IN ('READY','DRAFT') AND conversation_id IN (SELECT id FROM conversation WHERE " +
        condition +
        ")",
      args
    );
    db.jdbc.update(
      "UPDATE automation_job j SET state='WAITING',lease_token=NULL,lease_until=NULL,context_version=c.version,due_at=now()+interval '8 seconds' FROM conversation c WHERE c.id=j.conversation_id AND j.conversation_id IN (SELECT id FROM conversation WHERE " +
        condition +
        ") AND j.state IN ('WAITING','GENERATING')",
      args
    );
    db.audit("SETTINGS_UPDATED", scope);
  }

  public static void merge(ObjectNode base, ObjectNode override) {
    override.fields().forEachRemaining(e -> {
      if (e.getValue().isObject() && base.path(e.getKey()).isObject()) merge(
        (ObjectNode) base.get(e.getKey()),
        (ObjectNode) e.getValue()
      );
      else if (!e.getValue().isNull()) base.set(e.getKey(), e.getValue());
    });
  }

  private void validateScope(String scope) {
    if (scope.equals("global")) return;
    String prefix = scope.startsWith("connection:")
      ? "connection:"
      : scope.startsWith("conversation:")
        ? "conversation:"
        : "";
    if (prefix.isEmpty()) throw new ApiException(400, "INVALID_SCOPE");
    UUID id = UUID.fromString(scope.substring(prefix.length()));
    db.one(
      "SELECT id FROM " + (prefix.equals("connection:") ? "connection" : "conversation") + " WHERE id=?",
      id
    );
  }

  private void validate(ObjectNode value) {
    var allowed = read("global");
    value.fieldNames().forEachRemaining(k -> {
      if (!allowed.has(k)) throw new ApiException(400, "UNKNOWN_SETTING");
      JsonNode v = value.get(k),
        expected = allowed.get(k);
      if (
        v.isNull() ||
        v.isContainerNode() != expected.isContainerNode() ||
        v.isTextual() != expected.isTextual() ||
        v.isNumber() != expected.isNumber() ||
        v.isBoolean() != expected.isBoolean() ||
        v.isArray() != expected.isArray()
      ) throw new ApiException(400, "INVALID_SETTING_TYPE");
      if (v.isArray()) for (JsonNode item : v)
        if (!item.isTextual()) throw new ApiException(400, "INVALID_SETTING_TYPE");
    });
    if (value.has("commitments")) {
      var rules = value.path("commitments");
      rules.fieldNames().forEachRemaining(k -> {
        if (!Set.of("meetings", "promises", "money", "personal").contains(k)) throw new ApiException(
          400,
          "INVALID_COMMITMENT"
        );
        var rule = rules.path(k);
        if (
          !rule.isObject() ||
          !Set.of("DRAFT", "DISCUSS", "RULED").contains(rule.path("mode").asText()) ||
          !rule.path("rule").isTextual()
        ) throw new ApiException(400, "INVALID_COMMITMENT");
        if(rule.path("mode").asText().equals("RULED")&&rule.path("rule").asText().isBlank())throw new ApiException(400,"COMMITMENT_RULE_REQUIRED");
      });
    }
    for (String k : List.of("quietSeconds", "maxBurstSeconds", "delayMinSeconds", "delayMaxSeconds"))
      if (
        value.has(k) && (value.path(k).asInt(-1) < 0 || value.path(k).asInt() > 300)
      ) throw new ApiException(400, "INVALID_TIMING");
    for (String k : List.of("messageRetentionDays", "fileRetentionDays"))
      if (value.has(k) && (value.path(k).asInt() < 1 || value.path(k).asInt() > 3650)) throw new ApiException(
        400,
        "INVALID_RETENTION"
      );
    if (
      value.has("monthlyBudgetUsd") &&
      (value.path("monthlyBudgetUsd").asDouble() < 0 || value.path("monthlyBudgetUsd").asDouble() > 10000)
    ) throw new ApiException(400, "INVALID_BUDGET");
    if (
      value.has("maxReplyChars") &&
      (value.path("maxReplyChars").asInt() < 20 || value.path("maxReplyChars").asInt() > 4000)
    ) throw new ApiException(400, "INVALID_REPLY_LENGTH");
    if (value.has("timezone")) ZoneId.of(value.path("timezone").asText());
    for (String k : List.of("quietHoursStart", "quietHoursEnd"))
      if (value.has(k) && !value.path(k).asText().isEmpty()) LocalTime.parse(value.path(k).asText());
    if (Json.write(value).length() > 30000) throw new ApiException(400, "SETTINGS_TOO_LARGE");
  }

  public boolean allowedNow(JsonNode config, Instant instant) {
    String start = config.path("quietHoursStart").asText(),
      end = config.path("quietHoursEnd").asText();
    if (start.isEmpty() || end.isEmpty() || start.equals(end)) return true;
    var time = instant.atZone(ZoneId.of(config.path("timezone").asText("UTC"))).toLocalTime();
    var a = LocalTime.parse(start);
    var b = LocalTime.parse(end);
    return a.isBefore(b) ? time.isBefore(a) || !time.isBefore(b) : time.isBefore(a) && !time.isBefore(b);
  }

  public Optional<String> credential(String name) {
    var rows = db.jdbc.queryForList(
      "SELECT encrypted_value FROM credential WHERE name=?",
      String.class,
      name
    );
    return rows.isEmpty() ? Optional.empty() : Optional.of(box.decrypt(rows.getFirst()));
  }

  public void credential(String name, String value) {
    if (!name.equals("openai")) throw new ApiException(400, "INVALID_CREDENTIAL");
    if (value.isBlank()) {
      db.jdbc.update("DELETE FROM credential WHERE name=?", name);
      return;
    }
    db.jdbc.update(
      "INSERT INTO credential(name,encrypted_value) VALUES (?,?) ON CONFLICT(name) DO UPDATE SET encrypted_value=excluded.encrypted_value",
      name,
      box.encrypt(value)
    );
  }
}

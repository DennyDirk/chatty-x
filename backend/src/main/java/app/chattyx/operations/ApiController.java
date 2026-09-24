package app.chattyx.operations;

import app.chattyx.connections.*;
import app.chattyx.conversations.ConversationService;
import app.chattyx.delivery.DeliveryService;
import app.chattyx.generation.ReplyModel;
import app.chattyx.media.MediaService;
import app.chattyx.memory.MemoryService;
import app.chattyx.personas.SettingsService;
import app.chattyx.shared.*;
import java.util.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
public class ApiController {

  private final Db db;
  private final ConnectionService connections;
  private final ChannelRegistry channels;
  private final ConversationService chats;
  private final DeliveryService delivery;
  private final SettingsService settings;
  private final MemoryService memory;
  private final MediaService media;
  private final BudgetService budget;
  private final ReplyModel model;
  private final Events events;

  public ApiController(
    Db db,
    ConnectionService connections,
    ChannelRegistry channels,
    ConversationService chats,
    DeliveryService delivery,
    SettingsService settings,
    MemoryService memory,
    MediaService media,
    BudgetService budget,
    ReplyModel model,
    Events events
  ) {
    this.db = db;
    this.connections = connections;
    this.channels = channels;
    this.chats = chats;
    this.delivery = delivery;
    this.settings = settings;
    this.memory = memory;
    this.media = media;
    this.budget = budget;
    this.model = model;
    this.events = events;
  }

  @GetMapping("/connections")
  public Object connections() {
    return connections.list();
  }

  @GetMapping("/adapters")
  public Object adapters() {
    return channels
      .all()
      .stream()
      .map(a -> Map.of("name", a.name(), "capabilities", a.capabilities()))
      .toList();
  }

  public record NewConnection(String name, String adapter) {}

  @PostMapping("/connections")
  public Object create(@RequestBody NewConnection body) {
    return Map.of("id", connections.create(body.name(), body.adapter()));
  }

  public record AuthorizationRequest(String method, String step, String value) {}

  @PostMapping("/connections/{id}/authorization")
  public Object auth(@PathVariable UUID id, @RequestBody AuthorizationRequest body) {
    return body.step() == null
      ? connections.authorize(id, body.method() == null ? "qr" : body.method())
      : channels.forConnection(id).submitAuthorization(id, body.step(), body.value());
  }

  @GetMapping("/connections/{id}/authorization")
  public Object authState(@PathVariable UUID id) {
    return connections.authState(id);
  }

  public record Enabled(boolean enabled) {}

  @PutMapping("/connections/{id}/enabled")
  public void enabled(@PathVariable UUID id, @RequestBody Enabled body) {
    connections.enable(id, body.enabled());
  }

  @PostMapping("/connections/{id}/disconnect")
  public void disconnect(@PathVariable UUID id, @RequestParam(defaultValue = "true") boolean revoke) {
    connections.disconnect(id, revoke);
  }

  @PostMapping("/connections/{id}/refresh")
  public void discover(@PathVariable UUID id) {
    channels.forConnection(id).discover(id);
  }

  @GetMapping("/conversations")
  public Object conversations() {
    return chats.list();
  }

  @GetMapping("/conversations/{id}")
  public Object conversation(@PathVariable UUID id) {
    return chats.get(id);
  }

  @GetMapping("/conversations/{id}/messages")
  public Object messages(
    @PathVariable UUID id,
    @RequestParam(required = false) String cursor,
    @RequestParam(defaultValue = "") String search
  ) {
    return chats.messages(id, cursor, search);
  }

  public record Selection(boolean selected) {}

  @PutMapping("/conversations/{id}/selection")
  public void select(@PathVariable UUID id, @RequestBody Selection body) {
    connections.select(id, body.selected());
  }

  @PostMapping("/conversations/{id}/import")
  public void history(@PathVariable UUID id) {
    connections.importHistory(id);
  }

  @DeleteMapping("/conversations/{id}/import")
  public void cancelHistory(@PathVariable UUID id) {
    connections.cancelImport(id);
  }

  @PostMapping("/conversations/{id}/control/{action}")
  public void control(@PathVariable UUID id, @PathVariable String action) {
    chats.control(id, action);
  }

  public record Send(String text, String requestKey) {}

  @PostMapping("/conversations/{id}/messages")
  public Object send(@PathVariable UUID id, @RequestBody Send body) {
    return delivery.manual(id, body.text(), body.requestKey());
  }

  @GetMapping("/conversations/{id}/outbound")
  public Object outbound(@PathVariable UUID id) {
    chats.get(id);
    return db.list("SELECT * FROM outbound WHERE conversation_id=? ORDER BY created_at DESC LIMIT 30", id);
  }

  public record Draft(String text, long version, String requestKey) {}

  @PostMapping("/conversations/{id}/drafts/{draft}/send")
  public void draft(@PathVariable UUID id, @PathVariable UUID draft, @RequestBody Draft body) {
    delivery.draft(id, draft, body.text(), body.version(), body.requestKey());
  }

  @DeleteMapping("/conversations/{id}/drafts/{draft}")
  public void discard(@PathVariable UUID id, @PathVariable UUID draft) {
    db.jdbc.update(
      "UPDATE outbound SET status='CANCELLED' WHERE id=? AND conversation_id=? AND status IN ('DRAFT','STALE','READY')",
      draft,
      id
    );
    events.changed();
  }

  @PostMapping("/outbound/{id}/reconcile")
  public void reconcile(@PathVariable UUID id) {
    delivery.reconcile(id);
  }

  @GetMapping("/settings")
  public Object settings(@RequestParam(defaultValue = "global") String scope) {
    return settings.view(scope);
  }

  @GetMapping("/conversations/{id}/effective-settings")
  public Object effective(@PathVariable UUID id) {
    return Json.MAPPER.convertValue(settings.effective(id), Object.class);
  }

  public record Settings(long version, Map<String, Object> body) {}

  @PutMapping("/settings")
  public void settings(@RequestParam(defaultValue = "global") String scope, @RequestBody Settings body) {
    settings.save(scope, body.version(), body.body());
    events.changed();
  }

  @PostMapping("/stop")
  public void stop() {
    var view = settings.view("global");
    var body = Json.MAPPER.convertValue(settings.read("global"), Map.class);
    body.put("enabled", false);
    settings.save("global", ((Number) view.get("version")).longValue(), body);
    events.changed();
  }

  public record Credential(String value) {}

  @PutMapping("/credentials/openai")
  public void credential(@RequestBody Credential body) {
    settings.credential("openai", body.value());
  }

  @GetMapping("/credentials")
  public Object credentials() {
    return Map.of("openaiConfigured", settings.credential("openai").isPresent());
  }

  @GetMapping("/conversations/{id}/memory")
  public Object memory(@PathVariable UUID id) {
    return memory.list(id);
  }

  public record Fact(String content, boolean pinned, long version) {}

  @PutMapping("/conversations/{id}/memory/{fact}")
  public void editFact(@PathVariable UUID id, @PathVariable UUID fact, @RequestBody Fact body) {
    memory.edit(id, fact, body.content(), body.pinned(), body.version());
    events.changed();
  }

  @DeleteMapping("/conversations/{id}/memory/{fact}")
  public void forget(@PathVariable UUID id, @PathVariable UUID fact, @RequestParam long version) {
    memory.forget(id, fact, version);
    events.changed();
  }

  @PostMapping("/conversations/{id}/memory/refresh")
  public void refreshMemory(@PathVariable UUID id) {
    memory.refresh(id);
    events.changed();
  }

  @GetMapping("/attachments/{id}")
  public ResponseEntity<FileSystemResource> attachment(@PathVariable UUID id) throws java.io.IOException {
    var path = media.file(id);
    String type = path.toString().endsWith(".jpg") ? "image/jpeg" : "audio/ogg";
    return ResponseEntity.ok()
      .header("Cache-Control", "private, no-store")
      .header("X-Content-Type-Options", "nosniff")
      .contentType(MediaType.parseMediaType(type))
      .body(new FileSystemResource(path));
  }

  @GetMapping("/usage")
  public Object usage() {
    return budget.overview();
  }

  @GetMapping("/diagnostics")
  public Object diagnostics() {
    return Map.of(
      "connections",
      connections.list(),
      "jobs",
      db.list("SELECT * FROM automation_job ORDER BY due_at"),
      "uncertainDelivery",
      db.list("SELECT id,conversation_id,status,reason FROM outbound WHERE status IN ('FAILED','UNKNOWN')"),
      "audit",
      db.list("SELECT * FROM audit_event ORDER BY id DESC LIMIT 50")
    );
  }

  @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter events() {
    return events.subscribe();
  }

  public record Playground(String text, Map<String, Object> settings) {}

  @PostMapping("/playground")
  public Object playground(@RequestBody Playground body) {
    if (body.text() == null || body.text().isBlank() || body.text().length() > 12000) throw new ApiException(
      400,
      "INVALID_INPUT"
    );
    var config = settings.read("global");
    if (body.settings() != null) SettingsService.merge(
      config,
      (com.fasterxml.jackson.databind.node.ObjectNode) Json.MAPPER.valueToTree(body.settings())
    );
    return model.generate(
      new ReplyModel.Context(
        null,
        config,
        "",
        List.of(),
        List.of(Map.of("source", "CONTACT", "text", body.text(), "new", true)),
        List.of()
      )
    );
  }

  public record Inject(String text, boolean outgoing) {}

  @PostMapping("/conversations/{id}/simulate")
  public void inject(@PathVariable UUID id, @RequestBody Inject body) {
    if (body.text() == null || body.text().length() > 12000) throw new ApiException(400, "INVALID_INPUT");
    var c = chats.get(id);
    UUID connection = UUID.fromString(c.get("connectionId").toString());
    var adapter = channels.forConnection(connection);
    if (!(adapter instanceof FakeMessagingAdapter fake)) throw new ApiException(404, "NOT_FOUND");
    fake.inject(connection, c.get("externalId").toString(), body.text(), body.outgoing());
  }

  @GetMapping("/export")
  public ResponseEntity<?> export() {
    return ResponseEntity.ok()
      .header("Content-Disposition", "attachment; filename=chatty-x-export.json")
      .header("Cache-Control", "no-store")
      .body(
        Map.of(
          "connections",
          connections.list(),
          "conversations",
          chats.list(),
          "messages",
          db.list("SELECT * FROM message WHERE NOT deleted"),
          "facts",
          db.list("SELECT * FROM memory_fact"),
          "settings",
          db.list("SELECT scope,version,body FROM app_settings")
        )
      );
  }
}

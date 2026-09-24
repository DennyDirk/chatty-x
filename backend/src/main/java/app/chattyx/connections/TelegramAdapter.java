package app.chattyx.connections;

import app.chattyx.identity.SecretBox;
import app.chattyx.shared.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TelegramAdapter implements MessagingAdapter {

  private final Db db;
  private final SecretBox box;
  private final int apiId;
  private final String apiHash;
  private final Path root;
  private TdTransport transport;
  private Consumer<Event> listener = e -> {};
  private final Map<UUID, Integer> clients = new ConcurrentHashMap<>();
  private final Map<UUID, Authorization> states = new ConcurrentHashMap<>();

  public TelegramAdapter(
    Db db,
    SecretBox box,
    @Value("${chatty.telegram-api-id}") int apiId,
    @Value("${chatty.telegram-api-hash}") String apiHash,
    @Value("${chatty.data-dir}") String root
  ) {
    this.db = db;
    this.box = box;
    this.apiId = apiId;
    this.apiHash = apiHash;
    this.root = Path.of(root).toAbsolutePath();
  }

  public String name() {
    return "telegram";
  }

  public Capabilities capabilities() {
    return new Capabilities(true, true, true, true, true);
  }

  public void events(Consumer<Event> listener) {
    this.listener = listener;
  }

  private synchronized TdTransport transport() {
    if (transport == null) transport = new TdTransport();
    return transport;
  }

  private int client(UUID id) {
    Integer client = clients.get(id);
    if (client == null) throw new ApiException(409, "TELEGRAM_NOT_CONNECTED");
    return client;
  }

  public Authorization authorization(UUID id) {
    return states.getOrDefault(id, new Authorization("DISCONNECTED", Map.of()));
  }

  public synchronized Authorization beginAuthorization(UUID id, String method) {
    if (apiId <= 0 || apiHash.isBlank()) throw new ApiException(409, "TELEGRAM_APP_CREDENTIALS_REQUIRED");
    if (!clients.containsKey(id)) {
      int c = transport().create(event -> update(id, event));
      clients.put(id, c);
      states.put(id, new Authorization("INITIALIZING", Map.of("method", method)));
      transport().async(c, Json.object("getAuthorizationState"));
    } else if (method.equals("qr")) transport().async(
      client(id),
      Json.object("requestQrCodeAuthentication").set("other_user_ids", Json.MAPPER.createArrayNode())
    );
    return authorization(id);
  }

  private void parameters(UUID id) {
    try {
      Path dir = root.resolve("telegram").resolve(id.toString());
      Files.createDirectories(dir);
      String keyName = "tdlib:" + id;
      var keys = db.jdbc.queryForList(
        "SELECT encrypted_value FROM credential WHERE name=?",
        String.class,
        keyName
      );
      String key;
      if (keys.isEmpty()) {
        byte[] b = new byte[32];
        new java.security.SecureRandom().nextBytes(b);
        key = Base64.getEncoder().encodeToString(b);
        db.jdbc.update(
          "INSERT INTO credential(name,encrypted_value) VALUES (?,?)",
          keyName,
          box.encrypt(key)
        );
      } else key = box.decrypt(keys.getFirst());
      var p = Json.object("setTdlibParameters")
        .put("api_id", apiId)
        .put("api_hash", apiHash)
        .put("database_directory", dir.toString())
        .put("files_directory", dir.resolve("files").toString())
        .put("database_encryption_key", key)
        .put("use_file_database", true)
        .put("use_chat_info_database", true)
        .put("use_message_database", false)
        .put("use_secret_chats", false)
        .put("system_language_code", "ru")
        .put("device_model", "Chatty-X server")
        .put("system_version", "Linux")
        .put("application_version", "0.1.0");
      transport().async(client(id), p);
    } catch (Exception e) {
      listener.accept(new State(id, "ERROR", "ERROR", Map.of("code", "TELEGRAM_INITIALIZATION_FAILED")));
    }
  }

  private void update(UUID id, JsonNode event) {
    String type = event.path("@type").asText();
    switch (type) {
      case "updateAuthorizationState" -> {
        var state = event.path("authorization_state");
        String step = state.path("@type").asText();
        switch (step) {
          case "authorizationStateWaitTdlibParameters" -> parameters(id);
          case "authorizationStateWaitPhoneNumber" -> {
            String method = states
              .getOrDefault(id, new Authorization("", Map.of()))
              .fields()
              .getOrDefault("method", "phone");
            if (method.equals("qr")) transport().async(
              client(id),
              Json.object("requestQrCodeAuthentication").set("other_user_ids", Json.MAPPER.createArrayNode())
            );
            setState(id, "AUTHORIZING", "PHONE", Map.of());
          }
          case "authorizationStateWaitCode" -> setState(id, "AUTHORIZING", "CODE", Map.of());
          case "authorizationStateWaitPassword" -> setState(id, "AUTHORIZING", "PASSWORD", Map.of());
          case "authorizationStateWaitEmailAddress" -> setState(id, "AUTHORIZING", "EMAIL", Map.of());
          case "authorizationStateWaitEmailCode" -> setState(id, "AUTHORIZING", "EMAIL_CODE", Map.of());
          case "authorizationStateWaitOtherDeviceConfirmation" -> setState(
            id,
            "AUTHORIZING",
            "QR",
            Map.of("link", state.path("link").asText())
          );
          case "authorizationStateReady" -> {
            setState(id, "READY", "READY", Map.of());
            var me = transport().call(client(id), Json.object("getMe"));
            db.jdbc.update("UPDATE connection SET self_id=? WHERE id=?", me.path("id").asText(), id);
            discover(id);
          }
          case "authorizationStateClosed", "authorizationStateLoggingOut" -> {
            setState(id, "DISCONNECTED", "DISCONNECTED", Map.of());
            clients.remove(id);
          }
          default -> setState(id, "AUTHORIZING", "UNSUPPORTED_STEP", Map.of("state", step));
        }
      }
      case "updateNewChat" -> {
        var chat = event.path("chat");
        boolean eligible = chat.path("type").path("@type").asText().equals("chatTypePrivate");
        if (eligible) {
          String user = chat.path("type").path("user_id").asText();
          try {
            var u = transport().call(client(id), Json.object("getUser").put("user_id", Long.parseLong(user)));
            eligible =
              !u.path("type").path("@type").asText().equals("userTypeBot") && !u.path("is_self").asBoolean();
          } catch (Exception e) {
            eligible = false;
          }
        }
        listener.accept(new Chat(id, chat.path("id").asText(), chat.path("title").asText(), eligible));
      }
      case "updateNewMessage" -> listener.accept(new NewMessage(incoming(id, event.path("message"), false)));
      case "updateMessageContent" -> listener.accept(
        new Edited(
          id,
          event.path("chat_id").asText(),
          event.path("message_id").asText(),
          text(event.path("new_content"))
        )
      );
      case "updateDeleteMessages" -> {
        if (event.path("is_permanent").asBoolean()) {
          List<String> ids = new ArrayList<>();
          event.path("message_ids").forEach(x -> ids.add(x.asText()));
          listener.accept(new Deleted(id, event.path("chat_id").asText(), ids));
        }
      }
      case "updateMessageSendSucceeded" -> {
        var m = event.path("message");
        listener.accept(
          new Delivered(
            id,
            m.path("chat_id").asText(),
            event.path("old_message_id").asText(),
            m.path("id").asText(),
            true,
            "CONFIRMED"
          )
        );
      }
      case "updateMessageSendFailed" -> {
        var m = event.path("message");
        listener.accept(
          new Delivered(
            id,
            m.path("chat_id").asText(),
            event.path("old_message_id").asText(),
            m.path("id").asText(),
            false,
            "TELEGRAM_SEND_FAILED"
          )
        );
      }
      default -> {
      }
    }
  }

  private void setState(UUID id, String status, String step, Map<String, String> fields) {
    states.put(id, new Authorization(step, fields));
    listener.accept(new State(id, status, step, Map.of()));
  }

  public Authorization submitAuthorization(UUID id, String step, String value) {
    com.fasterxml.jackson.databind.node.ObjectNode request = switch (step) {
      case "PHONE" -> Json.object("setAuthenticationPhoneNumber").put("phone_number", value);
      case "CODE" -> Json.object("checkAuthenticationCode").put("code", value);
      case "PASSWORD" -> Json.object("checkAuthenticationPassword").put("password", value);
      case "EMAIL" -> Json.object("setAuthenticationEmailAddress").put("email_address", value);
      case "EMAIL_CODE" -> Json.object("checkAuthenticationEmailCode").set(
        "code",
        Json.object("emailAddressAuthenticationCode").put("code", value)
      );
      default -> throw new ApiException(400, "INVALID_AUTH_STEP");
    };
    transport().call(client(id), request);
    return authorization(id);
  }

  public void disconnect(UUID id, boolean revoke) {
    if (clients.containsKey(id)) transport().call(client(id), Json.object(revoke ? "logOut" : "close"));
    states.remove(id);
  }

  public void discover(UUID id) {
    transport().async(client(id), Json.object("loadChats").put("limit", 200));
  }

  public History fetchHistory(UUID id, String chat, String cursor, int limit) {
    var result = transport().call(
      client(id),
      Json.object("getChatHistory")
        .put("chat_id", Long.parseLong(chat))
        .put("from_message_id", cursor == null ? 0 : Long.parseLong(cursor))
        .put("offset", 0)
        .put("limit", Math.min(100, limit + (cursor==null?0:1)))
        .put("only_local", false)
    );
    List<Incoming> messages = new ArrayList<>();
    for (var item : result.path("messages")) if(!item.path("id").asText().equals(cursor) && messages.size()<limit) messages.add(incoming(id, item, true));
    return new History(messages, messages.isEmpty() ? null : messages.getLast().messageId());
  }

  public Receipt sendText(Outgoing message) {
    var content = Json.object("inputMessageText");
    content.set("text", Json.object("formattedText").put("text", message.text()));
    var request = Json.object("sendMessage").put("chat_id", Long.parseLong(message.chatId()));
    request.set("input_message_content", content);
    request.set(
      "options",
      Json.object("messageSendOptions")
        .put("sending_id", message.sendingId())
        .put("paid_message_star_count", 0)
    );
    var result = transport().call(client(message.connectionId()), request);
    String id = result.path("id").asText();
    boolean confirmed = result.path("sending_state").isMissingNode() || result.path("sending_state").isNull();
    return new Receipt(id, confirmed ? id : null, confirmed);
  }

  public void releaseFile(UUID id,String fileId) {
    if(fileId.isBlank())return;
    transport().call(client(id),Json.object("deleteFile").put("file_id",Integer.parseInt(fileId)));
  }

  public Optional<Snapshot> fetchMessages(UUID id,String chat,List<String> ids) {
    var numbers=Json.MAPPER.createArrayNode();ids.forEach(value->numbers.add(Long.parseLong(value)));
    var request=Json.object("getMessages").put("chat_id",Long.parseLong(chat));request.set("message_ids",numbers);
    var result=transport().call(client(id),request).path("messages");
    if(!result.isArray() || result.size()!=ids.size())throw new ApiException(502,"INVALID_HISTORY_SNAPSHOT");
    List<Incoming> messages=new ArrayList<>();List<String> missing=new ArrayList<>();
    for(int n=0;n<ids.size();n++){if(result.get(n).isNull())missing.add(ids.get(n));else messages.add(incoming(id,result.get(n),true));}
    return Optional.of(new Snapshot(messages,missing));
  }

  public void markRead(UUID id, String chat, String message) {
    transport().async(
      client(id),
      Json.object("viewMessages")
        .put("chat_id", Long.parseLong(chat))
        .put("force_read", true)
        .set("message_ids", Json.MAPPER.createArrayNode().add(Long.parseLong(message)))
    );
  }

  public void setTyping(UUID id, String chat, boolean active) {
    transport().async(
      client(id),
      Json.object("sendChatAction")
        .put("chat_id", Long.parseLong(chat))
        .set("action", Json.object(active ? "chatActionTyping" : "chatActionCancel"))
    );
  }

  public Path download(UUID id, String file, long maxBytes) {
    var meta = transport().call(client(id), Json.object("getFile").put("file_id", Integer.parseInt(file)));
    if (
      meta.path("size").asLong() > maxBytes || meta.path("expected_size").asLong() > maxBytes
    ) throw new ApiException(413, "MEDIA_TOO_LARGE");
    var result = transport().call(
      client(id),
      Json.object("downloadFile")
        .put("file_id", Integer.parseInt(file))
        .put("priority", 16)
        .put("synchronous", true)
    );
    Path path = Path.of(result.path("local").path("path").asText()).toAbsolutePath().normalize();
    if (!path.startsWith(root.resolve("telegram").resolve(id.toString()))) throw new ApiException(
      500,
      "INVALID_MEDIA_PATH"
    );
    try {
      if (Files.size(path) > maxBytes) throw new ApiException(413, "MEDIA_TOO_LARGE");
    } catch (java.io.IOException e) {
      throw new ApiException(502, "MEDIA_UNAVAILABLE");
    }
    return path;
  }

  public Optional<Receipt> reconcile(UUID id, String chat, String temporary) {
    if (temporary == null || !clients.containsKey(id)) return Optional.empty();
    try {
      var result = transport().call(
        client(id),
        Json.object("getMessage")
          .put("chat_id", Long.parseLong(chat))
          .put("message_id", Long.parseLong(temporary))
      );
      if (
        result.path("sending_state").isNull() || result.path("sending_state").isMissingNode()
      ) return Optional.of(new Receipt(temporary, result.path("id").asText(), true));
    } catch (Exception ignored) {}
    return Optional.empty();
  }

  private Incoming incoming(UUID id, JsonNode m, boolean historical) {
    var c = m.path("content");
    String type = c.path("@type").asText();
    Media media = null;
    if (type.equals("messagePhoto")) {
      var sizes = c.path("photo").path("sizes");
      if (!sizes.isEmpty()) {
        var file = sizes.get(sizes.size() - 1).path("photo");
        media = new Media(file.path("id").asText(), "PHOTO", "image/jpeg", file.path("size").asLong(), 0);
      }
    } else if (type.equals("messageVoiceNote")) {
      var voice = c.path("voice_note");
      media = new Media(
        voice.path("voice").path("id").asText(),
        "VOICE",
        voice.path("mime_type").asText("audio/ogg"),
        voice.path("voice").path("size").asLong(),
        voice.path("duration").asInt()
      );
    } else if (!type.equals("messageText")) media = new Media(
      "",
      "UNSUPPORTED",
      "application/octet-stream",
      0,
      0
    );
    JsonNode sid = m.path("sending_state").path("sending_id");
    boolean ephemeral =
      !m.path("self_destruct_type").isMissingNode() && !m.path("self_destruct_type").isNull();
    return new Incoming(
      id,
      m.path("chat_id").asText(),
      m.path("id").asText(),
      text(c),
      Instant.ofEpochSecond(m.path("date").asLong()),
      m.path("is_outgoing").asBoolean(),
      historical,
      ephemeral,
      m.path("reply_to").path("message_id").asText(null),
      media,
      sid.isNumber() ? sid.asInt() : null
    );
  }

  private String text(JsonNode content) {
    return content.path("@type").asText().equals("messageText")
      ? content.path("text").path("text").asText()
      : content.path("caption").path("text").asText();
  }

  @PreDestroy
  public void close() {
    if (transport != null) transport.close();
  }
}

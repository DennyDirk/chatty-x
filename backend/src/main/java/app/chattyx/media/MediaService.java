package app.chattyx.media;

import app.chattyx.connections.ChannelRegistry;
import app.chattyx.operations.BudgetService;
import app.chattyx.personas.SettingsService;
import app.chattyx.shared.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class MediaService {

  private final Db db;
  private final ChannelRegistry channels;
  private final BudgetService budget;
  private final SettingsService settings;
  private final Path root;
  private final boolean demo;

  public MediaService(
    Db db,
    ChannelRegistry channels,
    BudgetService budget,
    SettingsService settings,
    @Value("${chatty.data-dir}") String root,
    @Value("${chatty.mode}") String mode
  ) {
    this.db = db;
    this.channels = channels;
    this.budget = budget;
    this.settings = settings;
    this.root = Path.of(root).toAbsolutePath().resolve("attachments");
    demo = mode.equals("demo");
  }

  public void prepare(UUID chat) {
    if (settings.localModel() && db.jdbc.queryForObject(
        "SELECT count(*) FROM message WHERE conversation_id=? AND NOT handled AND NOT deleted AND kind<>'TEXT'",
        Integer.class, chat) > 0) throw new ApiException(409, "LOCAL_MEDIA_UNSUPPORTED");
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    for (var a : db.list(
      "SELECT a.*,m.conversation_id,c.connection_id FROM attachment a JOIN message m ON m.id=a.message_id JOIN conversation c ON c.id=m.conversation_id WHERE m.conversation_id=? AND NOT m.handled AND NOT m.deleted AND a.status NOT IN ('READY','HISTORICAL')",
      chat
    )) {
      UUID id = UUID.fromString(a.get("id").toString());
      try {
        if (System.nanoTime() > deadline) throw new ApiException(408, "MEDIA_TIMEOUT");
        String kind = a.get("kind").toString();
        if (!Set.of("PHOTO", "VOICE").contains(kind)) throw new ApiException(415, "UNSUPPORTED_ATTACHMENT");
        long size = ((Number) a.get("sizeBytes")).longValue();
        int duration = ((Number) a.get("durationSeconds")).intValue();
        if (
          size > 20 * 1024 * 1024 || (kind.equals("VOICE") && (duration <= 0 || duration > 600))
        ) throw new ApiException(413, "MEDIA_LIMIT");
        Path source = channels
          .forConnection(UUID.fromString(a.get("connectionId").toString()))
          .download(
            UUID.fromString(a.get("connectionId").toString()),
            a.get("externalFileId").toString(),
            20 * 1024 * 1024
          );
        Files.createDirectories(root);
        String name = id + (kind.equals("PHOTO") ? ".jpg" : ".ogg");
        Path target = root.resolve(name);
        String transcript = null;
        if (kind.equals("PHOTO")) resize(source, target);
        else {
          Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
          transcript = transcribe(chat, target, duration);
        }
        db.jdbc.update(
          "UPDATE attachment SET status='READY',local_name=?,transcript=? WHERE id=?",
          name,
          transcript,
          id
        );
      } catch (Exception e) {
        db.jdbc.update("UPDATE attachment SET status='FAILED' WHERE id=?", id);
        throw e instanceof ApiException api ? api : new ApiException(502, "MEDIA_PROCESSING_FAILED");
      }
    }
  }

  private void resize(Path source, Path target) throws IOException {
    try (var stream = ImageIO.createImageInputStream(source.toFile())) {
      var readers = ImageIO.getImageReaders(stream);
      if (!readers.hasNext()) throw new ApiException(415, "INVALID_IMAGE");
      var reader = readers.next();
      try {
        reader.setInput(stream);
        int w = reader.getWidth(0),
          h = reader.getHeight(0);
        if ((long) w * h > 40_000_000) throw new ApiException(413, "IMAGE_DIMENSIONS_TOO_LARGE");
        var params = reader.getDefaultReadParam();
        int sample = Math.max(1, Math.max(w, h) / 1600);
        params.setSourceSubsampling(sample, sample, 0, 0);
        BufferedImage image = reader.read(0, params);
        double scale = Math.min(1, 1024.0 / Math.max(image.getWidth(), image.getHeight()));
        var output = new BufferedImage(
          Math.max(1, (int) (image.getWidth() * scale)),
          Math.max(1, (int) (image.getHeight() * scale)),
          BufferedImage.TYPE_INT_RGB
        );
        var graphics = output.createGraphics();
        try {
          graphics.drawImage(image, 0, 0, output.getWidth(), output.getHeight(), null);
        } finally {
          graphics.dispose();
        }
        ImageIO.write(output, "jpg", target.toFile());
      } finally {
        reader.dispose();
      }
    }
  }

  private String transcribe(UUID chat, Path source, int duration) throws Exception {
    if (settings.localModel()) throw new ApiException(409, "LOCAL_MEDIA_UNSUPPORTED");
    if (demo) return "Тестовая расшифровка голосового сообщения";
    String key = settings.credential("openai").orElseThrow(() -> new ApiException(409, "MODEL_KEY_REQUIRED"));
    Path converted = root.resolve(UUID.randomUUID() + ".wav");
    UUID reservation = null;
    try {
      var process = new ProcessBuilder(
        "ffmpeg",
        "-nostdin",
        "-v",
        "error",
        "-i",
        source.toString(),
        "-ar",
        "16000",
        "-ac",
        "1",
        "-t",
        "600",
        converted.toString()
      )
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .start();
      if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new ApiException(408, "AUDIO_CONVERSION_TIMEOUT");
      }
      if (process.exitValue() != 0) throw new ApiException(415, "INVALID_AUDIO");
      reservation = budget.reserve(
        chat,
        "transcription",
        "gpt-4o-mini-transcribe",
        "audio-v1",
        BigDecimal.valueOf((.03 * duration) / 60.0 + .01)
      );
      String boundary = "Chatty" + UUID.randomUUID();
      var bytes = new ByteArrayOutputStream();
      bytes.write(
        (
          "--" +
          boundary +
          "\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\ngpt-4o-mini-transcribe\r\n--" +
          boundary +
          "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"voice.wav\"\r\nContent-Type: audio/wav\r\n\r\n"
        ).getBytes(java.nio.charset.StandardCharsets.UTF_8)
      );
      Files.copy(converted, bytes);
      bytes.write(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      var request = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/audio/transcriptions"))
        .timeout(Duration.ofSeconds(35))
        .header("Authorization", "Bearer " + key)
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray()))
        .build();
      var response = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
        .send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) throw new ApiException(502, "TRANSCRIPTION_FAILED");
      String text = Json.read(response.body()).path("text").asText();
      if (text.isBlank()) throw new ApiException(422, "AUDIO_UNINTELLIGIBLE");
      budget.complete(reservation, 0, 0, BigDecimal.valueOf((.003 * duration) / 60.0));
      return text;
    } catch (Exception e) {
      if (reservation != null) budget.unknown(reservation);
      throw e;
    } finally {
      Files.deleteIfExists(converted);
    }
  }

  public List<String> images(UUID chat) {
    List<String> result = new ArrayList<>();
    for (var row : db.list(
      "SELECT a.local_name FROM attachment a JOIN message m ON m.id=a.message_id WHERE m.conversation_id=? AND NOT m.handled AND NOT m.deleted AND a.kind='PHOTO' AND a.status='READY' ORDER BY m.sent_at DESC LIMIT 4",
      chat
    ))
      try {
        result.add(
          "data:image/jpeg;base64," +
            Base64.getEncoder().encodeToString(Files.readAllBytes(path(row.get("localName").toString())))
        );
      } catch (IOException e) {
        throw new ApiException(502, "MEDIA_UNAVAILABLE");
      }
    return result;
  }

  private Path path(String name) {
    Path p = root.resolve(name).normalize();
    if (!p.startsWith(root) || !p.getParent().equals(root)) throw new ApiException(400, "INVALID_PATH");
    return p;
  }

  public Path file(UUID id) {
    var row = db.one(
      "SELECT a.local_name FROM attachment a JOIN message m ON m.id=a.message_id WHERE a.id=? AND a.status='READY' AND NOT m.deleted",
      id
    );
    Path p = path(row.get("localName").toString());
    if (!Files.isRegularFile(p)) throw new ApiException(404, "MEDIA_UNAVAILABLE");
    return p;
  }

  public void releaseCache(UUID attachment) {
    var item=db.one("SELECT a.external_file_id,a.cache_released,c.connection_id FROM attachment a JOIN message m ON m.id=a.message_id JOIN conversation c ON c.id=m.conversation_id WHERE a.id=?",attachment);
    if(Boolean.TRUE.equals(item.get("cacheReleased")))return;
    UUID connection=UUID.fromString(item.get("connectionId").toString());
    channels.forConnection(connection).releaseFile(connection,item.get("externalFileId").toString());
    db.jdbc.update("UPDATE attachment SET cache_released=true WHERE id=?",attachment);
  }

  public void remove(String name) {
    if (name == null) return;
    try {
      Files.deleteIfExists(path(name));
    } catch (IOException e) {
      throw new IllegalStateException("Media cleanup failed");
    }
  }
}

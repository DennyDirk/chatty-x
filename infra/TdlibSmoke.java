package app.chattyx.smoke;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Pattern;

/** Exercises the official JNI wrapper without credentials, databases or network access. */
public final class TdlibSmoke {
  private static final String REVISION = "ea97bcdd3a15523c58ddfe772b4547187cf5bbeb";

  public static void main(String[] args) throws Exception {
    if (!Files.readString(Path.of("/opt/tdlib/REVISION")).trim().equals(REVISION)) {
      throw new IllegalStateException("Unexpected TDLib revision");
    }
    Class<?> client = Class.forName("org.drinkless.tdlib.JsonClient");
    Method execute = client.getMethod("execute", String.class);
    Method send = client.getMethod("send", int.class, String.class);
    Method receive = client.getMethod("receive", double.class);
    String logging = (String) execute.invoke(null,
        "{\"@type\":\"setLogVerbosityLevel\",\"new_verbosity_level\":0}");
    requireType(logging, "ok");
    String entities = (String) execute.invoke(null,
        "{\"@type\":\"getTextEntities\",\"text\":\"hello @telegram\"}");
    requireType(entities, "textEntities");
    requireType(entities, "textEntityTypeMention");

    int id = (int) client.getMethod("createClientId").invoke(null);
    try {
      send.invoke(null, id,
          "{\"@type\":\"getOption\",\"name\":\"version\",\"@extra\":\"smoke-version\"}");
      String response = await(receive, "@extra", "smoke-version");
      requireType(response, "optionValueString");
      var version = Pattern.compile("\"value\"\\s*:\\s*\"([0-9.]+)\"").matcher(response);
      if (!version.find()) throw new IllegalStateException("Missing TDLib version");
      System.out.println("TDLib version=" + version.group(1) + " revision=" + REVISION);
    } finally {
      send.invoke(null, id, "{\"@type\":\"close\",\"@extra\":\"smoke-close\"}");
      await(receive, "@type", "authorizationStateClosed");
    }
    System.out.println("PASS: JNI load, synchronous execute, asynchronous send/receive, client close");
  }

  private static void requireType(String json, String type) {
    if (json == null || !field("@type", type).matcher(json).find()) {
      throw new IllegalStateException("Expected TDLib type: " + type);
    }
  }

  private static String await(Method receive, String key, String value) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      String response = (String) receive.invoke(null, 0.25);
      if (response != null && field(key, value).matcher(response).find()) return response;
    }
    throw new IllegalStateException("Timed out waiting for TDLib " + value);
  }

  private static Pattern field(String key, String value) {
    return Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"" + Pattern.quote(value) + "\"");
  }
}

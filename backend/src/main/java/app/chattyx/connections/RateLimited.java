package app.chattyx.connections;

/** The provider explicitly rejected the request without sending the message. */
public final class RateLimited extends RuntimeException {

  private final int retryAfterSeconds;

  public RateLimited(int retryAfterSeconds) {
    super("TELEGRAM_RATE_LIMIT");
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public int retryAfterSeconds() {
    return retryAfterSeconds;
  }

  static RateLimited parse(String message) {
    var matcher = java.util.regex.Pattern.compile(
      "(?:FLOOD_WAIT_|retry after |Too Many Requests: retry after )(\\d+)",
      java.util.regex.Pattern.CASE_INSENSITIVE
    ).matcher(message);
    if (!matcher.find()) return null;
    try {
      int seconds = Integer.parseInt(matcher.group(1));
      return seconds > 0 && seconds <= 604800 ? new RateLimited(seconds) : null;
    } catch (NumberFormatException ignored) {
      return null;
    }
  }
}

package app.chattyx.automation;

import java.time.Instant;

public final class BurstPolicy {

  private BurstPolicy() {}

  public static Instant due(Instant first, Instant latest, int quiet, int maximum) {
    var a = latest.plusSeconds(quiet);
    var b = first.plusSeconds(maximum);
    return a.isBefore(b) ? a : b;
  }

  public static boolean current(
    long snapshot,
    long actual,
    String mode,
    boolean selected,
    boolean imported,
    boolean globalEnabled,
    boolean connected
  ) {
    return snapshot == actual && "AUTO".equals(mode) && selected && imported && globalEnabled && connected;
  }
}

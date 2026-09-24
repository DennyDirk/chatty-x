package app.chattyx.identity;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class Totp {

  private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

  private Totp() {}

  public static String secret() {
    byte[] bytes = new byte[20];
    new SecureRandom().nextBytes(bytes);
    var s = new StringBuilder();
    int buffer = 0,
      bits = 0;
    for (byte b : bytes) {
      buffer = (buffer << 8) | (b & 255);
      bits += 8;
      while (bits >= 5) {
        bits -= 5;
        s.append(ALPHABET.charAt((buffer >> bits) & 31));
      }
    }
    return s.toString();
  }

  public static String code(String secret, long step) {
    try {
      var out = new ByteArrayOutputStream();
      int buffer = 0,
        bits = 0;
      for (char c : secret.toCharArray()) {
        int n = ALPHABET.indexOf(c);
        if (n < 0) throw new IllegalArgumentException();
        buffer = (buffer << 5) | n;
        bits += 5;
        if (bits >= 8) {
          bits -= 8;
          out.write((buffer >> bits) & 255);
        }
      }
      var mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(out.toByteArray(), "HmacSHA1"));
      byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
      int n = ByteBuffer.wrap(h, h[19] & 15, 4).getInt() & 0x7fffffff;
      return String.format("%06d", n % 1000000);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid TOTP secret");
    }
  }

  public static long verify(String secret, String value, long seconds) {
    if (value == null || !value.matches("[0-9]{6}")) return -1;
    for (long s = seconds / 30 - 1; s <= seconds / 30 + 1; s++) if (
      java.security.MessageDigest.isEqual(code(secret, s).getBytes(), value.getBytes())
    ) return s;
    return -1;
  }
}

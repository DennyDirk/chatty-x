package app.chattyx.identity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SecretBox {

  private final SecretKeySpec key;

  public SecretBox(@Value("${chatty.master-key}") String value) {
    byte[] bytes = Base64.getDecoder().decode(value);
    if (bytes.length != 32) throw new IllegalArgumentException("MASTER_KEY must encode 32 bytes");
    key = new SecretKeySpec(bytes, "AES");
  }

  public String encrypt(String plain) {
    try {
      byte[] iv = new byte[12];
      new SecureRandom().nextBytes(iv);
      var c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
      byte[] body = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(
        ByteBuffer.allocate(iv.length + body.length)
          .put(iv)
          .put(body)
          .array()
      );
    } catch (Exception e) {
      throw new IllegalStateException("Encryption failed");
    }
  }

  public String decrypt(String value) {
    try {
      var b = ByteBuffer.wrap(Base64.getDecoder().decode(value));
      byte[] iv = new byte[12];
      b.get(iv);
      byte[] body = new byte[b.remaining()];
      b.get(body);
      var c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
      return new String(c.doFinal(body), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("Decryption failed");
    }
  }
}

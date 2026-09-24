package app.chattyx.identity;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class IdentityPrimitivesTest {

  @Test
  void totpMatchesPublishedRfc6238Sha1Vectors() {
    String secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    assertThat(Totp.code(secret, 59 / 30)).isEqualTo("287082");
    assertThat(Totp.code(secret, 1111111109L / 30)).isEqualTo("081804");
    assertThat(Totp.verify(secret, "287082", 59)).isEqualTo(1);
    assertThat(Totp.verify(secret, "287082", 300)).isEqualTo(-1);
  }

  @Test
  void encryptedSecretsAreRandomizedAndRejectTampering() {
    var box = new SecretBox("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    String first = box.encrypt("synthetic-test-key"),
      second = box.encrypt("synthetic-test-key");
    assertThat(first).isNotEqualTo(second).doesNotContain("synthetic");
    assertThat(box.decrypt(first)).isEqualTo("synthetic-test-key");
    byte[] bytes = java.util.Base64.getDecoder().decode(first);
    bytes[bytes.length - 1] ^= 1;
    assertThatThrownBy(() -> box.decrypt(java.util.Base64.getEncoder().encodeToString(bytes))).isInstanceOf(
      RuntimeException.class
    );
  }
}

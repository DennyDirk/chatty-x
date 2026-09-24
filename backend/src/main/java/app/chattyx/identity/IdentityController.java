package app.chattyx.identity;

import app.chattyx.shared.*;
import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/identity")
public class IdentityController {

  private final Db db;
  private final SecretBox box;
  private final PasswordEncoder passwords;
  private final Clock clock;
  private final String bootstrap;
  private final HttpSessionSecurityContextRepository contexts;
  private final TransactionTemplate tx;
  private final Map<String, List<Long>> attempts = new ConcurrentHashMap<>();

  public IdentityController(
    Db db,
    SecretBox box,
    PasswordEncoder passwords,
    Clock clock,
    @Value("${chatty.bootstrap-token}") String bootstrap,
    HttpSessionSecurityContextRepository contexts,
    org.springframework.transaction.PlatformTransactionManager tm
  ) {
    if (bootstrap.length() < 24) throw new IllegalArgumentException(
      "BOOTSTRAP_TOKEN must contain at least 24 characters"
    );
    this.db = db;
    this.box = box;
    this.passwords = passwords;
    this.clock = clock;
    this.bootstrap = bootstrap;
    this.contexts = contexts;
    this.tx = new TransactionTemplate(tm);
  }

  @GetMapping("/status")
  public Map<String, Object> status(HttpServletRequest req) {
    return Map.of(
      "setupRequired",
      db.jdbc.queryForObject("SELECT count(*) FROM owner_account", Integer.class) == 0,
      "authenticated",
      req.getUserPrincipal() != null
    );
  }

  @GetMapping("/csrf")
  public Map<String, String> csrf(CsrfToken token) {
    return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
  }

  @PostMapping("/enroll")
  public Map<String, String> enroll(@RequestBody Map<String, String> body, HttpServletRequest req) {
    checkBootstrap(body.get("bootstrapToken"), req);
    if (
      db.jdbc.queryForObject("SELECT count(*) FROM owner_account", Integer.class) != 0
    ) throw new ApiException(409, "ALREADY_CONFIGURED");
    String secret = Totp.secret();
    req.getSession().setAttribute("enrollment", secret);
    req.getSession().setAttribute("enrollmentAt", clock.millis());
    return Map.of(
      "secret",
      secret,
      "uri",
      "otpauth://totp/Chatty-X:owner?secret=" + secret + "&issuer=Chatty-X"
    );
  }

  public record Setup(String bootstrapToken, String username, String password, String otp) {}

  @PostMapping("/setup")
  public Map<String, Boolean> setup(
    @RequestBody Setup body,
    HttpServletRequest req,
    HttpServletResponse res
  ) {
    checkBootstrap(body.bootstrapToken(), req);
    var session = req.getSession();
    String secret = (String) session.getAttribute("enrollment");
    Long at = (Long) session.getAttribute("enrollmentAt");
    if (secret == null || at == null || clock.millis() - at > 600000) throw new ApiException(
      400,
      "ENROLLMENT_EXPIRED"
    );
    if (
      body.username() == null ||
      !body.username().matches("[a-zA-Z0-9_.-]{3,40}") ||
      body.password() == null ||
      body.password().length() < 12 ||
      body.password().getBytes(StandardCharsets.UTF_8).length > 72
    ) throw new ApiException(400, "WEAK_CREDENTIALS");
    long step = Totp.verify(secret, body.otp(), clock.instant().getEpochSecond());
    if (step < 0) throw new ApiException(400, "INVALID_OTP");
    String hash = passwords.encode(body.password());
    tx.executeWithoutResult(s -> {
      db.jdbc.execute("LOCK TABLE owner_account IN EXCLUSIVE MODE");
      if (
        db.jdbc.queryForObject("SELECT count(*) FROM owner_account", Integer.class) != 0
      ) throw new ApiException(409, "ALREADY_CONFIGURED");
      db.jdbc.update(
        "INSERT INTO owner_account(id,username,password_hash,totp_secret,last_totp_step) VALUES (1,?,?,?,?)",
        body.username(),
        hash,
        box.encrypt(secret),
        step
      );
    });
    session.removeAttribute("enrollment");
    session.removeAttribute("enrollmentAt");
    authenticate(body.username(), 0L, req, res);
    return Map.of("ok", true);
  }

  public record Login(String username, String password, String otp) {}

  @PostMapping("/login")
  public Map<String, Boolean> login(
    @RequestBody Login body,
    HttpServletRequest req,
    HttpServletResponse res
  ) {
    throttle(req);
    if (body.password() == null || body.password().length() > 100) throw new ApiException(
      401,
      "INVALID_CREDENTIALS"
    );
    var account = tx.execute(s -> {
      var rows = db.jdbc.queryForList("SELECT * FROM owner_account WHERE id=1 FOR UPDATE");
      if (rows.isEmpty()) throw new ApiException(401, "INVALID_CREDENTIALS");
      var row = rows.getFirst();
      boolean passwordOk = passwords.matches(body.password(), row.get("password_hash").toString());
      long step = Totp.verify(
        box.decrypt(row.get("totp_secret").toString()),
        body.otp(),
        clock.instant().getEpochSecond()
      );
      if (
        !passwordOk ||
        !row.get("username").equals(body.username()) ||
        step < 0 ||
        step <= ((Number) row.get("last_totp_step")).longValue()
      ) throw new ApiException(401, "INVALID_CREDENTIALS");
      db.jdbc.update("UPDATE owner_account SET last_totp_step=? WHERE id=1", step);
      return row;
    });
    authenticate(body.username(), ((Number) account.get("session_version")).longValue(), req, res);
    return Map.of("ok", true);
  }

  private void authenticate(String username, long version, HttpServletRequest req, HttpServletResponse res) {
    req.getSession();
    req.changeSessionId();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
      new UsernamePasswordAuthenticationToken(
        username,
        null,
        List.of(new SimpleGrantedAuthority("ROLE_OWNER"))
      )
    );
    SecurityContextHolder.setContext(context);
    req.getSession().setAttribute("ownerVersion", version);
    contexts.saveContext(context, req, res);
    db.audit("OWNER_LOGIN", null);
  }

  @PostMapping("/logout")
  public void logout(HttpServletRequest req) {
    var s = req.getSession(false);
    if (s != null) s.invalidate();
    SecurityContextHolder.clearContext();
  }

  @PostMapping("/logout-all")
  public void logoutAll(HttpServletRequest req) {
    db.jdbc.update("UPDATE owner_account SET session_version=session_version+1 WHERE id=1");
    logout(req);
  }

  private void checkBootstrap(String token, HttpServletRequest req) {
    throttle(req);
    if (
      token == null ||
      !java.security.MessageDigest.isEqual(
        bootstrap.getBytes(StandardCharsets.UTF_8),
        token.getBytes(StandardCharsets.UTF_8)
      )
    ) throw new ApiException(403, "INVALID_BOOTSTRAP_TOKEN");
  }

  private synchronized void throttle(HttpServletRequest req) {
    long now = clock.millis();
    attempts.values().forEach(list -> list.removeIf(t -> t < now - 300000));
    attempts.entrySet().removeIf(e -> e.getValue().isEmpty());
    if (attempts.size() > 1000) throw new ApiException(429, "TRY_LATER");
    var list = attempts.computeIfAbsent(req.getRemoteAddr(), k -> new ArrayList<>());
    if (list.size() >= 10) throw new ApiException(429, "TRY_LATER");
    list.add(now);
  }
}

package app.chattyx.shared;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiErrors {

  @ExceptionHandler(ApiException.class)
  ResponseEntity<?> known(ApiException e) {
    return ResponseEntity.status(e.status()).body(Map.of("code", e.code()));
  }

  @ExceptionHandler({ IllegalArgumentException.class, MethodArgumentNotValidException.class })
  ResponseEntity<?> invalid(Exception e) {
    return ResponseEntity.badRequest().body(Map.of("code", "INVALID_INPUT"));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unknown(Exception e) {
    org.slf4j.LoggerFactory.getLogger(getClass()).error(
      "request_failed type={}",
      e.getClass().getSimpleName()
    );
    return ResponseEntity.internalServerError().body(Map.of("code", "INTERNAL_ERROR"));
  }
}

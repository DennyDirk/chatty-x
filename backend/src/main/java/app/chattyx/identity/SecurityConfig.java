package app.chattyx.identity;

import app.chattyx.shared.Db;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {

  @Bean
  org.springframework.security.core.userdetails.UserDetailsService noGeneratedAccounts() {
    return username -> {
      throw new org.springframework.security.core.userdetails.UsernameNotFoundException(
        "Use owner authentication"
      );
    };
  }

  @Bean
  PasswordEncoder passwords() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  HttpSessionSecurityContextRepository contexts() {
    return new HttpSessionSecurityContextRepository();
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http, Db db, HttpSessionSecurityContextRepository contexts)
    throws Exception {
    http.authorizeHttpRequests(a ->
      a
        .requestMatchers(
          "/api/v1/identity/status",
          "/api/v1/identity/csrf",
          "/api/v1/identity/enroll",
          "/api/v1/identity/setup",
          "/api/v1/identity/login",
          "/health",
          "/error"
        )
        .permitAll()
        .anyRequest()
        .authenticated()
    );
    http.securityContext(c -> c.securityContextRepository(contexts));
    http.exceptionHandling(e ->
      e.authenticationEntryPoint((req, res, x) -> {
        res.setStatus(401);
        res.setContentType("application/json");
        res.getWriter().write("{\"code\":\"UNAUTHENTICATED\"}");
      })
    );
    http
      .formLogin(f -> f.disable())
      .httpBasic(b -> b.disable())
      .logout(l -> l.disable());
    http.addFilterAfter(
      new OncePerRequestFilter() {
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
          throws ServletException, IOException {
          var session = req.getSession(false);
          if (session != null && session.getAttribute("ownerVersion") instanceof Long version) {
            var current = db.jdbc.queryForList(
              "SELECT session_version FROM owner_account WHERE id=1",
              Long.class
            );
            if (current.isEmpty() || !current.getFirst().equals(version)) {
              session.invalidate();
              SecurityContextHolder.clearContext();
              res.setStatus(401);
              return;
            }
          }
          chain.doFilter(req, res);
        }
      },
      SecurityContextHolderFilter.class
    );
    return http.build();
  }
}

package at.spps;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.server.ResponseStatusException;

@Configuration
class Security {
  @Bean
  SecurityFilterChain chain(HttpSecurity http, @Value("${spps.demo}") boolean demo)
      throws Exception {
    http.authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/",
                        "/index.html",
                        "/app.js",
                        "/ui/**",
                        "/styles.css",
                        "/webjars/**",
                        "/health",
                        "/session",
                        "/feeds/**",
                        "/signin",
                        "/login",
                        "/error")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(e -> e.authenticationEntryPoint((q, r, x) -> r.sendError(401)))
        .logout(l -> l.logoutSuccessHandler((q, r, a) -> r.setStatus(204)));
    http.headers(
        h ->
            h.frameOptions(f -> f.disable())
                .addHeaderWriter(
                    (q, r) -> {
                      if (!q.getRequestURI().startsWith("/feeds/"))
                        r.setHeader("X-Frame-Options", "DENY");
                    }));
    if (demo)
      http.formLogin(
          f ->
              f.loginProcessingUrl("/login")
                  .successHandler((q, r, a) -> r.setStatus(204))
                  .failureHandler((q, r, x) -> r.sendError(401)));
    else http.oauth2Login(o -> o.defaultSuccessUrl("/", true));
    return http.build();
  }

  static final String[] ROLES = {
    "PLAN", "DIRECTION", "TEACHER", "CURATOR", "STUDENT", "GUARDIAN", "OFFICE", "ADMIN"
  };

  @Bean
  UserDetailsService demoUsers(@Value("${spps.demo}") boolean demo) {
    var service = new InMemoryUserDetailsManager();
    if (demo)
      for (String role : ROLES)
        service.createUser(
            User.withUsername(role.toLowerCase(java.util.Locale.ROOT))
                .password("{noop}schulplan-demo")
                .roles("USER")
                .build());
    return service;
  }

  static String subject(Authentication a) {
    return a.getPrincipal() instanceof OidcUser u ? u.getSubject() : a.getName();
  }

  record Member(String school, String role, String entity, String actor) {
    void allow(String... roles) {
      if (!java.util.List.of(roles).contains(role))
        throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    boolean full() {
      return java.util.List.of("PLAN", "DIRECTION", "OFFICE").contains(role);
    }
  }

  static Member member(JdbcTemplate db, Authentication a, String school) {
    String actor = subject(a);
    return db
        .query(
            "select role,entity from membership where school_id=? and subject=?",
            (r, n) -> new Member(school, r.getString(1), r.getString(2), actor),
            school,
            actor)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
  }
}

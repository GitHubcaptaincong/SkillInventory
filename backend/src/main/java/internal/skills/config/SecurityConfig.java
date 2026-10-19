package internal.skills.config;

import internal.skills.dto.Contracts;
import internal.skills.service.ApiKeys;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.*;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.*;
import org.springframework.security.web.context.*;
import org.springframework.security.web.csrf.*;
import org.springframework.security.web.authentication.session.*;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Configuration
public class SecurityConfig {
    static void error(ObjectMapper mapper, HttpServletRequest req, HttpServletResponse res, int status, String code, String message) throws IOException {
        res.setStatus(status); res.setContentType("application/json;charset=UTF-8"); res.setHeader("Cache-Control", "no-store");
        mapper.writeValue(res.getOutputStream(), new Contracts.Error(code, message, String.valueOf(req.getAttribute("requestId"))));
    }
    @Bean PasswordEncoder passwords() { return new BCryptPasswordEncoder(12); }
    @Bean public ApplicationRunner initialize(JdbcTemplate jdbc, PasswordEncoder encoder,
            @Value("${app.admin-username}") String username, @Value("${app.admin-password}") String password) {
        return args -> {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM administrator", Integer.class) != 0) return;
            if (username.isBlank() || username.length() > 64 || password.length() < 12 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
                throw new IllegalStateException("First startup requires ADMIN_USERNAME and ADMIN_INITIAL_PASSWORD (12-72 UTF-8 bytes)");
            // Unique singleton primary key makes concurrent initializers harmless; never update existing credentials.
            jdbc.update("INSERT INTO administrator(id,username,password_hash) VALUES(1,?,?) ON DUPLICATE KEY UPDATE id=id", username, encoder.encode(password));
        };
    }
    @Bean UserDetailsService users(JdbcTemplate jdbc) {
        return username -> jdbc.query("SELECT username,password_hash FROM administrator WHERE username=?", (rs,n) ->
                User.withUsername(rs.getString(1)).password(rs.getString(2)).roles("ADMIN").build(), username)
                .stream().findFirst().orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }
    @Bean AuthenticationManager authentication(UserDetailsService users, PasswordEncoder encoder) {
        var provider = new DaoAuthenticationProvider(users); provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }
    @Bean SecurityContextRepository contexts() { return new HttpSessionSecurityContextRepository(); }
    @Bean SessionAuthenticationStrategy sessions() {
        return new CompositeSessionAuthenticationStrategy(java.util.List.of(new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(new HttpSessionCsrfTokenRepository())));
    }
    @Bean @org.springframework.core.annotation.Order(1)
    SecurityFilterChain clients(HttpSecurity http, ApiKeys keys, ObjectMapper mapper) throws Exception {
        http.securityMatcher("/api/v1/**").csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
            .securityContext(s -> s.securityContextRepository(new NullSecurityContextRepository()))
            .requestCache(c -> c.disable()).authorizeHttpRequests(a -> a.anyRequest().permitAll());
        http.addFilterBefore(new OncePerRequestFilter() {
            protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, jakarta.servlet.FilterChain chain) throws jakarta.servlet.ServletException, IOException {
                boolean valid;
                try { valid=keys.valid(req.getHeader("X-API-Key")); }
                catch (RuntimeException e) { error(mapper,req,res,500,"INTERNAL_ERROR","认证服务暂时不可用"); return; }
                if (!valid) { error(mapper, req,res,401,"UNAUTHORIZED","有效 API Key 必填"); return; }
                chain.doFilter(req,res);
            }
        }, org.springframework.security.web.authentication.AnonymousAuthenticationFilter.class);
        return http.build();
    }
    @Bean SecurityFilterChain admin(HttpSecurity http, ObjectMapper mapper, SecurityContextRepository contexts) throws Exception {
        org.springframework.security.web.access.AccessDeniedHandler denied = (q,r,x) -> {
            var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            boolean missingAdmin=q.getRequestURI().startsWith("/api/admin/") && (auth==null || !auth.getAuthorities().stream().anyMatch(v -> v.getAuthority().equals("ROLE_ADMIN")));
            error(mapper,q,r,missingAdmin?401:403,missingAdmin?"UNAUTHORIZED":"FORBIDDEN",missingAdmin?"请先登录":"权限不足或 CSRF Token 无效");
        };
        http.securityContext(s -> s.securityContextRepository(contexts))
            .requestCache(c -> c.disable())
            .authorizeHttpRequests(a -> a.requestMatchers("/api/auth/csrf", "/api/auth/login", "/actuator/health").permitAll()
                .requestMatchers("/api/admin/**", "/api/auth/me", "/api/auth/logout").hasRole("ADMIN").anyRequest().denyAll())
            .exceptionHandling(e -> e.authenticationEntryPoint((q,r,x) -> error(mapper,q,r,401,"UNAUTHORIZED","请先登录"))
                .accessDeniedHandler(denied))
            .logout(l -> l.logoutRequestMatcher(new AntPathRequestMatcher("/api/auth/logout", "POST"))
                .logoutSuccessHandler((q,r,a) -> { r.setStatus(204); r.setHeader("Cache-Control","no-store"); }).deleteCookies("SESSION"));
        return http.build();
    }
}

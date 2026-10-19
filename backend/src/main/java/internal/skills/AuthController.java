package internal.skills;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
class AuthController {
    private final AuthenticationManager manager;
    private final SecurityContextRepository contexts;
    private final SessionAuthenticationStrategy sessions;
    // Global bounded window per process: no attacker-controlled map and no permanent account lock.
    private long window = System.currentTimeMillis();
    private int attempts;
    AuthController(AuthenticationManager manager, SecurityContextRepository contexts, SessionAuthenticationStrategy sessions) {
        this.manager=manager; this.contexts=contexts; this.sessions=sessions;
    }
    record Login(@NotBlank @Size(max=64) String username, @NotBlank @Size(max=128) String password) {}
    @GetMapping("/csrf") Map<String,String> csrf(CsrfToken token) { return Map.of("token",token.getToken(),"headerName",token.getHeaderName()); }
    @GetMapping("/me") Map<String,String> me(Authentication auth) { return Map.of("username", auth.getName()); }
    private synchronized void throttle() {
        long now=System.currentTimeMillis();
        if (now-window>=60000) { window=now; attempts=0; }
        if (attempts>=20) throw new ApiException(429,"LOGIN_THROTTLED","登录尝试过多，请一分钟后重试");
        attempts++;
    }
    @PostMapping("/login") Map<String,String> login(@Valid @RequestBody Login input, HttpServletRequest req, HttpServletResponse res) {
        throttle();
        try {
            Authentication auth=manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(input.username(),input.password()));
            sessions.onAuthentication(auth,req,res);
            var context=SecurityContextHolder.createEmptyContext(); context.setAuthentication(auth);
            SecurityContextHolder.setContext(context); contexts.saveContext(context,req,res);
            return Map.of("username",auth.getName());
        } catch (org.springframework.security.core.AuthenticationException e) {
            throw new ApiException(401,"INVALID_CREDENTIALS","用户名或密码错误");
        }
    }
}

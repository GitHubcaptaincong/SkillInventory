package internal.skills.security;

import internal.skills.dto.Contracts;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.net.URI;
import java.util.*;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestPolicy extends OncePerRequestFilter {
    public static final int MAX_BODY = 2 * 1024 * 1024;
    private final Set<String> allowed;
    private final ObjectMapper mapper;
    RequestPolicy(@Value("${app.allowed-origins}") String origins, ObjectMapper mapper) {
        this.mapper = mapper;
        allowed = new HashSet<>();
        for (String entry : origins.split(",")) {
            String value = entry.strip();
            try {
                URI u = URI.create(value);
                if (!Set.of("http", "https").contains(u.getScheme()) || u.getHost() == null || u.getRawUserInfo() != null
                        || (u.getRawPath() != null && !u.getRawPath().isEmpty()) || u.getRawQuery() != null || u.getRawFragment() != null)
                    throw new IllegalArgumentException();
            } catch (Exception e) { throw new IllegalArgumentException("ALLOWED_ORIGINS 必须是逗号分隔的精确 http(s) origin，无路径、通配符或尾斜杠"); }
            allowed.add(value);
        }
        if (allowed.isEmpty()) throw new IllegalArgumentException("必须配置 ALLOWED_ORIGINS");
    }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        req.setAttribute("requestId", requestId); res.setHeader("X-Request-Id", requestId);
        res.setHeader("X-Content-Type-Options", "nosniff"); res.setHeader("Cache-Control", "no-store");
        String origin = req.getHeader("Origin");
        boolean write = Set.of("POST", "PUT", "PATCH", "DELETE").contains(req.getMethod());
        boolean api = req.getRequestURI().startsWith("/api/");
        if (api && (write || req.getMethod().equals("OPTIONS")) && ((origin != null && !allowed.contains(origin))
                || "cross-site".equalsIgnoreCase(req.getHeader("Sec-Fetch-Site")))) {
            reject(res, 403, "ORIGIN_DENIED", "来源不允许，请检查 ALLOWED_ORIGINS", requestId); return;
        }
        if (api && origin != null && allowed.contains(origin)) {
            res.setHeader("Access-Control-Allow-Origin", origin); res.setHeader("Vary", "Origin"); res.setHeader("Access-Control-Allow-Credentials", "true");
            res.setHeader("Access-Control-Expose-Headers", "ETag,X-Request-Id");
        }
        if (api && req.getMethod().equals("OPTIONS")) {
            res.setHeader("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");
            res.setHeader("Access-Control-Allow-Headers", "Content-Type,If-None-Match,X-CSRF-TOKEN,X-API-Key");
            res.setStatus(204); return;
        }
        if (api && write) {
            try {
                MediaType type = req.getContentType() == null ? null : MediaType.parseMediaType(req.getContentType());
                if (type == null || !type.getType().equals("application") || !type.getSubtype().equals("json")) {
                    reject(res, 415, "UNSUPPORTED_MEDIA_TYPE", "写请求须使用 application/json（包括 DELETE）", requestId); return;
                }
            } catch (IllegalArgumentException e) { reject(res, 415, "UNSUPPORTED_MEDIA_TYPE", "无效媒体类型", requestId); return; }
            if (req.getContentLengthLong() > MAX_BODY) { reject(res, 413, "PAYLOAD_TOO_LARGE", "请求上限为 2 MiB", requestId); return; }
            byte[] body = req.getInputStream().readNBytes(MAX_BODY + 1);
            if (body.length > MAX_BODY) { reject(res, 413, "PAYLOAD_TOO_LARGE", "请求上限为 2 MiB", requestId); return; }
            chain.doFilter(new HttpServletRequestWrapper(req) {
                @Override public ServletInputStream getInputStream() {
                    ByteArrayInputStream input = new ByteArrayInputStream(body);
                    return new ServletInputStream() {
                        public int read() { return input.read(); }
                        public boolean isFinished() { return input.available() == 0; }
                        public boolean isReady() { return true; }
                        public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                    };
                }
                @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8)); }
            }, res); return;
        }
        chain.doFilter(req, res);
    }
    private void reject(HttpServletResponse res, int status, String code, String message, String id) throws IOException {
        res.setStatus(status); res.setContentType("application/json;charset=UTF-8"); res.setHeader("Cache-Control", "no-store");
        mapper.writeValue(res.getOutputStream(), new Contracts.Error(code, message, id));
    }
}

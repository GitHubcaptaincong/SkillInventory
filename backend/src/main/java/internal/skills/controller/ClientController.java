package internal.skills.controller;

import internal.skills.service.SkillService;
import internal.skills.exception.ApiException;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;


@RestController
@Validated
@RequestMapping("/api/v1/skills")
public class ClientController {
    private final SkillService service;
    private final ObjectMapper mapper;

    public ClientController(SkillService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping
    ResponseEntity<byte[]> list(@RequestParam(required = false) String keyword,
                                @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize,
                                HttpServletRequest request, HttpServletResponse response) throws Exception {
        return json(service.clientList(keyword, page, pageSize), request, response);
    }

    @GetMapping("/{name}")
    ResponseEntity<byte[]> detail(@PathVariable @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*") String name,
                                  HttpServletRequest request, HttpServletResponse response) throws Exception {
        checkName(name);
        return json(service.clientDetail(name), request, response);
    }

    @GetMapping(value = "/{name}/SKILL.md", produces = "text/plain;charset=UTF-8")
    ResponseEntity<byte[]> raw(@PathVariable @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*") String name,
                               HttpServletRequest request, HttpServletResponse response) {
        checkName(name);
        return cached(service.raw(name).getBytes(StandardCharsets.UTF_8), MediaType.parseMediaType("text/plain;charset=UTF-8"), request, response);
    }

    private void checkName(String name) {
        if (name.length() > 64) throw ApiException.bad("name 最多 64 个字符");
    }

    private ResponseEntity<byte[]> json(Object data, HttpServletRequest req, HttpServletResponse res) throws Exception {
        return cached(mapper.writeValueAsBytes(data), MediaType.APPLICATION_JSON, req, res);
    }

    private ResponseEntity<byte[]> cached(byte[] body, MediaType type, HttpServletRequest req, HttpServletResponse res) {
        String etag = "\"" + DigestUtils.md5DigestAsHex(body) + "\"";
        res.setHeader("Cache-Control", "private, no-cache");
        res.addHeader("Vary", "X-API-Key");
        // The resource was fetched before this framework check; removed resources never hit 304.
        if (new ServletWebRequest(req, res).checkNotModified(etag)) return null;
        // Spring 6's ServletWebRequest does not match a bare wildcard on GET. Parse with
        // HttpHeaders rather than splitting quoted tag lists; representation existence is known.
        HttpHeaders conditions = new HttpHeaders();
        java.util.Collections.list(req.getHeaders(HttpHeaders.IF_NONE_MATCH))
                .forEach(value -> conditions.add(HttpHeaders.IF_NONE_MATCH, value));
        if (conditions.getIfNoneMatch().contains("*")) {
            res.setStatus(304); res.setHeader(HttpHeaders.ETAG, etag); return null;
        }
        return ResponseEntity.ok().eTag(etag).contentType(type).body(body);
    }
}

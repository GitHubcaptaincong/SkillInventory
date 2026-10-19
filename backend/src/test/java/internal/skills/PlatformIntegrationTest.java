package internal.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"app.allowed-origins=http://localhost:8088", "app.admin-username=test-admin", "app.admin-password=isolated-admin-test-password"})
@AutoConfigureMockMvc
@Testcontainers
class PlatformIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.6")
            .withDatabaseName("skill_test").withUsername("test_app").withPassword("isolated-test-only");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> MYSQL.getJdbcUrl() + (MYSQL.getJdbcUrl().contains("?") ? "&" : "?")
                + "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&useAffectedRows=false");
        r.add("spring.datasource.username", MYSQL::getUsername); r.add("spring.datasource.password", MYSQL::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired SkillRepository repository;
    @Autowired org.springframework.web.context.WebApplicationContext context;
    @Autowired ApiKeys keys;
    MockMvc raw;
    @BeforeEach void clean() {
        repository.deleteAll();
        raw = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(RequestPolicy.class), context.getBean("springSessionRepositoryFilter", jakarta.servlet.Filter.class))
            .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        String key=keys.create(new ApiKeys.Input("business tests",null)).key();
        mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(RequestPolicy.class), context.getBean("springSessionRepositoryFilter", jakarta.servlet.Filter.class))
            .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
            .defaultRequest(get("/").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("test-admin").roles("ADMIN"))
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf().asHeader()).header("X-API-Key",key)).build();
    }
    JsonNode json(MvcResult result) throws Exception { return mapper.readTree(result.getResponse().getContentAsByteArray()); }
    ResultActions send(MockHttpServletRequestBuilder req, Object body) throws Exception {
        return mvc.perform(req.contentType("application/json").content(mapper.writeValueAsBytes(body)));
    }
    JsonNode create(String name) throws Exception {
        return json(send(post("/api/admin/skills"), Map.of("name", name, "description", "旧描述: 中文", "content", "  # 旧正文\n"))
                .andExpect(status().isCreated()).andReturn());
    }
    JsonNode publish(JsonNode s) throws Exception {
        return json(send(post("/api/admin/skills/" + s.get("id").asText() + "/publish"), Map.of("lockVersion", s.get("lockVersion").asLong()))
                .andExpect(status().isOk()).andReturn());
    }
    String id(JsonNode s) { return s.get("id").asText(); }
    long version(JsonNode s) { return s.get("lockVersion").asLong(); }

    @Test void fullSnapshotLifecycleAndRemovalWithOldEtags() throws Exception {
        var s = create("java-review"); String id = id(s);
        assertThat(s.get("id").isTextual()).isTrue();
        mvc.perform(get("/api/v1/skills")).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/v1/skills/java-review")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/skills/java-review/SKILL.md")).andExpect(status().isNotFound());
        s = publish(s);
        var list = json(mvc.perform(get("/api/v1/skills")).andExpect(status().isOk()).andReturn());
        assertThat(list.at("/items/0/description").asText()).isEqualTo("旧描述: 中文");
        assertThat(list.at("/items/0").has("content")).isFalse();
        assertThat(list.at("/items/0").has("lockVersion")).isFalse();
        var raw = mvc.perform(get("/api/v1/skills/java-review/SKILL.md")).andExpect(content().contentType("text/plain;charset=UTF-8")).andReturn();
        String old = raw.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String etag = raw.getResponse().getHeader("ETag");
        var detail = mvc.perform(get("/api/v1/skills/java-review")).andReturn();
        String detailTag = detail.getResponse().getHeader("ETag");
        assertThat(json(detail).get("skillMd").asText()).isEqualTo(old);
        s = json(send(put("/api/admin/skills/" + id), Map.of("description", "未发布秘密", "content", "新正文", "lockVersion", version(s))).andExpect(status().isOk()).andReturn());
        assertThat(s.get("status").asText()).isEqualTo("MODIFIED");
        mvc.perform(get("/api/admin/skills").param("status", "MODIFIED")).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/admin/skills").param("status", "PUBLISHED")).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/v1/skills/java-review/SKILL.md").header("If-None-Match", etag)).andExpect(status().isNotModified());
        mvc.perform(get("/api/v1/skills").param("keyword", "未发布秘密")).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/v1/skills/java-review")).andExpect(jsonPath("$.description").value("旧描述: 中文"));
        send(delete("/api/admin/skills/" + id).param("lockVersion", String.valueOf(version(s))), Map.of()).andExpect(status().isConflict());
        s = publish(s);
        mvc.perform(get("/api/v1/skills/java-review/SKILL.md").header("If-None-Match", etag)).andExpect(status().isOk());
        s = json(send(post("/api/admin/skills/" + id + "/unpublish"), Map.of("lockVersion", version(s))).andExpect(status().isOk()).andReturn());
        mvc.perform(get("/api/v1/skills")).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/v1/skills/java-review").header("If-None-Match", detailTag)).andExpect(status().isNotFound()).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/api/v1/skills/java-review/SKILL.md").header("If-None-Match", etag)).andExpect(status().isNotFound());
        send(delete("/api/admin/skills/" + id).param("lockVersion", String.valueOf(version(s))), Map.of()).andExpect(status().isNoContent());
        assertThat(id(create("java-review"))).isNotEqualTo(id);
    }

    @Test void noOpsAndMonotonicRepublish() throws Exception {
        var s = create("same");
        var same = json(send(put("/api/admin/skills/" + id(s)), Map.of("description", s.get("description").asText(), "content", s.get("content").asText(), "lockVersion", version(s))).andExpect(status().isOk()).andReturn());
        assertThat(version(same)).isEqualTo(version(s));
        assertThat(same).isEqualTo(s);
        s = publish(same); var again = publish(s);
        assertThat(again).isEqualTo(s);
        long published = s.at("/publishedSnapshot/publishedVersion").asLong();
        s = json(send(post("/api/admin/skills/" + id(s) + "/unpublish"), Map.of("lockVersion", version(s))).andExpect(status().isOk()).andReturn());
        var noOp = json(send(post("/api/admin/skills/" + id(s) + "/unpublish"), Map.of("lockVersion", version(s))).andExpect(status().isOk()).andReturn());
        assertThat(noOp).isEqualTo(s);
        assertThat(publish(s).at("/publishedSnapshot/publishedVersion").asLong()).isGreaterThan(published);
    }

    @Test void everyMutationRejectsStaleVersionIncludingNoOps() throws Exception {
        var s = publish(create("concurrent")); String id = id(s);
        send(put("/api/admin/skills/" + id), Map.of("description", "更新", "content", "正文", "lockVersion", 0)).andExpect(status().isConflict());
        send(post("/api/admin/skills/" + id + "/publish"), Map.of("lockVersion", 0)).andExpect(status().isConflict());
        send(post("/api/admin/skills/" + id + "/unpublish"), Map.of("lockVersion", 0)).andExpect(status().isConflict());
        send(delete("/api/admin/skills/" + id).param("lockVersion", "0"), Map.of()).andExpect(status().isConflict());
        s = json(send(post("/api/admin/skills/" + id + "/unpublish"), Map.of("lockVersion", version(s))).andReturn());
        send(post("/api/admin/skills/" + id + "/unpublish"), Map.of("lockVersion", version(s) - 1)).andExpect(status().isConflict());
        send(delete("/api/admin/skills/" + id).param("lockVersion", String.valueOf(version(s) - 1)), Map.of()).andExpect(status().isConflict());
    }

    @Test void simultaneousWritesHaveOneWinner() throws Exception {
        var s = create("race");
        var gate = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 2; i++) {
                String value = "writer-" + i;
                tasks.add(executor.submit(() -> {
                    gate.await(10, TimeUnit.SECONDS);
                    return send(put("/api/admin/skills/" + id(s)), Map.of("description", value, "content", value, "lockVersion", version(s))).andReturn().getResponse().getStatus();
                }));
            }
            assertThat(List.of(tasks.get(0).get(20, TimeUnit.SECONDS), tasks.get(1).get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(json(mvc.perform(get("/api/admin/skills/" + id(s))).andReturn()).get("lockVersion").asLong()).isEqualTo(1);
    }

    @Test void inputBoundariesAndSystemFieldBinding() throws Exception {
        create("unique"); createBad("unique", "ok", "body", 409);
        for (String n : List.of("Upper", "-start", "end-", "double--hyphen", "a_b", "a".repeat(65))) createBad(n, "ok", "body", 400);
        createBad("long-desc", "中".repeat(1025), "body", 400);
        createBad("unicode", "ok", "中".repeat(87382), 400);
        createBad("empty", "ok", " \n\t", 400);
        createBad("yaml", "ok", "---\nname: x\n---\nbody", 400);
        var s = create("immutable");
        send(put("/api/admin/skills/" + id(s)), Map.of("name", "override", "description", "ok", "content", "x", "lockVersion", 0)).andExpect(status().isBadRequest());
        send(post("/api/admin/skills"), Map.of("name", "system", "description", "ok", "content", "body", "publishedSnapshot", Map.of())).andExpect(status().isBadRequest());
        send(post("/api/admin/skills/" + id(s) + "/publish"), Map.of("lockVersion", -1)).andExpect(status().isBadRequest());
        send(post("/api/admin/skills/" + id(s) + "/publish"), Map.of("lockVersion", 0.5)).andExpect(status().isBadRequest());
        send(post("/api/admin/skills/" + id(s) + "/publish"), Map.of("lockVersion", "0")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/skills").contentType("application/json").content("x".repeat(RequestPolicy.MAX_BODY + 1))).andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.requestId").isNotEmpty());
    }
    void createBad(String name, String description, String content, int status) throws Exception {
        send(post("/api/admin/skills"), Map.of("name", name, "description", description, "content", content)).andExpect(status().is(status));
    }

    @Test void pagingLiteralSearchAndConditionalHeaders() throws Exception {
        var s = create("search");
        s = json(send(put("/api/admin/skills/" + id(s)), Map.of("description", "100%_ literal", "content", "body", "lockVersion", version(s))).andReturn());
        publish(s); create("another");
        mvc.perform(get("/api/admin/skills").param("keyword", "%_")).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/admin/skills").param("keyword", "' OR 1=1 --")).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/admin/skills").param("status", "PUBLISHED")).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/admin/skills").param("status", "MODIFIED")).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/admin/skills").param("pageSize", "1").param("page", "2")).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.total").value(2));
        for (String p : List.of("0", "100001", "2147483648")) mvc.perform(get("/api/v1/skills").param("page", p)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/skills").param("pageSize", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/skills").param("status", "BAD")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/skills").param("keyword", "x".repeat(129))).andExpect(status().isBadRequest());
        for (String path : List.of("/api/v1/skills", "/api/v1/skills/search", "/api/v1/skills/search/SKILL.md")) {
            var response = mvc.perform(get(path)).andExpect(header().string("Cache-Control", "private, no-cache")).andReturn().getResponse();
            String etag = response.getHeader("ETag");
            mvc.perform(get(path).header("If-None-Match", "\"different\", W/" + etag)).andExpect(status().isNotModified());
            mvc.perform(get(path).header("If-None-Match", "*")).andExpect(status().isNotModified());
        }
        String first = mvc.perform(get("/api/v1/skills")).andReturn().getResponse().getHeader("ETag");
        publish(create("new-item"));
        mvc.perform(get("/api/v1/skills").header("If-None-Match", first)).andExpect(status().isOk());
    }

    @Test void originMediaTypeAndErrors() throws Exception {
        var input = Map.of("name", "origin", "description", "ok", "content", "body");
        send(post("/api/admin/skills").header("Origin", "https://evil.test"), input).andExpect(status().isForbidden());
        send(post("/api/admin/skills").header("Sec-Fetch-Site", "cross-site"), input).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/skills").contentType("text/plain").content("{}"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.requestId").isNotEmpty()).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(post("/api/admin/skills").contentType("application/x-www-form-urlencoded").content("name=x")).andExpect(status().isUnsupportedMediaType());
        send(post("/api/admin/skills").header("Origin", "http://localhost:8088").header("Sec-Fetch-Site", "same-origin"), input).andExpect(status().isCreated());
        mvc.perform(get("/api/admin/skills/-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/skills/Upper")).andExpect(status().isBadRequest());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.components").doesNotExist());
    }

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;
    @Test void realSessionLoginCsrfLogoutAndPasswordInitialization() throws Exception {
        raw.perform(get("/api/admin/skills")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.requestId").isNotEmpty());
        raw.perform(post("/api/auth/login").contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
        for (String path : List.of("/api/admin/skills", "/api/admin/api-keys", "/api/admin/skills/1/publish", "/api/admin/api-keys/1/revoke")) {
            raw.perform(post(path).contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","no-store"));
        }
        var tokenResult=raw.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        var cookie=tokenResult.getResponse().getCookie("SESSION");
        String token=json(tokenResult).get("token").asText();
        raw.perform(post("/api/auth/login").cookie(cookie).header("X-CSRF-TOKEN",token).contentType("application/json")
            .content(mapper.writeValueAsBytes(Map.of("username","missing","password","incorrect"))))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        var login=raw.perform(post("/api/auth/login").cookie(cookie).header("X-CSRF-TOKEN",token).contentType("application/json")
            .content(mapper.writeValueAsBytes(Map.of("username","test-admin","password","isolated-admin-test-password"))))
            .andExpect(status().isOk()).andReturn();
        var authenticated=login.getResponse().getCookie("SESSION");
        assertThat(authenticated).isNotNull();
        assertThat(authenticated.isHttpOnly()).isTrue(); assertThat(authenticated.getPath()).isEqualTo("/");
        assertThat(authenticated.getAttribute("SameSite")).isEqualTo("Lax");
        assertThat(authenticated.getValue()).isNotEqualTo(cookie.getValue());
        raw.perform(get("/api/auth/me").cookie(authenticated)).andExpect(status().isOk()).andExpect(jsonPath("$.username").value("test-admin"));
        raw.perform(get("/api/admin/skills").cookie(authenticated)).andExpect(status().isOk());
        raw.perform(get("/api/v1/skills").cookie(authenticated)).andExpect(status().isUnauthorized());
        raw.perform(post("/api/admin/api-keys").cookie(authenticated).contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
        raw.perform(post("/api/admin/api-keys").cookie(authenticated).header("X-CSRF-TOKEN","wrong").contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
        var csrf=raw.perform(get("/api/auth/csrf").cookie(authenticated)).andReturn();
        String current=json(csrf).get("token").asText();
        raw.perform(post("/api/admin/api-keys").cookie(authenticated).header("X-CSRF-TOKEN",current).contentType("application/json")
            .content("{\"name\":\"browser key\"}")).andExpect(status().isCreated());
        raw.perform(post("/api/auth/logout").cookie(authenticated).header("X-CSRF-TOKEN",current).contentType("application/json").content("{}"))
            .andExpect(status().isNoContent());
        raw.perform(get("/api/auth/me").cookie(authenticated)).andExpect(status().isUnauthorized());
        String hash=jdbc.queryForObject("SELECT password_hash FROM administrator WHERE id=1",String.class);
        assertThat(hash).startsWith("$2"); assertThat(encoder.matches("isolated-admin-test-password",hash)).isTrue();
        new SecurityConfig().initialize(jdbc,encoder,"replacement","another-initial-password").run(null);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM administrator WHERE id=1",String.class)).isEqualTo(hash);
        // Expired persisted sessions must not authenticate on the next request.
        var fresh=raw.perform(get("/api/auth/csrf")).andReturn();
        var c=fresh.getResponse().getCookie("SESSION");
        var logged=raw.perform(post("/api/auth/login").cookie(c).header("X-CSRF-TOKEN",json(fresh).get("token").asText())
            .contentType("application/json").content(mapper.writeValueAsBytes(Map.of("username","test-admin","password","isolated-admin-test-password"))))
            .andExpect(status().isOk()).andReturn().getResponse().getCookie("SESSION");
        jdbc.update("UPDATE SPRING_SESSION SET LAST_ACCESS_TIME=0, EXPIRY_TIME=0");
        raw.perform(get("/api/auth/me").cookie(logged)).andExpect(status().isUnauthorized());
    }
    @Test void apiKeyBoundariesRevocationAndConditionalCaching() throws Exception {
        var created=keys.create(new ApiKeys.Input("external",null)); String key=created.key();
        for(String path:List.of("/api/v1/skills","/api/v1/skills/test","/api/v1/skills/test/SKILL.md")) {
            raw.perform(get(path)).andExpect(status().isUnauthorized());
            raw.perform(get(path).header("X-API-Key","invalid").header("If-None-Match","*"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","no-store"));
            raw.perform(get(path).param("api_key",key)).andExpect(status().isUnauthorized());
        }
        raw.perform(get("/api/admin/skills").header("X-API-Key",key)).andExpect(status().isUnauthorized());
        create("key-test");
        raw.perform(get("/api/v1/skills/key-test").header("X-API-Key",key)).andExpect(status().isNotFound());
        var skill=json(mvc.perform(get("/api/admin/skills")).andReturn()).at("/items/0");publish(skill);
        for(String path:List.of("/api/v1/skills","/api/v1/skills/key-test","/api/v1/skills/key-test/SKILL.md")) {
            var r=raw.perform(get(path).header("X-API-Key",key)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","private, no-cache")).andReturn();
            raw.perform(get(path).header("X-API-Key",key).header("If-None-Match",r.getResponse().getHeader("ETag")))
                .andExpect(status().isNotModified());
        }
        assertThat(mapper.writeValueAsString(keys.list(1,100))).doesNotContain(key);
        byte[] stored=jdbc.queryForObject("SELECT digest FROM api_key WHERE id=?",byte[].class,created.metadata().id());
        assertThat(stored).hasSize(32).isNotEqualTo(key.getBytes());
        jdbc.update("UPDATE api_key SET expires_at=? WHERE id=?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)),created.metadata().id());
        raw.perform(get("/api/v1/skills").header("X-API-Key",key).header("If-None-Match","*")).andExpect(status().isUnauthorized());
        var second=keys.create(new ApiKeys.Input("rotate",null));
        var first=keys.revoke(Long.parseLong(second.metadata().id()));
        assertThat(keys.revoke(Long.parseLong(second.metadata().id())).revokedAt()).isEqualTo(first.revokedAt());
        raw.perform(get("/api/v1/skills").header("X-API-Key",second.key()).header("If-None-Match","*")).andExpect(status().isUnauthorized());
        send(post("/api/admin/api-keys"),Map.of("name"," ")).andExpect(status().isBadRequest());
        send(post("/api/admin/api-keys"),Map.of("name","expired","expiresAt","2020-01-01T00:00:00Z")).andExpect(status().isBadRequest());
    }
}

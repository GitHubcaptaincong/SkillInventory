package internal.skills;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;

@Service
class ApiKeys {
    private final JdbcTemplate jdbc;
    private final SecureRandom random=new SecureRandom();
    ApiKeys(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    record Metadata(String id,String name,String masked,Instant createdAt,Instant expiresAt,Instant revokedAt,String status) {}
    record Created(Metadata metadata,String key) {}
    record Input(@NotBlank @Size(max=100) String name, Instant expiresAt) {}
    static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(); }
    }
    String random(int bytes) { byte[] b=new byte[bytes]; random.nextBytes(b); return HexFormat.of().formatHex(b); }
    boolean valid(String key) {
        if (key==null || !key.matches("sk_[0-9a-f]{24}_[0-9a-f]{64}")) return false;
        return jdbc.query("SELECT digest,expires_at,revoked_at FROM api_key WHERE lookup_id=?", (r,n) -> {
            boolean match=MessageDigest.isEqual(r.getBytes(1),digest(key));
            return match && r.getTimestamp(3)==null && (r.getTimestamp(2)==null || r.getTimestamp(2).toInstant().isAfter(Instant.now()));
        },key.substring(3,27)).stream().findFirst().orElse(false);
    }
    Metadata metadata(java.sql.ResultSet r,int n) throws java.sql.SQLException {
        Instant expires=r.getTimestamp("expires_at")==null?null:r.getTimestamp("expires_at").toInstant();
        Instant revoked=r.getTimestamp("revoked_at")==null?null:r.getTimestamp("revoked_at").toInstant();
        return new Metadata(r.getString("id"),r.getString("name"),r.getString("masked"),r.getTimestamp("created_at").toInstant(),expires,revoked,
            revoked!=null?"REVOKED":expires!=null&&!expires.isAfter(Instant.now())?"EXPIRED":"ACTIVE");
    }
    Created create(Input input) {
        if(input.expiresAt()!=null&&!input.expiresAt().isAfter(Instant.now())) throw ApiException.bad("过期时间必须晚于当前时间");
        String lookup=random(12),key="sk_"+lookup+"_"+random(32);
        jdbc.update("INSERT INTO api_key(name,lookup_id,digest,masked,created_at,expires_at) VALUES(?,?,?,?,?,?)",
            input.name().strip(),lookup,digest(key),"sk_"+lookup+"_…"+key.substring(key.length()-4),Timestamp.from(Instant.now()),input.expiresAt()==null?null:Timestamp.from(input.expiresAt()));
        return new Created(jdbc.query("SELECT * FROM api_key WHERE lookup_id=?",this::metadata,lookup).getFirst(),key);
    }
    Contracts.Page<Metadata> list(int page,int pageSize) {
        if(page<1||page>100000||pageSize<1||pageSize>100) throw ApiException.bad("分页参数不合法");
        return new Contracts.Page<>(jdbc.query("SELECT * FROM api_key ORDER BY id DESC LIMIT ? OFFSET ?",this::metadata,pageSize,(page-1)*pageSize),page,pageSize,jdbc.queryForObject("SELECT COUNT(*) FROM api_key",Long.class));
    }
    Metadata revoke(long id) {
        jdbc.update("UPDATE api_key SET revoked_at=COALESCE(revoked_at,?) WHERE id=?",Timestamp.from(Instant.now()),id);
        return jdbc.query("SELECT * FROM api_key WHERE id=?",this::metadata,id).stream().findFirst().orElseThrow(() -> new ApiException(404,"NOT_FOUND","Key 不存在"));
    }
}
@RestController
@Validated
@RequestMapping("/api/admin/api-keys")
class ApiKeyController {
    private final ApiKeys keys;
    ApiKeyController(ApiKeys keys) { this.keys=keys; }
    @GetMapping Contracts.Page<ApiKeys.Metadata> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) { return keys.list(page,pageSize); }
    @PostMapping @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    ApiKeys.Created create(@Valid @RequestBody ApiKeys.Input input) { return keys.create(input); }
    @PostMapping("/{id}/revoke") ApiKeys.Metadata revoke(@PathVariable @Min(1) long id) { return keys.revoke(id); }
}

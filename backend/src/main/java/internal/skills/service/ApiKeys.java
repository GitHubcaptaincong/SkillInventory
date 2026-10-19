package internal.skills.service;

import internal.skills.dto.Contracts;
import internal.skills.exception.ApiException;
import static internal.skills.dto.ApiKeyContracts.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;

@Service
public class ApiKeys {
    private final JdbcTemplate jdbc;
    private final SecureRandom random=new SecureRandom();
    public ApiKeys(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(); }
    }
    String random(int bytes) { byte[] b=new byte[bytes]; random.nextBytes(b); return HexFormat.of().formatHex(b); }
    public boolean valid(String key) {
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
    public Created create(Input input) {
        if(input.expiresAt()!=null&&!input.expiresAt().isAfter(Instant.now())) throw ApiException.bad("过期时间必须晚于当前时间");
        String lookup=random(12),key="sk_"+lookup+"_"+random(32);
        jdbc.update("INSERT INTO api_key(name,lookup_id,digest,masked,created_at,expires_at) VALUES(?,?,?,?,?,?)",
            input.name().strip(),lookup,digest(key),"sk_"+lookup+"_…"+key.substring(key.length()-4),Timestamp.from(Instant.now()),input.expiresAt()==null?null:Timestamp.from(input.expiresAt()));
        return new Created(jdbc.query("SELECT * FROM api_key WHERE lookup_id=?",this::metadata,lookup).getFirst(),key);
    }
    public Contracts.Page<Metadata> list(int page,int pageSize) {
        if(page<1||page>100000||pageSize<1||pageSize>100) throw ApiException.bad("分页参数不合法");
        return new Contracts.Page<>(jdbc.query("SELECT * FROM api_key ORDER BY id DESC LIMIT ? OFFSET ?",this::metadata,pageSize,(page-1)*pageSize),page,pageSize,jdbc.queryForObject("SELECT COUNT(*) FROM api_key",Long.class));
    }
    public Metadata revoke(long id) {
        jdbc.update("UPDATE api_key SET revoked_at=COALESCE(revoked_at,?) WHERE id=?",Timestamp.from(Instant.now()),id);
        return jdbc.query("SELECT * FROM api_key WHERE id=?",this::metadata,id).stream().findFirst().orElseThrow(() -> new ApiException(404,"NOT_FOUND","Key 不存在"));
    }
}

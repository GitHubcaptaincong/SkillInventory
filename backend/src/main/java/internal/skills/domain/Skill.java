package internal.skills.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

@Entity
@Table(name = "skill")
public class Skill {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 64, updatable = false) private String name;
    @Column(nullable = false, length = 1024) private String description;
    @Column(nullable = false, columnDefinition = "MEDIUMTEXT") private String content;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "published_snapshot", columnDefinition = "JSON") private Published snapshot;
    @Column(name = "lock_version", nullable = false) private long lockVersion;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "published_at") private Instant publishedAt;

    protected Skill() {}

    public Skill(String name, String description, String content, Instant createdAt) {
        this.name = name;
        this.description = description;
        this.content = content;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getContent() { return content; }
    public Published getSnapshot() { return snapshot; }
    public long getLockVersion() { return lockVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getPublishedAt() { return publishedAt; }

    public record Published(String name, String description, String content, long publishedVersion, Instant publishedAt) {}
    public boolean matchesSnapshot() {
        return snapshot != null && name.equals(snapshot.name()) && description.equals(snapshot.description()) && content.equals(snapshot.content());
    }
    public String status() { return snapshot == null ? "UNPUBLISHED" : matchesSnapshot() ? "PUBLISHED" : "MODIFIED"; }
}

package internal.skills;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

@Entity
@Table(name = "skill")
class Skill {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Column(nullable = false, length = 64, updatable = false) String name;
    @Column(nullable = false, length = 1024) String description;
    @Column(nullable = false, columnDefinition = "MEDIUMTEXT") String content;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "published_snapshot", columnDefinition = "JSON") Published snapshot;
    @Column(name = "lock_version", nullable = false) long lockVersion;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;
    @Column(name = "published_at") Instant publishedAt;

    public record Published(String name, String description, String content, long publishedVersion, Instant publishedAt) {}
    boolean matchesSnapshot() {
        return snapshot != null && name.equals(snapshot.name()) && description.equals(snapshot.description()) && content.equals(snapshot.content());
    }
    String status() { return snapshot == null ? "UNPUBLISHED" : matchesSnapshot() ? "PUBLISHED" : "MODIFIED"; }
}

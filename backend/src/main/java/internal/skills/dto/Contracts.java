package internal.skills.dto;

import internal.skills.domain.Skill;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class Contracts {
    private Contracts() {}
    public record Create(@NotNull @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*") @Size(min = 1, max = 64) String name,
                  @NotBlank @Size(max = 1024) String description, @NotNull String content) {}
    public record Update(@NotBlank @Size(max = 1024) String description, @NotNull String content,
                  @NotNull @Min(0) Long lockVersion) {}
    public record Version(@NotNull @Min(0) Long lockVersion) {}
    public record Admin(String id, String name, String description, String content, Skill.Published publishedSnapshot,
                 String status, long lockVersion, Instant createdAt, Instant updatedAt, Instant publishedAt) {
        public static Admin from(Skill s) { return new Admin(s.getId().toString(), s.getName(), s.getDescription(), s.getContent(), s.getSnapshot(),
                s.status(), s.getLockVersion(), s.getCreatedAt(), s.getUpdatedAt(), s.getPublishedAt()); }
    }
    public record Metadata(String id, String name, String description, long publishedVersion, Instant publishedAt, String skillMdPath) {}
    public record Detail(String id, String name, String description, long publishedVersion, Instant publishedAt, String skillMdPath, String skillMd) {}
    public record Page<T>(List<T> items, int page, int pageSize, long total) {}
    public record Error(String code, String message, String requestId) {}
}

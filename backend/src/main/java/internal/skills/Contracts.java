package internal.skills;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

final class Contracts {
    private Contracts() {}
    record Create(@NotNull @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*") @Size(min = 1, max = 64) String name,
                  @NotBlank @Size(max = 1024) String description, @NotNull String content) {}
    record Update(@NotBlank @Size(max = 1024) String description, @NotNull String content,
                  @NotNull @Min(0) Long lockVersion) {}
    record Version(@NotNull @Min(0) Long lockVersion) {}
    record Admin(String id, String name, String description, String content, Skill.Published publishedSnapshot,
                 String status, long lockVersion, Instant createdAt, Instant updatedAt, Instant publishedAt) {
        static Admin from(Skill s) { return new Admin(s.id.toString(), s.name, s.description, s.content, s.snapshot,
                s.status(), s.lockVersion, s.createdAt, s.updatedAt, s.publishedAt); }
    }
    record Metadata(String id, String name, String description, long publishedVersion, Instant publishedAt, String skillMdPath) {}
    record Detail(String id, String name, String description, long publishedVersion, Instant publishedAt, String skillMdPath, String skillMd) {}
    record Page<T>(List<T> items, int page, int pageSize, long total) {}
    record Error(String code, String message, String requestId) {}
}

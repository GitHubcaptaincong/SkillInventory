package internal.skills.dto;

import jakarta.validation.constraints.*;
import java.time.Instant;

public final class ApiKeyContracts {
    private ApiKeyContracts() {}
    public record Metadata(String id,String name,String masked,Instant createdAt,Instant expiresAt,Instant revokedAt,String status) {}
    public record Created(Metadata metadata,String key) {}
    public record Input(@NotBlank @Size(max=100) String name, Instant expiresAt) {}
}

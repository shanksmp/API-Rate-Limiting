package com.api_rate_limiting.demo.admin;

import com.api_rate_limiting.demo.apikey.ApiKey;
import com.api_rate_limiting.demo.ratelimit.Plan;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

final class AdminDtos {

    private AdminDtos() {
    }

    record CreateKeyRequest(
            @NotBlank @Size(max = 100) @Pattern(regexp = "[\\w .@-]+", message = "letters, digits, space, _ . @ - only")
            String owner,
            @NotBlank @Size(max = 50) String plan) {
    }

    record UpdateKeyRequest(@NotBlank @Size(max = 50) String plan) {
    }

    record KeyResponse(String id, String owner, String plan, String status, Instant createdAt) {
        static KeyResponse of(ApiKey k) {
            return new KeyResponse(k.id(), k.owner(), k.plan(), k.status().name(), k.createdAt());
        }
    }

    /** The only response that ever contains the secret. */
    record IssuedKeyResponse(String id, String apiKey, String owner, String plan, Instant createdAt) {
    }

    record PlanResponse(String name, String algorithm, long capacity, Double refillPerSecond, Long windowSeconds) {
        static PlanResponse of(Plan p) {
            return switch (p.algorithm()) {
                case TOKEN_BUCKET -> new PlanResponse(p.name(), "token-bucket", p.capacity(),
                        p.refillTokensPerMs() * 1000, null);
                case SLIDING_WINDOW -> new PlanResponse(p.name(), "sliding-window", p.capacity(), null,
                        p.windowMs() / 1000);
            };
        }
    }
}

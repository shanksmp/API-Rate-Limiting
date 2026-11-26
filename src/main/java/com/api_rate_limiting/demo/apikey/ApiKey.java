package com.api_rate_limiting.demo.apikey;

import java.time.Instant;

/** API key metadata. The secret itself is never stored, only its HMAC. */
public record ApiKey(String id, String owner, String plan, Status status, Instant createdAt) {

    public enum Status { ACTIVE, REVOKED }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    public ApiKey withStatus(Status s) {
        return new ApiKey(id, owner, plan, s, createdAt);
    }

    public ApiKey withPlan(String p) {
        return new ApiKey(id, owner, p, status, createdAt);
    }
}

package com.api_rate_limiting.demo.apikey;

/** Returned once at creation: the only time the plaintext secret is available. */
public record IssuedApiKey(ApiKey key, String secret) {
}

package com.api_rate_limiting.demo.apikey;

import com.api_rate_limiting.demo.config.RateLimitProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * Hashes API keys with HMAC-SHA256 and a server-side pepper. Keys are 256-bit random values, so
 * a fast keyed hash is enough (no bcrypt needed), and a leaked Redis dump can't be used to check
 * guesses without the pepper.
 */
@Component
public class ApiKeyHasher {

    private static final String ALGORITHM = "HmacSHA256";
    static final int MIN_PEPPER_LENGTH = 32;

    private final Mac prototype;

    public ApiKeyHasher(RateLimitProperties props) {
        String pepper = props.security().apiKeyPepper();
        if (pepper == null || pepper.length() < MIN_PEPPER_LENGTH) {
            throw new IllegalStateException("ratelimit.security.api-key-pepper (env API_KEY_PEPPER) must be set to at least "
                    + MIN_PEPPER_LENGTH + " characters");
        }
        try {
            prototype = Mac.getInstance(ALGORITHM);
            prototype.init(new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), ALGORITHM));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    public String hash(String secret) {
        try {
            // Mac isn't thread-safe; cloning an initialised one is cheaper than re-initialising
            Mac mac = (Mac) prototype.clone();
            return HexFormat.of().formatHex(mac.doFinal(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (CloneNotSupportedException e) {
            throw new IllegalStateException(e);
        }
    }
}

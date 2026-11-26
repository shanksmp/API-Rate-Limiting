package com.api_rate_limiting.demo.apikey;

import com.api_rate_limiting.demo.support.TestProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiKeyHasherTest {

    @Test
    void hashIsDeterministicAndHex() {
        ApiKeyHasher hasher = new ApiKeyHasher(TestProperties.defaults());
        assertThat(hasher.hash("rlk_x")).isEqualTo(hasher.hash("rlk_x")).hasSize(64).matches("[0-9a-f]+");
        assertThat(hasher.hash("rlk_x")).isNotEqualTo(hasher.hash("rlk_y"));
    }

    @Test
    void pepperChangesTheHash() {
        ApiKeyHasher a = new ApiKeyHasher(TestProperties.withSecrets(null, "a".repeat(32)));
        ApiKeyHasher b = new ApiKeyHasher(TestProperties.withSecrets(null, "b".repeat(32)));
        assertThat(a.hash("rlk_x")).isNotEqualTo(b.hash("rlk_x"));
    }

    @Test
    void refusesToStartWithoutAStrongPepper() {
        assertThatThrownBy(() -> new ApiKeyHasher(TestProperties.withSecrets(null, "short")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("API_KEY_PEPPER");
        assertThatThrownBy(() -> new ApiKeyHasher(TestProperties.withSecrets(null, null)))
                .isInstanceOf(IllegalStateException.class);
    }
}

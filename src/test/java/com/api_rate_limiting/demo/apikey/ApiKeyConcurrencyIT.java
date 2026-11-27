package com.api_rate_limiting.demo.apikey;

import com.api_rate_limiting.demo.support.AbstractRedisIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ApiKeyConcurrencyIT extends AbstractRedisIT {

    @Autowired
    ApiKeyService apiKeys;

    /** Review finding 1: a plan change racing a revocation must never bring the key back to ACTIVE. */
    @Test
    void revocationIsNeverUndoneByAConcurrentPlanChange() throws Exception {
        int reactivated = 0;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 300; i++) {
                String id = apiKeys.issue("race", "free").key().id();
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<?> revoke = pool.submit(() -> {
                    barrier.await();
                    return apiKeys.revoke(id);
                });
                Future<?> changePlan = pool.submit(() -> {
                    barrier.await();
                    return apiKeys.changePlan(id, "pro");
                });
                revoke.get();
                changePlan.get();
                if (apiKeys.find(id).orElseThrow().isActive()) {
                    reactivated++;
                }
            }
        }
        assertThat(reactivated).as("keys reactivated by a racing plan change").isZero();
    }
}

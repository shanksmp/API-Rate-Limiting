package com.api_rate_limiting.demo.api;

import com.api_rate_limiting.demo.apikey.ApiKey;
import com.api_rate_limiting.demo.ratelimit.Plan;
import com.api_rate_limiting.demo.ratelimit.PlanRegistry;
import com.api_rate_limiting.demo.web.ApiRateLimitFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Sample endpoints behind the limiter, plus an unprotected one for baseline benchmarks. */
@RestController
public class DemoController {

    private final PlanRegistry plans;

    public DemoController(PlanRegistry plans) {
        this.plans = plans;
    }

    @GetMapping("/api/hello")
    public Map<String, String> hello(@RequestAttribute(ApiRateLimitFilter.API_KEY_ATTRIBUTE) ApiKey key) {
        return Map.of("message", "hello, " + key.owner());
    }

    @GetMapping("/api/me")
    public Map<String, Object> me(@RequestAttribute(ApiRateLimitFilter.API_KEY_ATTRIBUTE) ApiKey key) {
        Plan plan = plans.resolve(key.plan());
        return Map.of(
                "keyId", key.id(),
                "owner", key.owner(),
                "plan", plan.name(),
                "algorithm", plan.algorithm().name().toLowerCase(),
                "limit", plan.capacity(),
                "windowSeconds", plan.policyWindowSeconds());
    }

    @GetMapping("/public/ping")
    public Map<String, String> ping() {
        return Map.of("status", "ok");
    }
}

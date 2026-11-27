package com.api_rate_limiting.demo.ratelimit;

import com.api_rate_limiting.demo.config.RateLimitProperties;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Component
public class PlanRegistry {

    private final Map<String, Plan> plans = new LinkedHashMap<>();
    private final Plan defaultPlan;

    public PlanRegistry(RateLimitProperties props) {
        props.plans().forEach((name, p) -> plans.put(name, Plan.from(name, p)));
        this.defaultPlan = plans.get(props.defaultPlan());
        if (defaultPlan == null) {
            throw new IllegalStateException("ratelimit.default-plan '" + props.defaultPlan() + "' is not a configured plan");
        }
    }

    public Optional<Plan> find(String name) {
        return Optional.ofNullable(name).map(plans::get);
    }

    /** The key's plan, or the default plan if that plan was removed from config. */
    public Plan resolve(String name) {
        return find(name).orElse(defaultPlan);
    }

    public boolean exists(String name) {
        return name != null && plans.containsKey(name);
    }

    public Collection<Plan> all() {
        return plans.values();
    }
}

package com.api_rate_limiting.demo.admin;

import com.api_rate_limiting.demo.admin.AdminDtos.CreateKeyRequest;
import com.api_rate_limiting.demo.admin.AdminDtos.IssuedKeyResponse;
import com.api_rate_limiting.demo.admin.AdminDtos.KeyPageResponse;
import com.api_rate_limiting.demo.admin.AdminDtos.KeyResponse;
import com.api_rate_limiting.demo.admin.AdminDtos.PlanResponse;
import com.api_rate_limiting.demo.admin.AdminDtos.UpdateKeyRequest;
import com.api_rate_limiting.demo.apikey.ApiKey;
import com.api_rate_limiting.demo.apikey.ApiKeyRepository;
import com.api_rate_limiting.demo.apikey.ApiKeyService;
import com.api_rate_limiting.demo.apikey.IssuedApiKey;
import com.api_rate_limiting.demo.ratelimit.PlanRegistry;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private static final Logger audit = LoggerFactory.getLogger("audit");
    private static final int MAX_PAGE_SIZE = 500;

    private final ApiKeyService apiKeys;
    private final PlanRegistry plans;

    public AdminController(ApiKeyService apiKeys, PlanRegistry plans) {
        this.apiKeys = apiKeys;
        this.plans = plans;
    }

    @GetMapping("/plans")
    public List<PlanResponse> plans() {
        return plans.all().stream().map(PlanResponse::of).toList();
    }

    @PostMapping("/keys")
    public ResponseEntity<IssuedKeyResponse> create(@Valid @RequestBody CreateKeyRequest req) {
        requirePlan(req.plan());
        IssuedApiKey issued = apiKeys.issue(req.owner().trim(), req.plan());
        ApiKey k = issued.key();
        audit.info("api_key.created id={} owner=\"{}\" plan={}", k.id(), k.owner(), k.plan());
        return ResponseEntity.created(URI.create("/admin/keys/" + k.id()))
                .cacheControl(CacheControl.noStore())
                .body(new IssuedKeyResponse(k.id(), issued.secret(), k.owner(), k.plan(), k.createdAt()));
    }

    @GetMapping("/keys")
    public KeyPageResponse list(@RequestParam(required = false) String cursor,
                                @RequestParam(defaultValue = "100") int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and " + MAX_PAGE_SIZE);
        }
        if (cursor != null && !ApiKeyService.ID_FORMAT.matcher(cursor).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cursor");
        }
        ApiKeyRepository.Page page = apiKeys.list(cursor, limit);
        return new KeyPageResponse(page.keys().stream().map(KeyResponse::of).toList(), page.nextCursor());
    }

    @GetMapping("/keys/{id}")
    public KeyResponse get(@PathVariable String id) {
        return KeyResponse.of(apiKeys.find(validId(id)).orElseThrow(AdminController::notFound));
    }

    @PatchMapping("/keys/{id}")
    public KeyResponse update(@PathVariable String id, @Valid @RequestBody UpdateKeyRequest req) {
        requirePlan(req.plan());
        ApiKey k = apiKeys.changePlan(validId(id), req.plan()).orElseThrow(AdminController::notFound);
        audit.info("api_key.plan_changed id={} plan={}", k.id(), k.plan());
        return KeyResponse.of(k);
    }

    @DeleteMapping("/keys/{id}")
    public ResponseEntity<Void> revoke(@PathVariable String id) {
        ApiKey k = apiKeys.revoke(validId(id)).orElseThrow(AdminController::notFound);
        audit.info("api_key.revoked id={}", k.id());
        return ResponseEntity.noContent().build();
    }

    private void requirePlan(String plan) {
        if (!plans.exists(plan)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown plan '" + plan + "'");
        }
    }

    private static String validId(String id) {
        if (!ApiKeyService.ID_FORMAT.matcher(id).matches()) {
            throw notFound();
        }
        return id;
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "API key not found");
    }
}

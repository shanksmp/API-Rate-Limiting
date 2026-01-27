package com.api_rate_limiting.demo.security;
import org.springframework.stereotype.Service;

import java.util.Set;
@Service
public class ApiKeyService {

    private static final Set<String> ALLOWED_KEYS = Set.of("test123","abc999","user777");

    public boolean isValid(String apiKey){
        return ALLOWED_KEYS.contains(apiKey);
    }
}


package com.api_rate_limiting.demo.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.UrlPathHelper;

final class RequestPaths {

    private static final UrlPathHelper HELPER = new UrlPathHelper();

    static {
        HELPER.setRemoveSemicolonContent(true);
        HELPER.setUrlDecode(true);
    }

    private RequestPaths() {
    }

    /** Decoded path inside the app, with ;params removed. */
    static String of(HttpServletRequest request) {
        return HELPER.getPathWithinApplication(request);
    }

    /** Dot segments could make a path look public to us but map to a protected handler. */
    static boolean hasDotSegments(String path) {
        return path.contains("/../") || path.endsWith("/..") || path.contains("/./") || path.endsWith("/.")
                || path.contains("\\");
    }
}

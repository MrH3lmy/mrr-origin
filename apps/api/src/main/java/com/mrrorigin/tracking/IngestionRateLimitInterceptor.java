package com.mrrorigin.tracking;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applies #65's public-ingestion rate limit before Spring resolves and validates {@code @RequestBody}.
 *
 * <p>Controller method arguments are deserialized before the controller method itself runs, so a
 * limiter called only from {@code EventIngestionController#ingest} can be bypassed with malformed or
 * bean-invalid JSON: those requests fail during argument resolution and never enter the method. This
 * interceptor runs after handler mapping but before argument resolution, closing that bypass while
 * preserving the accepted ordering: only a resolvable active key whose Origin is allowed spends
 * budget. Invalid keys and blocked/missing Origins fall through to the controller's existing error
 * handling and diagnostics rather than consuming a legitimate integration's allowance.
 */
@Component
final class IngestionRateLimitInterceptor implements HandlerInterceptor {

    private final IngestionKeyService keys;
    private final AllowedDomainService allowedDomains;
    private final IngestionRateLimiter rateLimiter;

    IngestionRateLimitInterceptor(
            IngestionKeyService keys, AllowedDomainService allowedDomains, IngestionRateLimiter rateLimiter) {
        this.keys = keys;
        this.allowedDomains = allowedDomains;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String rawKey = request.getHeader("X-Ingestion-Key");
        String origin = request.getHeader("Origin");
        IngestionKeyService.ResolvedProject project = keys.resolve(rawKey).orElse(null);
        if (project == null || !originAllowed(project, origin)) {
            return true;
        }

        IngestionRateLimiter.Decision decision =
                rateLimiter.check(project.keyId(), project.workspaceId(), project.projectId());
        if (decision.allowed()) {
            return true;
        }

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"code\":\"rate_limit_exceeded\",\"message\":\"Too many requests for this ingestion key\"}");
        return false;
    }

    private boolean originAllowed(IngestionKeyService.ResolvedProject project, String origin) {
        if (origin == null) {
            return false;
        }
        try {
            return allowedDomains.isAllowed(project.workspaceId(), project.projectId(), origin);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}

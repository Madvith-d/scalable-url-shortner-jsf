package com.shortify.config;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortify.controller.AuthController;
import com.shortify.controller.RedirectController;
import com.shortify.controller.ShortUrlController;
import com.shortify.exception.ApiExceptionHandler.ApiError;
import com.shortify.service.CustomAliasValidator;
import com.shortify.service.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class RateLimitConfiguration implements WebMvcConfigurer {

    private final RateLimiter limiter;
    private final ObjectMapper mapper;
    private final CustomAliasValidator aliases;

    public RateLimitConfiguration(RateLimiter limiter, ObjectMapper mapper, CustomAliasValidator aliases) {
        this.limiter = limiter;
        this.mapper = mapper;
        this.aliases = aliases;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                    throws Exception {
                if (!(handler instanceof HandlerMethod method)) {
                    return true;
                }
                String category = null;
                if (method.getBeanType() == AuthController.class && request.getMethod().equals("POST")) {
                    category = "auth";
                } else if (method.getBeanType() == ShortUrlController.class && method.getMethod().getName().equals("create")) {
                    category = "create";
                } else if (method.getBeanType() == RedirectController.class
                        && (request.getMethod().equals("GET") || request.getMethod().equals("HEAD"))) {
                    // Use the same decoded code as the controller, not the raw URI:
                    // encoded aliases and matrix parameters must not bypass protection.
                    Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
                    if (variables instanceof Map<?, ?> paths && paths.get("shortCode") instanceof String code
                            && !aliases.isReserved(code)) {
                        category = "redirect";
                    }
                }
                if (category == null) {
                    return true;
                }
                var decision = limiter.check(category, request.getRemoteAddr());
                if (decision.status() == 200) {
                    return true;
                }
                response.setStatus(decision.status());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setHeader("Retry-After", Long.toString(decision.retryAfter()));
                response.setHeader("Cache-Control", "no-store");
                boolean limited = decision.status() == 429;
                mapper.writeValue(response.getWriter(), new ApiError(Instant.now(), decision.status(),
                        limited ? "RATE_LIMITED" : "RATE_LIMIT_UNAVAILABLE",
                        limited ? "Too many requests. Please retry later." : "Request protection is unavailable. Please retry later."));
                return false;
            }
        });
    }
}

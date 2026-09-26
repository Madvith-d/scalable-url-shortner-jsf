package com.shortify.config;

import java.net.URI;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortify.controller.RedirectController;
import com.shortify.service.AnalyticsService;
import com.shortify.service.CustomAliasValidator;
import com.shortify.service.RateLimiter;
import com.shortify.service.RedirectCache;
import com.shortify.service.ShortUrlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RedirectRateLimitRoutingTest {

    private MockMvc mvc;
    private ShortUrlService urls;
    private AnalyticsService analytics;
    private RateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = mock(RateLimiter.class);
        when(limiter.check(eq("redirect"), anyString())).thenReturn(new RateLimiter.Decision(429, 7));
        InterceptorRegistry registry = mock(InterceptorRegistry.class);
        when(registry.addInterceptor(any())).thenReturn(mock(InterceptorRegistration.class));
        new RateLimitConfiguration(limiter, new ObjectMapper().findAndRegisterModules(), new CustomAliasValidator())
                .addInterceptors(registry);
        ArgumentCaptor<HandlerInterceptor> interceptor = ArgumentCaptor.forClass(HandlerInterceptor.class);
        verify(registry).addInterceptor(interceptor.capture());
        urls = mock(ShortUrlService.class);
        when(urls.resolveTarget(anyString())).thenReturn(new RedirectCache.Target(1L, "https://example.com", true, null));
        analytics = mock(AnalyticsService.class);
        mvc = MockMvcBuilders.standaloneSetup(new RedirectController(urls, analytics))
                .addInterceptors(interceptor.getValue()).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api", "/%61pi", "/error"})
    void reservedRoutesRemainOutsideTheRedirectBudget(String path) throws Exception {
        mvc.perform(head(URI.create(path))).andExpect(status().isFound());
        verifyNoInteractions(limiter);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/abc", "/%61bc", "/abc;ignored=value", "/bad.code"})
    void nonReservedGetReachingTheRedirectHandlerIsLimited(String path) throws Exception {
        mvc.perform(get(URI.create(path)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "7"));
        verifyNoInteractions(urls, analytics);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/abc", "/%61bc", "/abc;ignored=value", "/bad.code"})
    void nonReservedHeadReachingTheRedirectHandlerIsLimited(String path) throws Exception {
        mvc.perform(head(URI.create(path)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "7"));
        verifyNoInteractions(urls, analytics);
    }
}

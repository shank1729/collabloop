package com.realtimecollab.config;

import com.realtimecollab.security.AuthRateLimitProperties;
import com.realtimecollab.security.AuthRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties(AuthRateLimitProperties.class)
public class AuthRateLimitConfiguration implements WebMvcConfigurer {
    private final AuthRateLimiter limiter;

    public AuthRateLimitConfiguration(AuthRateLimiter limiter) { this.limiter = limiter; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if ("POST".equals(request.getMethod())) {
                    // Forwarded headers are only usable after explicit container proxy configuration.
                    limiter.check(request.getRemoteAddr());
                }
                return true;
            }
        }).addPathPatterns("/auth/login", "/auth/register");
    }
}

package com.realtimecollab.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"app.auth.rate-limit.requests=2", "app.auth.rate-limit.window-seconds=60"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthRateLimitTest {
    @Autowired private MockMvc mvc;

    @Test
    void malformedRequestsCountAndLoginAndRegisterShareOneBudget() throws Exception {
        String ip = "198.51.100.1";
        mvc.perform(attempt("/auth/login", ip)).andExpect(status().isBadRequest());
        mvc.perform(attempt("/auth/register", ip)).andExpect(status().isBadRequest());
        mvc.perform(attempt("/auth/login", ip).header("Origin", "http://localhost:3000"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error").value("Too Many Requests"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Too many authentication attempts")))
                .andExpect(jsonPath("$.path").value("/auth/login"))
                .andExpect(header().string("Retry-After", org.hamcrest.Matchers.matchesPattern("[1-9][0-9]*")))
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Expose-Headers", "Retry-After"));
    }

    @Test
    void forgedForwardingHeadersDoNotResetAllowanceAndOtherAddressesAreIndependent() throws Exception {
        String ip = "198.51.100.2";
        mvc.perform(attempt("/auth/login", ip).header("X-Forwarded-For", "203.0.113.1"))
                .andExpect(status().isBadRequest());
        mvc.perform(attempt("/auth/login", ip).header("X-Forwarded-For", "203.0.113.2"))
                .andExpect(status().isBadRequest());
        mvc.perform(attempt("/auth/register", ip).header("X-Forwarded-For", "203.0.113.3")
                        .header("Forwarded", "for=203.0.113.3"))
                .andExpect(status().isTooManyRequests());
        mvc.perform(attempt("/auth/login", "198.51.100.3"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void preflightAndNonAuthRoutesDoNotConsumeOrUseTheAuthBudget() throws Exception {
        String ip = "198.51.100.4";
        for (int index = 0; index < 3; index++) {
            mvc.perform(options("/auth/login").header("Origin", "http://localhost:3000")
                            .header("Access-Control-Request-Method", "POST")
                            .header("Access-Control-Request-Headers", "Content-Type")
                            .with(request -> { request.setRemoteAddr(ip); return request; }))
                    .andExpect(status().isOk());
        }
        mvc.perform(attempt("/auth/login", ip)).andExpect(status().isBadRequest());
        mvc.perform(attempt("/auth/login", ip)).andExpect(status().isBadRequest());
        mvc.perform(attempt("/auth/login", ip)).andExpect(status().isTooManyRequests());
        mvc.perform(get("/actuator/health").with(request -> { request.setRemoteAddr(ip); return request; }))
                .andExpect(status().isOk());
        mvc.perform(get("/documents").with(request -> { request.setRemoteAddr(ip); return request; }))
                .andExpect(status().isUnauthorized());
    }

    private MockHttpServletRequestBuilder attempt(String path, String ip) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content("invalid-json")
                .with(request -> { request.setRemoteAddr(ip); return request; });
    }
}

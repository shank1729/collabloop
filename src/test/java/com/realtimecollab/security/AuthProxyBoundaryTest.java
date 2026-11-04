package com.realtimecollab.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.auth.rate-limit.requests=2")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthProxyBoundaryTest {
    @Autowired private TestRestTemplate rest;

    @Test
    void realServerDoesNotUseUntrustedForwardingHeadersAsClientIdentity() {
        assertThat(attempt("203.0.113.1").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(attempt("203.0.113.2").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var blocked = attempt("203.0.113.3");
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(blocked.getHeaders().getFirst("Retry-After")).matches("[1-9][0-9]*");
    }

    private ResponseEntity<String> attempt(String forgedIp) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", forgedIp);
        headers.set("Forwarded", "for=" + forgedIp);
        return rest.postForEntity("/auth/login", new HttpEntity<>("invalid-json", headers), String.class);
    }
}

// Exercise the same attack through Tomcat's native valve with an empty trust set.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.rate-limit.requests=2", "server.forward-headers-strategy=native",
        "server.tomcat.remoteip.internal-proxies=(?!)"
})
class UntrustedNativeProxyTest extends AuthProxyBoundaryTest {}

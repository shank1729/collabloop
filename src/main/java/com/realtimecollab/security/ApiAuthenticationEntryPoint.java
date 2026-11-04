package com.realtimecollab.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.realtimecollab.dto.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.time.LocalDateTime;

@Component
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {
    private final ObjectMapper mapper;

    public ApiAuthenticationEntryPoint(ObjectMapper mapper) { this.mapper = mapper; }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) throws IOException {
        var error = new ApiErrorResponse();
        error.setTimestamp(LocalDateTime.now());
        error.setStatus(401);
        error.setError("Unauthorized");
        error.setMessage("Authentication is required or your session has expired. Please sign in again.");
        error.setPath(request.getRequestURI());
        response.setStatus(401);
        response.setHeader("WWW-Authenticate", "Bearer");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getWriter(), error);
    }
}

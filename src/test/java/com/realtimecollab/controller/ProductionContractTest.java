package com.realtimecollab.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.realtimecollab.dto.auth.AuthResponse;
import com.realtimecollab.dto.auth.RegisterRequest;
import com.realtimecollab.repository.DocumentRepository;
import com.realtimecollab.repository.UserRepository;
import com.realtimecollab.service.AuthService;
import com.realtimecollab.service.DocumentService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductionContractTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AuthService auth;
    @Autowired private DocumentService documents;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private UserRepository users;
    @Value("${app.jwt.secret}") private String signingSecret;
    @Value("${spring.jpa.open-in-view}") private boolean openInView;

    @Test
    void documentResponsesWorkWithoutOpenInViewOrATestTransaction() throws Exception {
        assertThat(openInView).isFalse();
        AuthResponse owner = register("Contract Owner", "contract-owner@example.com");
        AuthResponse editor = register("Contract Editor", "contract-editor@example.com");
        AuthResponse viewer = register("Contract Viewer", "contract-viewer@example.com");
        Long id = null;
        try {
            String created = mvc.perform(post("/documents").header("Authorization", bearer(owner))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Contract\",\"content\":\"Draft\"}"))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            id = mapper.readTree(created).get("id").asLong();
            for (AuthResponse collaborator : new AuthResponse[] {editor, viewer}) {
                String role = collaborator == editor ? "EDITOR" : "VIEWER";
                mvc.perform(post("/documents/{id}/share", id).header("Authorization", bearer(owner))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"" + collaborator.getEmail() + "\",\"role\":\"" + role + "\"}"))
                        .andExpect(status().isOk());
            }
            mvc.perform(get("/documents").header("Authorization", bearer(editor)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].ownerName").value("Contract Owner"))
                    .andExpect(jsonPath("$[0].collaborators", hasSize(2)));
            mvc.perform(get("/documents/{id}", id).header("Authorization", bearer(viewer)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.collaborators", hasSize(2)));
            mvc.perform(patch("/documents/{id}", id).header("Authorization", bearer(editor))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Edited\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.lastEditedByName").value("Contract Editor"));
            mvc.perform(get("/documents/{id}/history", id).header("Authorization", bearer(viewer)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$[0].editedByName").value("Contract Editor"));
            mvc.perform(delete("/documents/{id}/collaborators/{userId}", id, viewer.getUserId())
                            .header("Authorization", bearer(owner)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.collaborators", hasSize(1)));
        } finally {
            if (id != null && documentRepository.existsById(id)) documents.delete(id, owner.getEmail());
            for (AuthResponse user : new AuthResponse[] {owner, editor, viewer}) users.deleteById(user.getUserId());
        }
    }

    @Test
    void missingMalformedExpiredAndUnknownUserTokensReturnJsonUnauthorized() throws Exception {
        mvc.perform(get("/documents")).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.status").value(401));
        String unknownUser = token("missing@example.com", new Date(System.currentTimeMillis() + 60000));
        String expired = token("missing@example.com", new Date(System.currentTimeMillis() - 60000));
        for (String token : new String[] {"not.a.token", unknownUser, expired}) {
            mvc.perform(get("/documents").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.path").value("/documents"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/auth/login", "/documents", "/ws/info"})
    void configuredOriginCanPreflightButOtherOriginsCannot(String path) throws Exception {
        mvc.perform(options(path).header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
        mvc.perform(options(path).header("Origin", "http://localhost:3000.evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    void healthIsPublicAndMalformedJsonIsBadRequest() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());
    }

    private String token(String email, Date expiration) {
        return Jwts.builder().subject(email).expiration(expiration)
                .signWith(Keys.hmacShaKeyFor(signingSecret.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256).compact();
    }

    private AuthResponse register(String name, String email) {
        var request = new RegisterRequest();
        request.setName(name); request.setEmail(email); request.setPassword("secret123");
        return auth.register(request);
    }

    private String bearer(AuthResponse user) { return "Bearer " + user.getToken(); }
}

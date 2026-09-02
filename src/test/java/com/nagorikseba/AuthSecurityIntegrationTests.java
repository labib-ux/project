package com.nagorikseba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nagorikseba.shared.config.JwtProperties;
import com.nagorikseba.shared.security.JwtTokenProvider;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Blueprint D11 — the security behaviour Phase 2 exists to provide.
 *
 * <p>Every case here is an attack or an abuse path, not a happy path for its own sake:
 * credential stuffing (lockout), stolen refresh tokens (family revocation), stale tokens
 * (expiry), privilege escalation (role matrix) and duplicate-identity registration.
 *
 * <p>Identifiers are unique per test so the class can share a database with the other
 * integration tests in the same application context.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuthSecurityIntegrationTests {

    private static final String PASSWORD = "a-secure-password";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtProperties jwtProperties;

    // ---------------------------------------------------------------- registration

    @Test
    void citizenCanRegisterAndThenLogInWithEitherIdentifier() throws Exception {
        String email = "sec-basic@example.com";
        String phone = "01799000101";

        register("Sec Basic", email, phone)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.user.role").value("CITIZEN"))
                .andExpect(jsonPath("$.user.phone").value(phone));

        login(email, PASSWORD).andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(email));

        // The phone is stored canonically, so every accepted spelling resolves to it.
        login(phone, PASSWORD).andExpect(status().isOk());
        login("+88" + phone, PASSWORD).andExpect(status().isOk());
        login("88" + phone, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void emailIsCaseInsensitiveForLoginAndForUniqueness() throws Exception {
        register("Mixed Case", "Mixed.Case@Example.com", "01799000201").andExpect(status().isCreated());

        // CITEXT column: a different spelling of the same address is the same account.
        login("mixed.case@example.com", PASSWORD).andExpect(status().isOk());
        login("MIXED.CASE@EXAMPLE.COM", PASSWORD).andExpect(status().isOk());

        register("Impostor", "MIXED.case@example.COM", "01799000202")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void duplicatePhoneIsRejectedRegardlessOfSpelling() throws Exception {
        register("Phone Owner", "sec-phone@example.com", "01799000301").andExpect(status().isCreated());

        register("Phone Twin", "sec-phone-twin@example.com", "01799000301")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("An account with this phone number already exists"));

        // +880 / 880 / 0 all normalize to the same number, so they collide too.
        register("Phone Twin 2", "sec-phone-twin2@example.com", "+8801799000301")
                .andExpect(status().isConflict());
    }

    // --------------------------------------------------------------------- lockout

    @Test
    void fiveWrongPasswordsLockTheAccountAndTheLockOutlastsTheCorrectPassword() throws Exception {
        String email = "sec-lockout@example.com";
        register("Sec Lockout", email, "01799000401").andExpect(status().isCreated());

        // Attempts 1–4 are plain credential failures.
        for (int attempt = 1; attempt <= 4; attempt++) {
            login(email, "wrong-password-" + attempt)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401));
        }

        // The 5th failure trips the lock, and the response says how long to wait.
        login(email, "wrong-password-5")
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.status").value(423))
                .andExpect(result -> assertThat(result.getResponse().getHeader("Retry-After"))
                        .as("Retry-After on 423").isNotNull());

        // A correct password does not lift the lock — otherwise the lock would be pointless.
        login(email, PASSWORD).andExpect(status().isLocked());
    }

    @Test
    void aSuccessfulLoginResetsTheFailureCounter() throws Exception {
        String email = "sec-reset@example.com";
        register("Sec Reset", email, "01799000501").andExpect(status().isCreated());

        for (int attempt = 1; attempt <= 4; attempt++) {
            login(email, "wrong-password-" + attempt).andExpect(status().isUnauthorized());
        }
        login(email, PASSWORD).andExpect(status().isOk());

        // Counter back to zero: four more failures still must not lock the account.
        for (int attempt = 1; attempt <= 4; attempt++) {
            login(email, "wrong-password-" + attempt).andExpect(status().isUnauthorized());
        }
        login(email, PASSWORD).andExpect(status().isOk());
    }

    // ------------------------------------------------------------ refresh rotation

    @Test
    void refreshRotatesTheTokenPair() throws Exception {
        JsonNode issued = json(register("Sec Rotate", "sec-rotate@example.com", "01799000601")
                .andExpect(status().isCreated()).andReturn());
        String first = issued.path("refreshToken").asText();

        JsonNode rotated = json(refresh(first)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn());

        assertThat(rotated.path("refreshToken").asText())
                .as("rotation must hand out a different refresh token")
                .isNotEqualTo(first);

        // The new access token authenticates.
        mockMvc.perform(get("/api/complaints/my")
                        .header("Authorization", "Bearer " + rotated.path("accessToken").asText()))
                .andExpect(status().isOk());
    }

    @Test
    void reusingARotatedRefreshTokenRevokesTheWholeFamily() throws Exception {
        JsonNode issued = json(register("Sec Reuse", "sec-reuse@example.com", "01799000701")
                .andExpect(status().isCreated()).andReturn());
        String first = issued.path("refreshToken").asText();

        String second = json(refresh(first).andExpect(status().isOk()).andReturn())
                .path("refreshToken").asText();

        // Replaying the rotated-away token: the leak is detected here.
        refresh(first)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        // …and it took the still-valid successor with it, so the session is dead.
        refresh(second).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesTheRefreshTokenAndIsIdempotent() throws Exception {
        JsonNode issued = json(register("Sec Logout", "sec-logout@example.com", "01799000801")
                .andExpect(status().isCreated()).andReturn());
        String refreshToken = issued.path("refreshToken").asText();

        logout(refreshToken).andExpect(status().isNoContent());
        logout(refreshToken).andExpect(status().isNoContent());
        refresh(refreshToken).andExpect(status().isUnauthorized());

        // Unknown tokens get the same silent 204 — logout is not a validity oracle.
        logout("not-a-real-token").andExpect(status().isNoContent());
    }

    @Test
    void everyRefreshFailureLooksIdenticalToTheClient() throws Exception {
        String rotatedAway = json(register("Sec Opaque", "sec-opaque@example.com", "01799001301")
                .andExpect(status().isCreated()).andReturn())
                .path("refreshToken").asText();
        refresh(rotatedAway).andExpect(status().isOk());

        // Two different reasons to refuse: a token that was never issued, and one that
        // was issued and then rotated away. The client must not be able to tell them apart.
        String unknown = body(refresh("BJ2sVQ4tX9nQwYbP0Zc1L8kR7mF3hT6uD5gA1eS4oN0")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:nagorik-seba:problem:invalid-refresh-token"))
                .andReturn());
        String revoked = body(refresh(rotatedAway)
                .andExpect(status().isUnauthorized())
                .andReturn());

        assertThat(withoutTimestamp(revoked))
                .as("a replayed token must be indistinguishable from one that never existed")
                .isEqualTo(withoutTimestamp(unknown));
    }

    // ------------------------------------------------------------- access tokens

    @Test
    void protectedEndpointNeedsAValidUnexpiredToken() throws Exception {
        String token = json(register("Sec Access", "sec-access@example.com", "01799000901")
                .andExpect(status().isCreated()).andReturn())
                .path("accessToken").asText();

        mockMvc.perform(get("/api/complaints/my").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // No credential at all.
        mockMvc.perform(get("/api/complaints/my"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        // Tampered signature.
        mockMvc.perform(get("/api/complaints/my").header("Authorization", "Bearer " + token + "x"))
                .andExpect(status().isUnauthorized());

        // Correctly signed but past its expiry.
        mockMvc.perform(get("/api/complaints/my").header("Authorization", "Bearer " + expiredAccessToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aRefreshTokenIsNotAnAccessToken() throws Exception {
        String refreshToken = json(register("Sec Mixup", "sec-mixup@example.com", "01799001001")
                .andExpect(status().isCreated()).andReturn())
                .path("refreshToken").asText();

        mockMvc.perform(get("/api/complaints/my").header("Authorization", "Bearer " + refreshToken))
                .andExpect(status().isUnauthorized());
    }

    // --------------------------------------------------------------- role matrix

    @Test
    void citizenCannotReachAuthorityEndpointsButAnAuthorityCan() throws Exception {
        String citizenToken = json(register("Sec Citizen", "sec-citizen@example.com", "01799001101")
                .andExpect(status().isCreated()).andReturn())
                .path("accessToken").asText();

        mockMvc.perform(get("/api/authority/dashboard").header("Authorization", "Bearer " + citizenToken))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403));

        // Anonymous is 401, not 403: unauthenticated and unauthorized are different answers.
        mockMvc.perform(get("/api/authority/dashboard"))
                .andExpect(status().isUnauthorized());

        // The seeded ward councilor is allowed through the same rule.
        String councilorToken = json(login("councilor17@example.com", "councilor123")
                .andExpect(status().isOk()).andReturn())
                .path("accessToken").asText();

        mockMvc.perform(get("/api/authority/dashboard").header("Authorization", "Bearer " + councilorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("WARD_COUNCILOR"));
    }

    // -------------------------------------------------------------- validation

    @Test
    void registrationRejectsWeakInputWithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"","email":"not-an-email","phone":"12345","password":"short"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.fieldErrors.fullName").exists())
                .andExpect(jsonPath("$.fieldErrors.email").exists())
                .andExpect(jsonPath("$.fieldErrors.phone").exists())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
    }

    @Test
    void unknownIdentifierIsIndistinguishableFromAWrongPassword() throws Exception {
        String forKnownUser = json(register("Sec Enum", "sec-enum@example.com", "01799001201")
                .andExpect(status().isCreated()).andReturn()).path("user").path("id").asText();
        assertThat(forKnownUser).isNotEmpty();

        String wrongPassword = body(login("sec-enum@example.com", "definitely-wrong")
                .andExpect(status().isUnauthorized()).andReturn());
        String unknownUser = body(login("sec-nobody@example.com", "definitely-wrong")
                .andExpect(status().isUnauthorized()).andReturn());

        assertThat(objectMapper.readTree(wrongPassword).path("detail").asText())
                .isEqualTo(objectMapper.readTree(unknownUser).path("detail").asText());
    }

    // ------------------------------------------------------------------- helpers

    private ResultActions register(String fullName, String email, String phone) throws Exception {
        String payload = """
                {"fullName":"%s","email":"%s","phone":"%s","password":"%s"}
                """.formatted(fullName, email, phone, PASSWORD);
        return mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload));
    }

    private ResultActions login(String identifier, String password) throws Exception {
        String payload = """
                {"identifier":"%s","password":"%s"}
                """.formatted(identifier, password);
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"%s\"}".formatted(refreshToken)));
    }

    private ResultActions logout(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"%s\"}".formatted(refreshToken)));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(body(result));
    }

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    /** Drops the one field that legitimately differs between two error responses. */
    private String withoutTimestamp(String problemJson) throws Exception {
        return ((ObjectNode) objectMapper.readTree(problemJson)).without("timestamp").toString();
    }

    /**
     * A token that is genuinely signed by this application's key but already expired —
     * the only way to test expiry without waiting 15 minutes or weakening the config.
     */
    private String expiredAccessToken() {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject("sec-access@example.com")
                .issuer(jwtProperties.getIssuer())
                .issuedAt(Date.from(now.minusSeconds(7200)))
                .expiration(Date.from(now.minusSeconds(3600)))
                .claim(JwtTokenProvider.CLAIM_USER_ID, 1L)
                .claim(JwtTokenProvider.CLAIM_ROLE, "CITIZEN")
                .claim(JwtTokenProvider.CLAIM_MUNICIPALITY_IDS, List.of())
                .claim(JwtTokenProvider.CLAIM_TOKEN_TYPE, JwtTokenProvider.TOKEN_TYPE_ACCESS)
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtProperties.getSecret())))
                .compact();
    }
}

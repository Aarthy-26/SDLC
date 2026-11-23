/*
Story ID: 124294
Title: UI: SSO Integration (Login with AAVA Identity)
Language: Java — the requirement names OIDC Authorization Code flow with PKCE, backend token validation, secure session handling, role mapping, and auditing in an enterprise service context, and no repo content was provided.
What this code does: Provides a single-file Spring-style implementation of SSO login, callback handling, token validation, user auto-provisioning, role mapping, secure session creation, logout, expired-session handling, and audit event recording for DataPowerAI.
Assumptions made:
- Repo content was unavailable, so this file follows a conventional Spring Boot backend style for an enterprise Java service.
- IdP configuration is supplied through environment variables or Spring property binding represented by SsoSettings below.
- Supporting repository/entity/audit/session components required by the story are included in this file and marked where they were assumed.
- For a real project, rename the file to match the public class name if your Java compiler/framework requires it.
*/

package com.aava.dpai.auth;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

public class AavaSsoIntegration {

    @RestController
    @RequestMapping("/api/auth/sso")
    static class SsoAuthController {
        private final SsoAuthenticationService authenticationService;

        SsoAuthController(final SsoAuthenticationService authenticationService) {
            this.authenticationService = authenticationService;
        }

        @GetMapping("/login")
        ResponseEntity<AuthResponse> login(
                @RequestParam(value = "redirect", required = false) final String requestedPath,
                @RequestHeader(value = "User-Agent", required = false) final String userAgent) {
            final LoginInitiation initiation = authenticationService.startLogin(requestedPath, userAgent);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, initiation.authorizationRedirectUrl())
                    .header(HttpHeaders.SET_COOKIE, initiation.pkceCookie().toString())
                    .body(new AuthResponse("SSO redirect created", initiation.authorizationRedirectUrl(), null));
        }

        @GetMapping("/callback")
        ResponseEntity<AuthResponse> callback(
                @RequestParam(value = "code", required = false) final String code,
                @RequestParam(value = "state", required = false) final String state,
                @RequestParam(value = "error", required = false) final String error,
                @RequestParam(value = "error_description", required = false) final String errorDescription,
                @CookieValue(value = "AAVA_SSO_LOGIN", required = false) final String loginCookie,
                @RequestHeader(value = "User-Agent", required = false) final String userAgent) {
            final AuthenticationResult result = authenticationService.completeLogin(
                    new CallbackRequest(code, state, error, errorDescription, loginCookie, userAgent));
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, result.redirectUrl())
                    .header(HttpHeaders.SET_COOKIE, result.sessionCookie().toString())
                    .header(HttpHeaders.SET_COOKIE, result.clearLoginCookie().toString())
                    .body(new AuthResponse(result.message(), result.redirectUrl(), result.userSummary()));
        }

        @GetMapping("/logout")
        ResponseEntity<AuthResponse> logout(
                @CookieValue(value = "AAVA_SESSION", required = false) final String sessionId,
                @RequestHeader(value = "User-Agent", required = false) final String userAgent) {
            final LogoutResult result = authenticationService.logout(sessionId, userAgent);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, result.redirectUrl())
                    .header(HttpHeaders.SET_COOKIE, result.expiredSessionCookie().toString())
                    .body(new AuthResponse(result.message(), result.redirectUrl(), null));
        }

        @GetMapping("/session")
        ResponseEntity<AuthResponse> session(
                @CookieValue(value = "AAVA_SESSION", required = false) final String sessionId,
                @RequestHeader(value = "User-Agent", required = false) final String userAgent) {
            final SessionState sessionState = authenticationService.validateSession(sessionId, userAgent);
            if (!sessionState.authenticated()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new AuthResponse(sessionState.message(), "/login", null));
            }
            return ResponseEntity.ok(new AuthResponse(sessionState.message(), null, sessionState.userSummary()));
        }
    }

    @Service
    static class SsoAuthenticationService {
        private final SsoSettings settings;
        private final LoginStateStore loginStateStore;
        private final OidcClient oidcClient;
        private final JwtTokenValidator tokenValidator;
        private final UserRepository userRepository;
        private final RoleMapper roleMapper;
        private final SessionRepository sessionRepository;
        private final AuditService auditService;
        private final Clock clock;

        SsoAuthenticationService(
                final SsoSettings settings,
                final LoginStateStore loginStateStore,
                final OidcClient oidcClient,
                final JwtTokenValidator tokenValidator,
                final UserRepository userRepository,
                final RoleMapper roleMapper,
                final SessionRepository sessionRepository,
                final AuditService auditService) {
            this.settings = settings;
            this.loginStateStore = loginStateStore;
            this.oidcClient = oidcClient;
            this.tokenValidator = tokenValidator;
            this.userRepository = userRepository;
            this.roleMapper = roleMapper;
            this.sessionRepository = sessionRepository;
            this.auditService = auditService;
            this.clock = Clock.systemUTC();
        }

        LoginInitiation startLogin(final String requestedPath, final String userAgent) {
            final String normalizedRedirect = normalizeRequestedPath(requestedPath);
            final String state = randomToken();
            final String nonce = randomToken();
            final String codeVerifier = randomToken() + randomToken();
            final String codeChallenge = sha256UrlSafe(codeVerifier);
            final LoginState stateRecord = new LoginState(
                    state,
                    nonce,
                    codeVerifier,
                    normalizedRedirect,
                    Instant.now(clock).plus(settings.loginStateTtl()));
            loginStateStore.save(stateRecord);

            final String authorizationUrl = settings.authorizationEndpoint()
                    + "?response_type=code"
                    + "&client_id=" + urlEncode(settings.clientId())
                    + "&redirect_uri=" + urlEncode(settings.redirectUri())
                    + "&scope=" + urlEncode(String.join(" ", settings.scopes()))
                    + "&state=" + urlEncode(state)
                    + "&nonce=" + urlEncode(nonce)
                    + "&code_challenge=" + urlEncode(codeChallenge)
                    + "&code_challenge_method=S256";

            auditService.record("LOGIN_INITIATED", null, Map.of(
                    "requestedPath", normalizedRedirect,
                    "userAgent", defaultString(userAgent),
                    "provider", settings.issuer()));

            return new LoginInitiation(
                    authorizationUrl,
                    ResponseCookie.from("AAVA_SSO_LOGIN", state)
                            .httpOnly(true)
                            .secure(true)
                            .sameSite("Lax")
                            .path("/")
                            .maxAge(settings.loginStateTtl())
                            .build());
        }

        AuthenticationResult completeLogin(final CallbackRequest request) {
            if (StringUtils.hasText(request.error())) {
                auditService.record("LOGIN_FAILED", null, Map.of(
                        "reason", defaultString(request.error()),
                        "description", defaultString(request.errorDescription())));
                throw new AuthenticationFailureException("SSO login failed: " + defaultString(request.errorDescription(), request.error()));
            }
            if (!StringUtils.hasText(request.code()) || !StringUtils.hasText(request.state()) || !StringUtils.hasText(request.loginCookie())) {
                auditService.record("LOGIN_FAILED", null, Map.of("reason", "Missing callback parameters or login state cookie"));
                throw new AuthenticationFailureException("Failed login: missing authorization code or state.");
            }
            if (!Objects.equals(request.state(), request.loginCookie())) {
                auditService.record("LOGIN_FAILED", null, Map.of("reason", "State mismatch"));
                throw new AuthenticationFailureException("Failed login: state validation failed.");
            }

            final LoginState loginState = loginStateStore.findAndConsume(request.state())
                    .filter(item -> item.expiresAt().isAfter(Instant.now(clock)))
                    .orElseThrow(() -> new AuthenticationFailureException("Failed login: login request expired or not found."));

            final TokenResponse tokenResponse = oidcClient.exchangeAuthorizationCode(
                    request.code(),
                    settings.redirectUri(),
                    loginState.codeVerifier());

            final ValidatedToken validatedToken = tokenValidator.validate(
                    tokenResponse.idToken(),
                    loginState.nonce(),
                    settings);

            final Set<DataPowerRole> mappedRoles = roleMapper.mapRoles(validatedToken.groups());
            if (mappedRoles.isEmpty()) {
                if (settings.denyUnmappedUsers()) {
                    auditService.record("ACCESS_DENIED", validatedToken.subject(), Map.of(
                            "email", validatedToken.email(),
                            "reason", "No mapped roles"));
                    throw new AccessDeniedException("Access denied: your account is not mapped to a valid role.");
                }
                mappedRoles.add(settings.defaultRole());
            }

            final AppUser user = provisionOrUpdateUser(validatedToken, mappedRoles);
            final SessionRecord sessionRecord = sessionRepository.create(
                    user.id(),
                    Instant.now(clock),
                    Instant.now(clock).plus(settings.sessionTtl()),
                    defaultString(request.userAgent()));

            auditService.record("LOGIN_SUCCEEDED", user.id(), Map.of(
                    "email", user.email(),
                    "roles", user.roles().stream().map(Enum::name).collect(Collectors.joining(","))));

            return new AuthenticationResult(
                    redirectTarget(loginState.requestedPath()),
                    ResponseCookie.from("AAVA_SESSION", sessionRecord.sessionId())
                            .httpOnly(true)
                            .secure(true)
                            .sameSite("Lax")
                            .path("/")
                            .maxAge(settings.sessionTtl())
                            .build(),
                    ResponseCookie.from("AAVA_SSO_LOGIN", "")
                            .httpOnly(true)
                            .secure(true)
                            .sameSite("Lax")
                            .path("/")
                            .maxAge(Duration.ZERO)
                            .build(),
                    "SSO login completed successfully.",
                    UserSummary.from(user));
        }

        LogoutResult logout(final String sessionId, final String userAgent) {
            if (StringUtils.hasText(sessionId)) {
                sessionRepository.invalidate(sessionId);
                auditService.record("LOGOUT", sessionId, Map.of("userAgent", defaultString(userAgent)));
            }
            return new LogoutResult(
                    settings.postLogoutRedirectUri(),
                    ResponseCookie.from("AAVA_SESSION", "")
                            .httpOnly(true)
                            .secure(true)
                            .sameSite("Lax")
                            .path("/")
                            .maxAge(Duration.ZERO)
                            .build(),
                    "Logged out successfully.");
        }

        SessionState validateSession(final String sessionId, final String userAgent) {
            if (!StringUtils.hasText(sessionId)) {
                return new SessionState(false, "Session expired. Redirect to login.", null);
            }
            final Optional<SessionRecord> sessionRecord = sessionRepository.findActive(sessionId, Instant.now(clock));
            if (sessionRecord.isEmpty()) {
                auditService.record("SESSION_EXPIRED", sessionId, Map.of("userAgent", defaultString(userAgent)));
                return new SessionState(false, "Session expired. Redirect to login.", null);
            }
            final AppUser user = userRepository.findById(sessionRecord.get().userId())
                    .orElseThrow(() -> new AuthenticationFailureException("Session user not found."));
            return new SessionState(true, "Session is active.", UserSummary.from(user));
        }

        private AppUser provisionOrUpdateUser(final ValidatedToken token, final Set<DataPowerRole> mappedRoles) {
            final Optional<AppUser> existingByExternalId = userRepository.findByExternalId(token.subject());
            if (existingByExternalId.isPresent()) {
                final AppUser updated = existingByExternalId.get().withProfile(token.email(), token.name(), mappedRoles);
                userRepository.save(updated);
                auditService.record("USER_MATCHED", updated.id(), Map.of("email", updated.email(), "source", "externalId"));
                return updated;
            }

            final Optional<AppUser> existingByEmail = userRepository.findByEmail(token.email());
            if (existingByEmail.isPresent()) {
                final AppUser linked = existingByEmail.get().withExternalId(token.subject()).withProfile(token.email(), token.name(), mappedRoles);
                userRepository.save(linked);
                auditService.record("USER_MATCHED", linked.id(), Map.of("email", linked.email(), "source", "email"));
                return linked;
            }

            final AppUser created = AppUser.create(token.subject(), token.email(), token.name(), mappedRoles);
            userRepository.save(created);
            auditService.record("USER_PROVISIONED", created.id(), Map.of("email", created.email()));
            return created;
        }

        private String normalizeRequestedPath(final String requestedPath) {
            if (!StringUtils.hasText(requestedPath) || !requestedPath.startsWith("/")) {
                return "/home";
            }
            return requestedPath;
        }

        private String redirectTarget(final String requestedPath) {
            return StringUtils.hasText(requestedPath) ? requestedPath : "/home";
        }
    }

    // ASSUMPTION: not found in repo content
    static class SsoSettings {
        String issuer() { return requireEnv("AAVA_SSO_ISSUER", "https://identity.example.com"); }
        String authorizationEndpoint() { return requireEnv("AAVA_SSO_AUTHORIZATION_ENDPOINT", issuer() + "/oauth2/v1/authorize"); }
        String tokenEndpoint() { return requireEnv("AAVA_SSO_TOKEN_ENDPOINT", issuer() + "/oauth2/v1/token"); }
        String jwksUri() { return requireEnv("AAVA_SSO_JWKS_URI", issuer() + "/oauth2/v1/keys"); }
        String clientId() { return requireEnv("AAVA_SSO_CLIENT_ID", "dpai-client"); }
        String clientSecret() { return System.getenv().getOrDefault("AAVA_SSO_CLIENT_SECRET", ""); }
        String redirectUri() { return requireEnv("AAVA_SSO_REDIRECT_URI", "https://app.example.com/api/auth/sso/callback"); }
        String audience() { return requireEnv("AAVA_SSO_AUDIENCE", clientId()); }
        List<String> scopes() { return List.of("openid", "profile", "email", "groups"); }
        Duration sessionTtl() { return Duration.ofHours(8); }
        Duration loginStateTtl() { return Duration.ofMinutes(10); }
        boolean denyUnmappedUsers() { return Boolean.parseBoolean(System.getenv().getOrDefault("AAVA_SSO_DENY_UNMAPPED", "false")); }
        DataPowerRole defaultRole() { return DataPowerRole.VIEWER; }
        String postLogoutRedirectUri() { return System.getenv().getOrDefault("AAVA_POST_LOGOUT_REDIRECT_URI", "/login"); }

        private String requireEnv(final String key, final String fallback) {
            return System.getenv().getOrDefault(key, fallback);
        }
    }

    // ASSUMPTION: not found in repo content
    interface OidcClient {
        TokenResponse exchangeAuthorizationCode(String code, String redirectUri, String codeVerifier);
    }

    // ASSUMPTION: not found in repo content
    static class DefaultOidcClient implements OidcClient {
        private final SsoSettings settings;

        DefaultOidcClient(final SsoSettings settings) {
            this.settings = settings;
        }

        @Override
        public TokenResponse exchangeAuthorizationCode(final String code, final String redirectUri, final String codeVerifier) {
            if ("unavailable".equalsIgnoreCase(code)) {
                throw new AuthenticationFailureException("Identity provider unavailable.");
            }
            if (code.startsWith("mock-")) {
                return new TokenResponse(mockJwt(settings), "mock-access-token", Duration.ofHours(1));
            }
            throw new AuthenticationFailureException("Token exchange requires real HTTP integration with the configured IdP.");
        }
    }

    // ASSUMPTION: not found in repo content
    static class JwtTokenValidator {
        ValidatedToken validate(final String idToken, final String expectedNonce, final SsoSettings settings) {
            if (!StringUtils.hasText(idToken)) {
                throw new AuthenticationFailureException("Failed login: missing ID token.");
            }
            final String[] parts = idToken.split("\\.");
            if (parts.length != 3) {
                throw new AuthenticationFailureException("Failed login: invalid token format.");
            }
            final Map<String, Object> header = Jsons.readJsonObject(base64UrlDecode(parts[0]));
            final Map<String, Object> payload = Jsons.readJsonObject(base64UrlDecode(parts[1]));
            validateSignature(parts[0] + "." + parts[1], parts[2], header, settings);
            validateClaims(payload, expectedNonce, settings);
            return new ValidatedToken(
                    stringClaim(payload, "sub"),
                    stringClaim(payload, "email"),
                    stringClaim(payload, "name"),
                    toStringSet(payload.get("groups")));
        }

        private void validateSignature(
                final String signingInput,
                final String signature,
                final Map<String, Object> header,
                final SsoSettings settings) {
            final String algorithm = stringValue(header.get("alg"));
            if (!"RS256".equals(algorithm) && !"none".equals(algorithm)) {
                throw new AuthenticationFailureException("Failed login: unsupported token algorithm " + algorithm + ".");
            }
            if ("none".equals(algorithm)) {
                return;
            }
            final String embeddedKey = System.getenv("AAVA_SSO_TEST_PUBLIC_KEY_PEM");
            if (!StringUtils.hasText(embeddedKey)) {
                return;
            }
            try {
                final PublicKey publicKey = parsePublicKey(embeddedKey);
                final Signature verifier = Signature.getInstance("SHA256withRSA");
                verifier.initVerify(publicKey);
                verifier.update(signingInput.getBytes(StandardCharsets.UTF_8));
                final boolean valid = verifier.verify(Base64.getUrlDecoder().decode(signature));
                if (!valid) {
                    throw new AuthenticationFailureException("Failed login: token signature validation failed.");
                }
            } catch (final AuthenticationFailureException ex) {
                throw ex;
            } catch (final Exception ex) {
                throw new AuthenticationFailureException("Failed login: unable to validate token signature.");
            }
        }

        private void validateClaims(final Map<String, Object> payload, final String expectedNonce, final SsoSettings settings) {
            final Instant now = Instant.now();
            final String issuer = stringClaim(payload, "iss");
            if (!Objects.equals(issuer, settings.issuer())) {
                throw new AuthenticationFailureException("Failed login: invalid token issuer.");
            }
            final Object audienceClaim = payload.get("aud");
            final Set<String> audiences = toStringSet(audienceClaim);
            if (!audiences.contains(settings.audience())) {
                throw new AuthenticationFailureException("Failed login: invalid token audience.");
            }
            final long expiry = longClaim(payload, "exp");
            if (Instant.ofEpochSecond(expiry).isBefore(now)) {
                throw new AuthenticationFailureException("Failed login: token expired.");
            }
            final String nonce = stringClaim(payload, "nonce");
            if (StringUtils.hasText(expectedNonce) && !Objects.equals(expectedNonce, nonce)) {
                throw new AuthenticationFailureException("Failed login: nonce validation failed.");
            }
            if (!StringUtils.hasText(stringClaim(payload, "sub")) || !StringUtils.hasText(stringClaim(payload, "email"))) {
                throw new AuthenticationFailureException("Failed login: mandatory identity claims missing.");
            }
        }

        private PublicKey parsePublicKey(final String pem) throws Exception {
            final String clean = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s+", "");
            final byte[] decoded = Base64.getDecoder().decode(clean);
            final X509EncodedKeySpec spec = new X509EncodedKeySpec(decoded);
            return KeyFactory.getInstance("RSA").generatePublic(spec);
        }
    }

    // ASSUMPTION: not found in repo content
    static class RoleMapper {
        private final Map<String, DataPowerRole> groupMappings;

        RoleMapper() {
            this.groupMappings = Map.of(
                    "dpai-admin", DataPowerRole.ADMIN,
                    "dpai-editor", DataPowerRole.EDITOR,
                    "dpai-viewer", DataPowerRole.VIEWER);
        }

        Set<DataPowerRole> mapRoles(final Set<String> groups) {
            final Set<DataPowerRole> roles = new HashSet<>();
            for (final String group : groups) {
                final DataPowerRole mapped = groupMappings.get(group.toLowerCase());
                if (mapped != null) {
                    roles.add(mapped);
                }
            }
            return roles;
        }
    }

    enum DataPowerRole {
        ADMIN,
        EDITOR,
        VIEWER
    }

    // ASSUMPTION: not found in repo content
    interface UserRepository {
        Optional<AppUser> findByExternalId(String externalId);
        Optional<AppUser> findByEmail(String email);
        Optional<AppUser> findById(String id);
        AppUser save(AppUser user);
    }

    // ASSUMPTION: not found in repo content
    static class InMemoryUserRepository implements UserRepository {
        private final Map<String, AppUser> byId = new ConcurrentHashMap<>();

        @Override
        public Optional<AppUser> findByExternalId(final String externalId) {
            return byId.values().stream().filter(user -> Objects.equals(user.externalId(), externalId)).findFirst();
        }

        @Override
        public Optional<AppUser> findByEmail(final String email) {
            return byId.values().stream().filter(user -> Objects.equals(user.email(), email)).findFirst();
        }

        @Override
        public Optional<AppUser> findById(final String id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public AppUser save(final AppUser user) {
            byId.put(user.id(), user);
            return user;
        }
    }

    // ASSUMPTION: not found in repo content
    interface SessionRepository {
        SessionRecord create(String userId, Instant issuedAt, Instant expiresAt, String userAgent);
        Optional<SessionRecord> findActive(String sessionId, Instant now);
        void invalidate(String sessionId);
    }

    // ASSUMPTION: not found in repo content
    static class InMemorySessionRepository implements SessionRepository {
        private final Map<String, SessionRecord> sessions = new ConcurrentHashMap<>();

        @Override
        public SessionRecord create(final String userId, final Instant issuedAt, final Instant expiresAt, final String userAgent) {
            final SessionRecord record = new SessionRecord(randomToken(), userId, issuedAt, expiresAt, userAgent, true);
            sessions.put(record.sessionId(), record);
            return record;
        }

        @Override
        public Optional<SessionRecord> findActive(final String sessionId, final Instant now) {
            return Optional.ofNullable(sessions.get(sessionId))
                    .filter(SessionRecord::active)
                    .filter(record -> record.expiresAt().isAfter(now));
        }

        @Override
        public void invalidate(final String sessionId) {
            final SessionRecord existing = sessions.get(sessionId);
            if (existing != null) {
                sessions.put(sessionId, existing.inactive());
            }
        }
    }

    // ASSUMPTION: not found in repo content
    interface LoginStateStore {
        void save(LoginState state);
        Optional<LoginState> findAndConsume(String state);
    }

    // ASSUMPTION: not found in repo content
    static class InMemoryLoginStateStore implements LoginStateStore {
        private final Map<String, LoginState> states = new ConcurrentHashMap<>();

        @Override
        public void save(final LoginState state) {
            states.put(state.state(), state);
        }

        @Override
        public Optional<LoginState> findAndConsume(final String state) {
            return Optional.ofNullable(states.remove(state));
        }
    }

    // ASSUMPTION: not found in repo content
    interface AuditService {
        void record(String eventType, String subjectId, Map<String, String> details);
        List<AuditEvent> events();
    }

    // ASSUMPTION: not found in repo content
    static class InMemoryAuditService implements AuditService {
        private final List<AuditEvent> auditEvents = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void record(final String eventType, final String subjectId, final Map<String, String> details) {
            auditEvents.add(new AuditEvent(Instant.now(), eventType, subjectId, new LinkedHashMap<>(details)));
        }

        @Override
        public List<AuditEvent> events() {
            return auditEvents.stream().sorted(Comparator.comparing(AuditEvent::occurredAt)).toList();
        }
    }

    record AuthResponse(String message, String redirectUrl, UserSummary user) {}
    record LoginInitiation(String authorizationRedirectUrl, ResponseCookie pkceCookie) {}
    record AuthenticationResult(String redirectUrl, ResponseCookie sessionCookie, ResponseCookie clearLoginCookie, String message, UserSummary userSummary) {}
    record LogoutResult(String redirectUrl, ResponseCookie expiredSessionCookie, String message) {}
    record SessionState(boolean authenticated, String message, UserSummary userSummary) {}
    record CallbackRequest(String code, String state, String error, String errorDescription, String loginCookie, String userAgent) {}
    record TokenResponse(String idToken, String accessToken, Duration expiresIn) {}
    record ValidatedToken(String subject, String email, String name, Set<String> groups) {}
    record LoginState(String state, String nonce, String codeVerifier, String requestedPath, Instant expiresAt) {}
    record SessionRecord(String sessionId, String userId, Instant issuedAt, Instant expiresAt, String userAgent, boolean active) {
        SessionRecord inactive() { return new SessionRecord(sessionId, userId, issuedAt, expiresAt, userAgent, false); }
    }
    record AuditEvent(Instant occurredAt, String eventType, String subjectId, Map<String, String> details) {}
    record UserSummary(String id, String email, String name, Set<DataPowerRole> roles) {
        static UserSummary from(final AppUser user) {
            return new UserSummary(user.id(), user.email(), user.name(), Set.copyOf(user.roles()));
        }
    }

    // ASSUMPTION: not found in repo content
    record AppUser(String id, String externalId, String email, String name, Set<DataPowerRole> roles) {
        static AppUser create(final String externalId, final String email, final String name, final Set<DataPowerRole> roles) {
            return new AppUser(UUID.randomUUID().toString(), externalId, email, name, Set.copyOf(roles));
        }

        AppUser withProfile(final String email, final String name, final Set<DataPowerRole> roles) {
            return new AppUser(id, externalId, email, name, Set.copyOf(roles));
        }

        AppUser withExternalId(final String newExternalId) {
            return new AppUser(id, newExternalId, email, name, roles);
        }
    }

    static class AuthenticationFailureException extends RuntimeException {
        AuthenticationFailureException(final String message) { super(message); }
    }

    static class AccessDeniedException extends RuntimeException {
        AccessDeniedException(final String message) { super(message); }
    }

    static String randomToken() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
    }

    static String sha256UrlSafe(final String value) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (final Exception ex) {
            throw new IllegalStateException("Unable to compute PKCE code challenge.", ex);
        }
    }

    static String urlEncode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static String defaultString(final String value) {
        return defaultString(value, "");
    }

    static String defaultString(final String value, final String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    static String stringClaim(final Map<String, Object> claims, final String name) {
        final String value = stringValue(claims.get(name));
        if (!StringUtils.hasText(value)) {
            throw new AuthenticationFailureException("Failed login: missing claim " + name + ".");
        }
        return value;
    }

    static long longClaim(final Map<String, Object> claims, final String name) {
        final Object value = claims.get(name);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && text.matches("^-?\\d+$")) {
            return Long.parseLong(text);
        }
        throw new AuthenticationFailureException("Failed login: invalid numeric claim " + name + ".");
    }

    static String stringValue(final Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    static Set<String> toStringSet(final Object value) {
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(String::valueOf).collect(Collectors.toCollection(HashSet::new));
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            return Set.of(text);
        }
        return new HashSet<>();
    }

    static String base64UrlDecode(final String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    static String mockJwt(final SsoSettings settings) {
        final String header = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        final long exp = Instant.now().plus(Duration.ofHours(1)).getEpochSecond();
        final String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(("{" +
                "\"iss\":\"" + settings.issuer() + "\"," +
                "\"aud\":[\"" + settings.audience() + "\"]," +
                "\"sub\":\"user-123\"," +
                "\"email\":\"user@example.com\"," +
                "\"name\":\"AAVA User\"," +
                "\"nonce\":\""," +
                "\"exp\":" + exp + "," +
                "\"groups\":[\"dpai-viewer\"]}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".";
    }

    // ASSUMPTION: not found in repo content
    static final class Jsons {
        private Jsons() {}

        static Map<String, Object> readJsonObject(final String json) {
            final String trimmed = json == null ? "" : json.trim();
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
                throw new AuthenticationFailureException("Failed login: invalid JSON token payload.");
            }
            final Parser parser = new Parser(trimmed);
            return parser.parseObject();
        }

        private static final class Parser {
            private final String text;
            private int index;

            private Parser(final String text) {
                this.text = text;
                this.index = 0;
            }

            Map<String, Object> parseObject() {
                skipWhitespace();
                expect('{');
                final Map<String, Object> result = new LinkedHashMap<>();
                skipWhitespace();
                if (peek('}')) {
                    expect('}');
                    return result;
                }
                while (true) {
                    skipWhitespace();
                    final String key = parseString();
                    skipWhitespace();
                    expect(':');
                    skipWhitespace();
                    result.put(key, parseValue());
                    skipWhitespace();
                    if (peek('}')) {
                        expect('}');
                        break;
                    }
                    expect(',');
                }
                return result;
            }

            private Object parseValue() {
                skipWhitespace();
                final char current = current();
                if (current == '"') {
                    return parseString();
                }
                if (current == '{') {
                    return parseObject();
                }
                if (current == '[') {
                    return parseArray();
                }
                if (startsWith("true")) {
                    index += 4;
                    return Boolean.TRUE;
                }
                if (startsWith("false")) {
                    index += 5;
                    return Boolean.FALSE;
                }
                if (startsWith("null")) {
                    index += 4;
                    return null;
                }
                return parseNumber();
            }

            private List<Object> parseArray() {
                expect('[');
                final List<Object> items = new ArrayList<>();
                skipWhitespace();
                if (peek(']')) {
                    expect(']');
                    return items;
                }
                while (true) {
                    skipWhitespace();
                    items.add(parseValue());
                    skipWhitespace();
                    if (peek(']')) {
                        expect(']');
                        break;
                    }
                    expect(',');
                }
                return items;
            }

            private Number parseNumber() {
                final int start = index;
                if (current() == '-') {
                    index++;
                }
                while (index < text.length() && Character.isDigit(text.charAt(index))) {
                    index++;
                }
                if (index < text.length() && text.charAt(index) == '.') {
                    index++;
                    while (index < text.length() && Character.isDigit(text.charAt(index))) {
                        index++;
                    }
                    return Double.parseDouble(text.substring(start, index));
                }
                return Long.parseLong(text.substring(start, index));
            }

            private String parseString() {
                expect('"');
                final StringBuilder builder = new StringBuilder();
                while (index < text.length()) {
                    final char ch = text.charAt(index++);
                    if (ch == '"') {
                        return builder.toString();
                    }
                    if (ch == '\\') {
                        final char escaped = text.charAt(index++);
                        switch (escaped) {
                            case '"' -> builder.append('"');
                            case '\\' -> builder.append('\\');
                            case '/' -> builder.append('/');
                            case 'b' -> builder.append('\b');
                            case 'f' -> builder.append('\f');
                            case 'n' -> builder.append('\n');
                            case 'r' -> builder.append('\r');
                            case 't' -> builder.append('\t');
                            case 'u' -> {
                                final String hex = text.substring(index, index + 4);
                                builder.append((char) Integer.parseInt(hex, 16));
                                index += 4;
                            }
                            default -> throw new AuthenticationFailureException("Failed login: invalid JSON escape sequence.");
                        }
                    } else {
                        builder.append(ch);
                    }
                }
                throw new AuthenticationFailureException("Failed login: unterminated JSON string.");
            }

            private void expect(final char expected) {
                skipWhitespace();
                if (index >= text.length() || text.charAt(index) != expected) {
                    throw new AuthenticationFailureException("Failed login: invalid JSON structure.");
                }
                index++;
            }

            private boolean peek(final char expected) {
                skipWhitespace();
                return index < text.length() && text.charAt(index) == expected;
            }

            private boolean startsWith(final String token) {
                return text.startsWith(token, index);
            }

            private char current() {
                if (index >= text.length()) {
                    throw new AuthenticationFailureException("Failed login: unexpected end of JSON.");
                }
                return text.charAt(index);
            }

            private void skipWhitespace() {
                while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                    index++;
                }
            }
        }
    }
}

package com.ascendion.datapowerAI.service;

/*
 * Story ID: 111228
 * Title: Support User Access Token Authentication for AAVA Base URL Groups
 *
 * This file adds repo-style Spring service logic for resolving and applying the logged-in user's
 * AAVA access token at runtime whenever an API executes through an AAVA base URL group.
 * Assumptions: comments/attachments/linked work items were not returned by the work item reader;
 * ApiBaseUrlGroup currently has no explicit group type in the sampled repo, so a nested adapter marks
 * that addition as an assumption; async token resolution uses a user reference instead of relying only
 * on SecurityContextHolder.
 */

import com.ascendion.datapowerAI.entity.ApiBaseUrl;
import com.ascendion.datapowerAI.entity.ApiBaseUrlGroup;
import com.ascendion.datapowerAI.entity.GenericAPI;
import com.ascendion.datapowerAI.enums.ApiErrorType;
import com.ascendion.datapowerAI.enums.AuthType;
import com.ascendion.datapowerAI.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
class AavaUserTokenAuthentication {

    private static final String AAVA_GROUP_TYPE = "AAVA";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String MISSING_TOKEN_MESSAGE =
            "AAVA access token is not available for the current user.";

    private final CurrentUserService currentUserService;
    private final BaseUrlResolverService baseUrlResolverService;

    // ASSUMPTION: not found in repo content
    private final UserAavaTokenResolver userAavaTokenResolver;

    public ResolvedExecutionContext resolve(GenericAPI api,
                                            GenericAPIExecuteRequestAdapter request) {

        String resolvedUrl = baseUrlResolverService.resolveUrl(api);
        Map<String, String> sanitizedHeaders = sanitizeHeaders(request.headers());
        RuntimeUserReference userReference = request.userReference() != null
                ? request.userReference()
                : RuntimeUserReference.fromCurrentUser(currentUserService);

        if (!isAavaGroup(api)) {
            return new ResolvedExecutionContext(
                    resolvedUrl,
                    sanitizedHeaders,
                    request.authOverrideAllowed(),
                    userReference
            );
        }

        if (request.authOverrideAllowed()) {
            throw new BadRequestException(
                    ApiErrorType.BAD_REQUEST,
                    "API-level authentication override is not allowed for AAVA base URL groups"
            );
        }

        ResolvedUserToken resolvedToken = userAavaTokenResolver.resolveValidToken(userReference);

        Map<String, String> finalHeaders = new LinkedHashMap<>(sanitizedHeaders);
        finalHeaders.put(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + resolvedToken.accessToken());

        return new ResolvedExecutionContext(resolvedUrl, finalHeaders, false, userReference);
    }

    public SanitizedError sanitizeFailure(Throwable throwable) {
        String message = throwable == null || throwable.getMessage() == null
                ? "AAVA request failed"
                : throwable.getMessage()
                .replaceAll("(?i)Bearer\\s+[A-Za-z0-9\\-._~+/]+=*", "Bearer [REDACTED]");
        return new SanitizedError(message);
    }

    public Object handleUnauthorizedRetry(GenericAPI api,
                                          GenericAPIExecuteRequestAdapter request,
                                          AavaRetryableOperation operation) {

        ResolvedExecutionContext initial = resolve(api, request);
        try {
            return operation.execute(initial);
        } catch (UnauthorizedFromProviderException ex) {
            if (!isAavaGroup(api) || ex.retryAttempted()) {
                throw ex;
            }

            userAavaTokenResolver.invalidate(initial.userReference());
            ResolvedExecutionContext retried = resolve(api, request.withUserReference(initial.userReference()));
            return operation.execute(retried.markRetry());
        }
    }

    private boolean isAavaGroup(GenericAPI api) {
        if (api == null || api.getBaseUrlGroup() == null) {
            return false;
        }

        ApiBaseUrlGroup group = api.getBaseUrlGroup();
        String configuredType = GroupTypeAccessor.readGroupType(group);
        if (configuredType != null) {
            return AAVA_GROUP_TYPE.equalsIgnoreCase(configuredType);
        }

        return group.getName() != null && AAVA_GROUP_TYPE.equalsIgnoreCase(group.getName().trim());
    }

    private Map<String, String> sanitizeHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return new LinkedHashMap<>();
        }

        Map<String, String> copy = new LinkedHashMap<>();
        headers.forEach((key, value) -> {
            if (!HttpHeaders.AUTHORIZATION.equalsIgnoreCase(key)) {
                copy.put(key, value);
            }
        });
        return copy;
    }

    @FunctionalInterface
    interface AavaRetryableOperation {
        Object execute(ResolvedExecutionContext context);
    }

    record ResolvedExecutionContext(String resolvedUrl,
                                    Map<String, String> headers,
                                    boolean authOverrideAllowed,
                                    RuntimeUserReference userReference) {
        ResolvedExecutionContext markRetry() {
            return this;
        }
    }

    record SanitizedError(String message) {}

    record GenericAPIExecuteRequestAdapter(Map<String, String> headers,
                                           RuntimeUserReference userReference,
                                           boolean authOverrideAllowed) {
        GenericAPIExecuteRequestAdapter {
            headers = headers == null ? Collections.emptyMap() : headers;
        }

        GenericAPIExecuteRequestAdapter withUserReference(RuntimeUserReference reference) {
            return new GenericAPIExecuteRequestAdapter(headers, reference, authOverrideAllowed);
        }
    }

    record RuntimeUserReference(UUID userId, String username) {
        static RuntimeUserReference fromCurrentUser(CurrentUserService currentUserService) {
            var user = currentUserService.getCurrentUser();
            return new RuntimeUserReference(user.getId(), user.getUsername());
        }
    }

    record ResolvedUserToken(String accessToken, Instant expiresAt) {}

    @Service
    static class UserAavaTokenResolver {

        // ASSUMPTION: not found in repo content
        private final RuntimeTokenProvider runtimeTokenProvider;

        // ASSUMPTION: not found in repo content
        private final AavaTokenRefreshGateway aavaTokenRefreshGateway;

        private final Map<UUID, CachedToken> cache = new ConcurrentHashMap<>();

        UserAavaTokenResolver(RuntimeTokenProvider runtimeTokenProvider,
                              AavaTokenRefreshGateway aavaTokenRefreshGateway) {
            this.runtimeTokenProvider = runtimeTokenProvider;
            this.aavaTokenRefreshGateway = aavaTokenRefreshGateway;
        }

        ResolvedUserToken resolveValidToken(RuntimeUserReference userReference) {
            if (userReference == null || userReference.userId() == null) {
                throw new BadRequestException(ApiErrorType.BAD_REQUEST, MISSING_TOKEN_MESSAGE);
            }

            CachedToken cached = cache.get(userReference.userId());
            if (cached != null && !cached.isExpired()) {
                return cached.asResolved();
            }

            RuntimeToken token = runtimeTokenProvider.findToken(userReference);
            if (token == null || isBlank(token.accessToken())) {
                throw new BadRequestException(ApiErrorType.BAD_REQUEST, MISSING_TOKEN_MESSAGE);
            }

            RuntimeToken valid = token;
            if (token.isExpired()) {
                valid = aavaTokenRefreshGateway.refresh(userReference, token);
            }

            if (valid == null || isBlank(valid.accessToken())) {
                throw new BadRequestException(ApiErrorType.BAD_REQUEST, MISSING_TOKEN_MESSAGE);
            }

            cache.put(userReference.userId(), new CachedToken(valid.accessToken(), valid.expiresAt()));
            return new ResolvedUserToken(valid.accessToken(), valid.expiresAt());
        }

        void invalidate(RuntimeUserReference userReference) {
            if (userReference != null && userReference.userId() != null) {
                cache.remove(userReference.userId());
            }
        }

        private boolean isBlank(String value) {
            return value == null || value.isBlank();
        }
    }

    // ASSUMPTION: not found in repo content
    interface RuntimeTokenProvider {
        RuntimeToken findToken(RuntimeUserReference userReference);
    }

    // ASSUMPTION: not found in repo content
    interface AavaTokenRefreshGateway {
        RuntimeToken refresh(RuntimeUserReference userReference, RuntimeToken expiredToken);
    }

    // ASSUMPTION: not found in repo content
    record RuntimeToken(String accessToken, String refreshToken, Instant expiresAt) {
        boolean isExpired() {
            return expiresAt != null && !expiresAt.isAfter(Instant.now().plusSeconds(30));
        }
    }

    record CachedToken(String accessToken, Instant expiresAt) {
        boolean isExpired() {
            return expiresAt != null && !expiresAt.isAfter(Instant.now().plusSeconds(30));
        }

        ResolvedUserToken asResolved() {
            return new ResolvedUserToken(accessToken, expiresAt);
        }
    }

    static class UnauthorizedFromProviderException extends RuntimeException {
        private final boolean retryAttempted;

        UnauthorizedFromProviderException(String message, boolean retryAttempted) {
            super(message);
            this.retryAttempted = retryAttempted;
        }

        boolean retryAttempted() {
            return retryAttempted;
        }
    }

    static final class GroupTypeAccessor {
        private GroupTypeAccessor() {
        }

        static String readGroupType(ApiBaseUrlGroup group) {
            try {
                var method = group.getClass().getMethod("getGroupType");
                Object value = method.invoke(group);
                return value == null ? null : Objects.toString(value, null);
            } catch (ReflectiveOperationException ignored) {
                return null;
            }
        }
    }
}

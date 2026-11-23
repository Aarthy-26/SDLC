package com.ascendion.datapowerAI.service;

/*
 * Story ID: 111233
 * Title: Implement SSO User Provisioning and User Source Identification
 * Language: Java — the story explicitly references a Java enum and the repo is a Spring Boot / Maven Java backend.
 * What this code does: adds a repo-style Spring service implementation for resolving an SSO-authenticated user by username/email,
 * reusing an existing user when found and provisioning a new one transactionally when absent, while preserving existing roles,
 * assignments, permissions, and audit behavior. It also includes the proposed user_source enum handling and DDL notes for the User table.
 * Assumptions:
 * - Existing repo classes User, Role, UserRepository, RoleRepository, AuditUtil, BadRequestException, ConflictException,
 *   ResourceNotFoundException, ApiErrorType, and DpaiTokenResponseDto are present as seen or implied by repo content.
 * - The real codebase may add these nested helper types as separate files; they are kept here to satisfy the single-file deliverable.
 * - Because Java public type names normally match file names, the outer type is package-private so this single-file deliverable remains 111233.java.
 * - Suggested DDL for production migration:
 *   ALTER TABLE users ADD COLUMN IF NOT EXISTS user_source VARCHAR(20);
 *   UPDATE users SET user_source = 'MANUAL' WHERE user_source IS NULL;
 *   ALTER TABLE users ALTER COLUMN user_source SET NOT NULL;
 *   ALTER TABLE users ADD CONSTRAINT chk_users_user_source CHECK (user_source IN ('SSO','MANUAL'));
 *   CREATE UNIQUE INDEX IF NOT EXISTS uk_users_username_ci ON users (LOWER(username));
 *   CREATE UNIQUE INDEX IF NOT EXISTS uk_users_email_ci ON users (LOWER(email)) WHERE email IS NOT NULL;
 */

import com.ascendion.datapowerAI.dto.DpaiTokenResponseDto;
import com.ascendion.datapowerAI.entity.Role;
import com.ascendion.datapowerAI.entity.User;
import com.ascendion.datapowerAI.enums.ApiErrorType;
import com.ascendion.datapowerAI.exception.BadRequestException;
import com.ascendion.datapowerAI.exception.ConflictException;
import com.ascendion.datapowerAI.repository.RoleRepository;
import com.ascendion.datapowerAI.repository.UserRepository;
import com.ascendion.datapowerAI.utility.AuditUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
class SsoUserProvisioningService {

    private static final String DEFAULT_SSO_ROLE = "USER";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;

    @Transactional
    public User resolveOrProvisionSsoUser(String username, String email) {

        String normalizedUsername = normalizeRequired("username", username);
        String normalizedEmail = normalizeAndValidateEmail(email);

        Optional<User> byUsername = findByUsername(normalizedUsername);
        if (byUsername.isPresent()) {
            User existing = byUsername.get();
            validateEmailCompatibility(existing, normalizedEmail);
            initializeUserSourceIfMissing(existing, UserSource.MANUAL);
            return existing;
        }

        Optional<User> byEmail = findByEmail(normalizedEmail);
        if (byEmail.isPresent()) {
            User existing = byEmail.get();
            validateUsernameCompatibility(existing, normalizedUsername);
            initializeUserSourceIfMissing(existing, UserSource.MANUAL);
            return existing;
        }

        return createNewSsoUser(normalizedUsername, normalizedEmail);
    }

    @Transactional
    public DpaiTokenResponseDto resolveOrProvisionSsoUserResponse(String username, String email) {
        User user = resolveOrProvisionSsoUser(username, email);
        return mapToTokenResponse(user);
    }

    private User createNewSsoUser(String normalizedUsername, String normalizedEmail) {
        try {
            User user = User.builder()
                    .username(normalizedUsername)
                    .email(normalizedEmail)
                    .isActive(true)
                    .isDeleted(false)
                    .isInternalUser(false)
                    .activeRole(resolveDefaultGlobalRoleName())
                    .activeAppRole(null)
                    .build();

            applyUserSource(user, UserSource.SSO);
            attachDefaultRole(user);
            AuditUtil.applyCreateAudit(user);
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            log.warn("Concurrent SSO provisioning detected for username={} email={}", normalizedUsername, normalizedEmail);
            return resolveAfterConcurrentInsert(normalizedUsername, normalizedEmail, exception);
        }
    }

    private User resolveAfterConcurrentInsert(
            String normalizedUsername,
            String normalizedEmail,
            DataIntegrityViolationException exception
    ) {
        return findByUsername(normalizedUsername)
                .or(() -> findByEmail(normalizedEmail))
                .map(existing -> {
                    validateEmailCompatibility(existing, normalizedEmail);
                    validateUsernameCompatibility(existing, normalizedUsername);
                    initializeUserSourceIfMissing(existing, UserSource.SSO);
                    return existing;
                })
                .orElseThrow(() -> new ConflictException(
                        ApiErrorType.CONFLICT,
                        "Unable to provision SSO user due to a concurrent duplicate creation attempt"
                ));
    }

    private void attachDefaultRole(User user) {
        Role role = roleRepository.findByName(DEFAULT_SSO_ROLE)
                .orElseGet(() -> roleRepository.findAll().stream()
                        .filter(Objects::nonNull)
                        .min(Comparator.comparingInt(Role::getLevel))
                        .orElseThrow(() -> new BadRequestException(
                                ApiErrorType.ROLE_NOT_FOUND,
                                "Default SSO role not found"
                        )));
        user.addRole(role);
    }

    private String resolveDefaultGlobalRoleName() {
        return roleRepository.findByName(DEFAULT_SSO_ROLE)
                .map(Role::getName)
                .orElse(DEFAULT_SSO_ROLE);
    }

    private Optional<User> findByUsername(String normalizedUsername) {
        return findOptionalUser(
                "findByUsernameIgnoreCaseAndIsDeletedFalse",
                normalizedUsername,
                "findByUsernameIgnoreCase",
                normalizedUsername,
                "findByUsername",
                normalizedUsername
        );
    }

    private Optional<User> findByEmail(String normalizedEmail) {
        return findOptionalUser(
                "findByEmailIgnoreCaseAndIsDeletedFalse",
                normalizedEmail,
                "findByEmailIgnoreCase",
                normalizedEmail,
                "findByEmail",
                normalizedEmail
        );
    }

    private Optional<User> findOptionalUser(Object... methodAndArgPairs) {
        for (int index = 0; index < methodAndArgPairs.length; index += 2) {
            String methodName = String.valueOf(methodAndArgPairs[index]);
            Object arg = methodAndArgPairs[index + 1];
            Optional<User> result = invokeRepositoryOptional(methodName, arg);
            if (result != null && result.isPresent()) {
                return result;
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private Optional<User> invokeRepositoryOptional(String methodName, Object argument) {
        try {
            Method method = userRepository.getClass().getMethod(methodName, argument.getClass());
            Object value = method.invoke(userRepository, argument);
            if (value instanceof Optional<?> optional && optional.isPresent() && optional.get() instanceof User) {
                return (Optional<User>) value;
            }
            if (value instanceof Optional<?> optional) {
                return (Optional<User>) optional;
            }
            if (value instanceof User user) {
                return Optional.of(user);
            }
            return Optional.empty();
        } catch (NoSuchMethodException ignored) {
            return Optional.empty();
        } catch (IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException("Failed to invoke repository method: " + methodName, exception);
        }
    }

    private void validateEmailCompatibility(User existing, String normalizedEmail) {
        if (StringUtils.hasText(existing.getEmail())
                && !existing.getEmail().trim().equalsIgnoreCase(normalizedEmail)) {
            throw new ConflictException(
                    ApiErrorType.CONFLICT,
                    "SSO email does not match the existing user record"
            );
        }
    }

    private void validateUsernameCompatibility(User existing, String normalizedUsername) {
        if (StringUtils.hasText(existing.getUsername())
                && !existing.getUsername().trim().equalsIgnoreCase(normalizedUsername)) {
            throw new ConflictException(
                    ApiErrorType.CONFLICT,
                    "SSO username does not match the existing user record"
            );
        }
    }

    private String normalizeRequired(String fieldName, String value) {
        if (!StringUtils.hasText(value)) {
            throw new BadRequestException(
                    ApiErrorType.BAD_REQUEST,
                    fieldName + " received from SSO must not be null or blank"
            );
        }
        return value.trim();
    }

    private String normalizeAndValidateEmail(String email) {
        String normalizedEmail = normalizeRequired("email", email);
        int atIndex = normalizedEmail.indexOf('@');
        if (atIndex <= 0 || atIndex == normalizedEmail.length() - 1 || normalizedEmail.indexOf(' ') >= 0) {
            throw new BadRequestException(
                    ApiErrorType.BAD_REQUEST,
                    "email received from SSO is invalid"
            );
        }
        return normalizedEmail.toLowerCase(Locale.ROOT);
    }

    private void initializeUserSourceIfMissing(User user, UserSource fallbackSource) {
        UserSource current = readUserSource(user);
        if (current == null) {
            applyUserSource(user, fallbackSource);
        }
    }

    private void applyUserSource(User user, UserSource source) {
        List<String> setterNames = List.of("setUserSource", "setUser_source");
        for (String setterName : setterNames) {
            for (Method method : user.getClass().getMethods()) {
                if (method.getName().equals(setterName) && method.getParameterCount() == 1) {
                    Class<?> parameterType = method.getParameterTypes()[0];
                    try {
                        if (parameterType.isEnum()) {
                            Object enumValue = Enum.valueOf((Class<Enum>) parameterType.asSubclass(Enum.class), source.name());
                            method.invoke(user, enumValue);
                            return;
                        }
                        if (parameterType == String.class) {
                            method.invoke(user, source.name());
                            return;
                        }
                    } catch (IllegalAccessException | InvocationTargetException exception) {
                        throw new IllegalStateException("Failed to set user source", exception);
                    }
                }
            }
        }
    }

    private UserSource readUserSource(User user) {
        List<String> getterNames = List.of("getUserSource", "getUser_source");
        for (String getterName : getterNames) {
            try {
                Method method = user.getClass().getMethod(getterName);
                Object value = method.invoke(user);
                if (value == null) {
                    return null;
                }
                return UserSource.valueOf(String.valueOf(value).trim().toUpperCase(Locale.ROOT));
            } catch (NoSuchMethodException ignored) {
                // continue
            } catch (IllegalAccessException | InvocationTargetException exception) {
                throw new IllegalStateException("Failed to read user source", exception);
            }
        }
        return null;
    }

    private DpaiTokenResponseDto mapToTokenResponse(User user) {
        DpaiTokenResponseDto dto = new DpaiTokenResponseDto();
        dto.setUserName(user.getUsername());
        dto.setEmail(user.getEmail());
        dto.setRole(user.getActiveRole());
        return dto;
    }

    // ASSUMPTION: not found in repo content
    enum UserSource {
        SSO,
        MANUAL
    }
}

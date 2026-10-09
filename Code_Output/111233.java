package com.aava.datapowerai.service;

import com.aava.datapowerai.dto.common.ApiResponse;
import com.aava.datapowerai.model.UserModel;
import com.aava.datapowerai.repository.UserRepositoryJdbc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/*
 * Work Item: 111233 (User Story) - Implement SSO User Provisioning and User Source Identification
 * State: New
 * Area/Iteration: AAVA\\AAVA - Data Studio / AAVA\\PI4\\Sprint 4
 *
 * What this file adds:
 * - A transactional SSO “resolve or provision” flow: if the SSO user exists, reuse it; otherwise create it.
 * - A user_source indicator (SSO vs MANUAL) via enum + persistence SQL snippets.
 * - Duplicate prevention via database uniqueness + transactional insert and safe retry on constraint violation.
 *
 * ASSUMPTIONS (explicit where story is ambiguous or repo lacks fields):
 * - SSO provides a stable unique username (preferred matching key); email is used only for validation and optional fallback lookup.
 * - Existing dpai.users table does not yet include user_source column; DDL is provided below.
 * - Existing UserModel/UserRepositoryJdbc do not include user_source; this file provides minimal repository/service methods
 *   required for SSO provisioning without altering other user attributes.
 * - For new SSO-created users, password is stored as NULL (SSO users authenticate externally). If the real schema requires
 *   non-null password, replace with a random strong value or a dedicated auth scheme.
 *
 * Required DB changes (PostgreSQL) - include in your migration tool:
 *
 * -- 1) Add user_source column (default MANUAL for existing rows)
 * ALTER TABLE dpai.users
 *   ADD COLUMN IF NOT EXISTS user_source VARCHAR(16) NOT NULL DEFAULT 'MANUAL';
 *
 * -- 2) Ensure uniqueness on username (already present in repo SQL usage) and (optionally) email
 * CREATE UNIQUE INDEX IF NOT EXISTS ux_users_username ON dpai.users(username);
 * -- Email uniqueness is optional; if enabled, be sure it matches business rules
 * -- CREATE UNIQUE INDEX IF NOT EXISTS ux_users_email ON dpai.users(lower(email));
 */
class ImplementSsoUserProvisioning {

    /**
     * Minimal controller to be called by SSO callback flow after successful authentication.
     * Kept thin: validation + delegation.
     *
     * Endpoint name is an assumption: repo currently has /api/auth/* but no SSO callback.
     */
    @RestController
    @RequestMapping("/api/auth")
    static class SsoProvisioningController {
        private final SsoUserProvisioningService ssoUserProvisioningService;

        SsoProvisioningController(SsoUserProvisioningService ssoUserProvisioningService) {
            this.ssoUserProvisioningService = ssoUserProvisioningService;
        }

        @PostMapping("/sso/resolve-user")
        public ResponseEntity<ApiResponse<UserModel>> resolveOrProvision(@RequestBody SsoLoginRequest request) {
            UserModel user = ssoUserProvisioningService.resolveOrProvisionUser(request.username(), request.email());
            return ResponseEntity.ok(ApiResponse.success("User resolved successfully", user));
        }

        /** SSO payload received by backend after successful SSO authentication. */
        static record SsoLoginRequest(String username, String email) {}
    }

    @Service
    static class SsoUserProvisioningService {
        private static final Pattern BASIC_EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

        private final SsoUserRepository ssoUserRepository;

        SsoUserProvisioningService(SsoUserRepository ssoUserRepository) {
            this.ssoUserRepository = ssoUserRepository;
        }

        /**
         * Transactional resolve-or-create to avoid duplicates.
         * - Match rule: username is primary identity key.
         * - Email is required and validated before provisioning new users.
         * - Existing user fields must remain unchanged.
         */
        @Transactional
        public UserModel resolveOrProvisionUser(String username, String email) {
            if (username == null || username.isBlank()) {
                throw new IllegalArgumentException("SSO username is required");
            }
            if (email == null || email.isBlank()) {
                throw new IllegalArgumentException("SSO email is required");
            }
            if (!BASIC_EMAIL.matcher(email.trim()).matches()) {
                throw new IllegalArgumentException("Invalid email address");
            }

            String normalizedUsername = username.trim();
            String normalizedEmail = email.trim();

            // 1) Preferred match: username
            Optional<UserModel> existing = ssoUserRepository.findByUsername(normalizedUsername);
            if (existing.isPresent()) {
                return existing.get();
            }

            // 2) Optional fallback: if username not found but email matches an existing MANUAL user,
            // reuse it (does not change user_source).
            // ASSUMPTION: allowed as "additional validation/fallback".
            Optional<UserModel> byEmail = ssoUserRepository.findByEmailIgnoreCase(normalizedEmail);
            if (byEmail.isPresent()) {
                return byEmail.get();
            }

            // 3) Create new SSO user (do not overwrite any existing user fields).
            try {
                UUID id = UUID.randomUUID();
                ssoUserRepository.insertSsoUser(id, normalizedUsername, normalizedEmail);
                return ssoUserRepository.findById(id)
                        .orElseThrow(() -> new IllegalStateException("User created but could not be loaded"));
            } catch (DataIntegrityViolationException dup) {
                // Concurrency: another request inserted concurrently. Re-read by username.
                return ssoUserRepository.findByUsername(normalizedUsername)
                        .orElseThrow(() -> dup);
            }
        }
    }

    /**
     * Repo uses JdbcTemplate repositories; this is a dedicated repo for SSO needs.
     * // ASSUMPTION: not found in repo content
     */
    @Service
    static class SsoUserRepository {
        private final JdbcTemplate jdbcTemplate;

        SsoUserRepository(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        private final org.springframework.jdbc.core.RowMapper<UserModel> rowMapper = (rs, rn) -> {
            UserModel u = new UserModel();
            u.setId(rs.getObject("id", UUID.class));
            u.setUsername(rs.getString("username"));
            u.setPassword(rs.getString("password"));
            u.setEmail(rs.getString("email"));
            u.setEnabled(rs.getBoolean("enabled"));
            u.setCreatedAt(rs.getObject("created_at", java.time.OffsetDateTime.class));
            return u;
        };

        public Optional<UserModel> findByUsername(String username) {
            var sql = "SELECT * FROM dpai.users WHERE username = ?";
            return jdbcTemplate.query(sql, rowMapper, username).stream().findFirst();
        }

        public Optional<UserModel> findByEmailIgnoreCase(String email) {
            var sql = "SELECT * FROM dpai.users WHERE lower(email) = lower(?) LIMIT 1";
            return jdbcTemplate.query(sql, rowMapper, email).stream().findFirst();
        }

        public Optional<UserModel> findById(UUID id) {
            var sql = "SELECT * FROM dpai.users WHERE id = ?";
            return jdbcTemplate.query(sql, rowMapper, id).stream().findFirst();
        }

        /**
         * Insert user with user_source=SSO.
         * Note: does NOT overwrite existing user data (no upsert) to satisfy AC #2.
         */
        public void insertSsoUser(UUID id, String username, String email) {
            var sql = """
                    INSERT INTO dpai.users (id, username, password, email, enabled, user_source)
                    VALUES (?, ?, NULL, ?, true, ?)
                    """;
            jdbcTemplate.update(sql, id, username, email, UserSource.SSO.name());
        }
    }

    /**
     * Creation source indicator.
     * // ASSUMPTION: not found in repo content
     */
    enum UserSource {
        SSO,
        MANUAL
    }
}

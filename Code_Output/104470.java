package com.aava.datapowerai.controller;

import com.aava.datapowerai.dto.common.ApiResponse;
import com.aava.datapowerai.model.UserModel;
import com.aava.datapowerai.service.AuditService;
import com.aava.datapowerai.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Story ID: 104470
 * Title: App Version Management / Version History – Backend
 *
 * Implements backend APIs for application version history:
 *  - GET /api/apps/{appId}/versions
 *  - POST /api/apps/{appId}/versions/{versionNumber}/restore
 *
 * Assumptions (not found in repo content):
 *  - "Application" is represented by a DB table dpai.apps with at least (id UUID, active_version INT).
 *  - Versions are represented by dpai.app_versions with at least (app_id UUID, version_number INT,
 *    created_at timestamptz, created_by UUID nullable, is_deleted boolean, is_accessible boolean,
 *    snapshot_json text).
 *  - Restoring a version updates dpai.apps.active_version and (optionally) dpai.apps.config_json from snapshot_json.
 *  - Concurrency is controlled via SELECT ... FOR UPDATE on dpai.apps row.
 *
 * SQL (DDL sketch) used by repositories (informational only):
 *  - dpai.apps(id uuid primary key, active_version int, config_json text, updated_at timestamptz)
 *  - dpai.app_versions(app_id uuid, version_number int, created_at timestamptz, created_by uuid,
 *      is_deleted boolean default false, is_accessible boolean default true, snapshot_json text,
 *      primary key(app_id, version_number))
 */
class AppVersionManagementBackend {

    // --- Controller ---

    @RestController
    @RequestMapping("/api/apps")
    @Tag(name = "App Versions", description = "Application version history and restore")
    static class AppVersionController {
        private static final Logger log = LoggerFactory.getLogger(AppVersionController.class);

        private final AppVersionService service;
        private final UserService userService;

        AppVersionController(AppVersionService service, UserService userService) {
            this.service = service;
            this.userService = userService;
        }

        @GetMapping("/{appId}/versions")
        @PreAuthorize("isAuthenticated()")
        @Operation(summary = "Get all versions for an application")
        public ResponseEntity<ApiResponse<List<AppVersionDto>>> getVersions(
                @PathVariable("appId") UUID appId,
                Authentication authentication) {

            // ASSUMPTION: repo does not have app-level authorization; we enforce minimal role-based guard.
            if (!hasAnyAdminOrEditorRole(authentication)) {
                return ResponseEntity.status(403).body(ApiResponse.failure("Forbidden — insufficient permissions"));
            }

            try {
                List<AppVersionDto> versions = service.getVersions(appId);
                if (versions.isEmpty()) {
                    return ResponseEntity.ok(ApiResponse.success("No versions available", versions));
                }
                return ResponseEntity.ok(ApiResponse.success("Versions retrieved successfully", versions));
            } catch (NotFoundException e) {
                return ResponseEntity.status(404).body(ApiResponse.failure(e.getMessage()));
            } catch (Exception e) {
                log.error("Failed to get versions for appId={}: {}", appId, e.getMessage(), e);
                return ResponseEntity.status(500).body(ApiResponse.failure("Failed to retrieve versions"));
            }
        }

        @PostMapping("/{appId}/versions/{versionNumber}/restore")
        @PreAuthorize("isAuthenticated()")
        @Operation(summary = "Restore an application to a selected version")
        public ResponseEntity<ApiResponse<RestoreResultDto>> restore(
                @PathVariable("appId") UUID appId,
                @PathVariable("versionNumber") int versionNumber,
                Authentication authentication) {

            // ASSUMPTION: restore requires elevated rights.
            if (!hasAnyAdminRole(authentication)) {
                return ResponseEntity.status(403).body(ApiResponse.failure("Forbidden — requires admin role"));
            }

            String actorUsername = userService.getCurrentUser().map(UserModel::getUsername).orElse("UNKNOWN");

            try {
                RestoreResultDto result = service.restore(appId, versionNumber, actorUsername);
                return ResponseEntity.ok(ApiResponse.success("Restore completed successfully", result));
            } catch (NotFoundException e) {
                return ResponseEntity.status(404).body(ApiResponse.failure(e.getMessage()));
            } catch (ConflictException e) {
                return ResponseEntity.status(409).body(ApiResponse.failure(e.getMessage()));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.status(400).body(ApiResponse.failure(e.getMessage()));
            } catch (Exception e) {
                log.error("Restore failed for appId={} version={}: {}", appId, versionNumber, e.getMessage(), e);
                return ResponseEntity.status(500).body(ApiResponse.failure("Restore failed"));
            }
        }

        private boolean hasAnyAdminRole(Authentication auth) {
            if (auth == null) return false;
            return auth.getAuthorities().stream().anyMatch(a -> {
                String r = a.getAuthority();
                return "ROLE_TOOL_ADMIN".equals(r) || "ROLE_PROJECT_ADMIN".equals(r) || "ROLE_ADMIN".equals(r);
            });
        }

        private boolean hasAnyAdminOrEditorRole(Authentication auth) {
            if (auth == null) return false;
            return auth.getAuthorities().stream().anyMatch(a -> {
                String r = a.getAuthority();
                return "ROLE_TOOL_ADMIN".equals(r) || "ROLE_PROJECT_ADMIN".equals(r) || "ROLE_ADMIN".equals(r)
                        || "ROLE_EDITOR".equals(r) || "ROLE_USER".equals(r) || "ROLE_READ_ONLY".equals(r);
            });
        }
    }

    // --- Service ---

    @Service
    static class AppVersionService {
        private final AppRepository appRepository;
        private final AppVersionRepository versionRepository;
        private final AuditService auditService;

        AppVersionService(AppRepository appRepository, AppVersionRepository versionRepository, AuditService auditService) {
            this.appRepository = appRepository;
            this.versionRepository = versionRepository;
            this.auditService = auditService;
        }

        public List<AppVersionDto> getVersions(UUID appId) {
            if (!appRepository.existsById(appId)) {
                throw new NotFoundException("Application not found");
            }

            Integer active = appRepository.findActiveVersion(appId).orElse(null);
            List<AppVersionModel> models = versionRepository.findValidAccessibleByAppId(appId);

            List<AppVersionDto> out = new ArrayList<>(models.size());
            for (AppVersionModel m : models) {
                out.add(new AppVersionDto(
                        m.versionNumber(),
                        m.createdAt(),
                        m.createdBy(),
                        Objects.equals(active, m.versionNumber())
                ));
            }
            return out;
        }

        @Transactional
        public RestoreResultDto restore(UUID appId, int versionNumber, String actorUsername) {
            if (versionNumber <= 0) {
                throw new IllegalArgumentException("versionNumber must be positive");
            }

            // lock app row to prevent concurrent restores leaving partial state
            AppModel app = appRepository.lockById(appId).orElseThrow(() -> new NotFoundException("Application not found"));

            AppVersionModel version = versionRepository.findValidAccessible(appId, versionNumber)
                    .orElseThrow(() -> new NotFoundException("Version not found"));

            // validate relationship
            if (!Objects.equals(version.appId(), app.id())) {
                throw new IllegalArgumentException("Invalid version/application relationship");
            }

            // conflict: restoring to already active version
            Integer current = app.activeVersion();
            if (current != null && current == versionNumber) {
                throw new ConflictException("Requested version is already the active version");
            }

            // restore config/data from snapshot
            appRepository.restoreFromSnapshot(appId, version.snapshotJson(), versionNumber);

            // audit per existing pattern
            auditService.logAction("APP_VERSION_RESTORE", "appId=" + appId + ", version=" + versionNumber + ", status=SUCCESS");

            return new RestoreResultDto(appId, versionNumber, current, OffsetDateTime.now(), actorUsername);
        }
    }

    // --- Repositories (JdbcTemplate style, matching repo conventions) ---

    @Repository
    static class AppRepository {
        private final JdbcTemplate jdbcTemplate;

        AppRepository(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        boolean existsById(UUID appId) {
            String sql = "SELECT 1 FROM dpai.apps WHERE id = ? LIMIT 1";
            List<Integer> r = jdbcTemplate.queryForList(sql, Integer.class, appId);
            return !r.isEmpty();
        }

        Optional<Integer> findActiveVersion(UUID appId) {
            String sql = "SELECT active_version FROM dpai.apps WHERE id = ?";
            List<Integer> list = jdbcTemplate.query(sql, (rs, rn) -> {
                int v = rs.getInt("active_version");
                return rs.wasNull() ? null : v;
            }, appId);
            return list.stream().findFirst();
        }

        Optional<AppModel> lockById(UUID appId) {
            // SELECT ... FOR UPDATE to serialize restore operations per app
            String sql = "SELECT id, active_version FROM dpai.apps WHERE id = ? FOR UPDATE";
            List<AppModel> list = jdbcTemplate.query(sql, (rs, rn) -> new AppModel(
                    rs.getObject("id", UUID.class),
                    (Integer) rs.getObject("active_version")
            ), appId);
            return list.stream().findFirst();
        }

        void restoreFromSnapshot(UUID appId, String snapshotJson, int versionNumber) {
            // ASSUMPTION: config_json exists and should be replaced by snapshot_json
            String sql = """
                    UPDATE dpai.apps
                       SET config_json = ?,
                           active_version = ?,
                           updated_at = now()
                     WHERE id = ?
                    """;
            int updated = jdbcTemplate.update(sql, snapshotJson, versionNumber, appId);
            if (updated != 1) {
                throw new DataAccessException("Failed to update app active version") {};
            }
        }
    }

    @Repository
    static class AppVersionRepository {
        private final JdbcTemplate jdbcTemplate;

        AppVersionRepository(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        private final RowMapper<AppVersionModel> rowMapper = (rs, rn) -> new AppVersionModel(
                rs.getObject("app_id", UUID.class),
                rs.getInt("version_number"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("created_by", UUID.class),
                rs.getBoolean("is_deleted"),
                rs.getBoolean("is_accessible"),
                rs.getString("snapshot_json")
        );

        List<AppVersionModel> findValidAccessibleByAppId(UUID appId) {
            String sql = """
                    SELECT app_id, version_number, created_at, created_by, is_deleted, is_accessible, snapshot_json
                      FROM dpai.app_versions
                     WHERE app_id = ?
                       AND COALESCE(is_deleted, false) = false
                       AND COALESCE(is_accessible, true) = true
                     ORDER BY version_number DESC
                    """;
            return jdbcTemplate.query(sql, rowMapper, appId);
        }

        Optional<AppVersionModel> findValidAccessible(UUID appId, int versionNumber) {
            String sql = """
                    SELECT app_id, version_number, created_at, created_by, is_deleted, is_accessible, snapshot_json
                      FROM dpai.app_versions
                     WHERE app_id = ? AND version_number = ?
                       AND COALESCE(is_deleted, false) = false
                       AND COALESCE(is_accessible, true) = true
                     LIMIT 1
                    """;
            List<AppVersionModel> list = jdbcTemplate.query(sql, rowMapper, appId, versionNumber);
            return list.stream().findFirst();
        }

        // Optional helper if future requirements need recording new audit/version data.
        UUID insertRestoreAudit(UUID appId, int versionNumber, String actorUsername, String status, String failureReason) {
            // ASSUMPTION: not found in repo content. If an audit table exists already, prefer AuditService.
            String sql = """
                    INSERT INTO dpai.app_version_restore_audit
                      (app_id, version_number, actor_username, status, failure_reason)
                    VALUES (?, ?, ?, ?, ?)
                    """;
            KeyHolder kh = new GeneratedKeyHolder();
            jdbcTemplate.update(con -> {
                PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                ps.setObject(1, appId);
                ps.setInt(2, versionNumber);
                ps.setString(3, actorUsername);
                ps.setString(4, status);
                ps.setString(5, failureReason);
                return ps;
            }, kh);
            var key = kh.getKey();
            return key == null ? UUID.randomUUID() : UUID.fromString(key.toString());
        }
    }

    // --- Models/DTOs ---

    // ASSUMPTION: not found in repo content
    static record AppModel(UUID id, Integer activeVersion) {}

    // ASSUMPTION: not found in repo content
    static record AppVersionModel(
            UUID appId,
            int versionNumber,
            OffsetDateTime createdAt,
            UUID createdBy,
            boolean deleted,
            boolean accessible,
            String snapshotJson
    ) {}

    static record AppVersionDto(
            int versionNumber,
            OffsetDateTime createdAt,
            UUID createdBy,
            boolean active
    ) {}

    static record RestoreResultDto(
            UUID appId,
            int restoredVersion,
            Integer previousActiveVersion,
            OffsetDateTime restoredAt,
            String restoredBy
    ) {}

    // --- Exceptions (kept minimal; repo uses GlobalExceptionHandler but controllers also map some statuses) ---

    static class NotFoundException extends RuntimeException {
        NotFoundException(String message) { super(message); }
    }

    static class ConflictException extends RuntimeException {
        ConflictException(String message) { super(message); }
    }
}

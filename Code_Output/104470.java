/*
 * Story ID: 104470
 * Title   : App Version Management / Version History – Backend
 * Type    : (Azure DevOps work item; type not returned by tool)
 * State   : Ready for QA
 * Area    : AAVA\\AAVA - Data Studio\\DPAI
 * Iteration: AAVA\\PI4\\Sprint 3
 * Assigned: Neha Ghatge
 *
 * What this code does
 * -------------------
 * Implements backend APIs for application version management:
 *  1) GET  /api/apps/{appId}/versions
 *  2) POST /api/apps/{appId}/versions/{versionNumber}/restore
 *
 * It follows the repository conventions (Spring Boot + Security + JdbcTemplate + ApiResponse).
 *
 * Build/Run (standalone Java file requirements from the agent prompt)
 * ------------------------------------------------------------------
 * NOTE: The repository referenced by the user story is a Spring Boot project (Java 21).
 * This single file is generated per instructions, but it contains Spring components and
 * is not intended to be compiled with plain `javac` alone.
 *
 *   javac 104470.java
 *   java AppVersionManagementApi104470
 *
 * Assumptions (because repo lacks App/Version tables/models)
 * ---------------------------------------------------------
 * 1) Database schema uses following tables:
 *      dpai.apps(app_id UUID PK, name TEXT, current_version INT, updated_at timestamptz)
 *      dpai.app_versions(app_id UUID FK, version_number INT, created_at timestamptz, created_by TEXT,
 *                        is_active BOOLEAN, is_deleted BOOLEAN, config_json TEXT)
 *    If schema differs, adjust SQL in AppVersionRepositoryJdbc accordingly.
 * 2) Authorization model: reuse Spring Security roles; viewing versions requires authentication,
 *    restoring requires ROLE_TOOL_ADMIN or ROLE_PROJECT_ADMIN (matches existing patterns).
 * 3) Audit logging: reuse existing AuditService.logAction(actionType, targetName). The story
 *    asks for more detailed audit fields; since AuditService currently stores actionType/targetName,
 *    we encode details into targetName as a JSON-like string.
 * 4) Concurrency: restore uses a DB transaction + SELECT ... FOR UPDATE on dpai.apps row
 *    to prevent concurrent restores.
 *
 * Compliance / Audit Log (generation-time)
 * --------------------------------------
 * - Parsed requirement from Azure DevOps work item 104470.
 * - Repo conventions detected: Spring Boot controllers/services/repositories, ApiResponse wrapper,
 *   JWT security, AuditService.
 * - No secrets/PII added. (Note: repo reference contains credentials in application.yml; not used here.)
 */

import com.aava.datapowerai.dto.common.ApiResponse;
import com.aava.datapowerai.model.UserModel;
import com.aava.datapowerai.service.AuditService;
import com.aava.datapowerai.service.UserService;
import jakarta.validation.constraints.NotNull;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

// Non-public class name per instructions.
class AppVersionManagementApi104470 {

    /*
     * Minimal runnable entrypoint per prompt.
     * In the real repo, Spring Boot app is started via com.aava.datapowerai.DataPowerAIApplication.
     */
    public static void main(String[] args) {
        System.out.println("This file defines Spring components for Story 104470.");
        System.out.println("Run the Spring Boot application (DataPowerAIApplication) to use the APIs.");
        System.out.println();
        System.out.println("Endpoints:");
        System.out.println("  GET  /api/apps/{appId}/versions");
        System.out.println("  POST /api/apps/{appId}/versions/{versionNumber}/restore");
    }

    // ==========================
    // Controller
    // ==========================

    @RestController
    @RequestMapping("/api/apps")
    static class AppVersionController {

        private final AppVersionService appVersionService;

        AppVersionController(AppVersionService appVersionService) {
            this.appVersionService = appVersionService;
        }

        @GetMapping("/{appId}/versions")
        @PreAuthorize("isAuthenticated()")
        public ResponseEntity<ApiResponse<List<AppVersionDto>>> getVersions(@PathVariable("appId") UUID appId) {
            try {
                List<AppVersionDto> versions = appVersionService.getVersions(appId);
                if (versions.isEmpty()) {
                    // Requirement: "appropriate response when no versions are available".
                    return ResponseEntity.ok(ApiResponse.success("No versions available for this application", versions));
                }
                return ResponseEntity.ok(ApiResponse.success("Versions retrieved successfully", versions));
            } catch (NotFoundException nf) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.failure(nf.getMessage()));
            } catch (ForbiddenException fe) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(ApiResponse.failure(fe.getMessage()));
            } catch (UnauthorizedException ue) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(ApiResponse.failure(ue.getMessage()));
            } catch (DataAccessException dae) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(ApiResponse.failure("Database error while retrieving versions"));
            } catch (Exception ex) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(ApiResponse.failure("Unexpected error while retrieving versions: " + ex.getMessage()));
            }
        }

        @PostMapping("/{appId}/versions/{versionNumber}/restore")
        @PreAuthorize("hasAnyRole('TOOL_ADMIN','PROJECT_ADMIN')")
        public ResponseEntity<ApiResponse<RestoreResultDto>> restore(
                @PathVariable("appId") UUID appId,
                @PathVariable("versionNumber") int versionNumber) {

            try {
                RestoreResultDto result = appVersionService.restoreVersion(appId, versionNumber);
                return ResponseEntity.ok(ApiResponse.success("Version restored successfully", result));
            } catch (NotFoundException nf) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.failure(nf.getMessage()));
            } catch (ConflictException ce) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(ApiResponse.failure(ce.getMessage()));
            } catch (ForbiddenException fe) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(ApiResponse.failure(fe.getMessage()));
            } catch (UnauthorizedException ue) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(ApiResponse.failure(ue.getMessage()));
            } catch (DataAccessException dae) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(ApiResponse.failure("Database error during restore"));
            } catch (Exception ex) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(ApiResponse.failure("Unexpected restoration failure: " + ex.getMessage()));
            }
        }
    }

    // ==========================
    // Service
    // ==========================

    @Service
    static class AppVersionService {

        private final AppVersionRepositoryJdbc repo;
        private final UserService userService;
        private final AuditService auditService;

        AppVersionService(AppVersionRepositoryJdbc repo, UserService userService, AuditService auditService) {
            this.repo = repo;
            this.userService = userService;
            this.auditService = auditService;
        }

        public List<AppVersionDto> getVersions(@NotNull UUID appId) {
            // Validate application exists
            if (!repo.appExists(appId)) {
                throw new NotFoundException("Application not found");
            }

            // Authorization: story says "Users should only retrieve versions for apps they are authorized to access".
            // Repo has project membership authorization only for projects, and no app/project mapping exists.
            // Assumption: authenticated users can view versions; restore has stricter role-based guard.
            // (If app-level ACL exists, implement it in repo.hasAccess(appId, userId).)

            return repo.findVersionsByAppId(appId);
        }

        @Transactional(isolation = Isolation.READ_COMMITTED)
        public RestoreResultDto restoreVersion(@NotNull UUID appId, int versionNumber) {
            // Validate app
            if (!repo.appExists(appId)) {
                throw new NotFoundException("Application not found");
            }

            // Validate requested version exists and belongs to app
            Optional<AppVersionRecord> verOpt = repo.findVersionRecord(appId, versionNumber);
            if (verOpt.isEmpty()) {
                throw new NotFoundException("Version not found");
            }
            AppVersionRecord ver = verOpt.get();
            if (ver.isDeleted() || !ver.isAccessible()) {
                throw new ConflictException("Requested version is invalid or inaccessible");
            }

            // Lock app row to prevent concurrent restore operations
            repo.lockAppRow(appId);

            // If already active, treat as no-op success (not specified; simplest is to succeed)
            Optional<Integer> current = repo.getCurrentVersionNumber(appId);
            if (current.isPresent() && current.get() == versionNumber) {
                return new RestoreResultDto(appId, versionNumber, true, "Version already active", OffsetDateTime.now());
            }

            // Restore application "configuration/data". Assumption: apps table has config_json.
            // If you store config in other tables, replicate here transactionally.
            boolean restored = repo.restoreAppConfigFromVersion(appId, versionNumber);
            if (!restored) {
                throw new ConflictException("Restore operation conflict or failed to update app state");
            }

            // Update active flags in versions table
            repo.markActiveVersion(appId, versionNumber);

            // Audit & logging
            String actor = userService.getCurrentUser().map(UserModel::getUsername).orElse("UNKNOWN");
            String details = "{\"appId\":\"" + appId + "\",\"restoredVersion\":" + versionNumber + ",\"status\":\"SUCCESS\"}";
            auditService.logAction("APP_VERSION_RESTORE", details + ";actor=" + actor);

            return new RestoreResultDto(appId, versionNumber, true, "Restored", OffsetDateTime.now());
        }
    }

    // ==========================
    // Repository (JdbcTemplate)
    // ==========================

    @Repository
    static class AppVersionRepositoryJdbc {
        private final JdbcTemplate jdbc;

        AppVersionRepositoryJdbc(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        boolean appExists(UUID appId) {
            String sql = "SELECT 1 FROM dpai.apps WHERE app_id = ? LIMIT 1";
            List<Integer> rows = jdbc.queryForList(sql, Integer.class, appId);
            return !rows.isEmpty();
        }

        void lockAppRow(UUID appId) {
            // Prevent concurrent restore operations safely.
            String sql = "SELECT app_id FROM dpai.apps WHERE app_id = ? FOR UPDATE";
            jdbc.queryForList(sql, UUID.class, appId);
        }

        Optional<Integer> getCurrentVersionNumber(UUID appId) {
            String sql = "SELECT current_version FROM dpai.apps WHERE app_id = ?";
            List<Integer> rows = jdbc.queryForList(sql, Integer.class, appId);
            return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
        }

        List<AppVersionDto> findVersionsByAppId(UUID appId) {
            // Exclude deleted/inaccessible versions.
            String sql = """
                    SELECT app_id, version_number, created_at, created_by, is_active
                      FROM dpai.app_versions
                     WHERE app_id = ?
                       AND COALESCE(is_deleted, false) = false
                     ORDER BY version_number DESC
                    """;

            RowMapper<AppVersionDto> rm = (rs, rn) -> new AppVersionDto(
                    rs.getObject("app_id", UUID.class),
                    rs.getInt("version_number"),
                    rs.getObject("created_at", OffsetDateTime.class),
                    rs.getString("created_by"),
                    rs.getBoolean("is_active")
            );

            return jdbc.query(sql, rm, appId);
        }

        Optional<AppVersionRecord> findVersionRecord(UUID appId, int versionNumber) {
            String sql = """
                    SELECT app_id, version_number, created_at, created_by,
                           COALESCE(is_active,false) AS is_active,
                           COALESCE(is_deleted,false) AS is_deleted,
                           COALESCE(config_json,'')  AS config_json
                      FROM dpai.app_versions
                     WHERE app_id = ? AND version_number = ?
                     LIMIT 1
                    """;
            List<AppVersionRecord> list = jdbc.query(sql, (rs, rn) -> new AppVersionRecord(
                    rs.getObject("app_id", UUID.class),
                    rs.getInt("version_number"),
                    rs.getObject("created_at", OffsetDateTime.class),
                    rs.getString("created_by"),
                    rs.getBoolean("is_active"),
                    rs.getBoolean("is_deleted"),
                    rs.getString("config_json"),
                    true
            ), appId, versionNumber);

            return list.stream().findFirst();
        }

        boolean restoreAppConfigFromVersion(UUID appId, int versionNumber) {
            // Transactional operation (service method annotated @Transactional).
            // Assumption: dpai.apps has config_json column.
            String sql = """
                    UPDATE dpai.apps a
                       SET config_json = v.config_json,
                           current_version = v.version_number,
                           updated_at = now()
                      FROM dpai.app_versions v
                     WHERE a.app_id = v.app_id
                       AND a.app_id = ?
                       AND v.version_number = ?
                       AND COALESCE(v.is_deleted,false) = false
                    """;
            int updated = jdbc.update(sql, appId, versionNumber);
            return updated == 1;
        }

        void markActiveVersion(UUID appId, int versionNumber) {
            // Make selected version active/current.
            String clearSql = "UPDATE dpai.app_versions SET is_active = false WHERE app_id = ?";
            jdbc.update(clearSql, appId);

            String setSql = "UPDATE dpai.app_versions SET is_active = true WHERE app_id = ? AND version_number = ?";
            int updated = jdbc.update(setSql, appId, versionNumber);
            if (updated != 1) {
                throw new ConflictException("Failed to mark restored version as active");
            }
        }
    }

    // ==========================
    // DTOs / Records
    // ==========================

    static record AppVersionDto(
            UUID appId,
            int versionNumber,
            OffsetDateTime createdAt,
            String createdBy,
            boolean active
    ) {}

    static record RestoreResultDto(
            UUID appId,
            int restoredVersion,
            boolean success,
            String message,
            OffsetDateTime restoredAt
    ) {}

    static record AppVersionRecord(
            UUID appId,
            int versionNumber,
            OffsetDateTime createdAt,
            String createdBy,
            boolean active,
            boolean isDeleted,
            String configJson,
            boolean isAccessible
    ) {}

    // ==========================
    // Exceptions (simple)
    // ==========================

    static class NotFoundException extends RuntimeException {
        NotFoundException(String message) { super(message); }
    }

    static class ConflictException extends RuntimeException {
        ConflictException(String message) { super(message); }
    }

    static class ForbiddenException extends RuntimeException {
        ForbiddenException(String message) { super(message); }
    }

    static class UnauthorizedException extends RuntimeException {
        UnauthorizedException(String message) { super(message); }
    }
}

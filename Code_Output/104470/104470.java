package com.aava.datapowerai.controller;

import com.aava.datapowerai.dto.common.ApiResponse;
import com.aava.datapowerai.model.UserModel;
import com.aava.datapowerai.service.AuditService;
import com.aava.datapowerai.service.UserService;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Story 104470: App Version Management / Version History – Backend
 *
 * Implements backend APIs to:
 *  - Retrieve application version history
 *  - Restore an application to a selected previous version
 *
 * Endpoints:
 *  - GET  /api/apps/{appId}/versions
 *  - POST /api/apps/{appId}/versions/{versionNumber}/restore
 *
 * Repo conventions reused:
 *  - Spring Boot MVC controllers in package com.aava.datapowerai.controller
 *  - Unified response wrapper: com.aava.datapowerai.dto.common.ApiResponse
 *  - Authorization via @PreAuthorize with Spring Security roles
 *  - Persistence via JdbcTemplate repositories
 *  - Auditing via com.aava.datapowerai.service.AuditService (logAction)
 *
 * ASSUMPTIONS (not found in repo content):
 *  - Applications exist in table dpai.apps (id UUID, name text, active_version int)
 *  - Version snapshots exist in table dpai.app_versions (app_id UUID, version_number int, snapshot_json text, created_by text, created_at timestamptz, deleted boolean)
 *  - A restore writes current app state to a new version (active_version+1) before activating the restored snapshot.
 *  - Any user who can access the application can list versions; only admins can restore.
 *
 * Embedded DDL (illustrative only; keep in this file as requested):
 * <pre>
 * CREATE TABLE IF NOT EXISTS dpai.apps (
 *   id uuid PRIMARY KEY,
 *   name text NOT NULL,
 *   active_version int NOT NULL DEFAULT 1
 * );
 *
 * CREATE TABLE IF NOT EXISTS dpai.app_versions (
 *   app_id uuid NOT NULL REFERENCES dpai.apps(id) ON DELETE CASCADE,
 *   version_number int NOT NULL,
 *   snapshot_json text NOT NULL,
 *   created_by text,
 *   created_at timestamptz NOT NULL DEFAULT now(),
 *   deleted boolean NOT NULL DEFAULT false,
 *   PRIMARY KEY (app_id, version_number)
 * );
 *
 * -- Optional: prevent concurrent restores per app.
 * -- In restore flow we use SELECT ... FOR UPDATE on dpai.apps.
 * </pre>
 */
class AppVersionManagementBackend {

    @RestController
    @RequestMapping("/api/apps")
    @Validated
    static class AppVersionController {

        private final AppVersionService service;

        AppVersionController(AppVersionService service) {
            this.service = service;
        }

        @GetMapping("/{appId}/versions")
        @PreAuthorize("isAuthenticated()")
        public ResponseEntity<ApiResponse<List<AppVersionDto>>> getVersions(@PathVariable("appId") UUID appId) {
            try {
                List<AppVersionDto> versions = service.listVersions(appId);
                if (versions.isEmpty()) {
                    return ResponseEntity.ok(ApiResponse.success("No versions available for the application", versions));
                }
                return ResponseEntity.ok(ApiResponse.success("Versions retrieved successfully", versions));
            } catch (NotFoundException nf) {
                return ResponseEntity.status(404).body(ApiResponse.failure(nf.getMessage()));
            } catch (ForbiddenException fb) {
                return ResponseEntity.status(403).body(ApiResponse.failure(fb.getMessage()));
            } catch (DataAccessException dae) {
                return ResponseEntity.status(500).body(ApiResponse.failure("Database failure: " + dae.getMessage()));
            } catch (Exception ex) {
                return ResponseEntity.status(500).body(ApiResponse.failure("Unexpected error: " + ex.getMessage()));
            }
        }

        @PostMapping("/{appId}/versions/{versionNumber}/restore")
        @PreAuthorize("hasAnyRole('TOOL_ADMIN','PROJECT_ADMIN','ADMIN')")
        public ResponseEntity<ApiResponse<RestoreResultDto>> restore(
                @PathVariable("appId") UUID appId,
                @PathVariable("versionNumber") @NotNull @Min(1) Integer versionNumber,
                Principal principal) {
            String actor = principal != null ? principal.getName() : "UNKNOWN";
            try {
                RestoreResultDto result = service.restoreVersion(appId, versionNumber, actor);
                return ResponseEntity.ok(ApiResponse.success("Restore completed successfully", result));
            } catch (NotFoundException nf) {
                return ResponseEntity.status(404).body(ApiResponse.failure(nf.getMessage()));
            } catch (ConflictException cf) {
                return ResponseEntity.status(409).body(ApiResponse.failure(cf.getMessage()));
            } catch (ForbiddenException fb) {
                return ResponseEntity.status(403).body(ApiResponse.failure(fb.getMessage()));
            } catch (DataAccessException dae) {
                return ResponseEntity.status(500).body(ApiResponse.failure("Database failure: " + dae.getMessage()));
            } catch (Exception ex) {
                return ResponseEntity.status(500).body(ApiResponse.failure("Unexpected restoration failure: " + ex.getMessage()));
            }
        }
    }

    @Service
    static class AppVersionService {
        private final AppRepository appRepository;
        private final AppVersionRepository versionRepository;
        private final UserService userService;
        private final AuditService auditService;

        AppVersionService(AppRepository appRepository,
                          AppVersionRepository versionRepository,
                          UserService userService,
                          AuditService auditService) {
            this.appRepository = appRepository;
            this.versionRepository = versionRepository;
            this.userService = userService;
            this.auditService = auditService;
        }

        public List<AppVersionDto> listVersions(UUID appId) {
            // Validate app exists.
            AppModel app = appRepository.findById(appId).orElseThrow(() -> new NotFoundException("Application not found"));

            // Authorization: requirement says app-level authorization should be applied.
            // ASSUMPTION: repo lacks app authorization model; we enforce authenticated access only.
            // If an app-level ACL exists, integrate it here.
            // Example: check that current user can access the app.
            if (userService.getCurrentUser().isEmpty()) {
                throw new ForbiddenException("Unauthorized");
            }

            List<AppVersionModel> models = versionRepository.findValidVersionsByAppId(appId);
            // Latest first as required.
            models.sort(Comparator.comparingInt(AppVersionModel::versionNumber).reversed());

            int active = app.activeVersion();
            List<AppVersionDto> out = new ArrayList<>(models.size());
            for (AppVersionModel m : models) {
                out.add(new AppVersionDto(
                        m.versionNumber(),
                        m.createdAt(),
                        m.createdBy(),
                        m.versionNumber() == active
                ));
            }
            return out;
        }

        @Transactional(isolation = Isolation.SERIALIZABLE)
        public RestoreResultDto restoreVersion(UUID appId, int versionNumber, String actorUsername) {
            // Validate user exists (optional) and authenticated.
            Optional<UserModel> currentUser = userService.getCurrentUser();
            if (currentUser.isEmpty()) {
                // Controller is already protected by PreAuthorize; keep this for defense-in-depth.
                throw new ForbiddenException("Unauthorized");
            }

            // Validate app exists and lock it to avoid concurrent restore conflicts.
            AppModel app = appRepository.findByIdForUpdate(appId)
                    .orElseThrow(() -> new NotFoundException("Application not found"));

            // Validate version belongs to app and is accessible.
            AppVersionModel toRestore = versionRepository.findByAppIdAndVersion(appId, versionNumber)
                    .orElseThrow(() -> new NotFoundException("Version not found"));
            if (toRestore.deleted()) {
                throw new ConflictException("Cannot restore a deleted/invalid version");
            }

            int currentActive = app.activeVersion();
            if (currentActive == versionNumber) {
                throw new ConflictException("Requested version is already active");
            }

            // Preserve audit/version information: snapshot current state into a new version.
            // ASSUMPTION: the current active snapshot is stored in app_versions.
            String currentSnapshot = versionRepository.findSnapshotJson(appId, currentActive)
                    .orElseThrow(() -> new ConflictException("Current active snapshot is missing; cannot preserve before restore"));

            int newVersion = currentActive + 1;
            try {
                versionRepository.insertVersionSnapshot(appId, newVersion, currentSnapshot, actorUsername, OffsetDateTime.now());
            } catch (DuplicateKeyException dke) {
                // Another restore may have happened; treat as conflict.
                throw new ConflictException("Restore conflict; please retry");
            }

            // Restore selected snapshot into the new active version number.
            // ASSUMPTION: "restoring" means setting apps.active_version to selected version.
            // Additionally, a real system would apply snapshot_json into various tables.
            // We represent restoration consistency by a single atomic update under SERIALIZABLE.
            appRepository.updateActiveVersion(appId, versionNumber);

            // Audit/logging per story.
            String auditTarget = "appId=" + appId + ", restoredVersion=" + versionNumber;
            auditService.logAction("APP_VERSION_RESTORE", auditTarget + ", user=" + actorUsername);

            return new RestoreResultDto(appId, versionNumber, currentActive, actorUsername, OffsetDateTime.now());
        }
    }

    // --- Repositories (JdbcTemplate) ---

    @Repository
    static class AppRepository {
        private final JdbcTemplate jdbc;

        AppRepository(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        // ASSUMPTION: not found in repo content
        private final RowMapper<AppModel> mapper = (rs, rn) -> new AppModel(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getInt("active_version")
        );

        Optional<AppModel> findById(UUID appId) {
            String sql = "SELECT id, name, active_version FROM dpai.apps WHERE id = ?";
            List<AppModel> list = jdbc.query(sql, mapper, appId);
            return list.stream().findFirst();
        }

        Optional<AppModel> findByIdForUpdate(UUID appId) {
            // Locks the row to prevent concurrent restores from interleaving.
            String sql = "SELECT id, name, active_version FROM dpai.apps WHERE id = ? FOR UPDATE";
            List<AppModel> list = jdbc.query(sql, mapper, appId);
            return list.stream().findFirst();
        }

        void updateActiveVersion(UUID appId, int activeVersion) {
            String sql = "UPDATE dpai.apps SET active_version = ? WHERE id = ?";
            int updated = jdbc.update(sql, activeVersion, appId);
            if (updated == 0) {
                throw new NotFoundException("Application not found");
            }
        }
    }

    @Repository
    static class AppVersionRepository {
        private final JdbcTemplate jdbc;

        AppVersionRepository(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        // ASSUMPTION: not found in repo content
        private final RowMapper<AppVersionModel> versionMapper = (rs, rn) -> new AppVersionModel(
                rs.getObject("app_id", UUID.class),
                rs.getInt("version_number"),
                rs.getString("snapshot_json"),
                rs.getString("created_by"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getBoolean("deleted")
        );

        List<AppVersionModel> findValidVersionsByAppId(UUID appId) {
            // Exclude deleted/invalid.
            String sql = """
                    SELECT app_id, version_number, snapshot_json, created_by, created_at, deleted
                      FROM dpai.app_versions
                     WHERE app_id = ? AND deleted = false
                    """;
            return jdbc.query(sql, versionMapper, appId);
        }

        Optional<AppVersionModel> findByAppIdAndVersion(UUID appId, int versionNumber) {
            String sql = """
                    SELECT app_id, version_number, snapshot_json, created_by, created_at, deleted
                      FROM dpai.app_versions
                     WHERE app_id = ? AND version_number = ?
                    """;
            List<AppVersionModel> list = jdbc.query(sql, versionMapper, appId, versionNumber);
            return list.stream().findFirst();
        }

        Optional<String> findSnapshotJson(UUID appId, int versionNumber) {
            String sql = "SELECT snapshot_json FROM dpai.app_versions WHERE app_id = ? AND version_number = ?";
            List<String> list = jdbc.queryForList(sql, String.class, appId, versionNumber);
            return list.stream().findFirst();
        }

        void insertVersionSnapshot(UUID appId, int versionNumber, String snapshotJson, String createdBy, OffsetDateTime createdAt) {
            String sql = """
                    INSERT INTO dpai.app_versions (app_id, version_number, snapshot_json, created_by, created_at, deleted)
                    VALUES (?, ?, ?, ?, ?, false)
                    """;
            jdbc.update(sql, appId, versionNumber, snapshotJson, createdBy, createdAt);
        }
    }

    // --- Models / DTOs ---

    // ASSUMPTION: not found in repo content
    static record AppModel(UUID id, String name, int activeVersion) {}

    // ASSUMPTION: not found in repo content
    static record AppVersionModel(UUID appId,
                                  int versionNumber,
                                  String snapshotJson,
                                  String createdBy,
                                  OffsetDateTime createdAt,
                                  boolean deleted) {
    }

    static record AppVersionDto(int versionNumber,
                                OffsetDateTime createdAt,
                                String createdBy,
                                boolean active) {
    }

    static record RestoreResultDto(UUID appId,
                                   int restoredVersion,
                                   int previousActiveVersion,
                                   String restoredBy,
                                   OffsetDateTime restoredAt) {
    }

    // --- Exceptions ---

    static class NotFoundException extends RuntimeException {
        NotFoundException(String message) { super(message); }
    }

    static class ForbiddenException extends RuntimeException {
        ForbiddenException(String message) { super(message); }
    }

    static class ConflictException extends RuntimeException {
        ConflictException(String message) { super(message); }
    }
}

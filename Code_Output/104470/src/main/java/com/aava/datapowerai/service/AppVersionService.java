package com.aava.datapowerai.service;

import com.aava.datapowerai.dto.app.AppVersionDTOs;
import com.aava.datapowerai.model.AppModel;
import com.aava.datapowerai.model.AppVersionModel;
import com.aava.datapowerai.repository.AppRepositoryJdbc;
import com.aava.datapowerai.repository.AppVersionRepositoryJdbc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Story 104470: App Version Management / Version History – Backend
 */
// ASSUMPTION: not found in repo content
@Service
public class AppVersionService {

    private static final Logger log = LoggerFactory.getLogger(AppVersionService.class);

    private final AppRepositoryJdbc appRepositoryJdbc;
    private final AppVersionRepositoryJdbc appVersionRepositoryJdbc;
    private final AppAuthorizationService appAuthorizationService;
    private final AuditService auditService;
    private final UserService userService;

    public AppVersionService(AppRepositoryJdbc appRepositoryJdbc,
                             AppVersionRepositoryJdbc appVersionRepositoryJdbc,
                             AppAuthorizationService appAuthorizationService,
                             AuditService auditService,
                             UserService userService) {
        this.appRepositoryJdbc = appRepositoryJdbc;
        this.appVersionRepositoryJdbc = appVersionRepositoryJdbc;
        this.appAuthorizationService = appAuthorizationService;
        this.auditService = auditService;
        this.userService = userService;
    }

    public AppVersionDTOs.AppVersionsResponse listVersions(String appId, Authentication authentication) {
        if (!appAuthorizationService.canView(authentication)) {
            throw new SecurityException("Forbidden");
        }

        Optional<AppModel> app = appRepositoryJdbc.findById(appId);
        if (app.isEmpty()) {
            throw new IllegalArgumentException("APP_NOT_FOUND");
        }

        List<AppVersionModel> versions = appVersionRepositoryJdbc.findValidVersionsByAppId(appId);
        Integer currentVersion = app.get().getCurrentVersion();

        List<AppVersionDTOs.AppVersionInfo> info = versions.stream()
                .map(v -> new AppVersionDTOs.AppVersionInfo(
                        v.getVersionNumber(),
                        v.getCreatedAt(),
                        v.getCreatedBy(),
                        currentVersion != null && currentVersion == v.getVersionNumber()
                ))
                .toList();

        return new AppVersionDTOs.AppVersionsResponse(appId, info);
    }

    @Transactional
    public AppVersionDTOs.RestoreResponse restoreVersion(String appId, int versionNumber, Authentication authentication) {
        if (!appAuthorizationService.canRestore(authentication)) {
            throw new SecurityException("Forbidden");
        }

        Optional<AppModel> app = appRepositoryJdbc.findById(appId);
        if (app.isEmpty()) {
            throw new IllegalArgumentException("APP_NOT_FOUND");
        }

        // lock app row for safe concurrent restores
        appVersionRepositoryJdbc.lockAppRowForUpdate(appId);

        Optional<AppVersionModel> version = appVersionRepositoryJdbc.findValidByAppIdAndVersion(appId, versionNumber);
        if (version.isEmpty()) {
            throw new IllegalArgumentException("VERSION_NOT_FOUND");
        }

        String actor = userService.getCurrentUser().map(u -> u.getUsername()).orElse("UNKNOWN");

        try {
            // restore app current state
            Optional<AppModel> updated = appRepositoryJdbc.updateCurrentState(appId, versionNumber, version.get().getConfigJson());
            if (updated.isEmpty()) {
                throw new IllegalStateException("Restore conflict or app not found");
            }

            auditService.logAction(
                    "APP_VERSION_RESTORE",
                    "appId=" + appId + ",version=" + versionNumber + ",user=" + actor + ",status=SUCCESS"
            );

            return new AppVersionDTOs.RestoreResponse(appId, versionNumber, true);

        } catch (DataAccessException dae) {
            auditService.logAction(
                    "APP_VERSION_RESTORE",
                    "appId=" + appId + ",version=" + versionNumber + ",user=" + actor + ",status=FAILED,reason=" + dae.getClass().getSimpleName()
            );
            throw dae;
        } catch (RuntimeException ex) {
            auditService.logAction(
                    "APP_VERSION_RESTORE",
                    "appId=" + appId + ",version=" + versionNumber + ",user=" + actor + ",status=FAILED,reason=" + ex.getMessage()
            );
            throw ex;
        }
    }
}

package com.aava.datapowerai.controller;

import com.aava.datapowerai.dto.app.AppVersionDTOs;
import com.aava.datapowerai.dto.common.ApiResponse;
import com.aava.datapowerai.service.AppVersionService;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * Story 104470: App Version Management / Version History – Backend
 */
// ASSUMPTION: not found in repo content
@RestController
@RequestMapping("/api/apps")
@Validated
public class AppVersionController {

    private final AppVersionService appVersionService;

    public AppVersionController(AppVersionService appVersionService) {
        this.appVersionService = appVersionService;
    }

    @GetMapping("/{appId}/versions")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<AppVersionDTOs.AppVersionsResponse>> listVersions(
            @PathVariable("appId") String appId,
            Authentication authentication) {
        try {
            AppVersionDTOs.AppVersionsResponse response = appVersionService.listVersions(appId, authentication);

            if (response.versions() == null || response.versions().isEmpty()) {
                return ResponseEntity.ok(ApiResponse.success("No versions available", response));
            }

            return ResponseEntity.ok(ApiResponse.success("Versions retrieved successfully", response));

        } catch (SecurityException se) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.failure("Forbidden"));
        } catch (IllegalArgumentException iae) {
            if ("APP_NOT_FOUND".equals(iae.getMessage())) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.failure("Application not found"));
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.failure("Invalid request: " + iae.getMessage()));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.failure("Failed to retrieve versions: " + ex.getMessage()));
        }
    }

    @PostMapping("/{appId}/versions/{versionNumber}/restore")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<AppVersionDTOs.RestoreResponse>> restore(
            @PathVariable("appId") String appId,
            @PathVariable("versionNumber") int versionNumber,
            Authentication authentication) {

        try {
            AppVersionDTOs.RestoreResponse restored = appVersionService.restoreVersion(appId, versionNumber, authentication);
            return ResponseEntity.ok(ApiResponse.success("Version restored successfully", restored));

        } catch (SecurityException se) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.failure("Forbidden"));
        } catch (IllegalArgumentException iae) {
            if ("APP_NOT_FOUND".equals(iae.getMessage())) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.failure("Application not found"));
            }
            if ("VERSION_NOT_FOUND".equals(iae.getMessage())) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.failure("Version not found"));
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.failure("Invalid request: " + iae.getMessage()));
        } catch (DataAccessException dae) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.failure("Database error during restore"));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.failure("Unexpected restore failure: " + ex.getMessage()));
        }
    }
}

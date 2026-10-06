package com.aava.datapowerai.dto.app;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Story 104470: App Version Management / Version History – Backend
 *
 * DTOs for app version history and restore operations.
 */
// ASSUMPTION: not found in repo content
public final class AppVersionDTOs {
    private AppVersionDTOs() {}

    @Schema(description = "Single version metadata for an app")
    public record AppVersionInfo(
            @Schema(description = "Version number", example = "5")
            int versionNumber,
            @Schema(description = "When this version was created")
            OffsetDateTime createdAt,
            @Schema(description = "Who created this version")
            String createdBy,
            @Schema(description = "Whether this version is currently active")
            boolean active
    ) {}

    @Schema(description = "Response payload for listing all versions")
    public record AppVersionsResponse(
            @Schema(description = "Application id")
            String appId,
            @Schema(description = "All available versions, latest first")
            List<AppVersionInfo> versions
    ) {}

    @Schema(description = "Response payload for restore operation")
    public record RestoreResponse(
            @Schema(description = "Application id")
            String appId,
            @Schema(description = "Restored version number")
            int restoredVersionNumber,
            @Schema(description = "Whether the restore succeeded")
            boolean restored
    ) {}
}

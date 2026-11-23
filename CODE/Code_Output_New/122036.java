package com.ascendion.datapowerAI.controller;

/*
Story ID: 122036
Title: Control App Creation Based on Environment Configuration
Chosen language: Java — the repo is a Spring Boot Maven codebase and this story changes the existing app creation endpoint/service behavior in that backend.
What this code does: Adds a DB-driven feature flag gate for app creation. When app.creation.enabled=false, app creation is blocked for everyone, including TOOL_ADMIN; when true, existing role-based authorization remains unchanged.
Assumptions:
- Comments/discussion/attachments were not returned by the work item tool; implementation is based on the returned fields.
- application_properties is already surfaced through GenericConfigurationService and ApplicationPropertiesLoader into Spring Environment.
- Existing AppController and AppService classes remain in the repo; this single-file deliverable shows the required controller/service changes and the needed property helper in one file.
- The property is read from Spring Environment key app.creation.enabled and defaults to true when missing to preserve current lower-environment behavior.
- If your repo requires one public top-level type per file, move each class in this file to its normal package path with matching filenames.
SQL note:
-- application_properties example
-- key: app.creation.enabled
-- value: true|false
*/

import com.ascendion.datapowerAI.dto.ApiResponseGenric;
import com.ascendion.datapowerAI.dto.AppRequestDto;
import com.ascendion.datapowerAI.dto.AppResponseDto;
import com.ascendion.datapowerAI.entity.App;
import com.ascendion.datapowerAI.enums.ApiErrorType;
import com.ascendion.datapowerAI.enums.RoleType;
import com.ascendion.datapowerAI.exception.ApiResponseUtil;
import com.ascendion.datapowerAI.exception.ForbiddenException;
import com.ascendion.datapowerAI.exception.ResourceNotFoundException;
import com.ascendion.datapowerAI.mapper.AppMapper;
import com.ascendion.datapowerAI.repository.AppRepository;
import com.ascendion.datapowerAI.utility.AuditUtil;
import com.ascendion.datapowerAI.utility.PreAuthorize;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(
        name = "Application Management",
        description = "Core CRUD operations for DataPowerAI applications. Manage app lifecycle, metadata, and permissions."
)
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/apps")
@RequiredArgsConstructor
@Slf4j
class AppCreationFeatureFlagController {

    private final AppCreationFeatureFlagService appService;

    /*
     * GLOBAL operation.
     *
     * Only TOOL_ADMIN can create a new app.
     * Additionally, creation can now be disabled globally by
     * DB-driven property app.creation.enabled.
     */
    @PreAuthorize(RoleType.TOOL_ADMIN)
    @Operation(
            summary = "Create a new application",
            description = "Creates a new DataPowerAI application when app.creation.enabled is true. When the flag is false, app creation is blocked for all users, including TOOL_ADMIN."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Application created successfully",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResponseGenric.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request body or validation failed"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized - JWT token missing or invalid"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden - requires TOOL_ADMIN role or app creation is disabled"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @PostMapping
    public ResponseEntity<ApiResponseGenric<AppResponseDto>> createApp(
            @Valid @RequestBody AppRequestDto dto,
            HttpServletRequest request
    ) {

        AppResponseDto created =
                appService.createApp(dto);

        return ApiResponseUtil.created(
                created,
                "App created successfully"
        );
    }
}

@Service
@RequiredArgsConstructor
@Transactional
class AppCreationFeatureFlagService {

    private static final String APP_CREATION_ENABLED_PROPERTY = "app.creation.enabled";

    private final AppRepository appRepository;
    private final AppCreationFeatureFlagPropertyService propertyService;

    public AppResponseDto createApp(AppRequestDto dto) {

        enforceAppCreationEnabled();

        validateRequest(dto);

        if (appRepository.existsByNameIgnoreCase(dto.getName().trim())) {
            throw new ForbiddenException(
                    ApiErrorType.ACCESS_DENIED,
                    "App with the same name already exists"
            );
        }

        App app = AppMapper.toEntity(dto);
        AuditUtil.applyCreateAudit(app);

        App savedApp = appRepository.save(app);

        return AppMapper.toDto(savedApp);
    }

    private void enforceAppCreationEnabled() {

        if (!propertyService.isAppCreationEnabled()) {
            throw new ForbiddenException(
                    ApiErrorType.ACCESS_DENIED,
                    "App creation is disabled for this environment"
            );
        }
    }

    private void validateRequest(AppRequestDto dto) {

        if (dto == null) {
            throw new ResourceNotFoundException(
                    ApiErrorType.INVALID_REQUEST,
                    "App request is required"
            );
        }

        String trimmedName = dto.getName() == null ? null : dto.getName().trim();

        if (trimmedName == null || trimmedName.isBlank()) {
            throw new ResourceNotFoundException(
                    ApiErrorType.INVALID_REQUEST,
                    "App name is required"
            );
        }
    }
}

@Component
@RequiredArgsConstructor
class AppCreationFeatureFlagPropertyService {

    private static final String APP_CREATION_ENABLED_PROPERTY = "app.creation.enabled";

    private final Environment environment;

    public boolean isAppCreationEnabled() {
        return Boolean.parseBoolean(
                environment.getProperty(APP_CREATION_ENABLED_PROPERTY, "true")
        );
    }
}

// ASSUMPTION: not found in repo content
class AppCreationFeatureFlagRequestDto {

    @NotBlank(message = "App name is required")
    @Size(min = 3, max = 50, message = "App name must be 3–50 characters")
    @Pattern(
            regexp = "^[A-Za-z0-9 _-]+$",
            message = "App name can contain only letters, numbers, spaces, hyphens, and underscores"
    )
    private String name;

    @Size(max = 200, message = "Description must be less than 200 characters")
    private String description;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}

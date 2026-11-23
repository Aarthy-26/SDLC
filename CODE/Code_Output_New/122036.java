/*
Story ID: 122036
Title: Control App Creation Based on Environment Configuration
Language: Java — the story describes an enterprise service feature using application properties and role-based authorization in a corporate backend context, and no repo context was provided.
What this code does: Adds a single-file, Spring-style implementation that enforces a DB-driven feature flag named `app.creation.enabled` before allowing app creation. When the flag is false, creation is blocked for every role; when true, existing TOOL_ADMIN authorization behavior is preserved.
Assumptions:
- No repo link was provided, so repo conventions could not be inspected.
- The existing system uses a DB-driven `application_properties` table and Spring-style dependency injection.
- Existing app creation behavior is represented here by TOOL_ADMIN authorization on the controller and service flow.
- Supporting repository/entity/DTO classes required by the story are included in this file as assumptions.
*/

package com.aava.datastudio.appcreation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.data.jpa.repository.JpaRepository;

final class AppCreationFeatureControl {

    private AppCreationFeatureControl() {
    }

    static final String APP_CREATION_ENABLED_PROPERTY = "app.creation.enabled";

    @RestController
    @RequestMapping("/api/apps")
    static class AppCreationController {
        private final AppCreationService appCreationService;

        AppCreationController(final AppCreationService appCreationService) {
            this.appCreationService = appCreationService;
        }

        @PostMapping
        @PreAuthorize("hasRole('TOOL_ADMIN')")
        ResponseEntity<AppCreationResponse> createApp(@Valid @RequestBody final CreateAppRequest request) {
            final AppCreationResponse response = appCreationService.createApp(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        }
    }

    @Service
    static class AppCreationService {
        private final ApplicationPropertyRepository applicationPropertyRepository;
        private final AppRepository appRepository;

        AppCreationService(
                final ApplicationPropertyRepository applicationPropertyRepository,
                final AppRepository appRepository
        ) {
            this.applicationPropertyRepository = applicationPropertyRepository;
            this.appRepository = appRepository;
        }

        AppCreationResponse createApp(final CreateAppRequest request) {
            enforceAppCreationEnabled();

            final AppEntity appEntity = new AppEntity();
            appEntity.setId(buildAppId(request.name()));
            appEntity.setName(request.name().trim());
            appEntity.setDescription(request.description() == null ? null : request.description().trim());
            appEntity.setCreatedAt(Instant.now());

            final AppEntity saved = appRepository.save(appEntity);
            return new AppCreationResponse(saved.getId(), saved.getName(), saved.getDescription(), saved.getCreatedAt());
        }

        private void enforceAppCreationEnabled() {
            final boolean enabled = applicationPropertyRepository
                    .findByPropertyKey(APP_CREATION_ENABLED_PROPERTY)
                    .map(ApplicationPropertyEntity::getPropertyValue)
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .map(Boolean::parseBoolean)
                    .orElse(true);

            if (!enabled) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "App creation is disabled by configuration property 'app.creation.enabled'."
                );
            }
        }

        private String buildAppId(final String appName) {
            final String normalized = appName.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-");
            final String compact = normalized.replaceAll("(^-+|-+$)", "");
            return compact.isEmpty() ? "app-" + Instant.now().toEpochMilli() : compact;
        }
    }

    // ASSUMPTION: not found in repo content
    interface ApplicationPropertyRepository extends JpaRepository<ApplicationPropertyEntity, String> {
        Optional<ApplicationPropertyEntity> findByPropertyKey(String propertyKey);
    }

    // ASSUMPTION: not found in repo content
    interface AppRepository extends JpaRepository<AppEntity, String> {
    }

    // ASSUMPTION: not found in repo content
    @Entity
    @Table(name = "application_properties")
    static class ApplicationPropertyEntity {
        @Id
        @Column(name = "property_key", nullable = false, length = 150)
        private String propertyKey;

        @Column(name = "property_value", nullable = false, length = 500)
        private String propertyValue;

        String getPropertyKey() {
            return propertyKey;
        }

        void setPropertyKey(final String propertyKey) {
            this.propertyKey = propertyKey;
        }

        String getPropertyValue() {
            return propertyValue;
        }

        void setPropertyValue(final String propertyValue) {
            this.propertyValue = propertyValue;
        }
    }

    // ASSUMPTION: not found in repo content
    @Entity
    @Table(name = "apps")
    static class AppEntity {
        @Id
        @Column(name = "id", nullable = false, length = 120)
        private String id;

        @Column(name = "name", nullable = false, length = 255)
        private String name;

        @Column(name = "description")
        private String description;

        @Column(name = "created_at", nullable = false)
        private Instant createdAt;

        String getId() {
            return id;
        }

        void setId(final String id) {
            this.id = id;
        }

        String getName() {
            return name;
        }

        void setName(final String name) {
            this.name = name;
        }

        String getDescription() {
            return description;
        }

        void setDescription(final String description) {
            this.description = description;
        }

        Instant getCreatedAt() {
            return createdAt;
        }

        void setCreatedAt(final Instant createdAt) {
            this.createdAt = createdAt;
        }
    }

    // ASSUMPTION: not found in repo content
    record CreateAppRequest(
            @NotBlank(message = "name is required")
            String name,
            String description
    ) {
        CreateAppRequest {
            Objects.requireNonNull(name, "name cannot be null");
        }
    }

    // ASSUMPTION: not found in repo content
    record AppCreationResponse(
            String id,
            String name,
            String description,
            Instant createdAt
    ) {
    }

    // ASSUMPTION: not found in repo content
    static class AppCreationDisabledException extends AccessDeniedException {
        AppCreationDisabledException(final String message) {
            super(message);
        }
    }
}

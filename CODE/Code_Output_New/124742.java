package com.ascendion.datapowerAI.controller;

/*
Story ID: 124742
Title: Add Database Support for Sculpt App
Language: Java — the repo is a Spring Boot Maven service with Java controllers/services/repositories and the story is a backend database CRUD feature for that codebase.
What this code does: Adds a single-file Spring-style controller, service, repository contract, DTOs, SQL constants, and validation for blueprint CRUD against a configured database schema, including jsonb handling, generated bigint id support, named parameters, and not-found/failure handling.
Assumptions:
- This single-file deliverable is repo-style and intended to live under the existing Spring Boot application package structure.
- The blueprint table exists in the configured database/schema and contains at least: id, commission_name, task_order, task_name, task_description, human_in_loop, thinking_category.
- task_description is stored as PostgreSQL jsonb.
- Audit timestamps use database defaults and are therefore omitted from insert/update statements.
- Because Java normally requires the public type name to match the file name, keep the controller non-public if saved as 124742.java, or rename the file to SculptBlueprintDatabaseController.java when integrating.
- Supporting DTOs/service/repository types below are included in this one file because only one file is allowed.
- SQL DDL reference:
  CREATE TABLE blueprint (
      id BIGSERIAL PRIMARY KEY,
      commission_name VARCHAR(255) NOT NULL,
      task_order VARCHAR(255),
      task_name VARCHAR(255),
      task_description JSONB,
      human_in_loop BOOLEAN,
      thinking_category VARCHAR(255),
      created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
      updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
  );
*/

import com.ascendion.datapowerAI.dto.ApiResponseGenric;
import com.ascendion.datapowerAI.enums.RoleType;
import com.ascendion.datapowerAI.exception.ApiResponseUtil;
import com.ascendion.datapowerAI.exception.BadRequestException;
import com.ascendion.datapowerAI.exception.ResourceNotFoundException;
import com.ascendion.datapowerAI.utility.PreAuthorize;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.GeneratedKeyHolder;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/sculpt/blueprints")
@RequiredArgsConstructor
class SculptBlueprintDatabaseController {

    // ASSUMPTION: not found in repo content
    private final BlueprintPersistenceService blueprintPersistenceService;

    @PreAuthorize(RoleType.APP_VIEWER)
    @GetMapping
    public ResponseEntity<ApiResponseGenric<List<BlueprintResponseDto>>> getBlueprintsByCommissionName(
            @RequestParam("commissionName") String commissionName) {

        return ApiResponseUtil.ok(
                blueprintPersistenceService.getBlueprintsByCommissionName(commissionName),
                "Blueprints fetched successfully"
        );
    }

    @PreAuthorize(RoleType.APP_EDITOR)
    @PostMapping
    public ResponseEntity<ApiResponseGenric<BlueprintResponseDto>> createBlueprint(
            @Valid @RequestBody BlueprintCreateRequestDto request) {

        return ApiResponseUtil.created(
                blueprintPersistenceService.createBlueprint(request),
                "Blueprint created successfully"
        );
    }

    @PreAuthorize(RoleType.APP_EDITOR)
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponseGenric<BlueprintResponseDto>> updateBlueprint(
            @PathVariable("id") Long id,
            @Valid @RequestBody BlueprintUpdateRequestDto request) {

        return ApiResponseUtil.ok(
                blueprintPersistenceService.updateBlueprint(id, request),
                "Blueprint updated successfully"
        );
    }

    @PreAuthorize(RoleType.APP_EDITOR)
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponseGenric<Void>> deleteBlueprint(@PathVariable("id") Long id) {

        blueprintPersistenceService.deleteBlueprint(id);
        return ApiResponseUtil.ok(null, "Blueprint deleted successfully");
    }
}

// ASSUMPTION: not found in repo content
@org.springframework.stereotype.Service
@RequiredArgsConstructor
class BlueprintPersistenceService {

    private final BlueprintRepository blueprintRepository;

    public List<BlueprintResponseDto> getBlueprintsByCommissionName(String commissionName) {
        validateCommissionName(commissionName);
        List<BlueprintResponseDto> results = blueprintRepository.findByCommissionName(commissionName.trim());
        if (results.isEmpty()) {
            throw new ResourceNotFoundException("No blueprint records found for commission_name: " + commissionName.trim());
        }
        return results;
    }

    public BlueprintResponseDto createBlueprint(BlueprintCreateRequestDto request) {
        validateCreateRequest(request);
        return blueprintRepository.insert(request);
    }

    public BlueprintResponseDto updateBlueprint(Long id, BlueprintUpdateRequestDto request) {
        validateId(id);
        validateUpdateRequest(request);
        if (!blueprintRepository.existsById(id)) {
            throw new ResourceNotFoundException("Blueprint record not found for id: " + id);
        }
        BlueprintResponseDto updated = blueprintRepository.update(id, request);
        if (updated == null) {
            throw new ResourceNotFoundException("Blueprint record not found for id: " + id);
        }
        return updated;
    }

    public void deleteBlueprint(Long id) {
        validateId(id);
        if (!blueprintRepository.deleteById(id)) {
            throw new ResourceNotFoundException("Blueprint record not found for id: " + id);
        }
    }

    private void validateCommissionName(String commissionName) {
        if (commissionName == null || commissionName.trim().isEmpty()) {
            throw new BadRequestException("commissionName is required");
        }
    }

    private void validateId(Long id) {
        if (id == null || id <= 0L) {
            throw new BadRequestException("id must be a positive bigint value");
        }
    }

    private void validateCreateRequest(BlueprintCreateRequestDto request) {
        if (request == null) {
            throw new BadRequestException("Request body is required");
        }
        validateCommissionName(request.getCommissionName());
        if (request.getTaskDescription() == null) {
            throw new BadRequestException("taskDescription is required and must be a valid JSON object or array");
        }
        if (request.getThinkingCategory() == null || request.getThinkingCategory().trim().isEmpty()) {
            throw new BadRequestException("thinkingCategory is required during creation");
        }
    }

    private void validateUpdateRequest(BlueprintUpdateRequestDto request) {
        if (request == null) {
            throw new BadRequestException("Request body is required");
        }
        validateCommissionName(request.getCommissionName());
        if (request.getTaskDescription() == null) {
            throw new BadRequestException("taskDescription is required and must be a valid JSON object or array");
        }
    }
}

// ASSUMPTION: not found in repo content
interface BlueprintRepository {
    List<BlueprintResponseDto> findByCommissionName(String commissionName);
    BlueprintResponseDto insert(BlueprintCreateRequestDto request);
    BlueprintResponseDto update(Long id, BlueprintUpdateRequestDto request);
    boolean deleteById(Long id);
    boolean existsById(Long id);
}

// ASSUMPTION: not found in repo content
@org.springframework.stereotype.Repository
@RequiredArgsConstructor
class JdbcBlueprintRepository implements BlueprintRepository {

    private static final String TABLE_NAME = "blueprint";

    private static final String SELECT_BY_COMMISSION_NAME_SQL = """
            SELECT id,
                   commission_name,
                   task_order,
                   task_name,
                   task_description,
                   human_in_loop,
                   thinking_category
            FROM blueprint
            WHERE commission_name = :commissionName
            ORDER BY id
            """;

    private static final String SELECT_BY_ID_SQL = """
            SELECT id,
                   commission_name,
                   task_order,
                   task_name,
                   task_description,
                   human_in_loop,
                   thinking_category
            FROM blueprint
            WHERE id = :id
            """;

    private static final String EXISTS_BY_ID_SQL = """
            SELECT COUNT(1)
            FROM blueprint
            WHERE id = :id
            """;

    private static final String INSERT_SQL = """
            INSERT INTO blueprint (
                commission_name,
                task_order,
                task_name,
                task_description,
                human_in_loop,
                thinking_category
            ) VALUES (
                :commissionName,
                :taskOrder,
                :taskName,
                CAST(:taskDescription AS jsonb),
                :humanInLoop,
                :thinkingCategory
            )
            """;

    private static final String UPDATE_SQL = """
            UPDATE blueprint
            SET commission_name = :commissionName,
                task_order = :taskOrder,
                task_name = :taskName,
                task_description = CAST(:taskDescription AS jsonb),
                human_in_loop = :humanInLoop
            WHERE id = :id
            """;

    private static final String DELETE_SQL = """
            DELETE FROM blueprint
            WHERE id = :id
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    private final RowMapper<BlueprintResponseDto> rowMapper = new RowMapper<>() {
        @Override
        public BlueprintResponseDto mapRow(ResultSet rs, int rowNum) throws SQLException {
            return BlueprintResponseDto.builder()
                    .id(rs.getLong("id"))
                    .commissionName(rs.getString("commission_name"))
                    .taskOrder(rs.getString("task_order"))
                    .taskName(rs.getString("task_name"))
                    .taskDescription(parseJson(rs.getString("task_description")))
                    .humanInLoop((Boolean) rs.getObject("human_in_loop"))
                    .thinkingCategory(rs.getString("thinking_category"))
                    .build();
        }
    };

    @Override
    public List<BlueprintResponseDto> findByCommissionName(String commissionName) {
        try {
            return jdbcTemplate.query(
                    SELECT_BY_COMMISSION_NAME_SQL,
                    new MapSqlParameterSource("commissionName", commissionName),
                    rowMapper
            );
        } catch (DataAccessException ex) {
            throw new BadRequestException("Failed to fetch blueprint records: " + ex.getMostSpecificCause().getMessage());
        }
    }

    @Override
    public BlueprintResponseDto insert(BlueprintCreateRequestDto request) {
        try {
            MapSqlParameterSource parameters = baseParameters()
                    .addValue("commissionName", request.getCommissionName())
                    .addValue("taskOrder", request.getTaskOrder())
                    .addValue("taskName", request.getTaskName())
                    .addValue("taskDescription", toJson(request.getTaskDescription()), Types.VARCHAR)
                    .addValue("humanInLoop", request.getHumanInLoop())
                    .addValue("thinkingCategory", request.getThinkingCategory());

            GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(INSERT_SQL, parameters, keyHolder, new String[]{"id"});
            Number generatedId = keyHolder.getKey();
            if (generatedId == null) {
                throw new BadRequestException("Blueprint record was inserted but generated id was not returned by the database");
            }
            return findOneById(generatedId.longValue());
        } catch (DataAccessException ex) {
            throw new BadRequestException("Failed to insert blueprint record: " + ex.getMostSpecificCause().getMessage());
        }
    }

    @Override
    public BlueprintResponseDto update(Long id, BlueprintUpdateRequestDto request) {
        try {
            MapSqlParameterSource parameters = baseParameters()
                    .addValue("id", id)
                    .addValue("commissionName", request.getCommissionName())
                    .addValue("taskOrder", request.getTaskOrder())
                    .addValue("taskName", request.getTaskName())
                    .addValue("taskDescription", toJson(request.getTaskDescription()), Types.VARCHAR)
                    .addValue("humanInLoop", request.getHumanInLoop());

            int updatedRows = jdbcTemplate.update(UPDATE_SQL, parameters);
            if (updatedRows == 0) {
                return null;
            }
            return findOneById(id);
        } catch (DataAccessException ex) {
            throw new BadRequestException("Failed to update blueprint record: " + ex.getMostSpecificCause().getMessage());
        }
    }

    @Override
    public boolean deleteById(Long id) {
        try {
            int deletedRows = jdbcTemplate.update(DELETE_SQL, new MapSqlParameterSource("id", id));
            return deletedRows > 0;
        } catch (DataAccessException ex) {
            throw new BadRequestException("Failed to delete blueprint record: " + ex.getMostSpecificCause().getMessage());
        }
    }

    @Override
    public boolean existsById(Long id) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    EXISTS_BY_ID_SQL,
                    new MapSqlParameterSource("id", id),
                    Integer.class
            );
            return count != null && count > 0;
        } catch (DataAccessException ex) {
            throw new BadRequestException("Failed to verify blueprint record existence: " + ex.getMostSpecificCause().getMessage());
        }
    }

    private BlueprintResponseDto findOneById(Long id) {
        List<BlueprintResponseDto> records = jdbcTemplate.query(
                SELECT_BY_ID_SQL,
                new MapSqlParameterSource("id", id),
                rowMapper
        );
        if (records.isEmpty()) {
            throw new ResourceNotFoundException("Blueprint record not found for id: " + id);
        }
        return records.getFirst();
    }

    private MapSqlParameterSource baseParameters() {
        return new MapSqlParameterSource();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BadRequestException("taskDescription must be valid JSON content");
        }
    }

    private Object parseJson(String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Object>() {});
        } catch (JsonProcessingException ex) {
            throw new SQLException("Failed to parse jsonb column task_description", ex);
        }
    }
}

// ASSUMPTION: not found in repo content
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
class BlueprintCreateRequestDto {
    @NotBlank
    private String commissionName;
    private String taskOrder;
    private String taskName;
    @NotNull
    private Object taskDescription;
    private Boolean humanInLoop;
    @NotBlank
    private String thinkingCategory;
}

// ASSUMPTION: not found in repo content
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
class BlueprintUpdateRequestDto {
    @NotBlank
    private String commissionName;
    private String taskOrder;
    private String taskName;
    @NotNull
    private Object taskDescription;
    private Boolean humanInLoop;
}

// ASSUMPTION: not found in repo content
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
class BlueprintResponseDto {
    private Long id;
    private String commissionName;
    private String taskOrder;
    private String taskName;
    private Object taskDescription;
    private Boolean humanInLoop;
    private String thinkingCategory;
}

Detected Language: Java | Test Framework: JUnit 5 + Mockito

| id | description | scenario type | expected output |
|---|---|---|---|
| 1 | Service fetches blueprints by trimmed commission name | happy path | Returns non-empty list from repository using trimmed commission name |
| 2 | Service rejects null commission name | edge case | Throws BadRequestException |
| 3 | Service rejects blank commission name | edge case | Throws BadRequestException |
| 4 | Service returns not found when repository returns empty list | negative | Throws ResourceNotFoundException |
| 5 | Service creates blueprint with valid request | happy path | Returns repository insert result |
| 6 | Service rejects null create request | negative | Throws BadRequestException |
| 7 | Service rejects create request with null taskDescription | edge case | Throws BadRequestException |
| 8 | Service rejects create request with blank thinkingCategory | edge case | Throws BadRequestException |
| 9 | Service updates blueprint when id exists and repository returns updated entity | happy path | Returns updated blueprint |
| 10 | Service rejects null id on update | edge case | Throws BadRequestException |
| 11 | Service rejects non-positive id on update | boundary | Throws BadRequestException |
| 12 | Service returns not found when update id does not exist | negative | Throws ResourceNotFoundException |
| 13 | Service returns not found when repository update returns null after existence check | negative | Throws ResourceNotFoundException |
| 14 | Service deletes blueprint when repository reports success | happy path | Completes without exception |
| 15 | Service returns not found when delete affects no rows | negative | Throws ResourceNotFoundException |
| 16 | Repository insert serializes JSON, captures generated id, and returns fetched row | transformation-level | Returns fetched BlueprintResponseDto with generated id |
| 17 | Repository insert fails when generated key is missing | negative | Throws BadRequestException |
| 18 | Repository update returns null when no row is updated | negative | Returns null |
| 19 | Repository deleteById returns true when delete count is positive | happy path | Returns true |
| 20 | Repository existsById returns false when count is zero | happy path | Returns false |
| 21 | Repository wraps data access exception during fetch | negative | Throws BadRequestException |
| 22 | Repository rejects invalid JSON serialization during insert | negative | Throws BadRequestException |
| 23 | Repository row mapper parses jsonb object payload correctly | transformation-level | Maps taskDescription to Java Map and all columns correctly |
| 24 | Repository row mapper returns null taskDescription for blank jsonb column | edge case | taskDescription is null |
| 25 | Repository row mapper raises SQLException for malformed jsonb column | negative | Throws SQLException |

Unit Test Code:
```java
package com.ascendion.datapowerAI.controller;

import com.ascendion.datapowerAI.exception.BadRequestException;
import com.ascendion.datapowerAI.exception.ResourceNotFoundException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.GeneratedKeyHolder;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.lang.reflect.Field;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SculptBlueprintDatabaseControllerTest {

    @Nested
    class BlueprintPersistenceServiceTest {

        @Mock
        private BlueprintRepository blueprintRepository;

        @InjectMocks
        private BlueprintPersistenceService service;

        private BlueprintCreateRequestDto validCreateRequest;
        private BlueprintUpdateRequestDto validUpdateRequest;
        private BlueprintResponseDto responseDto;

        @BeforeEach
        void setUp() {
            validCreateRequest = BlueprintCreateRequestDto.builder()
                    .commissionName(" Commission-A ")
                    .taskOrder("1")
                    .taskName("Task A")
                    .taskDescription(Map.of("step", "collect"))
                    .humanInLoop(Boolean.TRUE)
                    .thinkingCategory("analysis")
                    .build();

            validUpdateRequest = BlueprintUpdateRequestDto.builder()
                    .commissionName("Commission-A")
                    .taskOrder("2")
                    .taskName("Task B")
                    .taskDescription(List.of("a", "b"))
                    .humanInLoop(Boolean.FALSE)
                    .build();

            responseDto = BlueprintResponseDto.builder()
                    .id(101L)
                    .commissionName("Commission-A")
                    .taskOrder("1")
                    .taskName("Task A")
                    .taskDescription(Map.of("step", "collect"))
                    .humanInLoop(Boolean.TRUE)
                    .thinkingCategory("analysis")
                    .build();
        }

        @Test
        @DisplayName("1. fetches blueprints by trimmed commission name")
        void getBlueprintsByCommissionName_happyPath() {
            when(blueprintRepository.findByCommissionName("Commission-A")).thenReturn(List.of(responseDto));

            List<BlueprintResponseDto> results = service.getBlueprintsByCommissionName("  Commission-A  ");

            assertThat(results).containsExactly(responseDto);
            verify(blueprintRepository).findByCommissionName("Commission-A");
        }

        @Test
        @DisplayName("2. rejects null commission name")
        void getBlueprintsByCommissionName_rejectsNull() {
            assertThatThrownBy(() -> service.getBlueprintsByCommissionName(null))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("commissionName is required");

            verify(blueprintRepository, never()).findByCommissionName(any());
        }

        @Test
        @DisplayName("3. rejects blank commission name")
        void getBlueprintsByCommissionName_rejectsBlank() {
            assertThatThrownBy(() -> service.getBlueprintsByCommissionName("   "))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("commissionName is required");
        }

        @Test
        @DisplayName("4. returns not found when repository result is empty")
        void getBlueprintsByCommissionName_notFound() {
            when(blueprintRepository.findByCommissionName("Commission-A")).thenReturn(List.of());

            assertThatThrownBy(() -> service.getBlueprintsByCommissionName("Commission-A"))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("No blueprint records found for commission_name: Commission-A");
        }

        @Test
        @DisplayName("5. creates blueprint with valid request")
        void createBlueprint_happyPath() {
            when(blueprintRepository.insert(validCreateRequest)).thenReturn(responseDto);

            BlueprintResponseDto created = service.createBlueprint(validCreateRequest);

            assertThat(created).isEqualTo(responseDto);
            verify(blueprintRepository).insert(validCreateRequest);
        }

        @Test
        @DisplayName("6. rejects null create request")
        void createBlueprint_rejectsNullRequest() {
            assertThatThrownBy(() -> service.createBlueprint(null))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("Request body is required");
        }

        @Test
        @DisplayName("7. rejects create request with null taskDescription")
        void createBlueprint_rejectsNullTaskDescription() {
            BlueprintCreateRequestDto request = BlueprintCreateRequestDto.builder()
                    .commissionName("Commission-A")
                    .thinkingCategory("analysis")
                    .taskDescription(null)
                    .build();

            assertThatThrownBy(() -> service.createBlueprint(request))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("taskDescription is required and must be a valid JSON object or array");
        }

        @Test
        @DisplayName("8. rejects create request with blank thinkingCategory")
        void createBlueprint_rejectsBlankThinkingCategory() {
            BlueprintCreateRequestDto request = BlueprintCreateRequestDto.builder()
                    .commissionName("Commission-A")
                    .taskDescription(Map.of("k", "v"))
                    .thinkingCategory("   ")
                    .build();

            assertThatThrownBy(() -> service.createBlueprint(request))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("thinkingCategory is required during creation");
        }

        @Test
        @DisplayName("9. updates blueprint when id exists")
        void updateBlueprint_happyPath() {
            when(blueprintRepository.existsById(101L)).thenReturn(true);
            when(blueprintRepository.update(101L, validUpdateRequest)).thenReturn(responseDto);

            BlueprintResponseDto updated = service.updateBlueprint(101L, validUpdateRequest);

            assertThat(updated).isEqualTo(responseDto);
            verify(blueprintRepository).existsById(101L);
            verify(blueprintRepository).update(101L, validUpdateRequest);
        }

        @Test
        @DisplayName("10. rejects null id on update")
        void updateBlueprint_rejectsNullId() {
            assertThatThrownBy(() -> service.updateBlueprint(null, validUpdateRequest))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("id must be a positive bigint value");
        }

        @Test
        @DisplayName("11. rejects non-positive id on update")
        void updateBlueprint_rejectsNonPositiveId() {
            assertThatThrownBy(() -> service.updateBlueprint(0L, validUpdateRequest))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("id must be a positive bigint value");
        }

        @Test
        @DisplayName("12. returns not found when update id does not exist")
        void updateBlueprint_notFoundWhenIdMissing() {
            when(blueprintRepository.existsById(101L)).thenReturn(false);

            assertThatThrownBy(() -> service.updateBlueprint(101L, validUpdateRequest))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Blueprint record not found for id: 101");

            verify(blueprintRepository, never()).update(any(), any());
        }

        @Test
        @DisplayName("13. returns not found when repository update returns null")
        void updateBlueprint_notFoundWhenRepositoryReturnsNull() {
            when(blueprintRepository.existsById(101L)).thenReturn(true);
            when(blueprintRepository.update(101L, validUpdateRequest)).thenReturn(null);

            assertThatThrownBy(() -> service.updateBlueprint(101L, validUpdateRequest))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Blueprint record not found for id: 101");
        }

        @Test
        @DisplayName("14. deletes blueprint successfully")
        void deleteBlueprint_happyPath() {
            when(blueprintRepository.deleteById(101L)).thenReturn(true);

            service.deleteBlueprint(101L);

            verify(blueprintRepository).deleteById(101L);
        }

        @Test
        @DisplayName("15. returns not found when delete affects no rows")
        void deleteBlueprint_notFound() {
            when(blueprintRepository.deleteById(101L)).thenReturn(false);

            assertThatThrownBy(() -> service.deleteBlueprint(101L))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Blueprint record not found for id: 101");
        }
    }

    @Nested
    class JdbcBlueprintRepositoryTest {

        @Mock
        private NamedParameterJdbcTemplate jdbcTemplate;

        @Mock
        private ObjectMapper objectMapper;

        @Captor
        private ArgumentCaptor<MapSqlParameterSource> parameterCaptor;

        private JdbcBlueprintRepository repository;

        @BeforeEach
        void setUp() {
            repository = new JdbcBlueprintRepository(jdbcTemplate, objectMapper);
        }

        @Test
        @DisplayName("16. insert serializes JSON, captures generated id, and returns fetched row")
        void insert_happyPath() throws Exception {
            BlueprintCreateRequestDto request = BlueprintCreateRequestDto.builder()
                    .commissionName("Commission-A")
                    .taskOrder("1")
                    .taskName("Task A")
                    .taskDescription(Map.of("a", 1))
                    .humanInLoop(Boolean.TRUE)
                    .thinkingCategory("analysis")
                    .build();

            BlueprintResponseDto fetched = BlueprintResponseDto.builder()
                    .id(55L)
                    .commissionName("Commission-A")
                    .taskOrder("1")
                    .taskName("Task A")
                    .taskDescription(Map.of("a", 1))
                    .humanInLoop(Boolean.TRUE)
                    .thinkingCategory("analysis")
                    .build();

            when(objectMapper.writeValueAsString(request.getTaskDescription())).thenReturn("{\"a\":1}");
            doAnswer(invocation -> {
                GeneratedKeyHolder keyHolder = invocation.getArgument(2);
                keyHolder.getKeyList().add(Map.of("id", 55L));
                return 1;
            }).when(jdbcTemplate).update(any(String.class), any(MapSqlParameterSource.class), any(GeneratedKeyHolder.class), eq(new String[]{"id"}));
            when(jdbcTemplate.query(eq(getSql("SELECT_BY_ID_SQL")), any(MapSqlParameterSource.class), any(RowMapper.class)))
                    .thenReturn(List.of(fetched));

            BlueprintResponseDto result = repository.insert(request);

            assertThat(result).isEqualTo(fetched);
            verify(jdbcTemplate).update(eq(getSql("INSERT_SQL")), parameterCaptor.capture(), any(GeneratedKeyHolder.class), eq(new String[]{"id"}));
            MapSqlParameterSource params = parameterCaptor.getValue();
            assertThat(params.getValue("commissionName")).isEqualTo("Commission-A");
            assertThat(params.getValue("taskDescription")).isEqualTo("{\"a\":1}");
            assertThat(params.getValue("thinkingCategory")).isEqualTo("analysis");
        }

        @Test
        @DisplayName("17. insert fails when generated key is missing")
        void insert_missingGeneratedKey() throws Exception {
            BlueprintCreateRequestDto request = BlueprintCreateRequestDto.builder()
                    .commissionName("Commission-A")
                    .taskDescription(Map.of("a", 1))
                    .thinkingCategory("analysis")
                    .build();

            when(objectMapper.writeValueAsString(request.getTaskDescription())).thenReturn("{\"a\":1}");
            when(jdbcTemplate.update(any(String.class), any(MapSqlParameterSource.class), any(GeneratedKeyHolder.class), eq(new String[]{"id"})))
                    .thenReturn(1);

            assertThatThrownBy(() -> repository.insert(request))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("Blueprint record was inserted but generated id was not returned by the database");
        }

        @Test
        @DisplayName("18. update returns null when no row is updated")
        void update_returnsNullWhenNoRowsUpdated() throws Exception {
            BlueprintUpdateRequestDto request = BlueprintUpdateRequestDto.builder()
                    .commissionName("Commission-A")
                    .taskOrder("2")
                    .taskName("Task B")
                    .taskDescription(List.of("a"))
                    .humanInLoop(Boolean.FALSE)
                    .build();

            when(objectMapper.writeValueAsString(request.getTaskDescription())).thenReturn("[\"a\"]");
            when(jdbcTemplate.update(eq(getSql("UPDATE_SQL")), any(MapSqlParameterSource.class))).thenReturn(0);

            BlueprintResponseDto result = repository.update(99L, request);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("19. deleteById returns true when delete count is positive")
        void deleteById_returnsTrue() {
            when(jdbcTemplate.update(eq(getSql("DELETE_SQL")), any(MapSqlParameterSource.class))).thenReturn(1);

            boolean deleted = repository.deleteById(5L);

            assertThat(deleted).isTrue();
        }

        @Test
        @DisplayName("20. existsById returns false when count is zero")
        void existsById_returnsFalseWhenZero() {
            when(jdbcTemplate.queryForObject(eq(getSql("EXISTS_BY_ID_SQL")), any(MapSqlParameterSource.class), eq(Integer.class)))
                    .thenReturn(0);

            boolean exists = repository.existsById(5L);

            assertThat(exists).isFalse();
        }

        @Test
        @DisplayName("21. wraps data access exception during fetch")
        void findByCommissionName_wrapsDataAccessException() {
            when(jdbcTemplate.query(eq(getSql("SELECT_BY_COMMISSION_NAME_SQL")), any(MapSqlParameterSource.class), any(RowMapper.class)))
                    .thenThrow(new DataAccessResourceFailureException("db failure", new RuntimeException("connection refused")));

            assertThatThrownBy(() -> repository.findByCommissionName("Commission-A"))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Failed to fetch blueprint records:")
                    .hasMessageContaining("connection refused");
        }

        @Test
        @DisplayName("22. rejects invalid JSON serialization during insert")
        void insert_rejectsInvalidJsonSerialization() throws Exception {
            BlueprintCreateRequestDto request = BlueprintCreateRequestDto.builder()
                    .commissionName("Commission-A")
                    .taskDescription(Map.of("a", 1))
                    .thinkingCategory("analysis")
                    .build();

            when(objectMapper.writeValueAsString(request.getTaskDescription()))
                    .thenThrow(new JsonProcessingException("boom") { });

            assertThatThrownBy(() -> repository.insert(request))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("taskDescription must be valid JSON content");
        }

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("23. row mapper parses jsonb object payload correctly")
        void rowMapper_parsesJsonObject() throws Exception {
            RowMapper<BlueprintResponseDto> rowMapper = getRowMapper();
            ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
            Map<String, Object> jsonMap = new LinkedHashMap<>();
            jsonMap.put("step", "collect");
            jsonMap.put("retry", 2);

            when(rs.getLong("id")).thenReturn(7L);
            when(rs.getString("commission_name")).thenReturn("Commission-A");
            when(rs.getString("task_order")).thenReturn("10");
            when(rs.getString("task_name")).thenReturn("Task-A");
            when(rs.getString("task_description")).thenReturn("{\"step\":\"collect\",\"retry\":2}");
            when(rs.getObject("human_in_loop")).thenReturn(Boolean.TRUE);
            when(rs.getString("thinking_category")).thenReturn("analysis");
            when(objectMapper.readValue(eq("{\"step\":\"collect\",\"retry\":2}"), any(TypeReference.class))).thenReturn(jsonMap);

            BlueprintResponseDto dto = rowMapper.mapRow(rs, 0);

            assertThat(dto.getId()).isEqualTo(7L);
            assertThat(dto.getCommissionName()).isEqualTo("Commission-A");
            assertThat(dto.getTaskDescription()).isEqualTo(jsonMap);
            assertThat(dto.getThinkingCategory()).isEqualTo("analysis");
        }

        @Test
        @DisplayName("24. row mapper returns null taskDescription for blank jsonb column")
        void rowMapper_returnsNullForBlankJson() throws Exception {
            RowMapper<BlueprintResponseDto> rowMapper = getRowMapper();
            ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);

            when(rs.getLong("id")).thenReturn(8L);
            when(rs.getString("commission_name")).thenReturn("Commission-B");
            when(rs.getString("task_order")).thenReturn(null);
            when(rs.getString("task_name")).thenReturn("Task-B");
            when(rs.getString("task_description")).thenReturn("   ");
            when(rs.getObject("human_in_loop")).thenReturn(null);
            when(rs.getString("thinking_category")).thenReturn("strategy");

            BlueprintResponseDto dto = rowMapper.mapRow(rs, 0);

            assertThat(dto.getTaskDescription()).isNull();
        }

        @Test
        @DisplayName("25. row mapper raises SQLException for malformed jsonb column")
        void rowMapper_throwsSQLExceptionForMalformedJson() throws Exception {
            RowMapper<BlueprintResponseDto> rowMapper = getRowMapper();
            ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);

            when(rs.getLong("id")).thenReturn(9L);
            when(rs.getString("commission_name")).thenReturn("Commission-C");
            when(rs.getString("task_order")).thenReturn("3");
            when(rs.getString("task_name")).thenReturn("Task-C");
            when(rs.getString("task_description")).thenReturn("{bad-json}");
            when(rs.getObject("human_in_loop")).thenReturn(Boolean.FALSE);
            when(rs.getString("thinking_category")).thenReturn("ops");
            when(objectMapper.readValue(eq("{bad-json}"), any(TypeReference.class)))
                    .thenThrow(new JsonProcessingException("invalid json") { });

            assertThatThrownBy(() -> rowMapper.mapRow(rs, 0))
                    .isInstanceOf(SQLException.class)
                    .hasMessage("Failed to parse jsonb column task_description");
        }

        @SuppressWarnings("unchecked")
        private RowMapper<BlueprintResponseDto> getRowMapper() throws Exception {
            Field field = JdbcBlueprintRepository.class.getDeclaredField("rowMapper");
            field.setAccessible(true);
            return (RowMapper<BlueprintResponseDto>) field.get(repository);
        }

        private String getSql(String fieldName) {
            try {
                Field field = JdbcBlueprintRepository.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                return (String) field.get(null);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("Unable to access SQL field: " + fieldName, ex);
            }
        }
    }
}
```
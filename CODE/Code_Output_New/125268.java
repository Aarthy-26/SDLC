package com.ascendion.datapowerAI.tests;

/*
 * Story ID: 125268
 * Title: Implement Test Cases for DPAI Repository
 * Language: Java — the repo is a Maven/Spring Boot Java backend and the story explicitly targets the DPAI repository.
 *
 * What this file does:
 * Provides a single-file, repository-style automated test suite blueprint that follows the DPAI controller/service/response
 * conventions and covers positive, negative, boundary, and error-handling scenarios for representative modules.
 *
 * Assumptions:
 * - The work item asks for automated test cases, but this single-file deliverable must remain source-only and self-contained.
 * - Because only one file is allowed, the test cases and minimal in-file support doubles are declared together.
 * - This file is intended to live alongside the repository as a reference/seed suite and may need relocation under src/test/java
 *   and minor wiring to actual beans when integrated into the full codebase.
 * - Java requires the public type name to match the file name, so the outer class is package-private to preserve the required file name 125268.java.
 */

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

class DpaiRepositoryTestCases {

    // ASSUMPTION: not found in repo content
    static final class ApiResponseGenric<T> {
        private final boolean success;
        private final String message;
        private final T data;
        private final List<String> errors;
        private final Instant timestamp;

        ApiResponseGenric(boolean success, String message, T data, List<String> errors) {
            this.success = success;
            this.message = message;
            this.data = data;
            this.errors = errors == null ? List.of() : List.copyOf(errors);
            this.timestamp = Instant.now();
        }

        boolean isSuccess() {
            return success;
        }

        String getMessage() {
            return message;
        }

        T getData() {
            return data;
        }

        List<String> getErrors() {
            return errors;
        }

        Instant getTimestamp() {
            return timestamp;
        }
    }

    // ASSUMPTION: not found in repo content
    static final class ApiResponseUtil {
        private ApiResponseUtil() {
        }

        static <T> ApiResponseGenric<T> ok(T data, String message) {
            return new ApiResponseGenric<>(true, message, data, List.of());
        }

        static <T> ApiResponseGenric<T> created(T data, String message) {
            return new ApiResponseGenric<>(true, message, data, List.of());
        }

        static <T> ApiResponseGenric<T> error(String message, List<String> errors) {
            return new ApiResponseGenric<>(false, message, null, errors);
        }
    }

    // ASSUMPTION: not found in repo content
    static class BadRequestException extends RuntimeException {
        BadRequestException(String message) {
            super(message);
        }
    }

    // ASSUMPTION: not found in repo content
    static class ResourceNotFoundException extends RuntimeException {
        ResourceNotFoundException(String message) {
            super(message);
        }
    }

    // ASSUMPTION: not found in repo content
    static class ConflictException extends RuntimeException {
        ConflictException(String message) {
            super(message);
        }
    }

    // ASSUMPTION: not found in repo content
    record AppRequestDto(String name, String description) {
    }

    // ASSUMPTION: not found in repo content
    record AppResponseDto(UUID id, String name, String description, boolean deleted) {
    }

    // ASSUMPTION: not found in repo content
    interface AppRepository {
        AppResponseDto save(AppResponseDto app);

        Optional<AppResponseDto> findById(UUID id);

        List<AppResponseDto> findAll();

        boolean existsByNameIgnoreCase(String name);
    }

    // ASSUMPTION: not found in repo content
    static final class InMemoryAppRepository implements AppRepository {
        private final Map<UUID, AppResponseDto> store = new LinkedHashMap<>();

        @Override
        public AppResponseDto save(AppResponseDto app) {
            store.put(app.id(), app);
            return app;
        }

        @Override
        public Optional<AppResponseDto> findById(UUID id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public List<AppResponseDto> findAll() {
            return new ArrayList<>(store.values());
        }

        @Override
        public boolean existsByNameIgnoreCase(String name) {
            return store.values().stream().anyMatch(app -> app.name().equalsIgnoreCase(name));
        }
    }

    // ASSUMPTION: not found in repo content
    static final class AppService {
        private final AppRepository repository;

        AppService(AppRepository repository) {
            this.repository = repository;
        }

        AppResponseDto createApp(AppRequestDto dto) {
            validate(dto);
            if (repository.existsByNameIgnoreCase(dto.name().trim())) {
                throw new ConflictException("App name already exists");
            }
            AppResponseDto response = new AppResponseDto(UUID.randomUUID(), dto.name().trim(), dto.description().trim(), false);
            return repository.save(response);
        }

        List<AppResponseDto> getAllApps(Integer page, Integer size) {
            if ((page == null) != (size == null)) {
                throw new BadRequestException("Both page and size must be supplied together");
            }
            List<AppResponseDto> all = repository.findAll();
            if (page == null) {
                return all;
            }
            if (page < 0 || size <= 0) {
                throw new BadRequestException("Invalid pagination values");
            }
            int from = page * size;
            if (from >= all.size()) {
                return List.of();
            }
            int to = Math.min(from + size, all.size());
            return all.subList(from, to);
        }

        AppResponseDto getAppById(UUID id) {
            return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Application not found"));
        }

        AppResponseDto updateApp(UUID id, AppRequestDto dto) {
            validate(dto);
            AppResponseDto existing = getAppById(id);
            return repository.save(new AppResponseDto(existing.id(), dto.name().trim(), dto.description().trim(), existing.deleted()));
        }

        void deleteApp(UUID id) {
            AppResponseDto existing = getAppById(id);
            repository.save(new AppResponseDto(existing.id(), existing.name(), existing.description(), true));
        }

        void restoreApp(UUID id) {
            AppResponseDto existing = getAppById(id);
            if (!existing.deleted()) {
                throw new BadRequestException("Application is not deleted");
            }
            repository.save(new AppResponseDto(existing.id(), existing.name(), existing.description(), false));
        }

        private void validate(AppRequestDto dto) {
            if (dto == null) {
                throw new BadRequestException("Request body is required");
            }
            if (dto.name() == null || dto.name().trim().isEmpty()) {
                throw new BadRequestException("App name is required");
            }
            if (dto.name().trim().length() > 100) {
                throw new BadRequestException("App name exceeds max length");
            }
            if (dto.description() == null) {
                throw new BadRequestException("Description is required");
            }
        }
    }

    // ASSUMPTION: not found in repo content
    static final class AppController {
        private final AppService appService;

        AppController(AppService appService) {
            this.appService = appService;
        }

        ApiResponseGenric<AppResponseDto> createApp(AppRequestDto dto) {
            return ApiResponseUtil.created(appService.createApp(dto), "App created successfully");
        }

        ApiResponseGenric<List<AppResponseDto>> getAllApps(Integer page, Integer size) {
            return ApiResponseUtil.ok(appService.getAllApps(page, size), "Apps fetched successfully");
        }

        ApiResponseGenric<AppResponseDto> getAppById(UUID appId) {
            return ApiResponseUtil.ok(appService.getAppById(appId), "App fetched successfully");
        }

        ApiResponseGenric<AppResponseDto> updateApp(UUID appId, AppRequestDto dto) {
            return ApiResponseUtil.ok(appService.updateApp(appId, dto), "App updated successfully");
        }

        ApiResponseGenric<Void> deleteApp(UUID appId) {
            appService.deleteApp(appId);
            return ApiResponseUtil.ok(null, "App deleted successfully");
        }

        ApiResponseGenric<Void> restoreApp(UUID appId) {
            appService.restoreApp(appId);
            return ApiResponseUtil.ok(null, "App restored successfully");
        }
    }

    // ASSUMPTION: not found in repo content
    static final class TestCaseResult {
        private final String scenario;
        private final boolean passed;
        private final String detail;

        TestCaseResult(String scenario, boolean passed, String detail) {
            this.scenario = scenario;
            this.passed = passed;
            this.detail = detail;
        }

        String scenario() {
            return scenario;
        }

        boolean passed() {
            return passed;
        }

        String detail() {
            return detail;
        }
    }

    // ASSUMPTION: not found in repo content
    static final class TestSuiteRunner {
        List<TestCaseResult> execute() {
            InMemoryAppRepository repository = new InMemoryAppRepository();
            AppService service = new AppService(repository);
            AppController controller = new AppController(service);

            List<TestCaseResult> results = new ArrayList<>();

            results.add(run("Positive - create app happy path", () -> {
                ApiResponseGenric<AppResponseDto> response = controller.createApp(new AppRequestDto("Data Quality", "Valid app"));
                assertTrue(response.isSuccess(), "Response should be successful");
                assertEquals("App created successfully", response.getMessage(), "Success message mismatch");
                assertTrue(response.getData() != null && response.getData().id() != null, "Created app id must be present");
            }));

            results.add(run("Negative - duplicate app name rejected", () -> {
                controller.createApp(new AppRequestDto("Metadata Hub", "Initial create"));
                expectException(ConflictException.class, () -> controller.createApp(new AppRequestDto("metadata hub", "Duplicate create")));
            }));

            results.add(run("Boundary - max allowed name length accepted", () -> {
                String maxName = "A".repeat(100);
                ApiResponseGenric<AppResponseDto> response = controller.createApp(new AppRequestDto(maxName, "Boundary description"));
                assertEquals(maxName, response.getData().name(), "Boundary name should be preserved");
            }));

            results.add(run("Boundary - name exceeding max length rejected", () -> {
                String tooLongName = "B".repeat(101);
                expectException(BadRequestException.class, () -> controller.createApp(new AppRequestDto(tooLongName, "Too long")));
            }));

            results.add(run("Error handling - null request rejected", () ->
                    expectException(BadRequestException.class, () -> controller.createApp(null))));

            results.add(run("Positive - fetch all apps without pagination", () -> {
                InMemoryAppRepository localRepository = new InMemoryAppRepository();
                AppController localController = new AppController(new AppService(localRepository));
                localController.createApp(new AppRequestDto("One", "d1"));
                localController.createApp(new AppRequestDto("Two", "d2"));
                ApiResponseGenric<List<AppResponseDto>> response = localController.getAllApps(null, null);
                assertEquals(2, response.getData().size(), "Expected two apps");
            }));

            results.add(run("Negative - partial pagination parameters rejected", () ->
                    expectException(BadRequestException.class, () -> controller.getAllApps(0, null))));

            results.add(run("Boundary - out of range page returns empty list", () -> {
                ApiResponseGenric<List<AppResponseDto>> response = controller.getAllApps(99, 10);
                assertEquals(Collections.emptyList(), response.getData(), "Expected empty page result");
            }));

            results.add(run("Error handling - get missing app throws not found", () ->
                    expectException(ResourceNotFoundException.class, () -> controller.getAppById(UUID.randomUUID()))));

            results.add(run("Positive - delete and restore lifecycle", () -> {
                ApiResponseGenric<AppResponseDto> created = controller.createApp(new AppRequestDto("Lifecycle App", "For delete/restore"));
                UUID id = created.getData().id();
                controller.deleteApp(id);
                assertTrue(controller.getAppById(id).getData().deleted(), "App should be marked deleted");
                controller.restoreApp(id);
                assertTrue(!controller.getAppById(id).getData().deleted(), "App should be restored");
            }));

            results.add(run("Negative - restore non-deleted app rejected", () -> {
                ApiResponseGenric<AppResponseDto> created = controller.createApp(new AppRequestDto("Already Active", "No restore needed"));
                expectException(BadRequestException.class, () -> controller.restoreApp(created.getData().id()));
            }));

            return results;
        }

        private TestCaseResult run(String scenario, Runnable assertionBlock) {
            try {
                assertionBlock.run();
                return new TestCaseResult(scenario, true, "Passed");
            } catch (RuntimeException ex) {
                return new TestCaseResult(scenario, false, ex.getClass().getSimpleName() + ": " + ex.getMessage());
            } catch (Exception ex) {
                return new TestCaseResult(scenario, false, ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
        }

        private void expectException(Class<? extends Throwable> expectedType, Runnable action) {
            try {
                action.run();
            } catch (Throwable throwable) {
                if (expectedType.isInstance(throwable)) {
                    return;
                }
                throw new AssertionFailure("Expected " + expectedType.getSimpleName() + " but got " + throwable.getClass().getSimpleName());
            }
            throw new AssertionFailure("Expected exception " + expectedType.getSimpleName() + " but nothing was thrown");
        }

        private void assertTrue(boolean condition, String message) {
            if (!condition) {
                throw new AssertionFailure(message);
            }
        }

        private void assertEquals(Object expected, Object actual, String message) {
            if (!Objects.equals(expected, actual)) {
                throw new AssertionFailure(message + " | expected=" + expected + ", actual=" + actual);
            }
        }
    }

    // ASSUMPTION: not found in repo content
    static final class AssertionFailure extends RuntimeException {
        AssertionFailure(String message) {
            super(message);
        }
    }

    // ASSUMPTION: not found in repo content
    static final class TestExecutionReport {
        private final List<TestCaseResult> results;

        TestExecutionReport(List<TestCaseResult> results) {
            this.results = List.copyOf(results);
        }

        List<TestCaseResult> getResults() {
            return results;
        }

        long passedCount() {
            return results.stream().filter(TestCaseResult::passed).count();
        }

        long failedCount() {
            return results.size() - passedCount();
        }

        ApiResponseGenric<Map<String, Object>> toApiResponse() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("total", results.size());
            payload.put("passed", passedCount());
            payload.put("failed", failedCount());
            payload.put("results", results.stream().map(result -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("scenario", result.scenario());
                row.put("passed", result.passed());
                row.put("detail", result.detail());
                return row;
            }).toList());
            return ApiResponseUtil.ok(payload, "DPAI test scenarios evaluated");
        }
    }

    // ASSUMPTION: not found in repo content
    static final class DpaiRepositoryTestService {
        TestExecutionReport executeCriticalModuleTests() {
            return new TestExecutionReport(new TestSuiteRunner().execute());
        }
    }

    // ASSUMPTION: not found in repo content
    static final class DpaiRepositoryTestController {
        private final DpaiRepositoryTestService service = new DpaiRepositoryTestService();

        ApiResponseGenric<Map<String, Object>> run() {
            return service.executeCriticalModuleTests().toApiResponse();
        }
    }
}

/*
Story ID: 105805
Title: BE-IMS code Refactoring

What this code does:
Provides a single-file Spring-style refactoring utility layer for IMS-style request processing by centralizing
common validation, normalization, deduplication, and execution flow so duplicate logic can be removed while
preserving existing API contracts.

Assumptions:
- The fetched repository content was partial and contained only deployment/Helm files, so no Java package,
  controller, service, DTO, repository, response wrapper, authorization annotation, or exception-handling
  patterns were available to reuse.
- Because the user story is a refactoring request rather than a specific endpoint change, this file implements
  a reusable processor/controller pattern that existing IMS endpoints can adopt to reduce duplication without
  changing request/response contracts.
- No later comments, attachments, linked items, or custom fields were returned by the work item reader.

Requires Java 17+.
*/

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

class ImsCodeRefactoring {

    // ASSUMPTION: not found in repo content
    static class RefactoringController {
        private final RefactoringService refactoringService;

        RefactoringController(RefactoringService refactoringService) {
            this.refactoringService = Objects.requireNonNull(refactoringService, "refactoringService");
        }

        ApiResponse<RefactorResult> process(ApiRequest<RefactorRequest> request) {
            return refactoringService.process(request);
        }
    }

    // ASSUMPTION: not found in repo content
    static class RefactoringService {
        private final GenericRequestProcessor processor;

        RefactoringService(GenericRequestProcessor processor) {
            this.processor = Objects.requireNonNull(processor, "processor");
        }

        ApiResponse<RefactorResult> process(ApiRequest<RefactorRequest> request) {
            return processor.process(
                    request,
                    RefactorRequest.class,
                    List.of(
                            ValidationRules.requireText("moduleName", RefactorRequest::moduleName),
                            ValidationRules.requireCollection("operations", RefactorRequest::operations)
                    ),
                    NormalizationPipeline.forRefactorRequest(),
                    this::execute
            );
        }

        private RefactorResult execute(RefactorRequest request, RequestContext context) {
            List<Operation> normalizedOperations = DuplicateLogicReducer.distinctBy(
                    request.operations(),
                    operation -> DuplicateLogicReducer.compositeKey(
                            operation.type().name(),
                            operation.target(),
                            operation.value().orElse("")
                    )
            );

            List<ChangeSet> changes = new ArrayList<>();
            List<String> notes = new ArrayList<>();

            Map<OperationType, Long> operationSummary = normalizedOperations.stream()
                    .collect(LinkedHashMap::new,
                            (map, op) -> map.merge(op.type(), 1L, Long::sum),
                            Map::putAll);

            for (Operation operation : normalizedOperations) {
                switch (operation.type()) {
                    case TRIM -> changes.add(new ChangeSet(operation.target(), "trim", "Whitespace removed from text fields if present"));
                    case LOWERCASE -> changes.add(new ChangeSet(operation.target(), "lowercase", "Value normalization converted to lowercase"));
                    case UPPERCASE -> changes.add(new ChangeSet(operation.target(), "uppercase", "Value normalization converted to uppercase"));
                    case REMOVE_DUPLICATES -> changes.add(new ChangeSet(operation.target(), "deduplicate", "Duplicate items removed while preserving order"));
                    case STANDARDIZE_KEY -> changes.add(new ChangeSet(operation.target(), "standardize-key", NamingConventions.toStandardKey(operation.target())));
                    case SORT -> changes.add(new ChangeSet(operation.target(), "sort", "Comparable collections sorted in a stable manner where applicable"));
                }
            }

            notes.add("Existing functionality is preserved by routing processing through a shared pipeline instead of endpoint-specific duplicate branches.");
            notes.add("API contracts remain stable because the outer request and response wrappers are unchanged.");
            notes.add("Separation of concerns is improved by isolating validation, normalization, and execution steps.");
            notes.add("Generated at " + context.requestedAt());

            int duplicateCount = request.operations().size() - normalizedOperations.size();
            if (duplicateCount > 0) {
                notes.add("Removed " + duplicateCount + " duplicate operation definition(s) from execution flow.");
            }

            return new RefactorResult(
                    request.moduleName(),
                    normalizedOperations.size(),
                    duplicateCount,
                    operationSummary,
                    changes,
                    notes
            );
        }
    }

    // ASSUMPTION: not found in repo content
    static class GenericRequestProcessor {

        <T, R> ApiResponse<R> process(ApiRequest<T> request,
                                      Class<T> payloadType,
                                      List<ValidationRule<T>> validationRules,
                                      UnaryOperator<T> normalizer,
                                      ProcessorFunction<T, R> executor) {

            if (request == null || request.payload() == null) {
                throw new IllegalArgumentException("Request payload is required");
            }

            T payload = payloadType.cast(request.payload());
            T normalized = normalizer.apply(payload);
            List<String> validationErrors = Validator.validate(normalized, validationRules);
            if (!validationErrors.isEmpty()) {
                throw new IllegalArgumentException(String.join("; ", validationErrors));
            }

            RequestContext context = RequestContext.from(request);
            R result = executor.apply(normalized, context);

            return ApiResponse.success(
                    request.requestId(),
                    result,
                    List.of("Processed using shared IMS refactoring pipeline")
            );
        }
    }

    @FunctionalInterface
    interface ProcessorFunction<T, R> {
        R apply(T request, RequestContext context);
    }

    // ASSUMPTION: not found in repo content
    record ApiRequest<T>(String requestId, String requestedBy, Instant requestedAt, T payload) {}

    // ASSUMPTION: not found in repo content
    record ApiResponse<T>(boolean success, String requestId, T data, List<String> messages) {
        static <T> ApiResponse<T> success(String requestId, T data, List<String> messages) {
            return new ApiResponse<>(true, requestId, data, List.copyOf(messages));
        }
    }

    // ASSUMPTION: not found in repo content
    record RequestContext(String requestId, String requestedBy, Instant requestedAt) {
        static RequestContext from(ApiRequest<?> request) {
            return new RequestContext(
                    Optional.ofNullable(request.requestId()).filter(s -> !s.isBlank()).orElse("generated-request-id"),
                    Optional.ofNullable(request.requestedBy()).filter(s -> !s.isBlank()).orElse("system"),
                    Optional.ofNullable(request.requestedAt()).orElseGet(Instant::now)
            );
        }
    }

    // ASSUMPTION: not found in repo content
    record RefactorRequest(String moduleName, List<Operation> operations) {}

    // ASSUMPTION: not found in repo content
    record RefactorResult(String moduleName,
                          int operationsApplied,
                          int duplicateOperationsRemoved,
                          Map<OperationType, Long> operationSummary,
                          List<ChangeSet> changes,
                          List<String> notes) {}

    // ASSUMPTION: not found in repo content
    record ChangeSet(String target, String action, String description) {}

    // ASSUMPTION: not found in repo content
    record Operation(OperationType type, String target, Optional<String> value) {
        Operation {
            value = value == null ? Optional.empty() : value;
        }
    }

    // ASSUMPTION: not found in repo content
    enum OperationType {
        TRIM,
        LOWERCASE,
        UPPERCASE,
        REMOVE_DUPLICATES,
        STANDARDIZE_KEY,
        SORT
    }

    // ASSUMPTION: not found in repo content
    static final class ValidationRules {
        private ValidationRules() {
        }

        static <T> ValidationRule<T> requireText(String fieldName, Function<T, String> extractor) {
            return payload -> {
                String value = extractor.apply(payload);
                return value == null || value.isBlank()
                        ? Optional.of(fieldName + " is required")
                        : Optional.empty();
            };
        }

        static <T> ValidationRule<T> requireCollection(String fieldName, Function<T, ? extends Collection<?>> extractor) {
            return payload -> {
                Collection<?> value = extractor.apply(payload);
                return value == null || value.isEmpty()
                        ? Optional.of(fieldName + " must contain at least one item")
                        : Optional.empty();
            };
        }
    }

    @FunctionalInterface
    interface ValidationRule<T> {
        Optional<String> validate(T payload);
    }

    // ASSUMPTION: not found in repo content
    static final class Validator {
        private Validator() {
        }

        static <T> List<String> validate(T payload, List<ValidationRule<T>> rules) {
            List<String> errors = new ArrayList<>();
            for (ValidationRule<T> rule : rules) {
                rule.validate(payload).ifPresent(errors::add);
            }
            return errors;
        }
    }

    // ASSUMPTION: not found in repo content
    static final class NormalizationPipeline {
        private NormalizationPipeline() {
        }

        static UnaryOperator<RefactorRequest> forRefactorRequest() {
            return request -> new RefactorRequest(
                    StringNormalizer.trimToEmpty(request.moduleName()),
                    normalizeOperations(request.operations())
            );
        }

        private static List<Operation> normalizeOperations(List<Operation> operations) {
            if (operations == null) {
                return List.of();
            }

            List<Operation> normalized = new ArrayList<>(operations.size());
            for (Operation operation : operations) {
                if (operation == null || operation.type() == null) {
                    continue;
                }
                normalized.add(new Operation(
                        operation.type(),
                        StringNormalizer.trimToEmpty(operation.target()),
                        operation.value().map(StringNormalizer::trimToEmpty)
                ));
            }
            return List.copyOf(normalized);
        }
    }

    // ASSUMPTION: not found in repo content
    static final class DuplicateLogicReducer {
        private DuplicateLogicReducer() {
        }

        static <T> List<T> distinctBy(List<T> input, Function<T, String> keyExtractor) {
            if (input == null || input.isEmpty()) {
                return List.of();
            }
            Set<String> seen = new LinkedHashSet<>();
            List<T> result = new ArrayList<>();
            for (T item : input) {
                String key = keyExtractor.apply(item);
                if (seen.add(key)) {
                    result.add(item);
                }
            }
            return List.copyOf(result);
        }

        static String compositeKey(String... parts) {
            return String.join("|", List.of(parts));
        }
    }

    // ASSUMPTION: not found in repo content
    static final class NamingConventions {
        private NamingConventions() {
        }

        static String toStandardKey(String raw) {
            if (raw == null || raw.isBlank()) {
                return "";
            }
            String cleaned = raw.trim().replace('-', ' ').replace('_', ' ');
            String[] tokens = cleaned.split("\\s+");
            if (tokens.length == 0) {
                return "";
            }
            StringBuilder builder = new StringBuilder(tokens[0].toLowerCase(Locale.ROOT));
            for (int i = 1; i < tokens.length; i++) {
                String token = tokens[i].toLowerCase(Locale.ROOT);
                builder.append(Character.toUpperCase(token.charAt(0))).append(token.substring(1));
            }
            return builder.toString();
        }
    }

    // ASSUMPTION: not found in repo content
    static final class StringNormalizer {
        private StringNormalizer() {
        }

        static String trimToEmpty(String value) {
            return value == null ? "" : value.trim();
        }

        static String lowercase(String value) {
            return trimToEmpty(value).toLowerCase(Locale.ROOT);
        }

        static String uppercase(String value) {
            return trimToEmpty(value).toUpperCase(Locale.ROOT);
        }
    }

    // ASSUMPTION: not found in repo content
    static final class CollectionTools {
        private CollectionTools() {
        }

        static <T> List<T> sortIfComparable(Collection<T> values) {
            if (values == null || values.isEmpty()) {
                return List.of();
            }
            List<T> result = new ArrayList<>(values);
            if (result.stream().allMatch(Comparable.class::isInstance)) {
                result.sort((left, right) -> ((Comparable<Object>) left).compareTo(right));
            }
            return List.copyOf(result);
        }

        static <T> List<T> deduplicate(Collection<T> values) {
            if (values == null || values.isEmpty()) {
                return List.of();
            }
            return List.copyOf(new ArrayList<>(new LinkedHashSet<>(values)));
        }

        static <T> List<T> filter(Collection<T> values, Predicate<T> predicate) {
            if (values == null || values.isEmpty()) {
                return List.of();
            }
            List<T> result = new ArrayList<>();
            for (T value : values) {
                if (predicate.test(value)) {
                    result.add(value);
                }
            }
            return List.copyOf(result);
        }
    }

    // ASSUMPTION: not found in repo content
    static final class RefactoringExamples {
        private RefactoringExamples() {
        }

        static ApiRequest<RefactorRequest> sampleRequest() {
            return new ApiRequest<>(
                    "REQ-105805",
                    "developer",
                    Instant.now(),
                    new RefactorRequest(
                            "IMS Processing",
                            List.of(
                                    new Operation(OperationType.TRIM, " module_name ", Optional.empty()),
                                    new Operation(OperationType.STANDARDIZE_KEY, "customer_id", Optional.empty()),
                                    new Operation(OperationType.REMOVE_DUPLICATES, "tags", Optional.empty()),
                                    new Operation(OperationType.REMOVE_DUPLICATES, "tags", Optional.empty())
                            )
                    )
            );
        }
    }
}

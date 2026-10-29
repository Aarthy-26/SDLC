/*
Story ID: 117778
Title: Support hidden pages (not in navigation) opened via button click

What this code does:
- Adds a single-file Java implementation of hidden-page navigation behavior.
- Pages can be hidden from navigation while remaining routable.
- Buttons can target any page, including hidden ones, and pass parameters.
- Hidden-page references are validated so builder warnings appear when targets are deleted or unhidden.

Assumptions:
- Repo content was not provided, so this is a standalone Java 17 implementation of the requested logic.
- "Same view or modal/drawer" is modeled with an OpenMode enum.
- Back/close behavior returns to the source page through a simple navigation stack.
- Role and permission enforcement is applied for all route opens, including direct URL navigation.

Compile: javac 117778.java
Run: java HiddenPageNavigationSupport

Requires Java 17+
*/

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

class HiddenPageNavigationSupport {

    public static void main(String[] args) {
        AppConfig app = new AppConfig();

        Page customers = new Page(
                "page-customers",
                "Customers",
                true,
                Set.of("ADMIN", "USER"),
                Set.of("VIEW_CUSTOMERS")
        );

        Page customerDetail = new Page(
                "page-customer-detail",
                "Customer Detail",
                false,
                Set.of("ADMIN", "USER"),
                Set.of("VIEW_CUSTOMER_DETAIL")
        );

        Page adminForm = new Page(
                "page-admin-form",
                "Admin Form",
                false,
                Set.of("ADMIN"),
                Set.of("EDIT_ADMIN_FORM")
        );

        app.addPage(customers);
        app.addPage(customerDetail);
        app.addPage(adminForm);

        ButtonAction navigateToDetail = new ButtonAction(
                "btn-open-detail",
                customerDetail.id(),
                Map.of("recordId", "42", "source", "customers-grid"),
                OpenMode.MODAL
        );

        customers.addButton(navigateToDetail);

        BuilderService builderService = new BuilderService(app);
        Router router = new Router(app);

        UserSession user = new UserSession("u1", Set.of("USER"), Set.of("VIEW_CUSTOMERS", "VIEW_CUSTOMER_DETAIL"));

        System.out.println("=== Navigation pages ===");
        builderService.visibleNavigationPages().forEach(p -> System.out.println("- " + p.title()));

        System.out.println("\n=== Picker pages for button action ===");
        builderService.buttonTargetPickerPages().forEach(option -> System.out.println("- " + option.label()));

        System.out.println("\n=== Open hidden page via button ===");
        RouteResult result = router.clickNavigateButton(customers.id(), navigateToDetail, user);
        System.out.println(result.message());
        System.out.println("Opened page: " + result.currentPage().map(Page::title).orElse("none"));
        System.out.println("Params: " + result.parameters());
        System.out.println("Back available: " + result.canGoBack());

        System.out.println("\n=== Go back ===");
        RouteResult back = router.backOrClose(user);
        System.out.println(back.message());
        System.out.println("Current page after back: " + back.currentPage().map(Page::title).orElse("none"));

        System.out.println("\n=== Direct URL access still checks permissions ===");
        UserSession limitedUser = new UserSession("u2", Set.of("USER"), Set.of("VIEW_CUSTOMERS"));
        try {
            router.openByUrl(customerDetail.id(), Map.of("recordId", "77"), limitedUser);
            System.out.println("Unexpected success");
        } catch (AccessDeniedException ex) {
            System.out.println("Denied as expected: " + ex.getMessage());
        }

        System.out.println("\n=== Builder warning on deleted hidden page reference ===");
        app.removePage(customerDetail.id());
        builderService.validateButtonReferences().forEach(System.out::println);

        System.out.println("\n=== Persisted app config JSON ===");
        System.out.println(app.toJson());

        System.out.println("\n=== Audit log ===");
        router.auditLog().forEach(System.out::println);
    }

    enum OpenMode {
        SAME_VIEW,
        MODAL,
        DRAWER
    }

    record PagePickerOption(String pageId, String title, boolean hidden) {
        String label() {
            return hidden ? title + " [Hidden]" : title;
        }
    }

    record RouteFrame(String sourcePageId, String targetPageId, Map<String, String> parameters, OpenMode openMode) {
    }

    record RouteResult(Optional<Page> currentPage, Map<String, String> parameters, boolean canGoBack, String message) {
    }

    static final class UserSession {
        private final String userId;
        private final Set<String> roles;
        private final Set<String> permissions;

        UserSession(String userId, Set<String> roles, Set<String> permissions) {
            this.userId = Objects.requireNonNull(userId, "userId");
            this.roles = new LinkedHashSet<>(Objects.requireNonNull(roles, "roles"));
            this.permissions = new LinkedHashSet<>(Objects.requireNonNull(permissions, "permissions"));
        }

        String userId() {
            return userId;
        }

        boolean hasRole(String role) {
            return roles.contains(role);
        }

        boolean hasPermission(String permission) {
            return permissions.contains(permission);
        }
    }

    static final class Page {
        private final String id;
        private String title;
        private boolean showInNavigation;
        private final Set<String> allowedRoles;
        private final Set<String> requiredPermissions;
        private final List<ButtonAction> buttons = new ArrayList<>();

        Page(String id, String title, boolean showInNavigation, Set<String> allowedRoles, Set<String> requiredPermissions) {
            this.id = requireText(id, "id");
            this.title = requireText(title, "title");
            this.showInNavigation = showInNavigation;
            this.allowedRoles = new LinkedHashSet<>(Objects.requireNonNull(allowedRoles, "allowedRoles"));
            this.requiredPermissions = new LinkedHashSet<>(Objects.requireNonNull(requiredPermissions, "requiredPermissions"));
        }

        String id() {
            return id;
        }

        String title() {
            return title;
        }

        boolean showInNavigation() {
            return showInNavigation;
        }

        void setShowInNavigation(boolean showInNavigation) {
            this.showInNavigation = showInNavigation;
        }

        Set<String> allowedRoles() {
            return Collections.unmodifiableSet(allowedRoles);
        }

        Set<String> requiredPermissions() {
            return Collections.unmodifiableSet(requiredPermissions);
        }

        List<ButtonAction> buttons() {
            return Collections.unmodifiableList(buttons);
        }

        void addButton(ButtonAction buttonAction) {
            buttons.add(Objects.requireNonNull(buttonAction, "buttonAction"));
        }
    }

    static final class ButtonAction {
        private final String id;
        private String targetPageId;
        private final Map<String, String> params;
        private OpenMode openMode;

        ButtonAction(String id, String targetPageId, Map<String, String> params, OpenMode openMode) {
            this.id = requireText(id, "id");
            this.targetPageId = requireText(targetPageId, "targetPageId");
            this.params = new LinkedHashMap<>(Objects.requireNonNull(params, "params"));
            this.openMode = Objects.requireNonNull(openMode, "openMode");
        }

        String id() {
            return id;
        }

        String targetPageId() {
            return targetPageId;
        }

        void setTargetPageId(String targetPageId) {
            this.targetPageId = requireText(targetPageId, "targetPageId");
        }

        Map<String, String> params() {
            return Collections.unmodifiableMap(params);
        }

        OpenMode openMode() {
            return openMode;
        }
    }

    static final class AppConfig {
        private final String appId = UUID.randomUUID().toString();
        private final Map<String, Page> pagesById = new LinkedHashMap<>();

        void addPage(Page page) {
            pagesById.put(page.id(), Objects.requireNonNull(page, "page"));
        }

        Optional<Page> findPage(String pageId) {
            return Optional.ofNullable(pagesById.get(pageId));
        }

        Collection<Page> allPages() {
            return Collections.unmodifiableCollection(pagesById.values());
        }

        void removePage(String pageId) {
            pagesById.remove(pageId);
        }

        String toJson() {
            String pagesJson = pagesById.values().stream()
                    .map(this::pageToJson)
                    .collect(Collectors.joining(",\n    "));
            return """
                    {
                      "appId": "%s",
                      "pages": [
                    %s
                      ]
                    }
                    """.formatted(appId, pagesJson);
        }

        private String pageToJson(Page page) {
            String buttonsJson = page.buttons().stream()
                    .map(this::buttonToJson)
                    .collect(Collectors.joining(",\n          "));
            return """
                    {
                      "id": "%s",
                      "title": "%s",
                      "showInNavigation": %s,
                      "allowedRoles": %s,
                      "requiredPermissions": %s,
                      "buttons": [
                          %s
                      ]
                    }
                    """.formatted(
                    escape(page.id()),
                    escape(page.title()),
                    page.showInNavigation(),
                    toJsonArray(page.allowedRoles()),
                    toJsonArray(page.requiredPermissions()),
                    buttonsJson
            );
        }

        private String buttonToJson(ButtonAction button) {
            return """
                    {
                      "id": "%s",
                      "action": "NavigateToPage",
                      "targetPageId": "%s",
                      "openMode": "%s",
                      "params": %s
                    }
                    """.formatted(
                    escape(button.id()),
                    escape(button.targetPageId()),
                    button.openMode().name(),
                    toJsonObject(button.params())
            );
        }

        private String toJsonArray(Collection<String> values) {
            return values.stream()
                    .map(value -> "\"" + escape(value) + "\"")
                    .collect(Collectors.joining(", ", "[", "]"));
        }

        private String toJsonObject(Map<String, String> map) {
            return map.entrySet().stream()
                    .map(e -> "\"" + escape(e.getKey()) + "\": \"" + escape(e.getValue()) + "\"")
                    .collect(Collectors.joining(", ", "{", "}"));
        }

        private String escape(String input) {
            return input.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }

    static final class BuilderService {
        private final AppConfig appConfig;

        BuilderService(AppConfig appConfig) {
            this.appConfig = Objects.requireNonNull(appConfig, "appConfig");
        }

        List<Page> visibleNavigationPages() {
            return appConfig.allPages().stream()
                    .filter(Page::showInNavigation)
                    .toList();
        }

        List<PagePickerOption> buttonTargetPickerPages() {
            return appConfig.allPages().stream()
                    .map(page -> new PagePickerOption(page.id(), page.title(), !page.showInNavigation()))
                    .toList();
        }

        List<String> validateButtonReferences() {
            List<String> warnings = new ArrayList<>();
            for (Page page : appConfig.allPages()) {
                for (ButtonAction button : page.buttons()) {
                    Optional<Page> target = appConfig.findPage(button.targetPageId());
                    if (target.isEmpty()) {
                        warnings.add("Warning: Button '" + button.id() + "' on page '" + page.title() + "' references a deleted page ('" + button.targetPageId() + "').");
                    } else if (target.get().showInNavigation()) {
                        warnings.add("Warning: Button '" + button.id() + "' on page '" + page.title() + "' references page '" + target.get().title() + "', which is no longer hidden.");
                    }
                }
            }
            return warnings;
        }
    }

    static final class Router {
        private final AppConfig appConfig;
        private final Deque<RouteFrame> navigationStack = new ArrayDeque<>();
        private final List<String> auditLog = new ArrayList<>();

        Router(AppConfig appConfig) {
            this.appConfig = Objects.requireNonNull(appConfig, "appConfig");
        }

        RouteResult clickNavigateButton(String sourcePageId, ButtonAction action, UserSession userSession) {
            Objects.requireNonNull(action, "action");
            Page target = resolveAccessiblePage(action.targetPageId(), userSession, "button");
            navigationStack.push(new RouteFrame(sourcePageId, target.id(), action.params(), action.openMode()));
            auditLog.add("BUTTON_NAVIGATION user=" + userSession.userId() + " source=" + sourcePageId + " target=" + target.id() + " mode=" + action.openMode());
            return new RouteResult(Optional.of(target), action.params(), navigationStack.size() > 1 || sourcePageId != null, "Opened page via button navigation.");
        }

        RouteResult openByUrl(String pageId, Map<String, String> parameters, UserSession userSession) {
            Page target = resolveAccessiblePage(pageId, userSession, "url");
            navigationStack.push(new RouteFrame(null, target.id(), new LinkedHashMap<>(parameters), OpenMode.SAME_VIEW));
            auditLog.add("DIRECT_URL_NAVIGATION user=" + userSession.userId() + " target=" + target.id());
            return new RouteResult(Optional.of(target), parameters, navigationStack.size() > 1, "Opened page directly by URL.");
        }

        RouteResult backOrClose(UserSession userSession) {
            if (navigationStack.isEmpty()) {
                return new RouteResult(Optional.empty(), Map.of(), false, "No open page to close.");
            }

            RouteFrame closed = navigationStack.pop();
            auditLog.add("BACK_OR_CLOSE user=" + userSession.userId() + " closedTarget=" + closed.targetPageId());

            if (closed.sourcePageId() == null) {
                Optional<Page> current = navigationStack.isEmpty()
                        ? Optional.empty()
                        : appConfig.findPage(navigationStack.peek().targetPageId());
                return new RouteResult(current, current.map(p -> navigationStack.peek().parameters()).orElse(Map.of()), !navigationStack.isEmpty(), "Closed current page.");
            }

            Optional<Page> source = appConfig.findPage(closed.sourcePageId());
            return new RouteResult(source, Map.of(), !navigationStack.isEmpty(), "Returned to source page.");
        }

        List<String> auditLog() {
            return Collections.unmodifiableList(auditLog);
        }

        private Page resolveAccessiblePage(String pageId, UserSession userSession, String accessType) {
            Page page = appConfig.findPage(pageId)
                    .orElseThrow(() -> new InvalidConfigurationException("Target page not found: " + pageId));
            enforceAccess(page, userSession, accessType);
            return page;
        }

        private void enforceAccess(Page page, UserSession userSession, String accessType) {
            boolean roleAllowed = page.allowedRoles().isEmpty() || page.allowedRoles().stream().anyMatch(userSession::hasRole);
            boolean permissionAllowed = page.requiredPermissions().isEmpty() || page.requiredPermissions().stream().allMatch(userSession::hasPermission);
            if (!roleAllowed || !permissionAllowed) {
                throw new AccessDeniedException("Access denied for " + accessType + " navigation to page '" + page.title() + "'. Hidden pages use the same role and permission checks.");
            }
        }
    }

    static final class AccessDeniedException extends RuntimeException {
        AccessDeniedException(String message) {
            super(message);
        }
    }

    static final class InvalidConfigurationException extends RuntimeException {
        InvalidConfigurationException(String message) {
            super(message);
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}

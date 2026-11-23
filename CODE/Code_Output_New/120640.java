package com.ascendion.datapowerAI.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.util.ArrayList;
import java.util.List;

/*
 * Story ID: 120640
 * Title: Configure DataPowerAI API Routing for AAVA Domain Deployment
 * Language: Java — the story targets a Spring Boot backend and the repo is a Java Maven Spring Boot service.
 *
 * What this code does:
 * - Configures the application to serve under the /ava/dpai context path without changing existing controller mappings.
 * - Keeps direct backend access available by making the context path configurable and optional via properties.
 * - Aligns forwarded headers and OpenAPI server URLs so domain-based routing, login API access, and Swagger work behind Kubernetes Ingress.
 *
 * Assumptions:
 * - No repo comments, attachments, links, or related work item details were returned by the work item reader.
 * - Existing Helm charts, Ingress, and Service manifests are outside the sampled repo content, so this file focuses on application-side routing compatibility.
 * - Set server.servlet.context-path=/ava/dpai in the target environment to enable the required domain prefix.
 * - Existing direct backend access continues where applicable by allowing the property to be overridden to an empty value in environments that still need root-path exposure.
 */
@Configuration(proxyBeanMethods = false)
class AavaDomainRoutingConfiguration {

    private static final String DEFAULT_CONTEXT_PATH = "/ava/dpai";

    @Bean
    WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> aavaContextPathCustomizer(
            @Value("${app.routing.context-path:" + DEFAULT_CONTEXT_PATH + "}") String configuredContextPath,
            @Value("${server.servlet.context-path:}") String existingServerContextPath) {

        return factory -> {
            String resolvedContextPath = normalizeContextPath(
                    hasText(existingServerContextPath) ? existingServerContextPath : configuredContextPath
            );
            factory.setContextPath(resolvedContextPath);
            factory.setPort(8080);
        };
    }

    @Bean
    ForwardedHeaderFilter forwardedHeaderFilter() {
        return new ForwardedHeaderFilter();
    }

    @Bean
    @Primary
    OpenAPI aavaDomainAwareOpenApi(
            @Value("${app.routing.context-path:" + DEFAULT_CONTEXT_PATH + "}") String configuredContextPath,
            @Value("${server.servlet.context-path:}") String existingServerContextPath,
            @Value("${app.routing.public-domain:}") String publicDomain,
            @Value("${server.port:8080}") String serverPort) {

        String resolvedContextPath = normalizeContextPath(
                hasText(existingServerContextPath) ? existingServerContextPath : configuredContextPath
        );

        OpenAPI openAPI = new OpenAPI()
                .info(new Info()
                        .title("Ascendion DataPowerAI - Data Apps")
                        .version("1.0.0")
                        .description("OpenAPI configuration aligned for AAVA domain and Kubernetes ingress routing."));

        List<Server> servers = new ArrayList<>();
        servers.add(new Server().url(buildLocalServerUrl(serverPort, resolvedContextPath)).description("Local / direct backend access"));

        if (hasText(publicDomain)) {
            servers.add(new Server().url(buildDomainServerUrl(publicDomain, resolvedContextPath)).description("AAVA domain routed access"));
        }

        openAPI.setServers(servers);
        return openAPI;
    }

    // ASSUMPTION: not found in repo content
    static String normalizeContextPath(String rawContextPath) {
        if (!hasText(rawContextPath) || "/".equals(rawContextPath.trim())) {
            return "";
        }

        String normalized = rawContextPath.trim();

        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }

        while (normalized.contains("//")) {
            normalized = normalized.replace("//", "/");
        }

        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        return normalized;
    }

    // ASSUMPTION: not found in repo content
    static String buildDomainServerUrl(String domain, String contextPath) {
        String normalizedDomain = domain.trim();

        if (!normalizedDomain.startsWith("http://") && !normalizedDomain.startsWith("https://")) {
            normalizedDomain = "https://" + normalizedDomain;
        }

        if (normalizedDomain.endsWith("/")) {
            normalizedDomain = normalizedDomain.substring(0, normalizedDomain.length() - 1);
        }

        return normalizedDomain + contextPath;
    }

    // ASSUMPTION: not found in repo content
    static String buildLocalServerUrl(String serverPort, String contextPath) {
        return "http://localhost:" + serverPort + contextPath;
    }

    // ASSUMPTION: not found in repo content
    static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    // ASSUMPTION: not found in repo content
    static final class HelmIngressValues {
        static final String YAML_SNIPPET = """
                # Suggested Helm values for Story 120640
                service:
                  port: 8080
                  targetPort: 8080
                ingress:
                  enabled: true
                  annotations:
                    nginx.ingress.kubernetes.io/rewrite-target: /$2
                    nginx.ingress.kubernetes.io/use-regex: \"true\"
                  hosts:
                    - host: <domain>
                      paths:
                        - path: /ava/dpai(/|$)(.*)
                          pathType: ImplementationSpecific
                env:
                  - name: SERVER_SERVLET_CONTEXT_PATH
                    value: /ava/dpai
                  - name: APP_ROUTING_CONTEXT_PATH
                    value: /ava/dpai
                  - name: APP_ROUTING_PUBLIC_DOMAIN
                    value: https://<domain>
                """;

        private HelmIngressValues() {
        }
    }

    // ASSUMPTION: not found in repo content
    static final class RoutingValidationChecklist {
        static final List<String> CHECKS = List.of(
                "Login API should resolve through https://<domain>/ava/dpai/auth/gettoken or the repo's active login endpoint mapping.",
                "Existing controller mappings remain unchanged because only the servlet context path is prefixed.",
                "Swagger UI and /v3/api-docs should resolve through the same /ava/dpai prefix behind ingress.",
                "Kubernetes Service must continue forwarding traffic to container port 8080.",
                "Forwarded headers from ingress are honored to avoid incorrect redirects and generated URLs."
        );

        private RoutingValidationChecklist() {
        }
    }
}

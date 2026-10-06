/*
 * Work Item: 96550 - Implement SSO in DPAI Using AAVA Starter Library
 *
 * What this program does:
 * This is a small, runnable Java 17+ command-line helper that validates and prints the
 * required SSO-related configuration properties/environment variables that would be
 * needed when integrating an SSO starter library (per the work item).
 *
 * NOTE / Assumption:
 * The Azure DevOps work item does not provide the actual AAVA Starter Library artifact,
 * its package names, or concrete SSO property keys; therefore this program cannot truly
 * "integrate" SSO in DPAI. Instead, it provides the closest useful tool: a configuration
 * checker that ensures required SSO settings are present and shows what would be used
 * for redirect/login endpoints.
 *
 * Compile:
 *   javac 96550.java
 * Run:
 *   java DpaiSsoConfigChecker [--print-all]
 *
 * Requires: Java 17+
 */

import java.util.*;

class DpaiSsoConfigChecker {

    // Minimal set of typical SSO/OIDC style configuration keys.
    // Since the story does not specify exact keys, these are assumptions.
    private static final List<ConfigKey> REQUIRED = List.of(
            new ConfigKey("AAVA_SSO_ISSUER", "Issuer/authority URL for AAVA SSO", true),
            new ConfigKey("AAVA_SSO_CLIENT_ID", "OAuth2/OIDC client id", true),
            new ConfigKey("AAVA_SSO_REDIRECT_URI", "Redirect/callback URL for DPAI", true),
            new ConfigKey("AAVA_SSO_LOGIN_URL", "Login/authorization endpoint (or base URL)", true)
    );

    private static final List<ConfigKey> OPTIONAL = List.of(
            new ConfigKey("AAVA_SSO_CLIENT_SECRET", "Client secret (if confidential client)", false),
            new ConfigKey("AAVA_SSO_SCOPES", "Requested scopes (space or comma separated)", false),
            new ConfigKey("AAVA_SSO_AUDIENCE", "Audience/resource (if applicable)", false)
    );

    static final class ConfigKey {
        final String name;
        final String description;
        final boolean required;

        ConfigKey(String name, String description, boolean required) {
            this.name = name;
            this.description = description;
            this.required = required;
        }
    }

    public static void main(String[] args) {
        boolean printAll = Arrays.asList(args).contains("--print-all");

        Map<String, String> values = new LinkedHashMap<>();
        List<String> missingRequired = new ArrayList<>();

        for (ConfigKey k : REQUIRED) {
            String v = readConfig(k.name);
            if (isBlank(v)) missingRequired.add(k.name);
            values.put(k.name, v);
        }
        for (ConfigKey k : OPTIONAL) {
            values.put(k.name, readConfig(k.name));
        }

        System.out.println("DPAI SSO Configuration Check (work item 96550)");
        System.out.println("------------------------------------------------");

        if (!missingRequired.isEmpty()) {
            System.out.println("Status: FAIL (missing required SSO configuration)");
            System.out.println("Missing required keys:");
            for (String m : missingRequired) {
                System.out.println("  - " + m);
            }
            System.out.println();
            printHelp();
            // Graceful termination; requirement didn't specify exit codes.
            return;
        }

        System.out.println("Status: PASS (required SSO configuration present)");
        System.out.println();

        // Print what would be used for redirect behavior per acceptance criteria.
        String loginUrl = values.get("AAVA_SSO_LOGIN_URL");
        String redirectUri = values.get("AAVA_SSO_REDIRECT_URI");
        System.out.println("When authentication is required, users should be redirected to:");
        System.out.println("  " + loginUrl);
        System.out.println("After login, the SSO provider should redirect back to:");
        System.out.println("  " + redirectUri);
        System.out.println();

        if (printAll) {
            System.out.println("Configuration values (environment variables preferred; -D system properties supported):");
            for (ConfigKey k : concat(REQUIRED, OPTIONAL)) {
                String v = values.get(k.name);
                // Avoid printing secrets in cleartext.
                if ("AAVA_SSO_CLIENT_SECRET".equals(k.name) && !isBlank(v)) {
                    v = "(set; " + v.length() + " chars)";
                }
                System.out.printf("  %-24s = %s%n", k.name, isBlank(v) ? "(not set)" : v);
            }
            System.out.println();
        }

        System.out.println("Assumptions used by this checker:");
        System.out.println("- The exact AAVA Starter Library coordinates and property names were not provided in the work item.");
        System.out.println("- This tool checks common SSO/OIDC-style configuration keys as environment variables or -D properties.");
    }

    private static List<ConfigKey> concat(List<ConfigKey> a, List<ConfigKey> b) {
        List<ConfigKey> out = new ArrayList<>(a.size() + b.size());
        out.addAll(a);
        out.addAll(b);
        return out;
    }

    private static String readConfig(String key) {
        // Env var takes precedence; fallback to Java system property (-DKEY=value)
        String env = System.getenv(key);
        if (!isBlank(env)) return env;
        return System.getProperty(key);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static void printHelp() {
        System.out.println("How to set configuration:");
        System.out.println("- Environment variables (recommended):");
        System.out.println("    export AAVA_SSO_ISSUER=...",);
        System.out.println("    export AAVA_SSO_CLIENT_ID=...",);
        System.out.println("    export AAVA_SSO_REDIRECT_URI=...",);
        System.out.println("    export AAVA_SSO_LOGIN_URL=...",);
        System.out.println();
        System.out.println("- Or JVM system properties:");
        System.out.println("    java -DAAVA_SSO_ISSUER=... -DAAVA_SSO_CLIENT_ID=... -DAAVA_SSO_REDIRECT_URI=... -DAAVA_SSO_LOGIN_URL=... DpaiSsoConfigChecker");
        System.out.println();
        System.out.println("Optional flags:");
        System.out.println("  --print-all   Print all detected config values (secrets are not printed)." );
    }
}

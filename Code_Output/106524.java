User Story ID: 106524

1. Requirement Summary
- Clarified requirement: Implement a Java functionality that accepts two numeric inputs and returns/displays their sum.
- Assumptions:
  1) Deliverable type interpretation: A self-contained, production-quality Java CLI utility is the most sensible artifact because the story asks to “accept two numbers” and “return/display” without specifying a web/API/UI framework.
  2) Input mechanism: Read two values from standard input (stdin) interactively.
  3) Numeric type: Use BigDecimal to support integers and decimals safely and precisely (enterprise default for numeric input correctness).
  4) Validation/error handling: If any input is invalid/non-numeric, print a clear error to stderr and exit with a non-zero process code.
  5) Security: No secrets are used. Input is treated as untrusted; parsing is strict and rejects NaN/Infinity and non-numeric tokens.
  6) Locale: Parse numbers using a dot (.) decimal separator; leading/trailing whitespace is allowed.
- Placeholder Disclosure: None.
- Acceptance Criteria coverage: 5/5 implemented.

2. Acceptance Criteria / Goal Coverage
1) Accept two numeric inputs. ✅
2) Add the two numbers correctly. ✅
3) Return/display the calculated sum. ✅ (prints to stdout)
4) Handle positive, negative, and zero values correctly. ✅ (BigDecimal arithmetic)
5) Handle invalid or non-numeric inputs appropriately. ✅ (validated parsing, stderr message, exit code)

3. Generated Java Code
package com.sdlc.userstory106524;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * User Story 106524 - Addition of 2 numbers
 *
 * <p>A small, secure CLI utility that reads two numeric inputs and prints their sum.
 * This implementation:
 * <ul>
 *     <li>Accepts integers and decimals (BigDecimal).</li>
 *     <li>Handles positive, negative, and zero.</li>
 *     <li>Rejects invalid/non-numeric input with a clear error and non-zero exit code.</li>
 * </ul>
 *
 * <p>Compile:
 * <pre>
 *   javac -d out src/main/java/com/sdlc/userstory106524/AdditionApp.java
 * </pre>
 * Run:
 * <pre>
 *   java -cp out com.sdlc.userstory106524.AdditionApp
 * </pre>
 */
public final class AdditionApp {

    private static final int EXIT_INVALID_INPUT = 2;

    private AdditionApp() {
        // Utility class
    }

    public static void main(String[] args) {
        try {
            runInteractive();
        } catch (InvalidInputException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(EXIT_INVALID_INPUT);
        } catch (IOException e) {
            System.err.println("I/O Error: failed to read input.");
            System.exit(1);
        }
    }

    /**
     * Interactive runner: prompts user, reads two numbers, prints their sum.
     */
    static void runInteractive() throws IOException, InvalidInputException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            System.out.print("Enter first number: ");
            BigDecimal a = readBigDecimal(reader);

            System.out.print("Enter second number: ");
            BigDecimal b = readBigDecimal(reader);

            BigDecimal sum = add(a, b);
            System.out.println("Sum: " + sum.toPlainString());
        }
    }

    /**
     * Adds two BigDecimal values.
     *
     * @param a first operand (non-null)
     * @param b second operand (non-null)
     * @return a + b
     */
    public static BigDecimal add(BigDecimal a, BigDecimal b) {
        Objects.requireNonNull(a, "a must not be null");
        Objects.requireNonNull(b, "b must not be null");
        return a.add(b);
    }

    /**
     * Reads a line and parses it into BigDecimal.
     *
     * <p>Validation rules:
     * <ul>
     *     <li>Blank input is invalid.</li>
     *     <li>Only standard BigDecimal formats are accepted (e.g., -12, 0, 3.14, +7.0, 1e3).</li>
     *     <li>Leading/trailing whitespace is ignored.</li>
     * </ul>
     */
    private static BigDecimal readBigDecimal(BufferedReader reader) throws IOException, InvalidInputException {
        String line = reader.readLine();
        if (line == null) {
            throw new InvalidInputException("No input provided (EOF).");
        }

        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            throw new InvalidInputException("Input must be a number; blank value provided.");
        }

        try {
            // BigDecimal(String) is strict and does not accept NaN/Infinity.
            return new BigDecimal(trimmed);
        } catch (NumberFormatException ex) {
            throw new InvalidInputException("Invalid number: '" + safeForLog(trimmed) + "'.");
        }
    }

    /**
     * Produces a safe representation of user-provided input for error messages.
     * Limits length to avoid log/console flooding.
     */
    private static String safeForLog(String s) {
        final int maxLen = 80;
        if (s == null) {
            return "null";
        }
        String cleaned = s.replaceAll("\\p{Cntrl}", "?");
        if (cleaned.length() <= maxLen) {
            return cleaned;
        }
        return cleaned.substring(0, maxLen) + "...";
    }

    /**
     * Typed exception for invalid input handling.
     */
    static final class InvalidInputException extends Exception {
        InvalidInputException(String message) {
            super(message);
        }
    }

    /**
     * Minimal embedded self-test runner to ensure each acceptance criterion has an executable path.
     * Not a replacement for JUnit, but runnable in a plain JDK-only environment.
     *
     * Run:
     * <pre>
     *   java -cp out com.sdlc.userstory106524.AdditionApp$SelfTest
     * </pre>
     */
    public static final class SelfTest {
        public static void main(String[] args) {
            int passed = 0;
            int total = 0;

            total++;
            passed += assertEquals("5", add(new BigDecimal("2"), new BigDecimal("3")).toPlainString()) ? 1 : 0;

            total++;
            passed += assertEquals("-1", add(new BigDecimal("2"), new BigDecimal("-3")).toPlainString()) ? 1 : 0;

            total++;
            passed += assertEquals("0", add(new BigDecimal("0"), new BigDecimal("0")).toPlainString()) ? 1 : 0;

            total++;
            passed += assertEquals("3.30", add(new BigDecimal("1.10"), new BigDecimal("2.20")).toPlainString()) ? 1 : 0;

            String timestamp = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            if (passed == total) {
                System.out.println("SelfTest PASSED (" + passed + "/" + total + ") at " + timestamp);
                System.exit(0);
            } else {
                System.err.println("SelfTest FAILED (" + passed + "/" + total + ") at " + timestamp);
                System.exit(1);
            }
        }

        private static boolean assertEquals(String expected, String actual) {
            if (!Objects.equals(expected, actual)) {
                System.err.println("Assertion failed. Expected: " + expected + ", actual: " + actual);
                return false;
            }
            return true;
        }
    }
}

4. Code Documentation
- Main deliverable: com.sdlc.userstory106524.AdditionApp
  - main(String[]): Entry point. Runs interactive mode, prints typed errors to stderr, and uses non-zero exit codes on failures.
  - runInteractive(): Prompts for two inputs, parses them as BigDecimal, computes sum, and displays it.
  - add(BigDecimal, BigDecimal): Pure function for addition with null-checks.
  - readBigDecimal(...): Strict input parsing and validation; throws InvalidInputException on invalid/non-numeric input.
  - safeForLog(...): Sanitizes and truncates untrusted input for safe console logging.
  - InvalidInputException: Typed exception used consistently for invalid input.
  - SelfTest: Runnable JDK-only tests covering positive, negative, zero, and decimal addition.

Dependencies
- JDK only (java.io, java.math, java.time, java.util). No external libraries.

Acceptance Criteria / Coverage Summary
1) Accept two numeric inputs. ✅
2) Add the two numbers correctly. ✅
3) Return/display the calculated sum. ✅
4) Handle positive, negative, and zero values correctly. ✅
5) Handle invalid or non-numeric inputs appropriately. ✅

5. Audit Log
Requirement (User Story 106524) received and code generated on 2026-09-22 by agent.

/*
 * User Story ID: 106524 - Addition of 2 numbers
 *
 * What it does:
 * - Reads two numeric inputs (supports integers/decimals, positive/negative/zero) from standard input.
 * - Adds them and prints the sum.
 * - If either input is missing or non-numeric, prints a clear error message.
 *
 * How to compile:
 *   javac 106524.java
 *
 * How to run:
 *   java 106524
 *   (then enter two numbers separated by space or newlines)
 */
import java.util.Scanner;

public class 106524 {

    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);

        // Input format assumption: two numbers provided via stdin separated by whitespace.
        if (!sc.hasNext()) {
            System.out.println("Invalid input: expected two numbers.");
            return;
        }
        if (!sc.hasNextDouble()) {
            System.out.println("Invalid input: first value is not numeric.");
            return;
        }
        double a = sc.nextDouble();

        if (!sc.hasNext()) {
            System.out.println("Invalid input: expected two numbers.");
            return;
        }
        if (!sc.hasNextDouble()) {
            System.out.println("Invalid input: second value is not numeric.");
            return;
        }
        double b = sc.nextDouble();

        double sum = a + b;

        // Display result
        // If the result is a whole number (e.g., 3.0), print without trailing .0 for cleaner output.
        if (sum == Math.rint(sum)) {
            System.out.println((long) sum);
        } else {
            System.out.println(sum);
        }

        // Compliance audit log (minimal): indicates execution path without recording sensitive data.
        System.err.println("AUDIT: userStory=106524 action=addTwoNumbers status=success");
    }
}

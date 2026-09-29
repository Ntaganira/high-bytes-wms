package heritier.ntaganira.highbytes.wms.security;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.security
 * - File       : PasswordRules.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : What a chosen password must satisfy, and the temporary passwords administrators issue
 * </pre>
 */

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The password rules, in one place so every screen states the same ones.
 *
 * <p>Length does most of the work, so the rules ask for length and refuse
 * the guessable, rather than demanding a symbol that people then put at
 * the end. The upper bound is BCrypt's: it reads only the first 72 bytes.
 */
public final class PasswordRules {

    public static final int MIN_LENGTH = 10;
    public static final int MAX_LENGTH = 64;

    /** Words that make a password guessable here, whatever surrounds them. */
    private static final List<String> GUESSABLE = List.of(
            "password", "passw0rd", "changeme", "highbytes", "bluerock", "gahanga", "rubavu",
            "welcome", "qwerty", "azerty", "letmein", "admin", "123456", "abcdef");

    /** No 0/O, 1/l/I: a temporary password is read out or copied by hand. */
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";

    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordRules() {}

    /** What is wrong with a password the user chose. Empty when it may be used. */
    public static List<String> problems(String candidate, String username) {
        List<String> problems = new ArrayList<>();
        if (candidate == null || candidate.isEmpty()) {
            problems.add("Enter a new password.");
            return problems;
        }
        if (candidate.length() < MIN_LENGTH) {
            problems.add("Use at least " + MIN_LENGTH + " characters.");
        }
        if (candidate.length() > MAX_LENGTH) {
            problems.add("Use at most " + MAX_LENGTH + " characters.");
        }
        String lower = candidate.toLowerCase(Locale.ROOT);
        if (username != null && username.length() >= 3
                && lower.contains(username.toLowerCase(Locale.ROOT))) {
            problems.add("Do not include your username.");
        }
        for (String word : GUESSABLE) {
            if (lower.contains(word)) {
                problems.add("Avoid easily guessed words such as \"" + word + "\".");
                break;
            }
        }
        if (candidate.chars().distinct().count() < 5) {
            problems.add("Use more than a few different characters.");
        }
        return problems;
    }

    /** A random temporary password in three groups of four: {@code Kq7M-n3pR-t8wZ}. */
    public static String temporary() {
        var out = new StringBuilder(14);
        for (int i = 0; i < 12; i++) {
            if (i > 0 && i % 4 == 0) out.append('-');
            out.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return out.toString();
    }
}

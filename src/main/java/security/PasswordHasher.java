package security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * מחלקת PasswordHasher מספקת:
 * 1. Hashing של סיסמה עם מליח (salt) מבוסס Blowfish_CTR
 * 2. אימות סיסמה מול ה-hash השמור
 * 3. גנרציית מפתח (deriveKey) מתוך סיסמה, מלח ו-cost
 * המטרה: להבטיח עמידות מול התקפות Dictionary ו-Brute-Force על ידי שימוש ב-COST גבוה
 */
public class PasswordHasher {


    /** גודל בלוק להצפנה בבייטים (64 ביט) */
    /** COST משמעו מספר סיבובים (2^COST) לחיזוק ההגנה */
    private static final int MIN_COST = 4;
    private static final int MAX_COST = 31;
    private static final int DEFAULT_COST = 12;
    private static final int HASH_LENGTH = 23;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * מחשב hash של סיסמה באופן הבא:
     * 1. יוצר מלח אקראי בטוח בגודל SALT_LENGTH
     * 2. מריץ hashPassword (Blowfish_CTR + XOR salt) על הסיסמה והמלח
     * 3. מקודד לתצורת Base64 בפורמט: hash$salt$cost
     *
     * @param password הסיסמה הגולמית
     * @return מחרוזת hash עם salt ו-cost
     * @throws IllegalArgumentException אם הסיסמה ריקה או null
     * @throws RuntimeException אם יש בעיה ביצירת hash
     */
    public static String hash(String password) {
        return hash(password, DEFAULT_COST);
    }

    /**
     * מחשב hash של סיסמה באופן הבא:
     * 1. יוצר מלח אקראי בגודל BLOCK_SIZE
     * 2. מריץ hashPassword (Blowfish_CTR + XOR salt) על הסיסמה והמלח
     * 3. מקודד לתצורת Base64 בפורמט: hash$salt$cost
     *
     * @param password הסיסמה הגולמית
     * @return מחרוזת hash עם salt ו-cost
     * @throws IllegalArgumentException אם הסיסמה ריקה
     */
    public static String hash(String password, int cost) {

        validatePassword(password);
        validateCost(cost);

        byte[] salt = Bcrypt.generateSalt();
        Blowfish_ECB state = Bcrypt.eks(cost, salt, password.getBytes(StandardCharsets.UTF_8));
        byte[] rawHash = Bcrypt.bcryptCrypt(state);

        String saltB64 = Bcrypt.encodeBase64(salt, 22);
        String hashB64 = Bcrypt.encodeBase64(rawHash, 31);
        return String.format("$2a$%02d$%s%s", cost, saltB64, hashB64);

    }

    /**
     * מאמת סיסמה שהוזנה מול ה-hash השמור:
     * 1. מפרק את ה-hash ל-hash, salt ו-cost
     * 2. מחשב מחדש hashPassword עם אותם salt ו-cost
     * 3. משווה בתים
     *
     * @param enteredPassword הסיסמה שהוזנה
     * @param storedPasswordHash המחרוזת מהמאגר
     * @return true אם מתאים, false אחרת
     */
    public static boolean verify(String enteredPassword, String storedPasswordHash) {
        if (enteredPassword == null || storedPasswordHash == null || !storedPasswordHash.startsWith("$2a$")) {
            performDummyComputation();
            return false;
        }

        try {
            if (storedPasswordHash.length() < 60) { // 4 + 2 + 1 + 22 + 31
                performDummyComputation();
                return false;
            }

            int cost = Integer.parseInt(storedPasswordHash.substring(4, 6));
            String rest = storedPasswordHash.substring(7);
            byte[] salt = Bcrypt.decodeBase64(rest.substring(0,22), 16);
            byte[] targetHash = Bcrypt.decodeBase64(rest.substring(22), HASH_LENGTH);

            Blowfish_ECB state = Bcrypt.eks(cost, salt, enteredPassword.getBytes(StandardCharsets.UTF_8));
            byte[] rawHash = Bcrypt.bcryptCrypt(state);

            return constantTimeEquals(targetHash, rawHash);
        } catch (Exception e) {
            performDummyComputation();
            return false;
        }
    }

    /**
     * ולידציה של cost
     */
    private static void validateCost(int cost) {
        if (cost < MIN_COST || cost > MAX_COST) {
            throw new IllegalArgumentException(
                    String.format("Cost חייב להיות בין %d ל-%d", MIN_COST, MAX_COST)
            );
        }
    }

    /**
     * ולידציה של password
     */
    private static void validatePassword(String password) {
        if (password == null || password.isEmpty())
            throw new IllegalArgumentException("Password cannot be empty");
    }

    /**
     * ביצוע חישוב dummy למניעת timing attacks
     */
    private static void performDummyComputation() {
        try {
            byte[] dummy = new byte[32];
            SECURE_RANDOM.nextBytes(dummy);
            MessageDigest.getInstance("SHA-256").digest(dummy);
        } catch (NoSuchAlgorithmException e) {
            // ignore
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }
    public static void main(String[] args) {
        // הסיסמה לבדיקה
        String password = "MySecretP@ssw0rd";

        // יצירת ה־hash
        String storedHash = PasswordHasher.hash(password);
        System.out.println("Stored hash: " + storedHash);

        // בדיקה של הסיסמה הנכונה
        boolean correct = PasswordHasher.verify(password, storedHash);
        System.out.println("Verify correct password: " + correct);

        // בדיקה של סיסמה שגויה
        boolean incorrect = PasswordHasher.verify("WrongPassword", storedHash);
        System.out.println("Verify wrong password:   " + incorrect);
    }
}

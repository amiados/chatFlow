package security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * מחלקת PasswordHasher מספקת:
 * 1. Hashing של סיסמה עם מליח (salt) מבוסס Blowfish_CTR
 * 2. אימות סיסמה מול ה-hash השמור
 * 3. גנרציית מפתח (deriveKey) מתוך סיסמה, מלח ו-cost
 * המטרה: להבטיח עמידות מול התקפות Dictionary ו-Brute-Force על ידי שימוש ב-COST גבוה
 */
public class PasswordHasher {

    private static final Logger LOGGER = Logger.getLogger(PasswordHasher.class.getName());

    /** גודל בלוק להצפנה בבייטים (64 ביט) */
    private static final int BLOCK_SIZE = 8;
    /** COST משמעו מספר סיבובים (2^COST) לחיזוק ההגנה */
    private static final int MIN_COST = 4;
    private static final int MAX_COST = 31;
    private static final int DEFAULT_COST = 12;
    private static final int HASH_LENGTH = 32;
    private static final int SALT_LENGTH = 16;

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
        if (password == null || password.isEmpty())
            throw new IllegalArgumentException("Password cannot be empty");

        validateCost(cost);

        try {
            byte[] salt = generateSecureSalt();
            byte[] passwordBytes = password.getBytes(StandardCharsets.UTF_8);
            byte[] hash = hashPassword(passwordBytes, salt, cost);

            Arrays.fill(passwordBytes, (byte) 0);

            return encodeBase64(hash) + "$" + encodeBase64(salt) + "$" + cost;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "שגיאה ביצירת hash לסיסמה", e);
            throw new RuntimeException("שגיאה ביצירת hash", e);
        }
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
        if (enteredPassword == null || storedPasswordHash == null) {
            performDummyComputation();
            return false;
        }

        try {
            String[] parts = storedPasswordHash.split("\\$");
            if (parts.length != 3){
                performDummyComputation();
                return false;
            }

            byte[] storedHash = decodeBase64(parts[0]);
            byte[] salt = decodeBase64(parts[1]);
            int cost = Integer.parseInt(parts[2]);

            validateCost(cost);

            byte[] enteredPasswordBytes = enteredPassword.getBytes(StandardCharsets.UTF_8);
            byte[] computedHash = hashPassword(enteredPasswordBytes, salt, cost);

            Arrays.fill(enteredPasswordBytes, (byte) 0);

            boolean result = constantTimeEquals(storedHash, computedHash);

            Arrays.fill(computedHash, (byte) 0);

            return result;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "שגיאה באימות סיסמה", e);
            performDummyComputation();
            return false;
        }
    }

    /**
     * ליבת חישוב ה-hash:
     *  - מריץ 2^cost סיבובי Blowfish_CTR על ה-
     *    hash הנוכחי ואז XOR עם salt
     *
     * @param password הסיסמה בבייטים
     * @param salt המלח שנוצר
     * @param cost כוח החישוב (למספר הסיבובים)
     * @return מערך בתים של hash
     */
    private static byte[] hashPassword(byte[] password, byte[] salt, int cost) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(password);
            sha256.update(salt);
            byte[] hash = sha256.digest();

            if (hash.length > HASH_LENGTH) {
                hash = Arrays.copyOf(hash, HASH_LENGTH);
            }

            Blowfish_CTR bf = new Blowfish_CTR(password, salt);
            int rounds = 1 << cost;

            for (int i = 0; i < rounds; i++) {
                hash = bf.process(hash);

                for (int j = 0; j < hash.length; j++) {
                    hash[j] ^= salt[j % salt.length];
                }
            }
            Arrays.fill(password, (byte) 0);
            return hash;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
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
     * קידוד ל-Base64
     */
    private static String encodeBase64(byte[] input) {
        return Base64.getEncoder().encodeToString(input);
    }

    /**
     * פענוח מ-Base64
     */
    private static byte[] decodeBase64(String input) {
        return Base64.getDecoder().decode(input);
    }

    private static byte[] generateSecureSalt() {
        byte[] salt = new byte[SALT_LENGTH];
        SECURE_RANDOM.nextBytes(salt);
        return salt;
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

    /**
     * השוואה constant-time למניעת timing attacks
     */
    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }

        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }

        return result == 0;
    }
}

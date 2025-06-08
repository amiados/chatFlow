package security;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * מחלקת PasswordHasher מספקת:
 * 1. Hashing של סיסמה עם מליח (salt) מבוסס Blowfish_CTR
 * 2. אימות סיסמה מול ה-hash השמור
 * 3. גנרציית מפתח (deriveKey) מתוך סיסמה, מלח ו-cost
 * המטרה: להבטיח עמידות מול התקפות Dictionary ו-Brute-Force על ידי שימוש ב-COST גבוה
 */
public class PasswordHasher {

    /** גודל בלוק להצפנה בבייטים (64 ביט) */
    private static final int BLOCK_SIZE = 8;
    /** COST משמעו מספר סיבובים (2^COST) לחיזוק ההגנה */
    private static final int COST = 12;

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
    public static String hash(String password) {
        if (password == null || password.isEmpty())
            throw new IllegalArgumentException("Password cannot be empty");

        byte[] salt = Blowfish_CTR.ivGenerator(BLOCK_SIZE);
        byte[] hash = hashPassword(password.getBytes(StandardCharsets.UTF_8), salt, COST);
        return encodeBase64(hash) + "$" + encodeBase64(salt) + "$" + COST;
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
        if (enteredPassword == null || storedPasswordHash == null)
            return false;

        String[] parts = storedPasswordHash.split("\\$");
        if (parts.length != 3) return false;

        byte[] hash = decodeBase64(parts[0]);
        byte[] salt = decodeBase64(parts[1]);
        int cost = Integer.parseInt(parts[2]);

        byte[] computed = hashPassword(enteredPassword.getBytes(StandardCharsets.UTF_8), salt, cost);
        return Arrays.equals(hash, computed);
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
        byte[] hash = Arrays.copyOf(password, password.length);
        Blowfish_CTR bf = new Blowfish_CTR(password, salt);
        int rounds = 1 << cost;
        for (int i = 0; i < rounds; i++) {
            hash = bf.process(hash);
            for (int j = 0; j < hash.length; j++) {
                hash[j] ^= salt[j % salt.length];
            }
        }
        return hash;
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
}

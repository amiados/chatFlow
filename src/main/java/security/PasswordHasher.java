package security;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

public class PasswordHasher {

    // גודל הבלוק עבור Blowfish
    private static final int BLOCK_SIZE = 8;

    // ערך ה-COST המשפיע על מספר הסיבובים (למשל, 12 משמעותו 2^12 סיבובים)
    private static final int COST = 12;

    // פונקציה לחישוב ה-hash של סיסמה
    public static String hash(String password) {
        if (password == null || password.isEmpty())
            throw new IllegalArgumentException("Password cannot be empty");

        // יצירת מלח אקראי בגודל BLOCK_SIZE באמצעות Blowfish_CTR
        byte[] salt = Blowfish_CTR.ivGenerator(BLOCK_SIZE);

        // חישוב ה-hash של הסיסמה, על ידי שילוב הסיסמה עם המלח
        byte[] hash = hashPassword(password.getBytes(StandardCharsets.UTF_8), salt, COST);

        // מקודד את ה-hash והמלח ב-base64 ומחזיר את הערכים בפורמט של hash$salt$cost
        return encodeBase64(hash) + "$" + encodeBase64(salt) + "$" + COST;
    }

    // פונקציה לוודא שהסיסמה שהוזנה תואמת ל-hash ששמור
    public static boolean verify(String enteredPassword, String storedPasswordHash) {
        if (enteredPassword == null || storedPasswordHash == null)
            return false;

        // מפרק את המחרוזת לשלושה חלקים: HASH, SALT, COST
        String[] parts = storedPasswordHash.split("\\$");
        if (parts.length != 3) return false;

        // מפענח את ה-HASH וה-SALT מ-BASE64 וממיר את ה-COST לערך מספרי
        byte[] hash = decodeBase64(parts[0]);
        byte[] salt = decodeBase64(parts[1]);
        int cost = Integer.parseInt(parts[2]);

        // מחשב מחדש את ה-HASH מהסיסמא שסופקה ומשווה בין הערך שחושב לערך שהתקבל
        byte[] computedHash = hashPassword(enteredPassword.getBytes(), salt, cost);
        return Arrays.equals(hash, computedHash);
    }

    // פונקציה לחישוב ה-hash של הסיסמה
    private static byte[] hashPassword(byte[] password, byte[] salt, int cost) {
        byte[] hash = Arrays.copyOf(password, password.length); // יתחיל ב-hash כמו הסיסמא

        // יצירת מופע של Blowfish_CTR עם הסיסמה והמלח
        Blowfish_CTR blowFish = new Blowfish_CTR(password, salt);

        // מספר הסיבובים (למשל 2^12 = 4096)
        int rounds = 1 << cost;

        // סיבובי ההצפנה והעדכון של ה-hash
        for (int i = 0; i < rounds; i++) {
            hash = blowFish.process(hash); // הצפנה של ה-hash הנוכחי

            // חיבור ה-salt ל-hash לאחר כל סיבוב
            for (int j = 0; j < hash.length; j++) {
                hash[j] ^= salt[j % salt.length]; // XOR עם המלח על כל byte
            }
        }
        return hash;
    }

    // פונקציה להפקת מפתח מתוך סיסמה, מלח ו-cost
    public static byte[] deriveKey(String password, byte[] salt, int cost) {
        if (password == null || salt == null || salt.length == 0)
            throw new IllegalArgumentException("Password and salt must not be null or empty");

        // חיבור הסיסמה והמלח ליצירת קלט ראשוני
        byte[] hash = new byte[password.getBytes().length + salt.length];
        System.arraycopy(password.getBytes(StandardCharsets.UTF_8), 0, hash, 0, password.getBytes(StandardCharsets.UTF_8).length);
        System.arraycopy(salt, 0, hash, password.getBytes(StandardCharsets.UTF_8).length, salt.length);

        // מספר הסיבובים (למשל 2^12 = 4096)
        int rounds = 1 << cost;

        // סיבובי ההצפנה עם Blowfish_CTR
        for (int i = 0; i < rounds; i++) {
            // כל סיבוב משתמש ב-input חדש (מהסיבוב הקודם) ומעדכן אותו
            Blowfish_CTR blowFish = new Blowfish_CTR(Arrays.copyOf(hash, hash.length), salt); // יצירת מופע של Blowfish_CTR
            hash = blowFish.process(hash);

            // מוסיפים תלות במספר הסיבוב - מחזקים את הקשר לסיבוב
            for (int j = 0; j < hash.length; j++) {
                hash[j] ^= (byte) (i & 0xFF);  // XOR עם מספר הסיבוב
            }
        }

        // מחזיר את המפתח המגולל באורך 32 בייטים
        return Arrays.copyOf(hash, 32);
    }

    // פונקציה להמיר מידע ל-BASE64
    private static String encodeBase64(byte[] input) {
        return Base64.getEncoder().encodeToString(input);  // מקודד את המידע ל-base64
    }

    // פונקציה לפענח מידע מ-BASE64
    private static byte[] decodeBase64(String input) {
        return Base64.getDecoder().decode(input);  // מפענח את המידע מ-base64
    }
}

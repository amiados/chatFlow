package security;

import model.User;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Properties;
import java.util.UUID;

/**
 * מחלקת Token מייצרת ומוודאת טוקן מבוסס HMAC עבור משתמש.
 * הטוקן כולל:
 *  - UUID של המשתמש
 *  - זמן הנפקה (issuedAt) במילישניות
 *  - זמן תפוגה (expiresAt) = issuedAt + EXPIRATION_TIME
 *  מבנה הטוקן: Base64(payload) + "$" + Base64(signature)
 *  כאשר payload = "userId:issuedAt:expiresAt" והחתימה היא HMAC על ה-payload.
 */
public class Token {

    /** זמן תפוגה קבוע של 15 דקות (במילישניות) */
    private static final long EXPIRATION_TIME = 15 * 60 * 1000;

    /** מפתח סודי בגודל 256 ביט לשימוש ב-HMAC (יש לאחסן אך ורק בשרת) */
    private static final byte[] SECRET_KEY;

    static {
        Properties props = new Properties();
        try (InputStream in = Token.class
                .getClassLoader()
                .getResourceAsStream("application.properties")){

            if (in == null) {
                throw new RuntimeException("Token: לא נמצא application.properties ב־classpath");
            }

            props.load(in);
            String secret = props.getProperty("token.secret");
            if (secret == null || secret.isEmpty()) {
                throw new RuntimeException("Token: token.secret.base64 לא מוגדר ב־application.properties");
            }

            SECRET_KEY = Base64.getDecoder().decode(secret);
        } catch (IOException e) {
            throw new RuntimeException("Token: שגיאה בטעינת application.properties: " + e.getMessage(), e);
        }
    }
    private final UUID userId;      // מזהה המשתמש שאליו שייך הטוקן
    private final long issuedAt;    // זמן הנפקת הטוקן במילישניות
    private final String token;     // מחרוזת הטוקן המלאה

    /**
     * קונסטרקטור: יוצר טוקן חדש למשתמש נתון.
     * @param user אובייקט User המכיל את מזהה המשתמש
     */
    public Token(User user) {
        this.userId = user.getId();
        this.issuedAt = System.currentTimeMillis();
        this.token = generateToken();
    }

    /**
     * בונה ומחזיר את מחרוזת הטוקן:
     * 1. מחשב expiresAt = issuedAt + EXPIRATION_TIME
     * 2. יוצר payload = "userId:issuedAt:expiresAt"
     * 3. מחשב signature = HMAC(SECRET_KEY, payload)
     * 4. מחזיר Base64(payload) + "$" + Base64(signature)
     */
    private String generateToken() {
        long expiresAt = issuedAt + EXPIRATION_TIME;
        String payload = userId + ":" + issuedAt + ":" + expiresAt;
        byte[] signature = HMAC.generateHMAC(SECRET_KEY, payload.getBytes(StandardCharsets.UTF_8));

        String encodedPayload = Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String encodedSignature = Base64.getEncoder().encodeToString(signature);

        return encodedPayload + "$" + encodedSignature;
    }

    /**
     * מפענח ומחזיר את ה-UUID של המשתמש מתוך הטוקן.
     * @param token מחרוזת הטוקן
     * @return UUID או null אם הפורמט אינו תקין
     */
    public static UUID extractUserId(String token) {
        try {
            String[] parts = token.split("\\$");
            if (parts.length != 2) return null;

            byte[] payloadBytes = Base64.getDecoder().decode(parts[0]);
            String payload = new String(payloadBytes, StandardCharsets.UTF_8);
            String[] fields = payload.split(":");
            if (fields.length != 3) return null;

            return UUID.fromString(fields[0]);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * מחלץ את זמן התפוגה (expiresAt) ממחרוזת הטוקן.
     * @param token הטוקן בפורמט זהה ל-generateToken
     * @return זמן התפוגה במילישניות
     * @throws IllegalArgumentException אם הפורמט אינו תקין
     */
    public static long extractExpiry(String token) {
        String[] parts = token.split("\\$");
        if (parts.length != 2) throw new IllegalArgumentException("Invalid token format");

        String payload = new String(Base64.getDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        String[] fields = payload.split(":");
        if (fields.length != 3) throw new IllegalArgumentException("Invalid token payload");

        return Long.parseLong(fields[2]);
    }

    /**
     * מחלץ את זמן ההנפקה (issuedAt) ממחרוזת הטוקן.
     * @param token הטוקן בפורמט זהה ל-generateToken
     * @return זמן ההנפקה במילישניות
     * @throws IllegalArgumentException אם הפורמט אינו תקין
     */
    public static long extractIssuedAt(String token) {
        String[] parts = token.split("\\$");
        if (parts.length != 2) throw new IllegalArgumentException("Invalid token format");

        String payload = new String(Base64.getDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        String[] fields = payload.split(":");
        if (fields.length != 3) throw new IllegalArgumentException("Invalid token payload");

        return Long.parseLong(fields[1]);
    }

    /**
     * מאמת את תקינות הטוקן:
     * 1. מחלק לחלקי payload ו-signature
     * 2. מחשב HMAC על ה-payload ומשווה ל-signature
     * 3. בודק פורמט ה-UUID
     * 4. בודק אם זמן נוכחי לפני expiresAt
     * @param token הטוקן לבדיקה
     * @return true אם תקין, false אחרת
     */
    public static boolean verifyToken(String token) {
        try {
            String[] parts = token.split("\\$");
            if (parts.length != 2) return false;

            byte[] payloadBytes = Base64.getDecoder().decode(parts[0]);
            byte[] receivedSig = Base64.getDecoder().decode(parts[1]);

            byte[] expectedSig = HMAC.generateHMAC(SECRET_KEY, payloadBytes);
            if (!MessageDigest.isEqual(expectedSig, receivedSig)) return false;

            String payload = new String(payloadBytes, StandardCharsets.UTF_8);
            String[] fields = payload.split(":");
            if (fields.length != 3) return false;

            // אימות UUID
            try { UUID.fromString(fields[0]); } catch (IllegalArgumentException e) { return false; }

            long expiresAt = Long.parseLong(fields[2]);
            if (Instant.now().toEpochMilli() > expiresAt) return false;

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * מחזיר את מחרוזת הטוקן שהופקה
     */
    public String getToken() {
        return token;
    }

    /**
     * בודק אם הטוקן עדיין בתוקף בהתאם ל-issuedAt ו-EXPIRATION_TIME
     * @param now הזמן הנוכחי
     * @return true אם עדיין בתוקף
     */
    public boolean isValid(Instant now) {
        long expiresAt = issuedAt + EXPIRATION_TIME;
        return now.toEpochMilli() < expiresAt;
    }
}

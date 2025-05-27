package security;

import model.User;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

public class Token {

    // min * sec * millisecond -> 15 * 60 * 1000 -> 15 minutes
    private static final long EXPIRATION_TIME = 900000;

    // בגודל 256 ביט (32 בית)
    private static final byte[] SECRET_KEY = "SuperSecretKey123!".getBytes(StandardCharsets.UTF_8); // אחסן רק בשרת

    private final UUID userId;
    private final long issuedAt;
    private final String token;

    public Token(User user) {
        this.userId = user.getId();
        this.issuedAt = System.currentTimeMillis();
        this.token = generateToken();
    }

    private String generateToken() {
        long expiresAt = issuedAt + EXPIRATION_TIME;
        String token = userId + ":" + issuedAt + ":" + expiresAt;
        byte[] signature = HMAC.generateHMAC(SECRET_KEY, token.getBytes(StandardCharsets.UTF_8));

        String encodedToken = Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
        String encodedSignature = Base64.getEncoder().encodeToString(signature);

        return encodedToken + "$" + encodedSignature;
    }

    public static UUID extractUserId(String token){
        try {
            String[] parts = token.split("\\$");
            if(parts.length != 2) return null;

            byte[] tokenBytes = Base64.getDecoder().decode(parts[0]);
            String payload = new String(tokenBytes, StandardCharsets.UTF_8);
            String[] fields = payload.split(":");
            if (fields.length != 3) return  null;

            return UUID.fromString(fields[0]);
        } catch (Exception e){
            return null;
        }
    }

    /** מחלץ את שעת התפוגה מתוך ה־payload*/
    public static long extractExpiry(String token) {
        String[] parts = token.split("\\$");
        if (parts.length != 2) throw new IllegalArgumentException("Invalid token format");
        byte[] payloadBytes = Base64.getDecoder().decode(parts[0]);
        String payload = new String(payloadBytes, StandardCharsets.UTF_8);
        String[] fields = payload.split(":");
        if (fields.length != 3) throw new IllegalArgumentException("Invalid token payload");
        return Long.parseLong(fields[2]);
    }

    /** *  מחלץ את שעת ההנפקה מתוך ה־payload */
    public static long extractIssuedAt(String token) {
        String[] parts = token.split("\\$");
        if (parts.length != 2) throw new IllegalArgumentException("Invalid token format");
        byte[] payloadBytes = Base64.getDecoder().decode(parts[0]);
        String payload = new String(payloadBytes, StandardCharsets.UTF_8);
        String[] fields = payload.split(":");
        if (fields.length != 3) throw new IllegalArgumentException("Invalid token payload");
        return Long.parseLong(fields[1]);
    }

    public static boolean verifyToken(String token) {
        try {
            // חלוקה של הטוקל לשני חלקים (המוצפן והחתימה)
            String[] parts = token.split("\\$");
            if (parts.length != 2) return false;

            // המרת הטוקן לבתים
            byte[] tokenBytes = Base64.getDecoder().decode(parts[0]);
            byte[] receivedSignature  = Base64.getDecoder().decode(parts[1]);

            // יצירת חתימה שצפויה להתקבל
            byte[] expectedSignature = HMAC.generateHMAC(SECRET_KEY, tokenBytes);

            // השוואת חתימות
            if(!MessageDigest.isEqual(expectedSignature, receivedSignature)) {
                return false;
            }

            String payload = new String(tokenBytes, StandardCharsets.UTF_8);
            String[] fields = payload.split(":");

            // אם לא כל השדות קיימים
            if (fields.length != 3){
                return false;
            }

            // אימות UUID
            UUID userId;
            try{
                userId = UUID.fromString(fields[0]); // Validate UUID
            } catch (IllegalArgumentException e){
                return false;
            }

            // בדיקת תוקף
            long expiresAt = Long.parseLong(fields[2]);
            Instant expirationTime = Instant.ofEpochMilli(expiresAt);
            if (Instant.now().isAfter(expirationTime)) {
                return false; // אם הזמן הנוכחי אחרי זמן התפוגה
            }

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String getToken() {
        return token;
    }

    public boolean isValid(Instant now) {
        long expiresAt = issuedAt + EXPIRATION_TIME;
        return now.toEpochMilli() < expiresAt;
    }
}

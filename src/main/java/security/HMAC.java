package security;

import java.security.MessageDigest;
import java.util.Arrays;

/**
 * מחלקת HMAC מממשת אלגוריתם HMAC-SHA256:
 *  HMAC(K, M) = SHA256((K' ⊕ opad) || SHA256((K' ⊕ ipad) || M))
 *  כאשר K' הוא המפתח מותאם לגודל בלוק 64 בתים.
 */
public class HMAC {
    /** גודל הבלוק בבתים ל-HMAC-SHA256 (512 ביט) */
    private static final int BLOCK_SIZE = 64;

    /**
     * מייצר תג אימות HMAC עבור הודעה נתונה ומפתח נתון.
     *
     * @param key המפתח הסודי (K), יכול להיות בכל אורך
     * @param message ההודעה (M) עליו מחושבת ה-HMAC
     * @return מערך בתים של HMAC-SHA256 באורך 32 בתים
     * @throws RuntimeException אם חישוב ה-HMAC נכשל
     */
    public static byte[] generateHMAC(byte[] key, byte[] message) {
        try {
            // יצירת מופע SHA-256
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");

            // אם המפתח ארוך מ-BLOCK_SIZE, מקצרים אותו ב-SHA-256
            if (key.length > BLOCK_SIZE) {
                key = sha256.digest(key);
            }

            // אתחול בלוק מפתח באורך BLOCK_SIZE (padding עם אפסים)
            byte[] keyBlock = new byte[BLOCK_SIZE];
            System.arraycopy(key, 0, keyBlock, 0, key.length);

            // יצירת iPad ו-oPad על ידי XOR עם 0x36 ו-0x5C
            byte[] iKeyPad = new byte[BLOCK_SIZE];
            byte[] oKeyPad = new byte[BLOCK_SIZE];
            for (int i = 0; i < BLOCK_SIZE; i++) {
                iKeyPad[i] = (byte) (keyBlock[i] ^ 0x36);
                oKeyPad[i] = (byte) (keyBlock[i] ^ 0x5C);
            }

            // חישוב hash פנימי: SHA256(iPad || message)
            MessageDigest innerDigest = MessageDigest.getInstance("SHA-256");
            innerDigest.update(iKeyPad);
            innerDigest.update(message);
            byte[] innerHash = innerDigest.digest();

            // חישוב hash חיצוני: SHA256(oPad || innerHash)
            MessageDigest outerDigest = MessageDigest.getInstance("SHA-256");
            outerDigest.update(oKeyPad);
            outerDigest.update(innerHash);
            byte[] hmac = outerDigest.digest();

            // ניקוי נתונים רגישים מהזיכרון
            Arrays.fill(keyBlock, (byte) 0);
            Arrays.fill(iKeyPad, (byte) 0);
            Arrays.fill(oKeyPad, (byte) 0);

            return hmac;

        } catch (Exception e) {
            throw new RuntimeException("HMAC calculation failed", e);
        }
    }
}

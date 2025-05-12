package security;
import java.security.MessageDigest;
import java.util.Arrays;

public class HMAC {
    private static final int BLOCK_SIZE = 64;

    // HMAC(K, M) = hash((K' ⊕ opad) || hash((K' ⊕ ipad) || M))

    public static byte[] generateHMAC(byte[] key, byte[] message) {
        try {

            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");

            if (key.length > BLOCK_SIZE) {
                key = sha256.digest(key); // מקצר את המפתח אם הוא ארוך מדי
            }

            // השלמה עם אפסים עד לגודל הבלוק
            byte[] keyBlock = new byte[BLOCK_SIZE];
            System.arraycopy(key, 0, keyBlock, 0, key.length);

            byte[] oKeyPad = new byte[BLOCK_SIZE];
            byte[] iKeyPad = new byte[BLOCK_SIZE];

            for (int i = 0; i < BLOCK_SIZE; i++) {
                oKeyPad[i] = (byte) (keyBlock[i] ^ 0x5C);
                iKeyPad[i] = (byte) (keyBlock[i] ^ 0x36);
            }

            // inner hash
            MessageDigest innerDigest = MessageDigest.getInstance("SHA-256");
            innerDigest.update(iKeyPad);
            innerDigest.update(message);
            byte[] innerHash = sha256.digest();

            // outer hash
            MessageDigest outerDigest = MessageDigest.getInstance("SHA-256");
            outerDigest.update(oKeyPad);
            outerDigest.update(innerHash);

            // Clean sensitive data
            Arrays.fill(keyBlock, (byte) 0);
            Arrays.fill(iKeyPad, (byte) 0);
            Arrays.fill(oKeyPad, (byte) 0);

            return outerDigest.digest();

        } catch (Exception e) {
            throw new RuntimeException("HMAC calculation failed", e);
        }
    }
}

package security;

import java.security.SecureRandom;
import java.util.Arrays;
import static security.AES_ECB.*;

public class AES_CTR {
    private static final int IV_LENGTH = 12; // אורך ה-IV (12 בתים, 96 ביטים)
    private static final int BLOCK_SIZE = 16; // גודל הבלוק ב-AES (16 בתים)

    // פונקציה המפענחת הודעה במצב security.AES_CTR
    protected static byte[] decryptCTR(byte[] ciphertext, byte[][] roundKeys, byte[] iv) {
        return encryptCTR(ciphertext, roundKeys, iv);  // הפענוח הוא זהה להצפנה במצב security.AES_CTR
    }

    // פונקציה המפצפת הודעה במצב security.AES_CTR
    protected static byte[] encryptCTR(byte[] plainText, byte[][] roundKeys, byte[] iv) {
        byte[] cipher = new byte[plainText.length]; // המערך שבו תישמר ההודעה המפוצפת
        byte[] counter = Arrays.copyOf(iv, BLOCK_SIZE); // יצירת אוגדן מתחיל מ-IV
        byte[] encryptedCounter; // משתנה לשמירת התוצאה לאחר הצפנת האוגדן

        // הצפנה של ההודעה בלוקים, כאשר כל בלוק בגודל BLOCK_SIZE
        for (int pos = 0; pos < plainText.length; pos += BLOCK_SIZE) {

            // הצפנת האוגדן הנוכחי
            encryptedCounter = encrypt_block(counter, roundKeys);

            // חישוב ה-XOR בין האוגדן המוצפן להודעה הגולמית כדי לקבל את ההודעה המפוצפת
            int blockLength = Math.min(BLOCK_SIZE, plainText.length - pos);
            xor(encryptedCounter, plainText, pos, blockLength, cipher, pos);

            // הגדלת האוגדן לצורך השימוש הבא
            incrementCounter(counter);
        }
        return cipher;
    }

    // פונקציה לחישוב XOR בין אוגדן מוצפן להודעה, ותוצאה שתישמר במערך המוצפן
    private static void xor(byte[] encryptedCounter, byte[] plainText, int pos, int length, byte[] cipher, int outPos) {
        for (int i = 0; i < length; i++)
            cipher[outPos + i] = (byte) (plainText[pos + i] ^ encryptedCounter[i]);
    }

    // פונקציה ליצירת IV ייחודי בעזרת SecureRandom
    public static byte[] ivGenerator() {
        SecureRandom random = new SecureRandom();
        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(iv);
        return iv;
    }

    // פונקציה להגדלת אוגדן הספירה ב-1
    // זהו תהליך של חיבור מחזורי, המתחיל מהבית הפחות משמעותי (LSB) וממשיך עד להגעת הבית המשמעותי ביותר (MSB)
    private static void incrementCounter(byte[] counter) {
        int pos = counter.length - 1;
        while (pos >= 0) {
            if (++counter[pos] != 0)
                break; // אם לא הייתה חריגה (overflow) יוצאים מהלולאה
            pos--; // המשך מהבית הבא
        }
    }
}

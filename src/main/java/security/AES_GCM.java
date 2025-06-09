package security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import static security.AES_CTR.*;
import static security.AES_ECB.*;

/**
 * מחלקת AES_GCM מממשת את אלגוריתם ההצפנה והפענוח AES-GCM.
 *  - TAG_SIZE: גודל תג האימות (16 בתים)
 *  - IV_LENGTH: אורך ה-IV (12 בתים)
 *  - BLOCK_SIZE: גודל בלוק AES (16 בתים)
 */
public class AES_GCM {
    /** גודל התג באימות (בבתים) */
    public static final int TAG_SIZE = 16;
    /** אורך IV ב-GCM (12 בתים, 96 ביט) */
    private static final int IV_LENGTH = 12;
    /** גודל בלוק AES (16 בתים) */
    private static final int BLOCK_SIZE = 16;

    /**
     * מפעיל AES-GCM להצפנת הטקסט הגולמי ביחד עם AAD:
     * מחזיר מערך בתים המסודר: IV || ciphertext || tag
     *
     * @param plainText הטקסט הגולמי להצפנה
     * @param AAD נתונים נוספים לאימות (אופציונלי)
     * @param round_keys מפתחות הסיבוב שנוצרו מ-key schedule
     * @return מערך בתים: IV + ciphertext + תג האימות
     */
    public static byte[] encrypt(byte[] plainText, byte[] AAD, byte[][] round_keys) {
        // 1. צור IV אקראי בן 12 בתים
        byte[] iv = ivGenerator();

        // 2. הרכב את J0 = IV || 0x00000001
        byte[] J0 = new byte[BLOCK_SIZE];
        System.arraycopy(iv, 0, J0, 0, IV_LENGTH);
        J0[15] = 1;

        // 3. חשב H = E(K, 0^128) לשימוש ב-GHASH
        byte[] H = encrypt_block(new byte[BLOCK_SIZE], round_keys);

        // 4. הצפן ב-CTR החל מ-J0+1
        byte[] firstCounter = Arrays.copyOf(J0, BLOCK_SIZE);
        incrementCounter(firstCounter);
        byte[] cipher = encryptCTR(plainText, round_keys, firstCounter);

        // 5. חשב את תג האימות: Tag = E(K, J0) ⊕ GHASH(H, AAD, cipher)
        byte[] tag = generateAuthTag(cipher, AAD, round_keys, J0, H);

        // 6. הרכב את הפלט: IV || ciphertext || tag
        byte[] encryptedMessage = new byte[IV_LENGTH + cipher.length + TAG_SIZE];
        System.arraycopy(iv, 0, encryptedMessage, 0, IV_LENGTH);
        System.arraycopy(cipher, 0, encryptedMessage, IV_LENGTH, cipher.length);
        System.arraycopy(tag, 0, encryptedMessage, IV_LENGTH + cipher.length, tag.length);

        return encryptedMessage;
    }

    /**
     * מפעיל AES-GCM לפענוח:
     * מצפה למערך בתים: IV || ciphertext || tag
     *
     * @param encryptedMessage מערך בתים להצפייה: IV + ciphertext + tag
     * @param AAD נתונים נוספים לאימות
     * @param round_keys מפתחות הסיבוב
     * @return הטקסט הגולמי לאחר פענוח
     * @throws IllegalArgumentException אם הנתונים לא תקפים
     * @throws SecurityException אם אימות התג נכשל
     */
    public static byte[] decrypt(byte[] encryptedMessage, byte[] AAD, byte[][] round_keys) {
        if (encryptedMessage == null || encryptedMessage.length == 0) {
            throw new IllegalArgumentException("Data to decrypt cannot be null or empty.");
        }
        if (round_keys == null || round_keys.length == 0) {
            throw new IllegalArgumentException("Round keys are not valid.");
        }

        // פיצול IV, ciphertext, ו-tag
        byte[] iv = Arrays.copyOfRange(encryptedMessage, 0, IV_LENGTH);
        byte[] cipher = Arrays.copyOfRange(encryptedMessage, IV_LENGTH, encryptedMessage.length - TAG_SIZE);
        byte[] tag = Arrays.copyOfRange(encryptedMessage, encryptedMessage.length - TAG_SIZE, encryptedMessage.length);

        // הרכב J0 מחדש
        byte[] J0 = new byte[BLOCK_SIZE];
        System.arraycopy(iv, 0, J0, 0, IV_LENGTH);
        J0[15] = 1;

        // חשב H לשימוש ב-GHASH
        byte[] H = encrypt_block(new byte[BLOCK_SIZE], round_keys);

        // אימות התג
        if (!verifyTag(cipher, AAD, tag, round_keys, J0, H))
            throw new SecurityException("Authentication failed. Data may have been tampered with.");

        // פענוח ב-CTR החל מ-J0+1
        byte[] firstCounter = Arrays.copyOf(J0, BLOCK_SIZE);
        incrementCounter(firstCounter);
        return decryptCTR(cipher, round_keys, firstCounter);
    }

    /**
     * בודק שהתכלול של תג תקין על ידי חישוב תג מחדש והשוואה
     */
    private static boolean verifyTag(byte[] cipher, byte[] AAD, byte[] authTag, byte[][] roundKeys, byte[] J0, byte[] H) {
        byte[] computedTag = generateAuthTag(cipher, AAD, roundKeys, J0, H);
        return Arrays.equals(computedTag, authTag);
    }

    /**
     * מחשב את תג האימות: E(K,J0) ⊕ GHASH(H, AAD, cipher)
     */
    private static byte[] generateAuthTag(byte[] cipher, byte[] AAD, byte[][] round_keys, byte[] J0, byte[] H) {
        byte[] X = ghash(H, AAD, cipher);
        byte[] E_J0 = encrypt_block(J0, round_keys);
        byte[] tag = new byte[TAG_SIZE];
        for (int i = 0; i < TAG_SIZE; i++) {
            tag[i] = (byte) (E_J0[i] ^ X[i]);
        }
        return tag;
    }

    /**
     * מבצע GHASH על AAD ו-cipher, כולל padding והוספת בלוק אורכי
     */
    private static byte[] ghash(byte[] H, byte[] AAD, byte[] cipher) {
        byte[] Y = new byte[BLOCK_SIZE];

        // עיבוד AAD
        if (AAD != null && AAD.length > 0) {
            byte[] AAD_padded = zeroPad(AAD);
            for (int pos = 0; pos < AAD_padded.length; pos += BLOCK_SIZE) {
                byte[] block = Arrays.copyOfRange(AAD_padded, pos, pos + BLOCK_SIZE);
                xor(Y, block);
                Y = GF_Multiply(Y, H);
            }
        }

        // עיבוד הטקסט המוצפן
        if (cipher.length > 0) {
            byte[] cipher_padded = zeroPad(cipher);
            for (int pos = 0; pos < cipher_padded.length; pos += BLOCK_SIZE) {
                byte[] block = Arrays.copyOfRange(cipher_padded, pos, pos + BLOCK_SIZE);
                xor(Y, block);
                Y = GF_Multiply(Y, H);
            }
        }

        // בלוק אורכי AAD ו-cipher (ביטים)
        byte[] lengthBlock = new byte[BLOCK_SIZE];
        System.arraycopy(longToBytes(AAD.length * 8L), 0, lengthBlock, 0, 8);
        System.arraycopy(longToBytes(cipher.length * 8L), 0, lengthBlock, 8, 8);

        xor(Y, lengthBlock);
        Y = GF_Multiply(Y, H);
        return Y;
    }

    /**
     * XOR ביט לביט בין מערכים
     */
    private static void xor(byte[] a, byte[] b) {
        for (int i = 0; i < a.length; i++) {
            a[i] ^= b[i];
        }
    }

    /**
     * ממיר מספר long למערך בתים (Big Endian)
     */
    private static byte[] longToBytes(long val) {
        byte[] result = new byte[8];
        for (int i = 0; i < 8; i++) {
            result[7 - i] = (byte) (val >>> (i * 8));
        }
        return result;
    }

    /**
     * ביצוע כפל בשדה גאלואה GF(2^128)
     *  משמש כדי לקבל ערך שתלוי במערך שהתקבל. שינוי של אפילו ביט אחד יגרום לשינוי בערך
     */
    private static byte[] GF_Multiply(byte[] a, byte[] b) {
        byte[] result = new byte[BLOCK_SIZE];
        byte[] tempA = Arrays.copyOf(a, BLOCK_SIZE);

        for (int i = 0; i < 128; i++) {
            // מפצל את מערך B לסיביות, כל סבב עובדים על הסיבית הבאה
            int bit = (b[15 - (i / 8)] >> (7 - (i % 8))) & 1;
            // אם הסיבית היא 1 -> לבצע XOR
            if (bit == 1) {
                xor(result, tempA);
            }
            // לבדוק אם יש
            boolean carry = (tempA[0] & 0x80) != 0;
            shiftLeft(tempA);
            if (carry) {
                tempA[0] ^= (byte) 0x87; // פולינום אי-פריק
            }
        }
        return result;
    }

    /**
     * הזזה שמאלה של מערך בתים עם העברת נשירה
     */
    private static void shiftLeft(byte[] a) {
        byte carry = 0;
        for (int i = a.length - 1; i >= 0; i--) {
            byte nextCarry = (byte) ((a[i] & 0x80) != 0 ? 1 : 0);
            a[i] = (byte) ((a[i] << 1) | carry);
            carry = nextCarry;
        }
    }

    /**
     * מגדיל את הספירה בתווי נגד מ-IV_LENGTH ועד סוף הבלוק
     */
    private static void incrementCounter(byte[] counter) {
        for (int i = BLOCK_SIZE - 1; i >= IV_LENGTH; i--) {
            counter[i]++;
            if (counter[i] != 0) break;
        }
    }

    /**
     * מחולל IV אקראי עבור GCM
     */
    public static byte[] ivGenerator() {
        SecureRandom random = new SecureRandom();
        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(iv);
        return iv;
    }

    /**
     * עזר להדפסת מערך בתים בייצוג hex
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    /**
     * פדינג של אפסים כך שאורך הנתונים יהי Multiple of BLOCK_SIZE
     */
    private static byte[] zeroPad(byte[] data) {
        int rem = data.length % BLOCK_SIZE;
        if (rem == 0) return data;
        byte[] out = new byte[data.length + (BLOCK_SIZE - rem)];
        System.arraycopy(data, 0, out, 0, data.length);
        return out;
    }

    /**
     * דוגמה להרצה עצמאית ובדיקת ההצפנה והפענוח ב-main
     */
    public static void main(String[] args) {
        byte[] key = keyGenerator();
        byte[][] roundKeys = new byte[11][16];
        roundKeys[0] = Arrays.copyOf(key, 16);
        keySchedule(roundKeys);

        String text = "Hello, AES-GCM test!";
        byte[] plainText = text.getBytes(StandardCharsets.UTF_8);
        byte[] aad = "MyAAD".getBytes(StandardCharsets.UTF_8);

        byte[] encrypted = AES_GCM.encrypt(plainText, aad, roundKeys);
        System.out.println("Encrypted (hex): " + bytesToHex(encrypted));

        byte[] decrypted = AES_GCM.decrypt(encrypted, aad, roundKeys);
        String decryptedText = new String(decrypted, StandardCharsets.UTF_8);

        System.out.println("Decrypted text: " + decryptedText);
        System.out.println("Match: " + decryptedText.equals(text));
    }
}

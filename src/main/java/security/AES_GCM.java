package security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import static security.AES_CTR.*;
import static security.AES_ECB.*;

public class AES_GCM {
    public static final int TAG_SIZE = 16; // Block size for AES (16 bytes)
    private static final int IV_LENGTH = 12; // 12 bytes (96 bits)
    private static final int BLOCK_SIZE = 16;

    /**
     * Encrypt plaintext with AES-GCM: returns IV||ciphertext||tag
     */
    public static byte[] encrypt(byte[] plainText, byte[] AAD, byte[][] round_keys) {

        // 1. צור IV אקראי בן 12 בתים
        byte[] iv = ivGenerator();

        // 2. הרכב J0 = IV || 0x00000001
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
     * Decrypts AES-GCM: expects IV||ciphertext||tag
     */
    public static byte[] decrypt(byte[] encryptedMessage, byte[] AAD, byte[][] round_keys) {

        if(encryptedMessage == null || encryptedMessage.length == 0) {
            throw new IllegalArgumentException("Data to decrypt cannot be null or empty.");
        }

        if (round_keys == null || round_keys.length == 0) {
            throw new IllegalArgumentException("Round keys are not valid.");
        }

        // Split IV, ciphertext, tag
        byte[] iv = Arrays.copyOfRange(encryptedMessage, 0, IV_LENGTH);
        byte[] cipher = Arrays.copyOfRange(encryptedMessage, IV_LENGTH, encryptedMessage.length - TAG_SIZE);
        byte[] tag = Arrays.copyOfRange(encryptedMessage, encryptedMessage.length - TAG_SIZE, encryptedMessage.length);

        // Rebuild J0
        byte[] J0 = new byte[BLOCK_SIZE];
        System.arraycopy(iv, 0, J0, 0, IV_LENGTH);
        J0[15] = 1;

        // Compute H
        byte[] H = encrypt_block(new byte[BLOCK_SIZE], round_keys);

        // Verify tag
        if (!verifyTag(cipher, AAD, tag, round_keys, J0, H))
            throw new SecurityException("Authentication failed. Data may have been tampered with.");

        // Decrypt CTR starting at J0+1
        byte[] firstCounter = Arrays.copyOf(J0, BLOCK_SIZE);
        incrementCounter(firstCounter);
        return decryptCTR(cipher, round_keys, firstCounter);
    }

    private static boolean verifyTag(byte[] cipher, byte[] AAD, byte[] authTag, byte[][] roundKeys, byte[] J0, byte[] H) {
        byte[] computedTag = generateAuthTag(cipher, AAD, roundKeys, J0, H);
        System.out.println("🔐 received tag: " + bytesToHex(authTag));
        System.out.println("🔍 computed tag: " + bytesToHex(computedTag));
        return Arrays.equals(computedTag, authTag);
    }

    /**
     * Compute GHASH and then Tag = E(K,J0) ^ GHASH
     */
    private static byte[] generateAuthTag(
            byte[] cipher, byte[] AAD, byte[][] round_keys, byte[] J0, byte[] H) {
        byte[] X = ghash(H, AAD, cipher);
        byte[] E_J0 = encrypt_block(J0, round_keys);
        byte[] tag = new byte[TAG_SIZE];
        for(int i=0; i<TAG_SIZE;i++){
            tag[i] = (byte) (E_J0[i] ^ X[i]);
        }
        return tag;
        }

    /**
     * GHASH(H, A, C) = GHASH over padded AAD, padded cipher, and lengths
     */
    private static byte[] ghash(byte[] H, byte[] AAD, byte[] cipher) {

        byte[] Y = new byte[BLOCK_SIZE];

        // processing the AAD (extra information: user who sent, compression type)
        if(AAD != null && AAD.length > 0){
            byte[] AAD_padded = zeroPad(AAD);
            for (int pos = 0; pos < AAD_padded.length; pos += BLOCK_SIZE) {
                byte[] block = Arrays.copyOfRange(AAD_padded, pos, Math.min(pos + BLOCK_SIZE, AAD_padded.length));
                xor(Y, block);
                Y = GF_Multiply(Y, H);
            }
        }

        if(cipher.length > 0) {
            // processing the CipherText
            byte[] cipher_padded = zeroPad(cipher);
            for (int pos = 0; pos < cipher_padded.length; pos += BLOCK_SIZE) {
                byte[] block = Arrays.copyOfRange(cipher_padded, pos, Math.min(pos + BLOCK_SIZE, cipher_padded.length));
                xor(Y, block);
                Y = GF_Multiply(Y, H);
            }
        }
        // processing the length block
        byte[] lengthBlock = new byte[BLOCK_SIZE];
        System.arraycopy(longToBytes(AAD.length * 8L), 0, lengthBlock, 0, 8);
        System.arraycopy(longToBytes(cipher.length * 8L), 0, lengthBlock, 8, 8);

        xor(Y, lengthBlock);
        Y = GF_Multiply(Y, H);
        return Y;
    }

    private static void xor(byte[] a, byte[] b) {
        for (int i = 0; i < a.length; i++)
            a[i] = (byte) (a[i] ^ b[i]);
    }

    private static byte[] longToBytes(long val) {
        byte[] result = new byte[8];
        for (int i = 0; i < 8; i++)
            result[7 - i] = (byte) (val >>> (i * 8));

        return result;
    }

    /**
     * Galois field multiplication in GF(2^128)
     */
    private static byte[] GF_Multiply(byte[] a, byte[] b) {
        byte[] result = new byte[BLOCK_SIZE]; // התוצאה מתחילה באפס
        byte[] tempA = Arrays.copyOf(a, BLOCK_SIZE); // עותק של x שישמש להכפלות

        for (int i = 0; i < 128; i++) {
            int bit = (b[15 - (i / 8)] >> (7 - (i % 8))) & 1;
            if (bit == 1) { // אם הביט ה־j ב־y[i] דולק
                xor(result, tempA); // מבצעים XOR עם v
            }
            boolean carry = (tempA[0] & 0x80) != 0; // האם יש נשירה
            shiftLeft(tempA); // מזיזים ימינה

            if (carry) { // אם הייתה נשירה, מוסיפים את הפולינום הבלתי פריק
                tempA[0] ^= (byte) 0x87; // x^128 + x^7 + x^2 + x + 1
            }
        }
        return result;
    }

    // A usual shift Left won't work because the left bit of each cell we'll get reset, and won't pass to the next cell if needed
    private static void shiftLeft(byte[] a) {
        // each cell in an array
        byte carry = 0;
        for (int i = a.length - 1; i >= 0; i--) {
            // the bit we're going to add
            // if it's the last cell (a[a.length-1] -> add zero
            // if it's not the last cell -> add the first bit of the next cell
            byte nextCarry = (byte) ((a[i] & 0x80) != 0 ? 1 : 0);
            // do a normal shiftLeft on the cell and add at the end the next bit
            a[i] = (byte) ((a[i] << 1) | carry);
            carry = nextCarry;
        }
    }

    // Increment only last 4 bytes (counter) in big-endian
    private static void incrementCounter(byte[] counter) {
        for (int i = BLOCK_SIZE - 1; i >= IV_LENGTH; i--) {
            counter[i]++;
            if (counter[i] != 0) break;
        }
    }

    public static void main(String[] args) {
        // 1. בחר מפתח אקראי של 16 בתים, ותבנה ממנו roundKeys
        byte[] key = keyGenerator();  // AES_ECB.keyGenerator()
        byte[][] roundKeys = new byte[11][16];
        roundKeys[0] = Arrays.copyOf(key, 16);
        keySchedule(roundKeys);

        // 2. הגדר טקסט גולמי ותוספת נתונים מאומתים
        String text = "Hello, AES-GCM test!";
        byte[] plainText = text.getBytes(StandardCharsets.UTF_8);
        byte[] aad = "MyAAD".getBytes(StandardCharsets.UTF_8);

        // 3. הצפן
        byte[] encrypted = AES_GCM.encrypt(plainText, aad, roundKeys);
        System.out.println("Encrypted bytes: " + Arrays.toString(encrypted));
        System.out.println("Encrypted (hex): " + bytesToHex(encrypted));

        // 4. פענח
        byte[] decrypted = AES_GCM.decrypt(encrypted, aad, roundKeys);
        String decryptedText = new String(decrypted, StandardCharsets.UTF_8);

        // 5. אמת
        System.out.println("Decrypted bytes: " + Arrays.toString(decrypted));
        System.out.println("Decrypted text: " + decryptedText);
        System.out.println("Match: " + decryptedText.equals(text));
    }

    // עזר להדפסת hex
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static byte[] zeroPad(byte[] data) {
        int rem = data.length % BLOCK_SIZE;
        if (rem == 0) return data;
        byte[] out = new byte[data.length + (BLOCK_SIZE - rem)];
        System.arraycopy(data, 0, out, 0, data.length);
        // out[i] כבר 0x00 בברירת־מחדל
        return out;
    }

    public static byte[] ivGenerator() {
        SecureRandom random = new SecureRandom();
        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(iv);
        return iv;
    }
}


package security;

import java.util.Arrays;
import java.util.Base64;
import static security.AES_CTR.*;
import static security.AES_ECB.*;

public class AES_GCM {
    public static final int TAG_SIZE = 16; // Block size for AES (16 bytes)
    private static final int IV_LENGTH = 12; // 12 bytes (96 bits)
    private static final int BLOCK_SIZE = 16;

    // Encrypt plaintext with AES-GCM: returns IV||ciphertext||tag
    public static byte[] encrypt(byte[] plainText, byte[] AAD, byte[][] round_keys) {

        // 1. Generate random IV
        byte[] iv = ivGenerator();

        // 2. Prepare J0 = IV || 0x00000001
        byte[] J0 = ByteBuffer.allocate(BLOCK_SIZE)
                .put(iv)
                .putInt(1)
                .array();
        // the key for multiplying in GF
        byte[] H = encrypt_block(new byte[BLOCK_SIZE], round_keys);

        // encrypt the text using the AES_CTR mode
        byte[] cipher = encryptCTR(plainText, round_keys, iv);

        // generate the authentication tag
        byte[] tag = generateAuthTag(cipher, AAD, round_keys, iv, H);

        // combining the nonce, cipher and the tag in one output
        byte[] encryptedMessage = new byte[IV_LENGTH + cipher.length + TAG_SIZE];
        System.arraycopy(iv, 0, encryptedMessage, 0, IV_LENGTH);
        System.arraycopy(cipher, 0, encryptedMessage, IV_LENGTH, cipher.length);
        System.arraycopy(tag, 0, encryptedMessage, IV_LENGTH + cipher.length, tag.length);

        return encryptedMessage;
    }

    public static byte[] decrypt(byte[] encryptedMessage, byte[] AAD, byte[][] round_keys) {

        if(encryptedMessage == null || encryptedMessage.length == 0) {
            throw new IllegalArgumentException("Data to decrypt cannot be null or empty.");
        }

        if (round_keys == null || round_keys.length == 0) {
            throw new IllegalArgumentException("Round keys are not valid.");
        }

        byte[] iv = Arrays.copyOfRange(encryptedMessage, 0, IV_LENGTH);
        byte[] cipher = Arrays.copyOfRange(encryptedMessage, IV_LENGTH, encryptedMessage.length - TAG_SIZE);
        byte[] tag = Arrays.copyOfRange(encryptedMessage, encryptedMessage.length - TAG_SIZE, encryptedMessage.length);

        byte[] H = encrypt_block(new byte[BLOCK_SIZE], round_keys);

        if (!verifyTag(cipher, AAD, tag, round_keys, iv, H))
            throw new SecurityException("Authentication failed. Data may have been tampered with.");
        return decryptCTR(cipher, round_keys, iv);
    }

    private static boolean verifyTag(byte[] cipher, byte[] AAD, byte[] authTag, byte[][] roundKeys, byte[] iv, byte[] H) {
        byte[] computedTag = generateAuthTag(cipher, AAD, roundKeys, iv, H);
        return Arrays.equals(computedTag, authTag);
    }

    // calculating the tag for the cipher, aad and j0
    private static byte[] generateAuthTag(byte[] cipher, byte[] AAD, byte[][] round_keys, byte[] iv, byte[] H) {
        byte[] Y = Arrays.copyOf(iv, IV_LENGTH);

        // processing the AAD (extra information: user who sent, compression type)
        byte[] AAD_padded = addPadding(AAD, BLOCK_SIZE);
        for (int pos = 0; pos < AAD_padded.length; pos += BLOCK_SIZE) {
            byte[] block = Arrays.copyOfRange(AAD_padded, pos, Math.min(pos + BLOCK_SIZE, AAD_padded.length));
            xor(Y, block);
            Y = GF_Multiply(Y, H);
        }

        // processing the CipherText
        byte[] cipher_padded = addPadding(cipher, BLOCK_SIZE);
        for (int pos = 0; pos < cipher_padded.length; pos += BLOCK_SIZE) {
            byte[] block = Arrays.copyOfRange(cipher_padded, pos, Math.min(pos + BLOCK_SIZE, cipher_padded.length));
            xor(Y, block);
            Y = GF_Multiply(Y, H);
        }

        // processing the length block
        byte[] lengthBlock = new byte[16];
        System.arraycopy(longToBytes(AAD.length * 8), 0, lengthBlock, 0, 8);
        System.arraycopy(longToBytes(cipher.length * 8), 0, lengthBlock, 8, 8);

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

    private static byte[] GF_Multiply(byte[] a, byte[] b) {
        byte[] result = new byte[16]; // התוצאה מתחילה באפס
        byte[] tempA = Arrays.copyOf(a, 16); // עותק של x שישמש להכפלות

        for (int i = 0; i < 128; i++) {
            if ((b[15 - (i / 8)] & (1 << (7 - (i % 8)))) != 0) { // אם הביט ה־j ב־y[i] דולק
                xor(result, tempA); // מבצעים XOR עם v
            }
            boolean carry = (tempA[0] & 0x80) != 0; // האם יש נשירה
            shiftLeft(tempA); // מזיזים ימינה

            if (carry) { // אם הייתה נשירה, מוסיפים את הפולינום הבלתי פריק
                tempA[0] ^= 0x87; // x^128 + x^7 + x^2 + x + 1
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

}


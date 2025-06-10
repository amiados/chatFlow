package security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * מחלקת Blowfish_CTR מממשת את מצב ההפעלה CTR (Counter) על בסיס Blowfish_ECB:
 *  - גודל בלוק של 8 בתים (64 ביט)
 *  - Nonce או IV בגודל 8 בתים
 *  - Counter מתעדכן בכל בלוק להצפנה
 */
public class Blowfish_CTR {

    /** גודל בלוק ההצפנה בפונקציית ה-CTR */
    private static final int BLOCK_SIZE = 8;

    /** מופע של Blowfish_ECB לשם הצפנת בלוק ה-Nonce+Counter */
    private final Blowfish_ECB blowfish;

    /** Nonce או IV בגודל BLOCK_SIZE */
    private final byte[] nonce;

    /** מונה בלוקים להצפנה (מתחיל מאפס) */
    private long counter;

    /**
     * קונסטרקטור:
     *  - מאתחל Blowfish_ECB עם המפתח הנתון
     *  - מוודא ש-Nonce הוא באורך המתאים
     *  - מאפס את Counter
     *
     * @param key מפתח ההצפנה (4-56 בתים)
     * @param nonce מערך בתים בגודל BLOCK_SIZE לשמש כ-IV
     */
    public Blowfish_CTR(byte[] key, byte[] nonce) {
        if (nonce.length != BLOCK_SIZE) {
            throw new IllegalArgumentException("Nonce must be " + BLOCK_SIZE  + "bytes");
        }
        this.blowfish = new Blowfish_ECB(key);
        this.nonce = nonce.clone();
        this.counter = 0;
    }

    /**
     * מפעיל מצב CTR על הקלט:
     *  - יוצר בלוק CounterBlock = Nonce ^ counter
     *  - מצפין ב-ECB את הבלוק ומקבל סטרימינג keyStream
     *  - XOR בין ה-input ל-keyStream
     *  - מעדכן את counter לכל בלוק
     *
     * @param input מערך בתים (plain או cipher)
     * @return מערך מעובד באותו אורך
     */
    public byte[] process(byte[] input) {
        byte[] output = new byte[input.length];

        for (int offset = 0; offset < input.length; offset += BLOCK_SIZE) {

            // 1. בונים CounterBlock = NONCE || COUNTER
            byte[] counterBlock = createCounterBlock();

            // 2. מוציאים keystream בלי padding
            int[] encryptedCounter = blowfish.encryptBlock(
                    blowfish.bytesToInt(counterBlock, 0),
                    blowfish.bytesToInt(counterBlock, 4)
                    );

            byte[] keystream = new byte[BLOCK_SIZE];
            blowfish.intToBytes(encryptedCounter[0], keystream, 0);
            blowfish.intToBytes(encryptedCounter[1], keystream, 4);

            int blockSize = Math.min(BLOCK_SIZE, input.length - offset);

            // 3. XOR עם הפיאר־בלוק הרלוונטי
            for (int j = 0; j < blockSize; j++) {
                output[offset + j] = (byte) (input[offset + j] ^ keystream[j]);
            }

            counter++;
        }
        return output;
    }

    /**
     * בונה את בלוק ההצפנה לשלב ה-CTR:
     *  - מעתיק את ה-Nonce
     *  - XOR עם ערכי counter במיקום הסופי בכל בית
     */
    private byte[] createCounterBlock() {
        byte[] counterBlock = new byte[BLOCK_SIZE];
        System.arraycopy(nonce, 0, counterBlock, 0, 4);
        ByteBuffer.wrap(counterBlock, 4, 4).putInt((int)counter);
        return counterBlock;
    }

    /**
     * מחולל Nonce/IV אקראי באורך נתון
     *
     * @param length אורך המערך
     * @return מערך בתים אקראי
     */
    protected static byte[] ivGenerator(int length) {
        byte[] iv = new byte[length];
        new SecureRandom().nextBytes(iv);
        return iv;
    }

    /**
     * דוגמה להרצה עצמאית (encrypt+decrypt באותו Input)
     */
    public static void main(String[] args) {
        byte[] key = Blowfish_ECB.generateKey(16);
        byte[] iv = ivGenerator(8);

        Blowfish_CTR ctr = new Blowfish_CTR(key, iv);

        String msg = "AES_CTR mode test string!";
        byte[] encrypted = ctr.process(msg.getBytes(StandardCharsets.UTF_8));
        ctr.resetCounter();
        byte[] decrypted = ctr.process(encrypted);

        System.out.println("Original: " + msg);
        System.out.println("Encrypted (Base64): " + Base64.getEncoder().encodeToString(encrypted));
        System.out.println("Decrypted: " + new String(decrypted, StandardCharsets.UTF_8));
    }

    private void resetCounter() {
        this.counter = 0;
    }

}

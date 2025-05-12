package security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

public class Blowfish_CTR {
    private static final int BLOCK_SIZE = 8;
    private final Blowfish_ECB blowfish;
    private final byte[] nonce;
    private long counter;

    public Blowfish_CTR(byte[] key, byte[] nonce) {
        if (nonce.length != BLOCK_SIZE) {
            throw new IllegalArgumentException("Nonce must be " + BLOCK_SIZE  + "bytes");
        }
        this.blowfish = new Blowfish_ECB(key);
        this.nonce = Arrays.copyOf(nonce, BLOCK_SIZE);
        this.counter = 0;
    }

    public byte[] process(byte[] input) {
        byte[] output = new byte[input.length];

        for (int i = 0; i < input.length; i += BLOCK_SIZE) {
            byte[] counterBlock = createCounterBlock();
            byte[] encryptedCounter = blowfish.encrypt(counterBlock);

            int blockSize = Math.min(BLOCK_SIZE, input.length - i);
            for (int j = 0; j < blockSize; j++) {
                output[i + j] = (byte) (input[i + j] ^ encryptedCounter[j]);
            }

            counter++;
        }
        return output;
    }

    private byte[] createCounterBlock() {
        byte[] counterBlock = Arrays.copyOf(nonce, BLOCK_SIZE);

        for (int i = 0; i < 8; i++) {
            counterBlock[7 - i] ^= (byte) ((counter >>> (i * 8)) & 0xFF); // מייצר Counter + Nonce
        }
        return counterBlock;
    }

    protected static byte[] ivGenerator(int length) {
        byte[] iv = new byte[length];
        new SecureRandom().nextBytes(iv);
        return iv;
    }

    public static void main(String[] args) {
        byte[] key = Blowfish_ECB.generateKey(16);
        byte[] iv = ivGenerator(BLOCK_SIZE);

        Blowfish_CTR ctr = new Blowfish_CTR(key, iv);

        String msg = "AES_CTR mode test string!";
        byte[] encrypted = ctr.process(msg.getBytes(StandardCharsets.UTF_8));
        byte[] decrypted = ctr.process(encrypted);

        System.out.println("Original: " + msg);
        System.out.println("Encrypted (Base64): " + Base64.getEncoder().encodeToString(encrypted));
        System.out.println("Decrypted: " + new String(decrypted, StandardCharsets.UTF_8));
    }

}

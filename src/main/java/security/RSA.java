package security;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

public class RSA {
    private static final SecureRandom random = new SecureRandom();
    public static final int BIT_LENGTH = 2048;
    private static final BigInteger PUBLIC_EXPONENT  = BigInteger.valueOf(65537);

    private final BigInteger N;
    private final BigInteger phiN;
    private final BigInteger privateKey, publicKey;

    public RSA(){
        BigInteger p = generateRandomPrime();
        BigInteger q = generateRandomPrime();
        this.N = p.multiply(q);
        this.phiN = phi(p, q);
        this.publicKey = generatePublicKey();
        this.privateKey = publicKey.modInverse(phiN);
    }

    private static BigInteger generateRandomPrime(){
        return BigInteger.probablePrime(BIT_LENGTH / 2, random);
    }

    private BigInteger generatePublicKey() {
        if (gcd(PUBLIC_EXPONENT, phiN).equals(BigInteger.ONE))
            return PUBLIC_EXPONENT;

        BigInteger e = BigInteger.valueOf(3);
        while (!gcd(e, phiN).equals(BigInteger.ONE)) {
            e = e.add(BigInteger.TWO);
        }
        return e;
    }

    public BigInteger getPublicKey() {
        return publicKey;
    }

    public BigInteger getPrivateKey(){
        return privateKey;
    }

    public BigInteger getN() {
        return N;
    }

    // Kpub = (N, e), Kpri = (N, d)
    // p & q - private prime numbers with 100 bytes each
    // N = p * q, know to everyone
    // e - exponent, known to everyone
    // d = phi(N)
    // e * d = 1 mod phi(N)
    // phi(N) = (p-1)(q-1)
    // E(m, Kpub) = m^e mod N = c
    // D(c, Kpri) = c^d mod N = m
    public BigInteger encrypt(byte[] message) {
        // each user have a public encryption key(own) and private decryption key(server)
        // so each user send a msg and encrypt it with his public key and then sent to the server
        // who have everyone's private decryption key, and he uses it to decrypt the given msg
        // he encrypts the msg with his own public encryption key and send it to everyone,
        // and they use their private decryption key to decrypt the msg and see the original msg

        // using rsa to send to each user his
        byte[] paddedMessage = addSimplePadding(message);
        BigInteger m = new BigInteger(1, paddedMessage);
        if (m.compareTo(N) >= 0) {
            throw new IllegalArgumentException("Message too large for encryption");
        }
        return m.modPow(publicKey, N);
    }


    public byte[] decrypt(BigInteger cipherText) {
        BigInteger m = cipherText.modPow(privateKey, N);
        return removeSimplePadding(m.toByteArray());
    }

    public static byte[] encrypt(byte[] message, BigInteger publicKey, BigInteger N){
        byte[] paddedMessage = addSimplePadding(message); // הוספת Padding פשוט
        BigInteger m = new BigInteger(1, paddedMessage);

        if (m.compareTo(N) >= 0) {
            throw new IllegalArgumentException("Message too large for encryption");
        }

        BigInteger c = m.modPow(publicKey, N);
        return c.toByteArray();
    }

    public static byte[] decrypt(byte[] cipherText, BigInteger privateKey, BigInteger N){
        BigInteger c = new BigInteger(1, cipherText);

        if (c.compareTo(N) >= 0) {
            throw new IllegalArgumentException("Message too large for decryption");
        }

        BigInteger m = c.modPow(privateKey, N);
        return removeSimplePadding(m.toByteArray());
    }

    private static byte[] addSimplePadding(byte[] message) {
        byte[] padding = new byte[16];
        random.nextBytes(padding);
        byte[] result = new byte[padding.length + message.length];
        System.arraycopy(padding, 0, result, 0, padding.length);
        System.arraycopy(message, 0, result, padding.length, message.length);
        return result;
    }

    private static byte[] removeSimplePadding(byte[] padded) {
        if (padded.length <= 16) {
            throw new IllegalArgumentException("Invalid decrypted message length");
        }
        return Arrays.copyOfRange(padded, 16, padded.length);
    }
    // finds the GCD(Greatest Common Divisor) of two numbers
    // if a mod b equals to 0 -> b is the GCD
    // else: a = b, b = a mod b
    private BigInteger gcd(BigInteger a, BigInteger b){
        while (!b.equals(BigInteger.ZERO)) {
            BigInteger temp = b;
            b = a.mod(b);
            a = temp;
        }
        return a;
    }

    private BigInteger phi(BigInteger p, BigInteger q){
        return p.subtract(BigInteger.ONE).multiply(q.subtract(BigInteger.ONE));
    }

    public static void main(String[] args) {
        RSA rsa = new RSA();  // יצירת מפתחות עם RSA-2048
        String message = "Hello RSA!";

        // הצפנה
        BigInteger encrypted = rsa.encrypt(message.getBytes(StandardCharsets.UTF_8));
        System.out.println("Encrypted: " + encrypted);

        // פענוח
        byte[] decrypted = rsa.decrypt(encrypted);
        System.out.println("Decrypted: " + new String(decrypted, StandardCharsets.UTF_8));
    }
}

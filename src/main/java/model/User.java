package model;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.UUID;

import io.grpc.Status;
import security.*;
import utils.ValidationResult;

import static security.AES_ECB.keySchedule;

public class User {
    private final UUID id; // the unique ID the user get
    private String username; // the default display everyone see
    private final String passwordHash; // help to connect to the user
    private String email; // help to connect to the user in case of forgetting the password
    private boolean verified = false;
    private boolean online = false;
    private String authToken;
    private HashSet<UUID> chatIds;
    private Instant lastLogin;
    private final byte[] privateKey;
    private final byte[] publicKey, N;

    /**
     * Constructor used at registration time, deriving key and encrypting private key.
     * @param username display name
     * @param email user email
     * @param password raw password
     */
    public User(String username, String email, String password) {
        this.id = UUID.randomUUID();
        this.username = username;
        this.passwordHash = PasswordHasher.hash(password);
        this.email = email;
        this.chatIds = new HashSet<>();
        this.lastLogin = Instant.now();

        // generate RSA key pair
        RSA rsa = new RSA();
        this.publicKey = rsa.getPublicKey().toByteArray();
        this.N = rsa.getN().toByteArray();
        this.privateKey = rsa.getPrivateKey().toByteArray();
    }

    /**
     * Constructor used when loading from database. No raw password available.
     */
    public User(UUID id, String username, String email, String passwordHash, byte[] publicKey, byte[] privateKey, byte[] N) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.email = email;
        this.chatIds = new HashSet<>();
        this.lastLogin = Instant.now();
        this.publicKey = publicKey;
        this.privateKey = privateKey;
        this.N = N;
    }

    // --- Validators ---
    public static ValidationResult validate(String username, String email, String password) {
        ArrayList<String> errors = new ArrayList<>();

        if (!emailValidator(email)) {
            errors.add("Invalid email format. Use format like example@gmail.com");
        }

        if (!userNameValidator(username)) {
            errors.add("Invalid username format. Must be at least 6 characters, with at least 3 letters.");
        }

        if (!passwordValidator(password)) {
            errors.add("Invalid password format. Must contain at least 8 characters, one uppercase, one lowercase, one digit, and one special character.");
        }

        return new ValidationResult(errors.isEmpty(), errors);
    }
    public static ValidationResult validate(String email, String password) {
        ArrayList<String> errors = new ArrayList<>();

        if (!emailValidator(email)) {
            errors.add("Invalid email format. Use format like example@gmail.com");
        }

        if (!passwordValidator(password)) {
            errors.add("Invalid password format. Must contain at least 8 characters, one uppercase, one lowercase, one digit, and one special character.");
        }

        return new ValidationResult(errors.isEmpty(), errors);
    }
    public static Boolean passwordValidator(String password){
        // at least 8 characters
        // contains at least one Uppercase and one Lowercase letters, one number and one special character
        String passwordPattern = "^(?=.*[a-z])(?=.*[A-Z])(?=.*[0-9])(?=.*[~!@#$%^&*.|/+-_=<>?]).{8,}$";
        return password != null && !password.isEmpty() && password.matches(passwordPattern);
    }
    public static Boolean emailValidator(String recoverEmail){
        String emailPattern = "^[a-zA-Z0-9._%&#!+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$";
        return recoverEmail != null && !recoverEmail.isEmpty() && recoverEmail.matches(emailPattern);
    }
    public static Boolean userNameValidator(String userName){
        // at least 6 characters long
        // most contain 3 alphabetic chars
        // can contain numbers and special chars
        String usernamePattern = "^(?=(?:.*[a-zA-Z]){3,}).{6,}$";
        return userName != null && !userName.isEmpty() && userName.matches(usernamePattern);

    }

    // --- Getters & Setters ---
    public UUID getId() { return id; }
    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public boolean isVerified() { return verified; }
    public boolean isOnline() { return online; }
    public HashSet<UUID> getChatIds() { return chatIds; }
    public String getAuthToken() { return authToken; }
    public Instant getLastLogin() { return lastLogin; }
    public byte[] getPublicKey() { return publicKey; }
    public byte[] getPrivateKey() { return privateKey; }
    public byte[] getN(){ return N; }

    public void setVerified(boolean verified) { this.verified = verified; }
    public void setOnline(boolean online) { this.online = online; }
    public void setChatIds(HashSet<UUID> chatIds) { this.chatIds = chatIds; }
    public void setAuthToken(String authToken) { this.authToken = authToken; }
    public void setLastLogin(Instant lastLogin) { this.lastLogin = lastLogin; }

    // --- Methods ---
    public void addChat(UUID chatId) {
        chatIds.add(chatId);
    }
    public void removeChat(UUID chatId) { chatIds.remove(chatId);}

    public void changeEmail(String newEmail) {
        if (!emailValidator(newEmail))
            throw new IllegalArgumentException("Invalid email");
        email = newEmail;
    }
    public void changeUsername(String newUsername) {
        if (!userNameValidator(newUsername))
            throw new IllegalArgumentException("Invalid username");
        username = newUsername;
    }

}

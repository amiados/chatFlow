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

/**
 * מחלקת User מייצגת משתמש במערכת, הכוללת:
 * - מזהה ייחודי (UUID)
 * - שם משתמש
 * - סיסמת מנותחת (hash)
 * - כתובת אימייל
 * - סטטוס אימות
 * - סטטוס מקוון
 * - רשימת צ'אטים שהמשתמש משתתף בהם
 * - מידע על הלוגין האחרון
 * - מפתחות RSA (ציבורי ופרטי)
 * - ספירת ניסיונות כניסה כושלים
 * - תאריך עד מתי החשבון נעול במקרה של נעילה
 */
public class User {
    // מזהה ייחודי של המשתמש
    private final UUID id;
    // שם התצוגה שפנוי למשתמש וכל שאר המשתמשים רואים
    private String username;
    // סיסמה מוצפנת כ-hash לאימות
    private final String passwordHash;
    // כתובת אימייל לשחזור סיסמה ותקשורת
    private String email;
    // האם המשתמש אומת בהצלחה
    private boolean verified = false;
    // האם המשתמש מחובר כעת
    private boolean online = false;
    // רשימת מזהי הצ'אטים שהמשתמש חבר בהם
    private HashSet<UUID> chatIds;
    // זמן הלוגין האחרון במערכת
    private Instant lastLogin;
    // מפתח פרטי עבור RSA
    private final byte[] privateKey;
    // מפתח ציבורי ומודול RSA
    private final byte[] publicKey, N;
    // ספירת ניסיונות כניסה כושלים ברצף
    private int failedLogins;
    // זמן עד מתי החשבון נעול (null אם לא נעול)
    private Instant lockUntil;

    /**
     * קונסטרקטור להרשמה ראשונית:
     * יוצר מזהה חדש, מבצע hashing לסיסמה,
     * מייצר זוג מפתחות RSA, ואתחול שדות נוספים.
     *
     * @param username שם התצוגה של המשתמש
     * @param email כתובת אימייל
     * @param password סיסמה גולמית לאחסון כסיסמה מוצפנת
     */
    public User(String username, String email, String password) {
        this.id = UUID.randomUUID();
        this.username = username;
        this.passwordHash = PasswordHasher.hash(password);
        this.email = email;
        this.chatIds = new HashSet<>();
        this.lastLogin = Instant.now();

        // יצירת זוג מפתחות RSA
        RSA rsa = new RSA();
        this.publicKey = rsa.getPublicKey().toByteArray();
        this.N = rsa.getN().toByteArray();
        this.privateKey = rsa.getPrivateKey().toByteArray();
        this.failedLogins = 0;
        this.lockUntil = null;
    }

    /**
     * קונסטרקטור לטעינה ממסד נתונים:
     * נטען עם כל השדות השמורים ללא סיסמה גולמית.
     *
     * @param id מזהה המשתמש
     * @param username שם התצוגה
     * @param email כתובת אימייל
     * @param passwordHash הסיסמה המוצפנת השמורה
     * @param publicKey מפתח ציבורי RSA
     * @param privateKey מפתח פרטי RSA
     * @param N מודול RSA
     * @param failedLogins ספירת כניסות כושלות
     * @param lockUntil זמן נעילה אם קיים
     */
    public User(UUID id, String username, String email, String passwordHash,
                byte[] publicKey, byte[] privateKey, byte[] N,
                int failedLogins, Instant lockUntil) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.email = email;
        this.chatIds = new HashSet<>();
        this.lastLogin = Instant.now();
        this.publicKey = publicKey;
        this.privateKey = privateKey;
        this.N = N;
        this.failedLogins = failedLogins;
        this.lockUntil = lockUntil;
    }

    // --- ואלידציות ---

    /**
     * מאמת שם משתמש, אימייל וסיסמה לפי קריטריונים.
     *
     * @return ValidationResult עם סטטוס ושגיאות במידת הצורך.
     */
    public static ValidationResult validate(String username, String email, String password) {
        ArrayList<String> errors = new ArrayList<>();
        if (!emailValidator(email)) {
            errors.add("פורמט אימייל לא תקין. יש להשתמש בדוגמה: example@gmail.com");
        }
        if (!userNameValidator(username)) {
            errors.add("פורמט שם משתמש לא תקין. יש לפחות 6 תווים ו-3 אותיות לפחות.");
        }
        if (!passwordValidator(password)) {
            errors.add("פורמט סיסמה לא תקין. לפחות 8 תווים, אות גדולה, אות קטנה, ספרה ותו מיוחד.");
        }
        return new ValidationResult(errors.isEmpty(), errors);
    }

    /**
     * מאמת אימייל וסיסמה בלבד (לכניסה).
     */
    public static ValidationResult validate(String email, String password) {
        ArrayList<String> errors = new ArrayList<>();
        if (!emailValidator(email)) {
            errors.add("פורמט אימייל לא תקין. יש להשתמש בדוגמה: example@gmail.com");
        }
        if (!passwordValidator(password)) {
            errors.add("פורמט סיסמה לא תקין. לפחות 8 תווים, אות גדולה, אות קטנה, ספרה ותו מיוחד.");
        }
        return new ValidationResult(errors.isEmpty(), errors);
    }

    /**
     * בודק אם הסיסמה עומדת בתנאים:
     * לפחות 8 תווים, אות גדולה, אות קטנה, ספרה ותו מיוחד.
     */
    public static Boolean passwordValidator(String password) {
        String pattern = "^(?=.*[a-z])(?=.*[A-Z])(?=.*[0-9])(?=.*[~!@#$%^&*.|/+-_=<>?]).{8,}$";
        return password != null && password.matches(pattern);
    }

    /**
     * בודק תקינות אימייל לפי ביטוי רגולרי.
     */
    public static Boolean emailValidator(String email) {
        String pattern = "^[a-zA-Z0-9._%&#!+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$";
        return email != null && email.matches(pattern);
    }

    /**
     * בודק תקינות שם משתמש:
     * לפחות 6 תווים ו-3 אותיות לפחות.
     */
    public static Boolean userNameValidator(String userName) {
        String pattern = "^(?=(?:.*[a-zA-Z]){3,}).{6,}$";
        return userName != null && userName.matches(pattern);
    }

    // --- getters & setters ---

    /** מחזיר מזהה ייחודי */
    public UUID getId() { return id; }
    /** מחזיר שם תצוגה */
    public String getUsername() { return username; }
    /** מחזיר אימייל */
    public String getEmail() { return email; }
    /** מחזיר hash של הסיסמה */
    public String getPasswordHash() { return passwordHash; }
    /** האם אומת? */
    public boolean isVerified() { return verified; }
    /** האם מקוון? */
    public boolean isOnline() { return online; }
    /** מחזיר רשימת chatIds */
    public HashSet<UUID> getChatIds() { return chatIds; }
    /** מחזיר זמן הלוגין האחרון */
    public Instant getLastLogin() { return lastLogin; }
    /** מחזיר מפתח RSA ציבורי */
    public byte[] getPublicKey() { return publicKey; }
    /** מחזיר מפתח RSA פרטי */
    public byte[] getPrivateKey() { return privateKey; }
    /** מחזיר מודול RSA (N) */
    public byte[] getN() { return N; }
    /** מחזיר כמות כניסות כושלות */
    public int getFailedLogins() { return failedLogins; }
    /** מחזיר עד מתי נעול (null אם לא נעול) */
    public Instant getLockUntil() { return lockUntil; }

    /**
     * קובע סטטוס אימות
     * @param verified האם אומת
     */
    public void setVerified(boolean verified) { this.verified = verified; }
    /**
     * קובע סטטוס מקוון
     */
    public void setOnline(boolean online) { this.online = online; }
    /**
     * קובע רשימת chatIds
     */
    public void setChatIds(HashSet<UUID> chatIds) { this.chatIds = chatIds; }
    /**
     * קובע את זמן הלוגין האחרון
     */
    public void setLastLogin(Instant lastLogin) { this.lastLogin = lastLogin; }
    /**
     * קובע את ספירת הכניסות הכושלות
     */
    public void setFailedLogins(int failedLogins) { this.failedLogins = failedLogins; }
    /**
     * קובע עד מתי החשבון נעול
     */
    public void setLockUntil(Instant lockUntil) { this.lockUntil = lockUntil; }

    // --- שיטות נוספות ---

    /**
     * מוסיף chatId למשתמש
     */
    public void addChat(UUID chatId) {
        chatIds.add(chatId);
    }

    /**
     * מסיר chatId מהרשימה
     */
    public void removeChat(UUID chatId) { chatIds.remove(chatId); }

    /**
     * משנה את כתובת האימייל לאחר בדיקת תוקף
     * @throws IllegalArgumentException אם הפורמט לא תקין
     */
    public void changeEmail(String newEmail) {
        if (!emailValidator(newEmail))
            throw new IllegalArgumentException("פורמט אימייל לא תקין");
        email = newEmail;
    }

    /**
     * משנה את שם המשתמש לאחר בדיקת תוקף
     */
    public void changeUsername(String newUsername) {
        if (!userNameValidator(newUsername))
            throw new IllegalArgumentException("פורמט שם משתמש לא תקין");
        username = newUsername;
    }

    /**
     * בודק אם החשבון נעול כרגע
     * @return true אם נעול, false אחרת
     */
    public boolean isLocked() {
        return lockUntil != null && Instant.now().isBefore(lockUntil);
    }
}
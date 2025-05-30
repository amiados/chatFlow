package model;

import java.util.UUID;

/**
 * מייצג מפתח מוצפן עבור חבר בצ'אט בגרסה מסוימת.
 * משמש לאחסון מפתח מוצפן לכל משתמש בכל צ'אט לפי גרסת המפתח.
 */
public class ChatMemberKey {
    /** מזהה הצ'אט אליו משתייך המפתח */
    private final UUID chatId;
    /** מזהה המשתמש שהמפתח שייך לו */
    private final UUID userId;
    /** גרסת המפתח המוצפן */
    private final int keyVersion;
    /** המפתח המוצפן בפורמט byte array */
    private final byte[] encryptedKey;

    /**
     * בונה אובייקט ChatMemberKey חדש.
     *
     * @param chatId       מזהה ייחודי של הצ'אט (לא יכול להיות null)
     * @param userId       מזהה ייחודי של המשתמש (לא יכול להיות null)
     * @param keyVersion   מספר גרסת המפתח
     * @param encryptedKey המפתח המוצפן, במערך בתים (לא יכול להיות null)
     * @throws IllegalArgumentException אם אחד מהפרמטרים הוא null
     */
    public ChatMemberKey(UUID chatId, UUID userId, int keyVersion, byte[] encryptedKey) {
        if (chatId == null || userId == null || encryptedKey == null) {
            throw new IllegalArgumentException("None of the parameters can be null");
        }
        this.chatId = chatId;
        this.userId = userId;
        this.keyVersion = keyVersion;
        this.encryptedKey = encryptedKey;
    }

    /**
     * מחזיר את מזהה הצ'אט.
     *
     * @return UUID של הצ'אט
     */
    public UUID getChatId() {
        return chatId;
    }

    /**
     * מחזיר את מזהה המשתמש.
     *
     * @return UUID של המשתמש
     */
    public UUID getUserId() {
        return userId;
    }

    /**
     * מחזיר את גרסת המפתח.
     *
     * @return מספר הגרסה של המפתח
     */
    public int getKeyVersion() {
        return keyVersion;
    }

    /**
     * מחזיר את המפתח המוצפן.
     *
     * @return מערך בתים המכיל את המפתח המוצפן
     */
    public byte[] getEncryptedKey() {
        return encryptedKey;
    }
}

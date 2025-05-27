package model;

import java.util.UUID;

/**
 * מייצג שורת גרסת מפתח אישית של משתמש בצ'אט.
 * לכל גרסה שורטטת הרשומה שלה עם המפתח המוצפן.
 */
public class ChatMemberKey {
    private final UUID chatId;
    private final UUID userId;
    private final int keyVersion;
    private final byte[] encryptedKey;

    public ChatMemberKey(UUID chatId, UUID userId, int keyVersion, byte[] encryptedKey) {
        if (chatId == null || userId == null || encryptedKey == null) {
            throw new IllegalArgumentException("None of the parameters can be null");
        }
        this.chatId = chatId;
        this.userId = userId;
        this.keyVersion = keyVersion;
        this.encryptedKey = encryptedKey;
    }

    public UUID getChatId() {
        return chatId;
    }
    public UUID getUserId() {
        return userId;
    }
    public int getKeyVersion() {
        return keyVersion;
    }
    public byte[] getEncryptedKey() {
        return encryptedKey;
    }
}

package model;

import java.time.Instant;
import java.util.UUID;

/**
 * מחלקת Messages מייצגת הודעה שנשלחה בצ'אט.
 * כל הודעה מחוברת לשיחה מסוימת ומכילה את תוכן ההודעה, פרטי השולח, זמן שליחה ומזהה ייחודי להודעה.
 */
public class Messages {

    private final UUID messageId;    // מזהה ייחודי להודעה
    private final UUID chatId;       // מזהה השיחה בה נשלחה ההודעה
    private final UUID senderId;     // מזהה המשתמש ששולח את ההודעה
    private final byte[] content;    // תוכן ההודעה
    private final Instant timestamp; // זמן שליחת ההודעה
    private MessageStatus status;
    private final boolean isSystem;
    private final int keyVersion;

    /**
     * בונה אובייקט Messages חדש.
     *
     * @param chatId מזהה השיחה בה נשלחה ההודעה
     * @param senderId מזהה השולח
     * @param content תוכן ההודעה ב- byte[]
     * @param sentAt זמן שליחת ההודעה
     */
    public Messages(UUID chatId, UUID senderId, byte[] content, Instant sentAt, boolean isSystem, int keyVersion) {
        this(UUID.randomUUID(), chatId, senderId, content, sentAt, MessageStatus.SENT, isSystem, keyVersion);
    }

    /**
     * בונה אובייקט Messages עם מזהה הודעה ספציפי.
     *
     * @param messageId מזהה ייחודי להודעה
     * @param chatId מזהה השיחה בה נשלחה ההודעה
     * @param senderId מזהה השולח
     * @param content תוכן ההודעה ב- byte[]
     * @param sentAt זמן שליחת ההודעה
     */
    public Messages(UUID messageId, UUID chatId, UUID senderId, byte[] content, Instant sentAt, MessageStatus status, boolean isSystem, int keyVersion) {
        this.messageId = messageId;
        this.chatId = chatId;
        this.senderId = senderId;
        this.content = content;
        this.timestamp = sentAt;
        this.status = status;
        this.isSystem = isSystem;
        this.keyVersion = keyVersion;
    }

    // --- Getters

    /**
     * מחזיר את מזהה ההודעה.
     *
     * @return מזהה ההודעה
     */
    public UUID getMessageId() { return messageId; }

    /**
     * מחזיר את מזהה השיחה בה נשלחה ההודעה.
     *
     * @return מזהה השיחה
     */
    public UUID getChatId() {
        return chatId;
    }

    /**
     * מחזיר את מזהה השולח של ההודעה.
     *
     * @return מזהה השולח
     */
    public UUID getSenderId() {
        return senderId;
    }

    /**
     * מחזיר את תוכן ההודעה.
     *
     * @return תוכן ההודעה
     */
    public byte[] getContent() {
        return content;
    }

    /**
     * מחזיר את זמן שליחת ההודעה.
     *
     * @return זמן שליחת ההודעה
     */
    public Instant getTimestamp() {
        return timestamp;
    }

    public MessageStatus getStatus(){
        return status;
    }

    public boolean getIsSystem(){
        return isSystem;
    }

    public int getKeyVersion() {
        return keyVersion;
    }
}

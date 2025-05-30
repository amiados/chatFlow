package model;

import java.util.HashMap;
import java.util.UUID;
import java.time.Instant;

/**
 * מייצגת חדר צ'אט המכיל:
 * - מזהה ייחודי
 * - שם
 * - יוצר ותאריך יצירה
 * - רשימת חברים עם תפקידיהם
 * - נתוני סנכרון והצפנה
 */
public class ChatRoom {

    /** מזהה ייחודי של חדר הצ'אט */
    private final UUID chatId;

    /** שם חדר הצ'אט */
    private String name;

    /** מזהה המשתמש שיצר את החדר */
    private final UUID createdBy;

    /** תאריך ושעת יצירת החדר */
    private final Instant createdAt;

    /** מזהה התיקיה בחשבון ענן (Google Drive ועוד) */
    private String folderId;

    /** זמן ההודעה האחרונה בחדר, לצורך מיון והצגה */
    private Instant lastMessageTime;

    /** גרסת המפתח הנוכחית להצפנת ההודעות בחדר */
    private int currentKeyVersion;

    /** רשימת החברים בחדר, כאשר המפתח הוא UUID של המשתמש */
    private HashMap<UUID, ChatMember> members;

    /**
     * בונה מופע חדש של חדר צ'אט עם כל הפרמטרים.
     *
     * @param chatId מזהה ייחודי לחדר
     * @param name שם החדר
     * @param createdBy מזהה המשתמש שיצר את החדר
     * @param createdAt תאריך ושעת יצירה
     * @param folderId מזהה תיקיה לאחסון קבצים בענן (יכול להיות null)
     * @param members מפה של חברים (יכול להיות null לאתחול ריק)
     */
    public ChatRoom(UUID chatId, String name, UUID createdBy, Instant createdAt,
                    String folderId, HashMap<UUID, ChatMember> members) {
        this.chatId = chatId;
        this.name = name;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.folderId = folderId;
        this.members = members != null ? members : new HashMap<>();
    }

    /**
     * בונה מופע חדש של חדר צ'אט עם שם ויוצר בלבד.
     * שאר הפרטים (UUID, תאריך יצירה, רשימת חברים) מאופסים אוטומטית.
     *
     * @param name שם החדר
     * @param createdBy מזהה המשתמש שיצר את החדר
     */
    public ChatRoom(String name, UUID createdBy) {
        this(UUID.randomUUID(), name, createdBy, Instant.now(), null, new HashMap<>());
    }

    /** @return מזהה החדר */
    public UUID getChatId() {
        return chatId;
    }

    /** @return שם החדר */
    public String getName() {
        return name;
    }

    /** @return מזהה יוצר החדר */
    public UUID getCreatedBy() {
        return createdBy;
    }

    /** @return תאריך ושעת יצירה */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** @return מזהה התיקיה בענן */
    public String getFolderId() {
        return folderId;
    }

    /** @return זמן ההודעה האחרונה */
    public Instant getLastMessageTime() {
        return lastMessageTime;
    }

    /** @return גרסת המפתח הנוכחית */
    public int getCurrentKeyVersion() {
        return currentKeyVersion;
    }

    /** @return המפה של חברי החדר */
    public HashMap<UUID, ChatMember> getMembers() {
        return members;
    }

    /**
     * מעדכן את גרסת המפתח להצפנת ההודעות.
     *
     * @param currentKeyVersion מספר גרסה חדש
     */
    public void setCurrentKeyVersion(int currentKeyVersion) {
        this.currentKeyVersion = currentKeyVersion;
    }

    /**
     * משנה את שם החדר לאחר אימות תקינות:
     * - לא null ולא ריק
     * - עד 50 תווים המכילים אותיות, ספרות, רווח, מקף או תו קו תחתון
     *
     * @param newName השם החדש לחדר
     * @throws IllegalArgumentException אם השם אינו תקין
     */
    public void setName(String newName) {
        if (newName == null || newName.trim().isEmpty() ||
                !newName.matches("^[a-zA-Z0-9א-ת _-]{1,50}$")) {
            throw new IllegalArgumentException("Invalid chat name");
        }
        this.name = newName;
    }

    /**
     * מעדכן את זמן ההודעה האחרונה בחדר.
     *
     * @param lastMessageTime תאריך ושעה של ההודעה האחרונה
     */
    public void setLastMessageTime(Instant lastMessageTime) {
        this.lastMessageTime = lastMessageTime;
    }

    /**
     * מגדיר או מעדכן את מזהה התיקיה בענן.
     *
     * @param folderId מזהה התיקיה
     */
    public void setFolderId(String folderId) {
        this.folderId = folderId;
    }

    /**
     * מוסיף חבר חדש לחדר.
     *
     * @param chatMember האובייקט שמייצג את החבר להצגה ושמירה
     */
    public void addMember(ChatMember chatMember) {
        members.put(chatMember.getUserId(), chatMember);
    }

    /**
     * מסיר חבר מהחדר לאחר בדיקת הרשאות ADMIN.
     *
     * @param adminId מזהה המשתמש שמנסה להסיר
     * @param targetId מזהה המשתמש להסרה
     * @throws SecurityException אם המשתמש שמבצע אינו ADMIN
     * @throws IllegalStateException אם המשתמש המיועד אינו חבר
     */
    public void removeMember(UUID adminId, UUID targetId) {
        if (!isAdmin(adminId)) {
            throw new SecurityException("אין הרשאת ADMIN להסרת חבר");
        }
        if (!isMember(targetId)) {
            throw new IllegalStateException("המשתמש לא חבר בחדר");
        }
        members.remove(targetId);
    }

    /**
     * בודק האם מזהה המשתמש נמצא כרשום בחדר.
     *
     * @param userId מזהה המשתמש
     * @return true אם חבר, אחרת false
     */
    public boolean isMember(UUID userId) {
        return members.containsKey(userId);
    }

    /**
     * בודק האם למשתמש הרשאות ADMIN.
     *
     * @param userId מזהה המשתמש
     * @return true אם תפקידו ADMIN, אחרת false
     */
    public boolean isAdmin(UUID userId) {
        ChatMember member = members.get(userId);
        return member != null && member.getRole() == ChatRole.ADMIN;
    }
}

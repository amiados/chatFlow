package model;

import java.util.HashMap;
import java.util.UUID;
import java.time.Instant;

/**
 * מייצגת חדר צ'אט המכיל מזהה ייחודי, שם, יוצר, תאריך יצירה, מפתח מוצפן ורשימת חברים.
 * החדר תומך בניהול חברים, כולל הוספה, הסרה ושינוי תפקידים, תחת מגבלות הרשאה של ADMIN.
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

    /** רשימת החברים בצ'אט לפי מזהה משתמש */
    private HashMap<UUID, ChatMember> members;

    private String folderId;

    private Instant lastMessageTime;

    private int currentKeyVersion = 1;

    /**
     * בונה מופע חדש של חדר צ'אט עם הנתונים שסופקו.
     *
     * @param chatId מזהה ייחודי לחדר
     * @param name שם החדר
     * @param createdBy מזהה המשתמש שיצר את החדר
     * @param createdAt תאריך יצירה
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
     * בונה מופע חדש של חדר צ'אט עם הנתונים שסופקו(במקרה שחסר, משלים).
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

    /** @return תאריך ושעת יצירת החדר */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** @return רשימת חברי החדר */
    public HashMap<UUID, ChatMember> getMembers() {
        return members;
    }

    public String getFolderId() {
        return folderId;
    }

    public Instant getLastMessageTime() {
        return lastMessageTime;
    }

    public int getCurrentKeyVersion() {
        return currentKeyVersion;
    }

    public void setCurrentKeyVersion(int currentKeyVersion) {
        this.currentKeyVersion = currentKeyVersion;
    }

    /**
     * משנה את שם החדר לאחר בדיקת תקינות.
     *
     * @param newName השם החדש לחדר
     * @throws IllegalArgumentException אם השם אינו תקין
     */
    public void setName(String newName) {
        if (newName == null || newName.trim().isEmpty() || !newName.matches("^[a-zA-Z0-9א-ת _-]{1,50}$")) {
            throw new IllegalArgumentException("Invalid chat name");
        }
        this.name = newName;
    }

    public void setLastMessageTime(Instant lastMessageTime) {
        this.lastMessageTime = lastMessageTime;
    }

    public void setFolderId(String folderId) {
        this.folderId = folderId;
    }
    /**
     * מוסיף חבר חדש לחדר הצ'אט.
     * @param  chatMember החבר החדש
     */
    public void addMember(ChatMember chatMember) {
        members.put(chatMember.getUserId(), chatMember);
    }

    /**
     * מסיר חבר מהחדר
     * @param adminId מזהה המשתמש להסרה
     * @param targetId מזהה המשתמש להסרה
     */
    public void removeMember(UUID adminId, UUID targetId) {
        if(!isAdmin(adminId)){

        }
        if(!isMember(targetId)){

        }
        members.remove(targetId);
    }

    /**
     * בודק האם המשתמש הוא חבר בחדר.
     *
     * @param userId מזהה המשתמש
     * @return true אם המשתמש חבר, אחרת false
     */
    public boolean isMember(UUID userId) {
        return members.containsKey(userId);
    }

    /**
     * בודק האם למשתמש יש הרשאות ADMIN.
     *
     * @param userId מזהה המשתמש
     * @return true אם המשתמש הוא אדמין, אחרת false
     */
    public boolean isAdmin(UUID userId) {
        ChatMember member = members.get(userId);
        return member != null && member.getRole() == ChatRole.ADMIN;
    }
}

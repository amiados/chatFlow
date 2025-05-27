package model; // מציין שהמחלקה שייכת לחבילה 'model'

import java.time.Instant; // יבוא של מחלקה המייצגת תאריך ושעה
import java.util.UUID; // יבוא של מחלקה ליצירת מזהים ייחודיים אוניברסליים (UUID)

public class ChatMember { // מחלקה המייצגת משתמש כחבר בצ'אט מסוים
    private final UUID chatId; // מזהה ייחודי של הצ'אט שבו החבר משתתף
    private final UUID userId; // מזהה ייחודי של המשתמש
    private ChatRole role; // תפקיד המשתמש בצ'אט (Admin או Member)
    private final Instant joinDate; // מועד הצטרפות המשתמש לצ'אט
    private InviteStatus inviteStatus; // סטטוס ההזמנה לצ'אט (PENDING, ACCEPTED וכו')

    private int unreadMessages;
    private boolean active;

    // בנאי אתחול לכל הנתונים החיוניים ליצירת אובייקט של ChatMember
    public ChatMember(UUID chatId, UUID userId, ChatRole role, Instant joinDate, InviteStatus inviteStatus) {
        // בדיקה למניעת הכנסת ערכים null לפרמטרים קריטיים
        if (chatId == null || userId == null || role == null || joinDate == null)
            throw new IllegalArgumentException("None of the parameters can be null");

        // אתחול שדות המחלקה
        this.chatId = chatId;
        this.userId = userId;
        this.role = role;
        this.joinDate = joinDate;
        this.inviteStatus = inviteStatus != null ? inviteStatus : InviteStatus.PENDING;
        this.unreadMessages = 0;
        this.active = false;
    }

    // -------------------- Getters --------------------

    public UUID getChatId() {
        return chatId; // מחזיר את מזהה הצ'אט
    }
    public UUID getUserId() {
        return userId; // מחזיר את מזהה המשתמש
    }
    public ChatRole getRole() {
        return role; // מחזיר את תפקיד המשתמש בצ'אט
    }
    public Instant getJoinDate() {
        return joinDate; // מחזיר את תאריך ההצטרפות של המשתמש לצ'אט
    }
    public InviteStatus getInviteStatus() {
        return inviteStatus; // מחזיר את סטטוס ההזמנה לצ'אט
    }

    // -------------------- Setters --------------------

    public void setRole(ChatRole role) {
        // בדיקה למניעת הצבת null לתפקיד
        if (role == null)
            throw new IllegalArgumentException("Role cannot be null");

        this.role = role; // מעדכן את תפקיד המשתמש בצ'אט
    }

    public void setInviteStatus(InviteStatus inviteStatus) {
        this.inviteStatus = inviteStatus; // מעדכן את סטטוס ההזמנה לצ'אט
    }

    // --- Methods ---

    public int getUnreadMessages() { return unreadMessages; }
    public void setUnreadMessages(int unreadMessages){
        this.unreadMessages = unreadMessages;
    }
    public void incrementUnreadMessages() { unreadMessages++; }
    public void clearUnreadMessages() { unreadMessages = 0; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public boolean canAccess(){
        return inviteStatus.equals(InviteStatus.PENDING) ||
                inviteStatus.equals(InviteStatus.ACCEPTED);
    }
}

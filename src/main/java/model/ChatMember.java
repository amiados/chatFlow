/**
 * מחלקה המייצגת חבר בצ'אט ספציפי
 */
package model;

import java.time.Instant;
import java.util.UUID;

/**
 * ChatMember - מייצג קשר בין משתמש לצ'אט, כולל תפקיד וסטטוס הזמנה
 */
public class ChatMember {
    /**
     * מזהה ייחודי של הצ'אט שבו החבר משתתף
     */
    private final UUID chatId;

    /**
     * מזהה ייחודי של המשתמש
     */
    private final UUID userId;

    /**
     * תפקיד המשתמש בצ'אט (ADMIN, MEMBER)
     */
    private ChatRole role;

    /**
     * תאריך ושעת הצטרפות המשתמש לצ'אט
     */
    private final Instant joinDate;

    /**
     * סטטוס ההזמנה של המשתמש לצ'אט (PENDING, ACCEPTED, DECLINED, EXPIRED)
     */
    private InviteStatus inviteStatus;

    /**
     * מספר ההודעות שלא נקראו על ידי המשתמש
     */
    private int unreadMessages;

    /**
     * האם החבר פעיל כרגע בצ'אט
     */
    private boolean active;

    /**
     * בונה אובייקט ChatMember חדש עם הנתונים הנדרשים
     *
     * @param chatId מזהה הצ'אט (לא יכול להיות null)
     * @param userId מזהה המשתמש (לא יכול להיות null)
     * @param role  תפקיד המשתמש בצ'אט (לא יכול להיות null)
     * @param joinDate מועד ההצטרפות (לא יכול להיות null)
     * @param inviteStatus סטטוס ההזמנה; אם null יוגדר PENDING
     * @throws IllegalArgumentException אם אחד מהפרמטרים הקריטיים הוא null
     */
    public ChatMember(UUID chatId, UUID userId, ChatRole role, Instant joinDate, InviteStatus inviteStatus) {
        if (chatId == null || userId == null || role == null || joinDate == null)
            throw new IllegalArgumentException("None of the parameters can be null");

        this.chatId = chatId;
        this.userId = userId;
        this.role = role;
        this.joinDate = joinDate;
        this.inviteStatus = inviteStatus != null ? inviteStatus : InviteStatus.PENDING;
        this.unreadMessages = 0;
        this.active = false;
    }

    // -------------------- גטרים --------------------

    /**
     * מחזיר את מזהה הצ'אט
     * @return UUID של הצ'אט
     */
    public UUID getChatId() {
        return chatId;
    }

    /**
     * מחזיר את מזהה המשתמש
     * @return UUID של המשתמש
     */
    public UUID getUserId() {
        return userId;
    }

    /**
     * מחזיר את תפקיד המשתמש בצ'אט
     * @return ChatRole הנוכחי
     */
    public ChatRole getRole() {
        return role;
    }

    /**
     * מחזיר את תאריך ההצטרפות של המשתמש לצ'אט
     * @return Instant המייצג את זמן ההצטרפות
     */
    public Instant getJoinDate() {
        return joinDate;
    }

    /**
     * מחזיר את סטטוס ההזמנה הנוכחי לצ'אט
     * @return InviteStatus הנוכחי
     */
    public InviteStatus getInviteStatus() {
        return inviteStatus;
    }

    // -------------------- סטרים --------------------

    /**
     * מעדכן את תפקיד המשתמש בצ'אט
     *
     * @param role תפקיד חדש (לא יכול להיות null)
     * @throws IllegalArgumentException אם role הוא null
     */
    public void setRole(ChatRole role) {
        if (role == null)
            throw new IllegalArgumentException("Role cannot be null");

        this.role = role;
    }

    /**
     * מעדכן את סטטוס ההזמנה לצ'אט
     *
     * @param inviteStatus סטטוס חדש
     */
    public void setInviteStatus(InviteStatus inviteStatus) {
        this.inviteStatus = inviteStatus;
    }

    // -------------------- שאר הפונקציות --------------------

    /**
     * מחזיר את מספר ההודעות שלא נקראו
     * @return מספר ההודעות
     */
    public int getUnreadMessages() {
        return unreadMessages;
    }

    /**
     * מגדיר את מספר ההודעות שלא נקראו
     * @param unreadMessages מספר חדש
     */
    public void setUnreadMessages(int unreadMessages) {
        this.unreadMessages = unreadMessages;
    }

    /**
     * מגדיל ב-1 את מספר ההודעות שלא נקראו
     */
    public void incrementUnreadMessages() {
        unreadMessages++;
    }

    /**
     * מאפס את מספר ההודעות שלא נקראו
     */
    public void clearUnreadMessages() {
        unreadMessages = 0;
    }

    /**
     * בודק אם החבר פעיל בצ'אט
     * @return true אם active, אחרת false
     */
    public boolean isActive() {
        return active;
    }

    /**
     * מגדיר האם החבר פעיל בצ'אט
     * @param active true להפעלה, false לכיבוי
     */
    public void setActive(boolean active) {
        this.active = active;
    }

    /**
     * בודק אם המשתמש רשאי לגשת לצ'אט בהתאם לסטטוס ההזמנה
     *
     * @return true אם הסטטוס PENDING או ACCEPTED, אחרת false
     */
    public boolean canAccess() {
        return inviteStatus.equals(InviteStatus.PENDING) ||
                inviteStatus.equals(InviteStatus.ACCEPTED);
    }
}

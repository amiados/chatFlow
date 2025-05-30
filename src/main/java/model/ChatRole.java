package model;

/**
 * תפקיד בחדר הצ'אט.
 * יכול להיות ADMIN (מנהל) או MEMBER (חבר).
 */
public enum ChatRole {
    /** משתמש עם הרשאות ניהוליות בחדר */
    ADMIN,
    /** משתמש רגיל בחדר */
    MEMBER;

    /**
     * ממירה מחרוזת לתפקיד תואם.
     *
     * @param role המחרוזת שמייצגת את התפקיד (ללא תלות באותיות גדולות/קטנות)
     * @return התפקיד התואם
     * @throws IllegalArgumentException אם אין תפקיד תואם
     */
    public static ChatRole fromString(String role) {
        return ChatRole.valueOf(role.toUpperCase());
    }

    /**
     * מחזירה את שם התפקיד כאשר האות הראשונה היא גדולה והשאר קטנות.
     *
     * @return שם התפקיד בפורמט "Admin" או "Member"
     */
    @Override
    public String toString() {
        return name().charAt(0) + name().substring(1).toLowerCase();
    }
}

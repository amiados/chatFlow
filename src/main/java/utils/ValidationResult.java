package utils;

import java.util.List;

/**
 * תוצאה של תהליך ולידציה: האם התקבל תקין ורשימת הודעות שגיאה.
 */
public class ValidationResult {
    private final boolean valid;
    private final List<String> messages;

    /**
     * יוצר ValidationResult.
     * @param valid האם התקין
     * @param messages הודעות תוצאות (שגיאות או אזהרות)
     */
    public ValidationResult(boolean valid, List<String> messages) {
        this.valid = valid;
        this.messages = messages;
    }

    /**
     * @return true אם התקין
     */
    public boolean isValid() {
        return valid;
    }

    /**
     * @return הרשימה של הודעות
     */
    public List<String> getMessages() {
        return messages;
    }
}

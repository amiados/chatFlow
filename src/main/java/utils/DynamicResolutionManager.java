package utils;

/**
 * מנהל דינמי של רזולוציית וידאו מבוסס מהירות שליחת מסגרות.
 * מתאם את הרזולוציה מ-160x120 עד 640x480 לפי משך ההעברה.
 */
public class DynamicResolutionManager {
    private int targetWidth;
    private int targetHeight;

    private final int maxWidth = 640;
    private final int maxHeight = 480;
    private final int minWidth = 160;
    private final int minHeight = 120;

    /**
     * יוצר מניה עם הרזולוציה המקסימלית כברירת מחדל.
     */
    public DynamicResolutionManager() {
        this.targetWidth = maxWidth;
        this.targetHeight = maxHeight;
    }

    /**
     * מבצע התאמת רזולוציה לפי משך זמן שליחת מסגרת:
     * - אם איטי מ-200ms, מוריד באיכות
     * - אם מהיר מתחת ל-80ms, מעלה באיכות
     * @param sendDurationMillis משך ההעברה במילישניות
     */
    public void adjustResolution(long sendDurationMillis) {
        if (sendDurationMillis > 200 && targetWidth > minWidth) {
            // איטי מדי -> להקטין איכות
            targetWidth = Math.max(minWidth, targetWidth - 80);
            targetHeight = Math.max(minHeight, targetHeight - 60);
        } else if (sendDurationMillis < 80 && targetWidth < maxWidth) {
            // מהיר -> להגדיל איכות
            targetWidth = Math.min(maxWidth, targetWidth + 80);
            targetHeight = Math.min(maxHeight, targetHeight + 60);
        }
    }

    /**
     * @return הרוחב הנוכחי המותאם
     */
    public int getTargetWidth() {
        return targetWidth;
    }

    /**
     * @return הגובה הנוכחי המותאם
     */
    public int getTargetHeight() {
        return targetHeight;
    }
}

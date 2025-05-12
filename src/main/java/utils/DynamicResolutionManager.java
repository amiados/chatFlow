package utils;

public class DynamicResolutionManager {
    private int targetWidth;
    private int targetHeight;

    private final int maxWidth = 640;
    private final int maxHeight = 480;
    private final int minWidth = 160;
    private final int minHeight = 120;

    public DynamicResolutionManager() {
        this.targetWidth = maxWidth;
        this.targetHeight = maxHeight;
    }

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

    public int getTargetWidth() {
        return targetWidth;
    }

    public int getTargetHeight() {
        return targetHeight;
    }
}

package client;

import model.User;
import security.Token;
import com.chatFlow.Chat.*;

import javax.swing.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * מחלקה האחראית על רענון אוטומטי של טוקן המשתמש
 * כל פרק זמן קבוע לפני פקיעת הטוקן.
 * <p>
 * משתמשת ב‎AtomicReference‎ למניעת race conditions
 * ומבצעת ניסיון retry עם backoff מוגדל עד למקסימום ניסיונות.
 */
public class ClientTokenRefresher {

    /**
     * המרווח לפני פקיעת הטוקן שבו נבצע רענון (5 דקות לפני הפקיעה)
     */
    private static final long REFRESH_BEFORE_EXPIRY_MS = TimeUnit.MINUTES.toMillis(5);

    /**
     * מספר ניסיונות מרבי לפני הפסקת ה-retry
     */
    private static final int MAX_RETRIES = 3;

    /**
     * ערך התחלתי ל-backoff במילישניות (1 דקה)
     */
    private static final long BACKOFF_INITIAL_MS = TimeUnit.MINUTES.toMillis(1);

    // הלקוח המבצע את קריאות ה-refreshToken
    private final ChatClient client;

    // מתזמן משימות רענון
    private final ScheduledExecutorService scheduler;

    // מאזין לאירועי רענון
    private final TokenRefreshListener listener;

    // מונה ניסיונות ה-retry
    private int retryCount = 0;

    /**
     * בונה את ה-Refresher עם ה-ChatClient והמשתמש הרלוונטי
     *
     * @param client   הלקוח לביצוע קריאות refreshToken
     * @param user     המשתמש המחזיק את הטוקן (כרגע לא בשימוש פנימי)
     * @param listener מאזין לאירועי הצלחה/כשל ברענון
     */
    public ClientTokenRefresher(ChatClient client, User user, TokenRefreshListener listener) {
        this.client = client;
        this.listener = listener;

        // יצירת scheduler שרץ על ת'רד דמון יחיד
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TokenRefresher");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * מפעיל את מתזמן הרענון. בתזמון ראשוני ללא השהיה.
     */
    public void start() {
        scheduleNextRefresh(0);
    }

    /**
     * עוצר את תזמון הרענון וממתין לסיום פעילויות מתוזמנות.
     */
    public void stop() {
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException ignored) {
            scheduler.shutdownNow();
        }
    }

    /**
     * מתזמן את משימת ה-refreshTask לאחר השהייה נתונה
     *
     * @param delayMs השהייה במילישניות
     */
    private void scheduleNextRefresh(long delayMs) {
        scheduler.schedule(this::refreshTask, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * הלוגיקה לביצוע רענון טוקן כאשר מגיע תור
     * בודקת אם הזמן לרענון הגיע, מבצעת קריאה לשרת,
     * ומטפלת ב-success או כשל עם retry/backoff.
     */
    private void refreshTask() {
        String currentToken = client.getToken();
        long now = System.currentTimeMillis();

        long expiresAt;
        try {
            // חלץ תאריך פקיעה מתוך הפayload של הטוקן
            expiresAt = Token.extractExpiry(currentToken);
        } catch (Exception e) {
            // טוקן לא תקין או פג
            listener.onTokenRefreshFailed("Invalid token payload");
            return;
        }

        long timeUntilRefresh = (expiresAt - REFRESH_BEFORE_EXPIRY_MS) - now;
        if (timeUntilRefresh > 0 && retryCount == 0) {
            // עוד מוקדם מדי לרענון – נמתין עד לפני סף הפקיעה
            scheduleNextRefresh(timeUntilRefresh);
            return;
        }

        // הגיע הרגע לבצע רענון או שאנחנו במחזור retry
        try {
            listener.onBeforeTokenRefresh(retryCount);
            RefreshTokenRequest req = RefreshTokenRequest.newBuilder()
                    .setToken(currentToken)
                    .build();
            RefreshTokenResponse resp = client.refreshToken(req);

            if (resp.getSuccess()) {
                // רענון הצליח
                String newToken = resp.getNewToken();
                client.setToken(newToken);
                listener.onTokenRefreshed(newToken);
                retryCount = 0;

                // תזמון הרענון הבא לפני פקיעת ה-newToken
                long newExpiresAt = Token.extractExpiry(newToken);
                long nextDelay = (newExpiresAt - REFRESH_BEFORE_EXPIRY_MS) - System.currentTimeMillis();
                scheduleNextRefresh(Math.max(nextDelay, 0));
            } else {
                // השרת סירב – כשל טיפול
                handleRefreshFailure("Server refused: " + resp.getMessage());
            }
        } catch (Exception e) {
            // חריגה במהלך הרענון
            handleRefreshFailure(e.getMessage());
        }
    }

    /**
     * מטפל בתרחיש של כשל רענון:
     * אם לא הגענו למקסימום ניסיונות – מרענן עם backoff,
     * אחרת מדווח כשל סופי.
     *
     * @param reason סיבת הכשל שהתקבלה
     */
    private void handleRefreshFailure(String reason) {
        if (retryCount < MAX_RETRIES) {
            long backoff = BACKOFF_INITIAL_MS * (1L << retryCount); // 1,2,4 דקות...
            retryCount++;
            listener.onTokenRefreshRetry(retryCount, backoff);
            scheduleNextRefresh(backoff);
        } else {
            listener.onTokenRefreshFailed(reason);
            // לא מבצעים תזמון נוסף – להמתין לטיפול חיצוני בכשלון
        }
    }

    /**
     * ממשק לטיפול באירועי רענון הטוקן:
     */
    public interface TokenRefreshListener {
        /**
         * נקרא לפני ניסיון רענון (0 למקרה הראשוני)
         *
         * @param retryCount מספר ניסויי retry שבוצעו עד כה
         */
        void onBeforeTokenRefresh(int retryCount);

        /**
         * נקרא כאשר הטוקן רונן בהצלחה
         *
         * @param newToken הטוקן החדש שהתקבל
         */
        void onTokenRefreshed(String newToken);

        /**
         * נקרא כאשר ניסיון הרענון נכשל אך יתבצע retry
         *
         * @param retryCount מספר נסיון ה-retry הנוכחי
         * @param backoffMs פרק הזמן לפני הניסיון הבא במילישניות
         */
        void onTokenRefreshRetry(int retryCount, long backoffMs);

        /**
         * נקרא כאשר כל ניסיונות הרענון כשלו סופית
         *
         * @param reason סיבת הכשל הסופית
         */
        void onTokenRefreshFailed(String reason);
    }
}

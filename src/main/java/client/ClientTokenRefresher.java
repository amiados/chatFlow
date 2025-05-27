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
 * מרענן את הטוקן של המשתמש כל פרק זמן קבוע.
 * שימוש ב־AtomicReference כדי למנוע race-conditions,
 * והתחלה מיידית (initial delay = 0).
 */
public class ClientTokenRefresher{

    /** סף לפני פקיעה (5 דקות) */
    private static final long REFRESH_BEFORE_EXPIRY_MS = TimeUnit.MINUTES.toMillis(5);
    /** מקסימום ניסיונות retry */
    private static final int MAX_RETRIES = 3;
    /** חשבון backoff (ms) - מתחיל ב־1 דקה */
    private static final long BACKOFF_INITIAL_MS = TimeUnit.MINUTES.toMillis(1);

    private final ChatClient client;
    private final ScheduledExecutorService scheduler;
    private final TokenRefreshListener listener;

    private int retryCount = 0;

    /**
     * @param client   ה־ChatClient לביצוע קריאת refreshToken
     * @param user     ה־User שמחזיק את הטוקן
     */
    public ClientTokenRefresher(ChatClient client, User user, TokenRefreshListener listener) {
        this.client = client;
        this.listener = listener;

        // יוצר scheduler ברוחב יחיד, ללא חסימה על הסגירה
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TokenRefresher");
            t.setDaemon(true);
            return t;
        });
    }


    /** מפעיל לוח זמנים one-shot ראשון */
    public void start() {
        scheduleNextRefresh(0);
    }

    /** עוצר את המשימה */
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

    /** מתזמן רענון בעוד delayMs מילישניות */
    private void scheduleNextRefresh(long delayMs) {
        scheduler.schedule(this::refreshTask, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * כל פעם שמתוזמן, בודקים האם הטוקן עומד לפוג; אם כן – מרעננים
     */
    /** הלוגיקה שמתרחשת כשמגיע תור הרענון */
    /** הלוגיקה שמתרחשת כשמגיע תור הרענון */
    private void refreshTask() {
        String currentToken = client.getToken();
        long now = System.currentTimeMillis();

        long expiresAt;
        try {
            expiresAt = Token.extractExpiry(currentToken);
        } catch (Exception e) {
            // טוקן פג או פורמט שגוי
            listener.onTokenRefreshFailed("Invalid token payload");
            return;
        }

        long timeUntilRefresh = (expiresAt - REFRESH_BEFORE_EXPIRY_MS) - now;
        if (timeUntilRefresh > 0 && retryCount == 0) {
            // עוד מוקדם מדי – תזמן בדיוק לפני הסף
            scheduleNextRefresh(timeUntilRefresh);
            return;
        }

        // או: הגיע זמן רענון, או שמנסים retry
        try {
            listener.onBeforeTokenRefresh(retryCount);
            RefreshTokenRequest req = RefreshTokenRequest.newBuilder()
                    .setToken(currentToken)
                    .build();
            RefreshTokenResponse resp = client.refreshToken(req);

            if (resp.getSuccess()) {
                String newToken = resp.getNewToken();
                client.setToken(newToken);
                listener.onTokenRefreshed(newToken);
                retryCount = 0;

                // תזמן refresh הבא בהתבסס על הפקיעה של ה־newToken
                long newExpiresAt = Token.extractExpiry(newToken);
                long nextDelay = (newExpiresAt - REFRESH_BEFORE_EXPIRY_MS) - System.currentTimeMillis();
                scheduleNextRefresh(Math.max(nextDelay, 0));
            } else {
                handleRefreshFailure("Server refused: " + resp.getMessage());
            }
        } catch (Exception e) {
            handleRefreshFailure(e.getMessage());
        }
    }

    /** לוגיקת retry-backoff וכשיוזרך הודעה סופית */
    private void handleRefreshFailure(String reason) {
        if (retryCount < MAX_RETRIES) {
            long backoff = BACKOFF_INITIAL_MS * (1L << retryCount); // 1,2,4 דקות...
            retryCount++;
            listener.onTokenRefreshRetry(retryCount, backoff);
            scheduleNextRefresh(backoff);
        } else {
            listener.onTokenRefreshFailed(reason);
            // לא מתזמן שוב – המערכת צריכה לטפל בכשלון (למשל לנתק או לבקש login)
        }
    }

    /** ממשק callback לטיפול באירועים של רענון */
    public interface TokenRefreshListener {
        /** לפני ניסיון רענון (retryCount=0 למקרה הראשוני) */
        void onBeforeTokenRefresh(int retryCount);
        /** רענון בוצע בהצלחה */
        void onTokenRefreshed(String newToken);
        /** ניסיון רענון נכשל ויתקיים retry */
        void onTokenRefreshRetry(int retryCount, long backoffMs);
        /** כל ניסיונות הרענון כשלו סופית */
        void onTokenRefreshFailed(String reason);
    }

}

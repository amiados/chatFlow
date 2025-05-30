package utils;

import model.InviteDAO;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * שירות לרענון תוקף של הזמנות צ'אט.
 * מפעיל מטלה מחזורית שמסדרת כל ההזמנות בפנדינג ל-EXPIRED לאחר 24 שעות.
 */
public class InviteExpirationService {

    private final InviteDAO inviteDAO;
    private final ScheduledExecutorService scheduler;

    /**
     * יוצר שירות חדש עם InviteDAO ומשגר משימה מחזורית כל שעה.
     * @param inviteDAO ה-DAO לטיפול בהזמנות במסד הנתונים
     */
    public InviteExpirationService(InviteDAO inviteDAO){
        this.inviteDAO = inviteDAO;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "invite-expirer");
            thread.setDaemon(true);
            return thread;
        });

        // מתזמן את expireOldInvites להתבצע מיד ובכל שעה
        scheduler.scheduleAtFixedRate(this::expireOldInvites,
                0, 1, TimeUnit.HOURS);
    }

    /**
     * מבצע עדכון ל-EXPIRED על כל ההזמנות שיצאו לפנדינג לפני 24 שעות.
     * מנצל InviteDAO.expirePendingInvites ומדפיס סטטוס או שגיאות.
     */
    public void expireOldInvites() {
        Instant cutOff = Instant.now().minus(1, ChronoUnit.DAYS);
        try {
            int expired = inviteDAO.expirePendingInvites(cutOff);
            System.out.printf("[InviteExpiration] expired %d invites older than %s%n",
                    expired, cutOff);
        } catch (SQLException e) {
            System.err.println("[InviteExpiration] DB error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * עוצר את השירות ומפסיק את המטלה המחזורית.
     */
    public void stop() {
        scheduler.shutdown();
    }
}

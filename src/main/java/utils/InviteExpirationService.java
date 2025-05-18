package utils;

import model.InviteDAO;

import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class InviteExpirationService {

    private final InviteDAO inviteDAO;
    private final ScheduledExecutorService scheduler;

    public InviteExpirationService(InviteDAO inviteDAO){
        this.inviteDAO = inviteDAO;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "invite-expirer");
            thread.setDaemon(true);
            return thread;
        });

        scheduler.scheduleAtFixedRate(this::expireOldInvites,
                0, 1, TimeUnit.HOURS);
    }

    /**
     * מפעיל את המשימה המחזורית: כל שעה יעדכן ל־EXPIRED
     * את כל ההזמנות שנשלחו לפני 24 שעות.
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

    /** עוצר את השירות */
    public void stop() {
        scheduler.shutdown();
    }
}

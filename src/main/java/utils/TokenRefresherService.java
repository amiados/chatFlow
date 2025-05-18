package utils;

import model.User;
import model.UserDAO;
import security.Token;


import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TokenRefresherService {

    private static final Logger logger = Logger.getLogger(TokenRefresherService.class.getName());
    private final UserDAO userDAO;
    private final ConnectionManager connectionManager;
    private final ScheduledExecutorService scheduler;

    // פעם בדקה (60,000 ms)
    private static final long REFRESH_INTERVAL_MS = TimeUnit.MINUTES.toMillis(1);

    // נרענן אם נותר פחות מ־5 דקות (300,000 ms) עד לפקיעה
    private static final long REFRESH_BEFORE_EXPIRY_MS = TimeUnit.MINUTES.toMillis(5);

    public TokenRefresherService(UserDAO userDAO, ConnectionManager connectionManager) {
        this.userDAO = userDAO;
        this.connectionManager = connectionManager;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TokenRefresher");
            t.setDaemon(true);
            return t;
        });
        // לוחצים את המשימה
        scheduler.scheduleAtFixedRate(
                this::refreshAllTokens,
                REFRESH_INTERVAL_MS,
                REFRESH_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );
    }

    /** עוצר את המשימה המתוזמנת */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if(!scheduler.awaitTermination(5, TimeUnit.SECONDS)){
                scheduler.shutdown();
            }
        } catch (InterruptedException e){
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** נקראת כל 5 דקות, בודקת ורושמת פקיעה או רענון טוקן לקרובים לפקיעה */
    private void refreshAllTokens() {
        List<User> connected;
        try {
            connected = connectionManager.getConnectedUsers();
        } catch (Exception e){
            logger.log(Level.SEVERE, "Failed to fetch connected users", e);
            return;
        }

        long now = System.currentTimeMillis();
        for (User user : connected) {
            try {
                String oldToken = user.getAuthToken();

                // אם הטוקן לא תואם signature או פג תוקף – ניתוק
                if (!Token.verifyToken(oldToken)) {
                    logger.info("Token expired for " + user.getUsername());
                    disconnectUser(user);
                } else {
                    // רענון כבר כשנותרו ≤ 5 דקות עד לפקיעה
                    long expiresAt = Token.extractExpiry(oldToken);
                    if (expiresAt - now <= REFRESH_BEFORE_EXPIRY_MS) {

                        // יוצר טוקן חדש
                        Token newToken = new Token(user);
                        user.setAuthToken(newToken.getToken());

                        // שמר במסד
                        userDAO.updateToken(user, newToken);

                        // הסרה והכנסה מחדש כדי לעדכן מיפוי tokenToClient
                        connectionManager.removeUserFromConnected(user.getId());
                        // observe הוא אותו StreamObserver שהייתה לו
                        connectionManager.addActiveSession(
                                user,
                                connectionManager.getObserver(user.getId()));

                        logger.fine("Refreshed token for " + user.getUsername());

                    }
                }
            }catch (Exception e) {
                logger.log(Level.WARNING,
                        "Error while refreshing token for " + user.getUsername(), e);
            }
        }
    }

    /** * ניתוק של משתמש עם סיבה*/
    private void disconnectUser(User user) {
        try {
            connectionManager.removeUserFromConnected(user.getId());
            connectionManager.disconnectUser(user.getId(), "Token expired or invalid");
            user.setOnline(false);
            userDAO.updateUser(user);
        } catch (Exception e) {
            logger.log(Level.WARNING,
                    "Error disconnecting user " + user.getUsername() + ": " + "Token expired or invalid", e);
        }
    }
}
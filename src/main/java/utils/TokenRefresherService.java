package utils;

import model.User;
import model.UserDAO;
import security.Token;


import java.sql.SQLException;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TokenRefresherService {

    private static final Logger logger = Logger.getLogger(TokenRefresherService.class.getName());
    private final UserDAO userDAO;
    private final ConnectionManager connectionManager;
    private final Timer timer = new Timer(true);

    // 15 דקות
    private static final long REFRESH_INTERVAL = 15 * 60 * 1000; // 15 דקות

    // עדכון רק מי שיגיע לפקיעה בתוך דקה
    private static final long REFRESH_BEFORE_EXPIRY = 60 * 1000;

    public TokenRefresherService(UserDAO userDAO, ConnectionManager connectionManager) {
        this.userDAO = userDAO;
        this.connectionManager = connectionManager;
        timer.scheduleAtFixedRate(new RefreshTask(), REFRESH_INTERVAL, REFRESH_INTERVAL);
    }

    public void shutdown() {
        timer.cancel();
    }

    private class RefreshTask extends TimerTask {

        @Override
        public void run() {
            List<User> connected;
            try {
                connected = connectionManager.getConnectedUsers();
            } catch (Exception e){
                logger.log(Level.SEVERE, "Failed to fetch connected users", e);
                return;
            }for (User user : connected) {
                try {
                    String oldToken = user.getAuthToken();
                    if (!Token.verifyToken(oldToken)) {
                        // טוקן פג תוקף -> ניתוק
                        logger.info("Token expired for " + user.getUsername());
                        user.setOnline(false);
                        userDAO.updateUser(user);
                        connectionManager.removeUserFromConnected(user.getId());
                        connectionManager.disconnectUser(user.getId(), "Token expired");
                    } else {
                        // נרענן רק אם נותר פחות מ־5 דקות
                        long expiresAt = Token.extractExpiry(oldToken);
                        long now = System.currentTimeMillis();
                        if (expiresAt - now <= REFRESH_BEFORE_EXPIRY) {
                            Token newToken = new Token(user);
                            user.setAuthToken(newToken.getToken());
                            // הסרה והכנסה מחדש כדי לעדכן מיפוי tokenToClient
                            connectionManager.removeUserFromConnected(user.getId());
                            // observe הוא אותו StreamObserver שהייתה לו
                            connectionManager.addActiveSession(user, connectionManager.getObserver(user.getId()));
                            // שמור במסד
                            userDAO.updateToken(user, newToken);
                        }
                    }
                } catch (SQLException e) {
                    logger.log(Level.WARNING, "DB error refreshing token for " + user.getUsername(), e);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Unexpected error for " + user.getUsername(), e);
                }
            }
        }
    }
}
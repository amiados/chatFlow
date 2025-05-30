package utils;

import io.grpc.stub.StreamObserver;
import model.User;
import com.chatFlow.Chat.ConnectionResponse;
import security.Token;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * מנהל חיבורים פעילים: מאחסן משתמשים מחוברים, טוקנים ו-StreamObserver לשליחה לפונים.
 * מספק פעולות להוספה, הסרה ושידור הודעות לכל המשתמשים המחוברים.
 */
public class ConnectionManager {
    private static final Logger logger = Logger.getLogger(ConnectionManager.class.getName());
    private static final ConcurrentHashMap<UUID, ConnectedClient> activeClients = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ConnectedClient> tokenToClient = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor();

    static {
        // מנקה טוקנים שפג תוקפם כל 20 דקות
        cleaner.scheduleAtFixedRate(() -> {
            tokenToClient.entrySet().removeIf(entry -> {
                String token = entry.getKey();
                return !Token.verifyToken(token);
            });
        }, 20, 20, TimeUnit.MINUTES);
    }

    /**
     * מוסיף session חדש של משתמש. במקרה של חיבור חוזר, מנתק קודם.
     * @param user המשתמש
     * @param token טוקן אימות
     * @param observer StreamObserver לשליחת ConnectionResponse
     * @return true אם נוסף בהצלחה, false otherwise
     */
    public boolean addActiveSession(User user, String token, StreamObserver<ConnectionResponse> observer) {
        if (user == null || token == null || observer == null) {
            logger.warning("ניסיון להוסיף session עם פרטים חסרים");
            return false;
        }

        UUID userId = user.getId();
        if(!Token.verifyToken(token)){
            logger.warning("בעיה בטוקן: " + userId);
            return false;
        }

        // אם המשתמש כבר מחובר, ננתק אותו קודם
        if (activeClients.containsKey(userId)) {
            disconnectUser(userId, "User reconnected");
        }

        // יצירת מופע חדש של משתמש מחובר
        ConnectedClient newClient = new ConnectedClient(user, token, observer);

        activeClients.put(userId, newClient);

        // הוספת המשתמש למיפוי עם טוקן
        tokenToClient.put(token, newClient);

        logger.info("משתמש התחבר: " + userId);
        return true;
    }

    /**
     * מחזיר רשימת כל המשתמשים המחוברים.
     * @return List של משתמשים
     */
    public List<User> getConnectedUsers() {
        // מחזיר את כל המשתמשים המחוברים
        return activeClients.values().stream()
                .map(ConnectedClient::getUser)
                .collect(Collectors.toList());
    }

    /**
     * מנתק משתמש: שולח הודעת onNext ואז onCompleted, ומסיר ממיפויים.
     * @param userId מזהה המשתמש
     * @param reason סיבת הניתוק
     */
    public void disconnectUser(UUID userId, String reason) {
        // מחפש את המשתמש ברשימת המחוברים ומסיר אותו
        ConnectedClient client = activeClients.remove(userId);
        if (client != null) {
            tokenToClient.remove(client.getToken());
            try {
                // שולח הודעה למשתמש לפני שהוא מתנתק
                client.getObserver().onNext(
                        ConnectionResponse.newBuilder()
                        .setUsername("Disconnected: " + reason)
                        .build());
                client.getObserver().onCompleted();
            } catch (Exception e) {
                logger.info("משתמש נותק: " + userId);
            }
        }
    }

    /**
     * בודק אם משתמש מחובר.
     * @param userId מזהה המשתמש
     * @return true אם מחובר, false otherwise
     */
    public boolean isConnected(UUID userId) {
        return activeClients.containsKey(userId);  // בודק אם המשתמש נמצא ברשימת המחוברים
    }

    /**
     * מסיר משתמש מכל המיפויים בלי שליחת הודעה.
     * @param userId מזהה המשתמש
     */
    public void removeUserFromConnected(UUID userId) {
        // הסרת המשתמש ממערך המחוברים וממיפוי הטוקן
        ConnectedClient client = activeClients.remove(userId);
        if (client != null){
            tokenToClient.remove(client.getToken());
        }

        logger.info("משתמש הוסר מרשימת המחוברים: " + userId);
    }

    /**
     * משדר הודעה לכל המשתמשים המחוברים.
     * @param response האובייקט להודעה
     */
    public void broadcastMessage(ConnectionResponse response) {
        // שליחה לכל הלקוחות המחוברים
        activeClients.values().forEach(client -> {
            try {
                client.getObserver().onNext(response);
            } catch (Exception e) {
                logger.log(Level.WARNING, "שגיאה בשליחת הודעה ללקוח", e);
            }
        });
    }


    /**
     * מחזיר את ה-StreamObserver למשתמש לפי מזהה.
     */
    public StreamObserver<ConnectionResponse> getObserver(UUID userId) {
        ConnectedClient client = activeClients.get(userId);
        return client != null ? client.getObserver() : null;
    }

    /**
     * מחזיר את המשתמש לפי טוקן אימות.
     */
    public User getUserByToken(String token) {
        ConnectedClient client = tokenToClient.get(token);
        return client != null ? client.getUser() : null;
    }
    /**
     * מעדכן מיפוי טוקן בעת רענון: מסיר מפתח ישן ומוסיף חדש.
     */
    public void updateAuthToken(UUID userId, String oldToken, String newToken) {
        ConnectedClient client = activeClients.get(userId);
        if(client == null)
            return;

        // הסרה מהמפה הישן
        tokenToClient.remove(oldToken);

        // הכנסת המיפוי החדש
        tokenToClient.put(newToken, client);
    }

    /**
     * מחלקה פנימית לשמירת נתוני החיבור: משתמש, טוקן ו-observer.
     */
    public static class ConnectedClient {
        private final User user;  // המשתמש המחובר
        private final String token;
        private final StreamObserver<ConnectionResponse> observer;  // המעקב אחרי ההודעות

        public ConnectedClient(User user, String token, StreamObserver<ConnectionResponse> observer) {
            this.user = user;
            this.token = token;
            this.observer = observer;
        }

        public User getUser() {
            return user;  // מחזיר את המשתמש
        }
        public String getToken() { return token; }
        public StreamObserver<ConnectionResponse> getObserver() {
            return observer;  // מחזיר את המעקב אחרי ההודעות
        }
    }
}
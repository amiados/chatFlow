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

public class ConnectionManager {
    private static final Logger logger = Logger.getLogger(ConnectionManager.class.getName());

    private static final ConcurrentHashMap<UUID, ConnectedClient> activeClients = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ConnectedClient> tokenToClient = new ConcurrentHashMap<>();

    private static final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor();

    static {
        // כל 20 דקות, נסיר טוקנים שפג תוקפם
        cleaner.scheduleAtFixedRate(() -> {
            tokenToClient.entrySet().removeIf(entry -> {
                String token = entry.getKey();
                return !Token.verifyToken(token);
            });
        }, 20, 20, TimeUnit.MINUTES);
    }

    /**
     * Adds a new active user session.
     * This method stores the active user in the activeClients map, along with their observer.
     * The observer is used to send responses to the client.
     *
     * @param user the user who is connecting
     * @param observer the StreamObserver for the user
     * @return true if the session is successfully added, false if the user is already connected
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
     * Retrieves a list of all connected users.
     *
     * @return a list of all active users
     */
    public List<User> getConnectedUsers() {
        // מחזיר את כל המשתמשים המחוברים
        return activeClients.values().stream()
                .map(ConnectedClient::getUser)
                .collect(Collectors.toList());
    }

    /**
     * Disconnects a user from the system.
     *
     * @param userId the ID of the user to disconnect
     * @param reason the reason for disconnection
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
     * Checks if a user is currently connected.
     *
     * @param userId the ID of the user
     * @return true if the user is connected, false otherwise
     */
    public boolean isConnected(UUID userId) {
        return activeClients.containsKey(userId);  // בודק אם המשתמש נמצא ברשימת המחוברים
    }

    /**
     * Removes a user from the connected clients, both from activeClients and tokenToClient.
     *
     * @param userId the ID of the user to remove
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
     * Sends a message to all connected users.
     *
     * @param response the message to broadcast
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
     * Retrieves the StreamObserver for a specific user by their userId.
     *
     * @param userId the ID of the user
     * @return the observer associated with the user, or null if the user is not connected
     */
    public StreamObserver<ConnectionResponse> getObserver(UUID userId) {
        ConnectedClient client = activeClients.get(userId);
        return client != null ? client.getObserver() : null;
    }

    /**
     * Retrieves a user by their authToken.
     *
     * @param token the authToken of the user
     * @return the user associated with the token, or null if not found
     */
    public User getUserByToken(String token) {
        ConnectedClient client = tokenToClient.get(token);
        return client != null ? client.getUser() : null;
    }

    public void updateAuthToken(UUID userId, String oldToken, String newToken) {
        ConnectedClient client = activeClients.get(userId);
        if(client == null)
            return;

        // הסרה מהמפה הישן
        tokenToClient.remove(oldToken);

        // הכנסת המיפוי החדש
        tokenToClient.put(newToken, client);
    }

    // --- ConnectedClient class to store user and observer details ---
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
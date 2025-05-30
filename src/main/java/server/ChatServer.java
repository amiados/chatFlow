package server;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.grpc.Server;
import java.io.File;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import model.*;
import utils.*;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * מחלקת ChatServer מאתחלת ומפעילה את שרת ה-gRPC של הצ'אט עם TLS,
 * וכן מנהלת שרתי פקודות לניהול פג תוקף ההזמנות.
 */
public class ChatServer {
    /** ה-port שעליו השרת מאזין */
    private final int port;
    /** מופע ה-gRPC Server שמטפל בבקשות */
    private final Server server;
    /** שירות ניהול פג תוקף הזמנות (InviteExpirationService) */
    private final InviteExpirationService inviteExpirationService;

    /** ברירת מחדל של ה-port שבו השרת יפעל */
    public static final int PORT = 50051;

    /**
     * קונסטרקטור:
     *  - בונה ConnectionManager
     *  - מגדיר Cache ל-OTP, רישומים ממתינים, משתמשים ממתינים
     *  - אתחול DAO שונים
     *  - אתחול InviteExpirationService
     *  - הקמת ה-gRPC server עם TLS ותוספת שירות ChatServiceImpl
     */
    public ChatServer() {
        this.port = PORT;
        ConnectionManager connectionManager = new ConnectionManager();

        // Cache לניהול OTPs שפג תוקפן אחרי 5 דקות
        Cache<String, OTP_Entry> otpCache = CacheBuilder.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .build();

        // Cache לרישומים בהמתנה, תוקף 10 דקות, מקסימום 5000
        Cache<String, User> pendingRegistrations = CacheBuilder.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .maximumSize(5000)
                .build();

        // Cache למשתמשים בהמתנה לאימות, תוקף 10 דקות
        Cache<String, User> pendingUsers = CacheBuilder.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .maximumSize(5000)
                .build();

        // DAO לאינטראקציה עם מסד הנתונים
        UserDAO userDAO = new UserDAO();
        ChatRoomDAO chatRoomDAO = new ChatRoomDAO();
        MessageDAO messageDAO = new MessageDAO();
        InviteDAO inviteDAO = new InviteDAO();
        ChatMemberKeyDAO chatMemberKeyDAO = new ChatMemberKeyDAO();

        // שירות לבדיקת פג תוקף הזמנות ברקע
        inviteExpirationService = new InviteExpirationService(inviteDAO);

        // הקמת שרת gRPC עם TLS והוספת שירות ה-Chat
        this.server = NettyServerBuilder.forPort(port)
                .useTransportSecurity(
                        new File("certs/server.crt"),
                        new File("certs/server.key")
                )
                .addService(new ChatServiceImpl(
                        userDAO,
                        chatRoomDAO,
                        messageDAO,
                        inviteDAO,
                        chatMemberKeyDAO,
                        connectionManager,
                        otpCache,
                        pendingRegistrations,
                        pendingUsers
                ))
                .build();
    }

    /**
     * מפעיל את השרת ומדפיס סטאטוס.
     * מוסיף ShutdownHook כדי לכבות את השרת ושירות פג התוקף בבטחה.
     *
     * @throws IOException אם קרתה שגיאה בהפעלת השרת
     */
    public void start() throws IOException {
        server.start();
        System.out.println("Chat Server started on port " + port);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down gRPC server...");
            ChatServer.this.stop();
            inviteExpirationService.stop();
        }));
    }

    /**
     * מכבה את השרת אם הוא פעיל.
     */
    public void stop() {
        if (server != null) {
            server.shutdown();
        }
    }

    /**
     * ממתין עד שהשרת יסתיים (בלוקינג).
     *
     * @throws InterruptedException אם ממתין התרחש interruption
     */
    public void aWaitTermination() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }

    /**
     * נקודת הכניסה הראשית:
     * 1. יוצר מופע ChatServer
     * 2. מפעיל אותו ו
     * 3. ממתין לסיומו
     *
     * @param args פרמטרים מחרוזתיים (לא בשימוש)
     * @throws Exception שגיאות שונות (IOException, InterruptedException)
     */
    public static void main(String[] args) throws Exception {
        ChatServer chatServer = new ChatServer();
        chatServer.start();
        chatServer.aWaitTermination();
    }
}
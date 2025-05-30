
package server;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;

import java.io.File;
import java.io.IOException;

/**
 *מחלקת SignalingServer מפעילה שרת gRPC
 * לצורך שירות Signaling (למשל WebRTC):
 * - מאזינה על הפורט שניתן
 * - משתמשת ב-TLS עם תעודה ומפתח
 * - מספקת את SignalingServiceImpl
 */
public class SignalingServer {

    private final Server server; // מופע ה-gRPC Server

    /**
     * קונסטרקטור:
     * @param port הפורט שעליו השרת יאזין
     */
    public SignalingServer(int port) {
        this.server = NettyServerBuilder.forPort(port)
                .useTransportSecurity(
                        new File("certs/server.crt"),
                        new File("certs/server.key")
                )
                .addService(new SignalingServiceImpl())
                .build();
    }

    /**
     * מפעיל את השרת ומדפיס סטאטוס
     * @throws IOException אם כשל בהפעלת השרת
     */
    public void start() throws IOException {
        server.start();
        System.out.println("Signaling Server started on port " + server.getPort());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down signaling server...");
            SignalingServer.this.stop();
        }));
    }

    /**
     * מכבה את השרת אם הוא פעיל
     */
    public void stop() {
        if (server != null) {
            server.shutdown();
        }
    }

    /**
     * ממתין עד שהשרת יסתיים
     * @throws InterruptedException אם מתבצעת הפרעה במהלך ההמתנה
     */
    public void awaitTermination() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }

    /**
     * נקודת כניסה עצמאית להפעלת השרת בלבד
     */
    public static void main(String[] args) throws Exception {
        SignalingServer signalingServer = new SignalingServer(50052);
        signalingServer.start();
        signalingServer.awaitTermination();
    }
}

package utils;

import io.grpc.ManagedChannel;
import java.util.concurrent.TimeUnit;

/**
 * Singleton לניהול ערוץ gRPC (ManagedChannel).
 * מספק גישה לערוץ ומשתמש בדגם Singleton כדי לשמור מופע יחיד.
 */
public class ChannelManager {

    private static ChannelManager instance;
    private final ManagedChannel channel;

    /**
     * בנאי פרטי היוצר ערוץ gRPC.
     */
    private ChannelManager() {
        this.channel = ChannelManager.getInstance().getChannel();
    }

    /**
     * מחזיר את מופע ה-Singleton של ChannelManager, יוצר במידת הצורך.
     * @return מופע ChannelManager יחיד
     */
    public static ChannelManager getInstance() {
        if (instance == null) {
            synchronized (ChannelManager.class) {
                if (instance == null) {
                    instance = new ChannelManager();
                }
            }
        }
        return instance;
    }

    /**
     * מחזיר את ערוץ gRPC מנוהל (ManagedChannel).
     * @return ערוץ gRPC
     */
    public ManagedChannel getChannel() {
        return channel;
    }

    /**
     * סוגר את הערוץ בצורה מסודרת, מחכה עד 5 שניות לכל ניסיון shutdown,
     * ולאחריו מבצע shutdownNow במידת הצורך.
     */
    public void shutdown() {
        if (channel != null && !channel.isShutdown()) {
            channel.shutdown();
            try {
                if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                    channel.shutdownNow();
                    if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                        System.err.println("Channel did not terminate.");
                    }
                }
            } catch (InterruptedException e) {
                channel.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}

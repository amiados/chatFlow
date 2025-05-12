package utils;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.util.concurrent.TimeUnit;

public class ChannelManager {
    private static ChannelManager instance;
    private final ManagedChannel channel;

    private static final String SERVER_ADDRESS = "localhost";
    private static final int SERVER_PORT = 50051;

    private ChannelManager() {
        this.channel = ManagedChannelBuilder
                .forAddress(SERVER_ADDRESS, SERVER_PORT)
                .usePlaintext()
                .build();
    }

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

    public ManagedChannel getChannel() {
        return channel;
    }

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

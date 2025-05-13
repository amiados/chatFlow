package utils;

import io.grpc.ManagedChannel;

import java.util.concurrent.TimeUnit;

public class ChannelManager {
    private static ChannelManager instance;
    private final ManagedChannel channel;

    private ChannelManager() {
        this.channel = ChannelManager.getInstance().getChannel();
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

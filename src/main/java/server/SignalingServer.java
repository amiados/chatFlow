package server;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;

import java.io.File;
import java.io.IOException;

public class SignalingServer {

    private final Server server;

    public SignalingServer(int port) {
        this.server = NettyServerBuilder.forPort(port)
                .useTransportSecurity(
                        new File("certs/server.crt"),
                        new File("certs/server.key")
                )
                .addService(new SignalingServiceImpl())
                .build();
    }

    public void start() throws IOException {
        server.start();
        System.out.println("Signaling Server started on port 50052");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down signaling server...");
            SignalingServer.this.stop();
        }));
    }

    public void stop() {
        if (server != null) {
            server.shutdown();
        }
    }

    public void awaitTermination() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }

    public static void main(String[] args) throws Exception {
        SignalingServer signalingServer = new SignalingServer(50052);
        signalingServer.start();
        signalingServer.awaitTermination();
    }
}

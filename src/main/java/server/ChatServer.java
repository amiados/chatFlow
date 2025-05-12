package server;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import model.*;
import utils.ConnectionManager;
import utils.OTP_Entry;
import utils.TokenRefresherService;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class ChatServer {
    private final int port;
    private final Server server;
    //private final TokenRefresherService tokenRefresherService;

    public static final int PORT = 50051;

    public ChatServer(){
        this.port = PORT;
        ConnectionManager connectionManager = new ConnectionManager();

        Cache<String, OTP_Entry> otpCache = CacheBuilder.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .build();

        Cache<String, User> pendingRegistrations = CacheBuilder.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .maximumSize(5000)
                .build();

        Cache<String, User> pendingUsers = CacheBuilder.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .maximumSize(5000)
                .build();

        UserDAO userDAO = new UserDAO();
        ChatRoomDAO chatRoomDAO = new ChatRoomDAO();
        MessageDAO messageDAO = new MessageDAO();
        InviteDAO inviteDAO = new InviteDAO();

        //tokenRefresherService = new TokenRefresherService(userDAO, connectionManager);

        this.server = ServerBuilder.forPort(port)
                .addService(new ChatServiceImpl(
                        userDAO, chatRoomDAO, messageDAO, inviteDAO,
                        connectionManager, otpCache,
                        pendingRegistrations, pendingUsers))
                .build();
    }

    public void start() throws IOException {
        server.start();
        System.out.println("Chat Server started on port " + port);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down gRPC server...");
            ChatServer.this.stop();
            //tokenRefresherService.shutdown();
        }));
    }

    public void stop() {
        if (server != null) {
            server.shutdown();
        }
    }

    public void aWaitTermination() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }
    public static void main(String[] args) throws Exception {
        ChatServer chatServer = new ChatServer();

        chatServer.start();

        chatServer.aWaitTermination();
    }
}

package server;

import java.io.IOException;

public class MainServerLauncher {

    public static void main(String[] args) {
        // יצירת מופעים
        ChatServer chatServer = new ChatServer();
        SignalingServer signalingServer = new SignalingServer(50052);

        try {
            // הפעלת השרתים
            chatServer.start();
            signalingServer.start();

            System.out.println("Both servers are up and running!");

            // המתנה לסיום
            chatServer.aWaitTermination();
            signalingServer.awaitTermination();

        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            System.out.println("Error while starting servers: " + e.getMessage());
        }
    }
}

package server;

import java.io.IOException;

/**
 * מחלקת MainServerLauncher מאחדת את הפעלת שני השרתים:
 * 1. ChatServer על פורט 50051
 * 2. SignalingServer על פורט 50052
 * ומנהלת את מחזור החיים שלהם (start, awaitTermination, stop).
 */
public class MainServerLauncher {

    /**
     * נקודת הכניסה הראשית:
     * - יוצרת מופעים של ChatServer ו-SignalingServer
     * - מפעילה אותם
     * - ממתינה לסיום שלהם
     * - מבצעת ניקוי משאבים ב-finally
     */
    public static void main(String[] args) {
        ChatServer chatServer = new ChatServer();
        SignalingServer signalingServer = new SignalingServer(50052);

        try {
            // הפעלת השרתים
            chatServer.start();
            signalingServer.start();

            System.out.println("Both servers are up and running!");

            // המתנה עד לסגירה
            chatServer.aWaitTermination();
            signalingServer.awaitTermination();

        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            System.out.println("Error while starting servers: " + e.getMessage());
        } finally {
            // עצירת השרתים במקרה של שגיאה או סיום
            chatServer.stop();
            signalingServer.stop();
        }
    }
}
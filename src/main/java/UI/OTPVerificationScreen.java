package UI;

import javax.swing.*;
import java.awt.*;

import client.ChatClient;
import com.chatFlow.Chat.ConnectionResponse;
import com.chatFlow.Chat.VerifyOtpRequest;
import com.chatFlow.Chat.OtpMode;
import model.User;
import utils.OTPManager;

/**
 * מסך לאימות OTP עבור הרשמה או התחברות.
 * מציג שדה להזנת הקוד, כפתור לשליחה מחדש, כפתור לאימות,
 * וטיימר לספירת זמן עד תוקף הקוד.
 */
public class OTPVerificationScreen extends JFrame {

    private final OTPManager otpManager = new OTPManager();  // מנהל בקשות OTP
    private final JTextField otpField = new JTextField(6);   // שדה להזנת הקוד
    private final JLabel statusLabel = new JLabel(" ");    // תווית סטטוס פעולות
    private final JLabel timerLabel = new JLabel(" ");     // תווית טיימר לספירה לאחור

    private Timer countdownTimer;      // טיימר פנימי לחישוב זמן
    private int remainingSeconds = 300; // זמן התוקף ב־שניות (5 דקות)
    private int resendAttempts = 0;     // מספר הפעמים שנשלח מחדש

    private final String email;       // כתובת האימייל שעבורה מתבצע האימות
    private final OtpMode mode;       // מצב OTP: הרשמה או התחברות
    private final ChatClient client;  // לקוח ליצירת בקשות RPC

    /**
     * קונסטרקטור:
     * @param email כתובת האימייל לאימות
     * @param mode  מצב הפעולה (OtpMode.REGISTER או OtpMode.LOGIN)
     * @param client מופע ChatClient לביצוע קריאות לשרת
     */
    public OTPVerificationScreen(String email, OtpMode mode, ChatClient client) {
        this.email = email;
        this.mode = mode;
        this.client = client;

        setTitle("OTP Verification");
        setLayout(new GridLayout(7, 1, 5, 5));
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(400, 300);
        setLocationRelativeTo(null);

        // כפתור לשליחת OTP מחדש
        JButton sendOTPButton = new JButton("Send New OTP");
        sendOTPButton.addActionListener(e -> sendOTP());
        add(sendOTPButton);

        add(new JLabel("Enter OTP:"));
        add(otpField);

        // כפתור לאימות הקוד
        JButton verifyOTPButton = new JButton("Verify OTP");
        verifyOTPButton.addActionListener(e -> verifyOTP());
        add(verifyOTPButton);

        add(statusLabel);  // תצוגת סטטוס פעולות
        add(timerLabel);   // תצוגת טיימר

        startCountdown();  // התחלת הספירה לאחור
        setVisible(true);
    }

    /**
     * שליחת בקשת OTP חדש באמצעות ה-OTPManager
     * ומניעת שליחו יתר על המידה
     */
    private void sendOTP() {
        if (resendAttempts > 3) {
            statusLabel.setText("Maximum resend attempts reached. Wait 5 minutes.");
            return;
        }
        boolean sent = otpManager.requestOTP(email);
        if (sent) {
            statusLabel.setText("OTP sent to " + email);
            resendAttempts++;
            startCountdown();
        } else {
            statusLabel.setText("Failed to send OTP or user is locked.");
        }
    }

    /**
     * אימות ה-OTP שהוזן:
     * שליחת בקשת VerifyOtpRequest במסד רקע
     * וטיפול בתוצאה: מעבר למסך הראשי או הצגת שגיאה
     */
    private void verifyOTP() {
        String otp = otpField.getText().trim();
        if (otp.isEmpty()) {
            statusLabel.setText("Please enter the OTP.");
            return;
        }
        new SwingWorker<User, Void>() {
            @Override
            protected User doInBackground() throws Exception {
                VerifyOtpRequest req = VerifyOtpRequest.newBuilder()
                        .setEmail(email)
                        .setOtp(otp)
                        .build();
                ConnectionResponse resp = (mode == OtpMode.REGISTER)
                        ? client.verifyRegisterOtp(req)
                        : client.verifyLoginOtp(req);
                if (!resp.getSuccess()) {
                    throw new RuntimeException("Verification failed: " + resp.getMessage());
                }
                client.setToken(resp.getToken());
                User current = client.getCurrentUser();
                if (current == null) {
                    throw new RuntimeException("Failed to fetch current user");
                }
                client.setUser(current);
                return current;
            }
            @Override
            protected void done() {
                try {
                    User currentUser = get();
                    countdownTimer.stop();
                    statusLabel.setText("OTP verified successfully!");
                    JOptionPane.showMessageDialog(OTPVerificationScreen.this,
                            "Success! Redirecting to chat screen...");
                    dispose();
                    new MainScreen(currentUser, client).setVisible(true);
                } catch (Exception ex) {
                    statusLabel.setText(ex.getMessage());
                }
            }
        }.execute();
    }

    /**
     * התחלת ספירת זמן לאחור עד לפוגת הקוד
     * והצגת הזמן ב-label מתאים
     */
    private void startCountdown() {
        if (countdownTimer != null && countdownTimer.isRunning()) {
            countdownTimer.stop();
        }
        remainingSeconds = 300;
        resendAttempts = 0;
        countdownTimer = new Timer(1000, e -> {
            remainingSeconds--;
            int minutes = remainingSeconds / 60;
            int seconds = remainingSeconds % 60;
            timerLabel.setText(String.format("Time left: %02d:%02d", minutes, seconds));
            if (remainingSeconds <= 0) {
                countdownTimer.stop();
                timerLabel.setText("OTP expired. You can request again.");
                resendAttempts = 0;
            }
        });
        countdownTimer.start();
    }
}

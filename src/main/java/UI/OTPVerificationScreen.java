package UI;

import javax.swing.*;
import java.awt.*;

import client.ChatClient;
import com.chatFlow.Chat.ConnectionResponse;
import com.chatFlow.Chat.*;
import model.User;
import utils.OTPManager;
import com.chatFlow.Chat.OtpMode;

public class OTPVerificationScreen extends JFrame {

    private final OTPManager otpManager = new OTPManager();

    private final JTextField otpField = new JTextField(6);
    private final JLabel statusLabel = new JLabel(" ");
    private final JLabel timerLabel = new JLabel(" ");

    private Timer countdownTimer;
    private int remainingSeconds = 300;
    private int resendAttempts = 0;
    private final String email;
    private final OtpMode mode;
    private final ChatClient client;

    public OTPVerificationScreen(String email, OtpMode mode, ChatClient client) {
        this.email = email;
        this.mode = mode;
        this.client = client;

        setTitle("OTP Verification");
        setLayout(new GridLayout(7, 1, 5, 5));
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(400, 300);
        setLocationRelativeTo(null);

        JButton sendOTPButton = new JButton("Send New OTP");
        add(sendOTPButton);
        add(new JLabel("Enter OTP:"));
        add(otpField);
        JButton verifyOTPButton = new JButton("Verify OTP");
        add(verifyOTPButton);
        add(statusLabel);
        add(timerLabel);

        sendOTPButton.addActionListener(e -> sendOTP());
        verifyOTPButton.addActionListener(e -> verifyOTP());
        startCountdown();
        setVisible(true);
    }

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

    private void verifyOTP() {
        String otp = otpField.getText().trim();
        if (otp.isEmpty()) {
            statusLabel.setText("Please enter the OTP.");
            return;
        }

        // עבודת רשת מחוץ ל-EDT
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
package UI;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Base64;
import java.util.UUID;

import client.ChatClient;
import com.chatFlow.Chat;
import com.chatFlow.Chat.ConnectionResponse;
import com.chatFlow.Chat.*;
import model.User;
import utils.OTPManager;
import com.chatFlow.Chat.OtpMode;

public class OTPVerificationScreen extends JFrame {

    private final OTPManager otpManager = new OTPManager();

    private final JTextField otpField = new JTextField(6);
    private final JButton sendOTPButton = new JButton("Send New OTP");
    private final JButton verifyOTPButton = new JButton("Verify OTP");
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

        add(sendOTPButton);
        add(new JLabel("Enter OTP:"));
        add(otpField);
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
        try {
            VerifyOtpRequest request = VerifyOtpRequest.newBuilder()
                    .setEmail(email)
                    .setOtp(otp)
                    .build();

            ConnectionResponse response = (mode == OtpMode.REGISTER) ?
                    client.verifyRegisterOtp(request) :
                    client.verifyLoginOtp(request);

            if (response.getSuccess()) {
                if (countdownTimer != null) {
                    VerifyTokenRequest req = VerifyTokenRequest.newBuilder()
                            .setToken(response.getToken())
                            .build();
                    UserResponse userResp = client.getCurrentUser(req);

                    User user = new User(
                            UUID.fromString(userResp.getUserId()),
                            userResp.getUsername(),
                            userResp.getEmail(),
                            null,
                            Base64.getDecoder().decode(userResp.getPublicKey()),
                            null,
                            Base64.getDecoder().decode(userResp.getN())
                    );
                    user.setAuthToken(response.getToken());

                    countdownTimer.stop();
                    statusLabel.setText("OTP verified successfully!");
                    JOptionPane.showMessageDialog(this, "Success! Redirecting to chat screen...");
                    dispose();
                    new MainScreen(user, client).setVisible(true);
                }
            } else {
                statusLabel.setText("Verification failed: " + response.getMessage());
            }
        } catch (Exception e){
            JOptionPane.showMessageDialog(this, "Communication Error: " + e.getMessage(),
                    "Connection Failure", JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
        }
    }

    private void startCountdown() {
        if (countdownTimer != null && countdownTimer.isRunning()) {
            countdownTimer.stop();
        }

        remainingSeconds = 300;
        resendAttempts = 0;

        countdownTimer = new Timer(1000, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                remainingSeconds--;
                int minutes = remainingSeconds / 60;
                int seconds = remainingSeconds % 60;
                timerLabel.setText(String.format("Time left: %02d:%02d", minutes, seconds));

                if (remainingSeconds <= 0) {
                    countdownTimer.stop();
                    timerLabel.setText("OTP expired. You can request again.");
                    resendAttempts = 0;
                }
            }
        });

        countdownTimer.start();
    }

}
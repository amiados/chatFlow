package UI;

import client.ChatClient;
import com.chatFlow.Chat;
import com.chatFlow.Chat.ConnectionResponse;
import com.chatFlow.Chat.OtpMode;
import javax.swing.*;
import java.awt.*;

/**
 * חלון התחברות למערכת (Login).
 * משתמש ב-ChatClient לביצוע בקשת התחברות ומעביר למסך אימות OTP בעת הצלחה.
 */
public class LoginWindow extends JFrame {

    private JTextField emailField;
    private JPasswordField passwordField;
    private JButton loginButton;
    private final ChatClient client;

    /**
     * קונסטרקטור:
     * @param client מופע ChatClient לשיחות RPC
     */
    public LoginWindow(ChatClient client) {
        this.client = client;
        setTitle("Login");
        initUI();
    }

    /**
     * אתחול ממשק המשתמש: הגדרת תכונות החלון והוספת פאנל הטופס
     */
    private void initUI() {
        setSize(900, 900);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        add(buildFormPanel());
    }

    /**
     * בניית פאנל טופס עם שדות אימייל וסיסמה, כפתור התחברות וקישור להרשמה
     * @return JPanel מוכן להצגה
     */
    private JPanel buildFormPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(new Color(250, 250, 250));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(20, 20, 20, 20);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // כותרת
        JLabel titleLabel = new JLabel("User Login");
        titleLabel.setFont(new Font("Arial", Font.BOLD, 28));
        titleLabel.setHorizontalAlignment(SwingConstants.CENTER);
        gbc.gridwidth = 2; gbc.gridx = 0; gbc.gridy = 0;
        panel.add(titleLabel, gbc);
        gbc.gridwidth = 1;

        // שדה אימייל
        gbc.gridy++; gbc.gridx = 0;
        panel.add(new JLabel("Email:"), gbc);
        gbc.gridx = 1;
        emailField = new JTextField(20);
        panel.add(emailField, gbc);

        // שדה סיסמה
        gbc.gridy++; gbc.gridx = 0;
        panel.add(new JLabel("Password:"), gbc);
        gbc.gridx = 1;
        passwordField = new JPasswordField(20);
        panel.add(passwordField, gbc);

        // כפתור התחברות
        gbc.gridy++; gbc.gridx = 0; gbc.gridwidth = 2;
        loginButton = createButton("Login", new Color(70, 130, 180));
        panel.add(loginButton, gbc);

        // קישור להרשמה
        gbc.gridy++;
        JButton switchToRegisterButton = createFlatLink("Don't have an account? Register");
        panel.add(switchToRegisterButton, gbc);

        // מאזינים
        loginButton.addActionListener(e -> handleLogin());
        switchToRegisterButton.addActionListener(e -> switchToRegister());

        return panel;
    }

    /**
     * טיפול בלחיצה על כפתור התחברות:
     * - אימות שדות
     * - קריאה לשרת באמצעות SwingWorker
     * - מעבר למסך OTP בהצלחה
     */
    private void handleLogin() {
        String email = emailField.getText().trim();
        String password = new String(passwordField.getPassword());
        if (email.isEmpty() || password.isEmpty()) {
            showError("Please fill in all fields.");
            return;
        }
        if (!isValidEmail(email)) {
            showError("Invalid email format.");
            return;
        }
        loginButton.setEnabled(false);

        new SwingWorker<ConnectionResponse, Void>() {
            @Override
            protected ConnectionResponse doInBackground() throws Exception {
                Chat.LoginRequest req = Chat.LoginRequest.newBuilder()
                        .setEmail(email)
                        .setPassword(password)
                        .build();
                return client.login(req);
            }
            @Override
            protected void done() {
                loginButton.setEnabled(true);
                try {
                    ConnectionResponse resp = get();
                    if (resp.getSuccess()) {
                        dispose();
                        new OTPVerificationScreen(email, OtpMode.LOGIN, client).setVisible(true);
                    } else {
                        showError(resp.getMessage());
                    }
                } catch (Exception e) {
                    showError("Server Error: " + e.getMessage());
                }
            }
        }.execute();
    }

    /**
     * מעבר למסך הרשמה
     */
    private void switchToRegister() {
        dispose();
        new RegisterWindow(client).setVisible(true);
    }

    /**
     * הצגת הודעת שגיאה בדיאלוג
     */
    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Error", JOptionPane.ERROR_MESSAGE);
    }

    /**
     * יצירת כפתור מעוצב
     */
    private JButton createButton(String text, Color bgColor) {
        JButton button = new JButton(text);
        button.setPreferredSize(new Dimension(200, 40));
        button.setFont(new Font("Arial", Font.BOLD, 16));
        button.setBackground(bgColor);
        button.setForeground(Color.WHITE);
        return button;
    }

    /**
     * יצירת כפתור שטוח כהפניה
     */
    private JButton createFlatLink(String text) {
        JButton button = new JButton(text);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setForeground(Color.BLUE);
        return button;
    }

    /**
     * בדיקת פורמט אימייל פשוט באמצעות Regex
     */
    private boolean isValidEmail(String email) {
        return email.matches("^[\\w.-]+@[\\w.-]+\\.[a-zA-Z]{2,}$");
    }
}
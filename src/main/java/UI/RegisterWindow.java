package UI;

import javax.swing.*;
import java.awt.*;

import client.ChatClient;

import com.chatFlow.Chat.*;

public class RegisterWindow extends JFrame {
    private JTextField usernameField;
    private JTextField emailField;
    private JPasswordField passwordField;
    private JPasswordField confirmPasswordField;
    private JButton registerButton;
    private final ChatClient client;

    public RegisterWindow(ChatClient client) {
        this.client = client;
        setTitle("Register");
        initUI();
    }

    private void initUI() {
        setSize(900, 900);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        JPanel panel = buildFormPanel("User Registration");
        add(panel);
    }

    private JPanel buildFormPanel(String titleText) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(new Color(245, 245, 245));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(20, 20, 20, 20);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Title
        JLabel titleLabel = new JLabel(titleText);
        titleLabel.setFont(new Font("Arial", Font.BOLD, 28));
        titleLabel.setHorizontalAlignment(SwingConstants.CENTER);
        gbc.gridwidth = 2;
        gbc.gridx = 0;
        gbc.gridy = 0;
        panel.add(titleLabel, gbc);
        gbc.gridwidth = 1;

        // Username
        gbc.gridy++;
        gbc.gridx = 0;
        panel.add(new JLabel("Username:"), gbc);
        gbc.gridx = 1;
        usernameField = new JTextField(20);
        panel.add(usernameField, gbc);

        // Email
        gbc.gridy++;
        gbc.gridx = 0;
        panel.add(new JLabel("Email:"), gbc);
        gbc.gridx = 1;
        emailField = new JTextField(20);
        panel.add(emailField, gbc);

        // Password
        gbc.gridy++;
        gbc.gridx = 0;
        panel.add(new JLabel("Password:"), gbc);
        gbc.gridx = 1;
        passwordField = new JPasswordField(20);
        panel.add(passwordField, gbc);

        // Confirm
        gbc.gridy++;
        gbc.gridx = 0;
        panel.add(new JLabel("Confirm Password:"), gbc);
        gbc.gridx = 1;
        confirmPasswordField = new JPasswordField(20);
        panel.add(confirmPasswordField, gbc);

        // Register button
        gbc.gridy++;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        registerButton = createButton("Register", new Color(100, 150, 255));
        panel.add(registerButton, gbc);

        // Switch to login
        gbc.gridy++;
        JButton switchToLoginButton = createFlatLink("Already have an account? Login");
        panel.add(switchToLoginButton, gbc);

        registerButton.addActionListener(e -> handleRegister());
        switchToLoginButton.addActionListener(e -> switchToLogin());

        return panel;
    }

    private void handleRegister() {
        String username = usernameField.getText().trim();
        String email = emailField.getText().trim();
        String password = new String(passwordField.getPassword());
        String confirmPassword = new String(confirmPasswordField.getPassword());

        if (username.isEmpty() || email.isEmpty() || password.isEmpty()) {
            showError("Please fill in all fields.");
            return;
        }

        if (!isValidEmail(email)) {
            showError("Invalid email format.");
            return;
        }

        if (!password.equals(confirmPassword)) {
            showError("Passwords do not match.");
            return;
        }

        if (password.length() < 8) {
            showError("Password must be at least 8 characters and contains at least one Uppercase and one Lowercase letters, one number and one special character");
            return;
        }

        registerButton.setEnabled(false);

        new SwingWorker<ConnectionResponse, Void>() {
            @Override
            protected ConnectionResponse doInBackground() throws Exception {
                RegisterRequest req = RegisterRequest.newBuilder()
                        .setUsername(username)
                        .setEmail(email)
                        .setPassword(password)
                        .build();
                return client.register(req);
            }
            @Override
            protected void done() {
                registerButton.setEnabled(true);
                try {
                    ConnectionResponse resp = get();
                    if (resp.getSuccess()) {
                        dispose();
                        new OTPVerificationScreen(email, OtpMode.REGISTER, client)
                                .setVisible(true);
                    } else {
                        showError(resp.getMessage());
                    }
                } catch (Exception ex) {
                    showError("Server Error: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void switchToLogin() {
        dispose();
        new LoginWindow(client).setVisible(true);
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Error", JOptionPane.ERROR_MESSAGE);
    }

    private JButton createButton(String text, Color bgColor) {
        JButton button = new JButton(text);
        button.setPreferredSize(new Dimension(200, 40));
        button.setFont(new Font("Arial", Font.BOLD, 16));
        button.setBackground(bgColor);
        button.setForeground(Color.WHITE);
        return button;
    }

    private JButton createFlatLink(String text) {
        JButton button = new JButton(text);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setForeground(Color.BLUE);
        return button;
    }

    private boolean isValidEmail(String email) {
        return email.matches("^[\\w.-]+@[\\w.-]+\\.[a-zA-Z]{2,}$");
    }

}
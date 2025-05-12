package UI;

import client.ChatClient;

import javax.swing.*;
import java.awt.*;

public class WelcomeWindow extends JFrame {

    private final ChatClient client = new ChatClient();

    public WelcomeWindow() {
        setTitle("Welcome");
        setSize(900, 900);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        JPanel panel = new JPanel();
        panel.setLayout(new BorderLayout());
        panel.setBackground(new Color(240, 248, 255)); // light blue background

        // Title Section
        JLabel titleLabel = new JLabel("Welcome to ChatX");
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 40));
        titleLabel.setHorizontalAlignment(SwingConstants.CENTER);
        titleLabel.setBorder(BorderFactory.createEmptyBorder(80, 0, 40, 0));
        panel.add(titleLabel, BorderLayout.NORTH);

        // Center Section with buttons
        JPanel centerPanel = new JPanel();
        centerPanel.setOpaque(false);
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));

        JButton loginButton = createMainButton("Login");
        JButton registerButton = createMainButton("Register");

        loginButton.addActionListener(e -> {
            dispose();
            new LoginWindow(client).setVisible(true);
        });

        registerButton.addActionListener(e -> {
            dispose();
            new RegisterWindow(client).setVisible(true);
        });

        centerPanel.add(loginButton);
        centerPanel.add(Box.createRigidArea(new Dimension(0, 30)));
        centerPanel.add(registerButton);

        panel.add(centerPanel, BorderLayout.CENTER);

        add(panel);
    }

    private JButton createMainButton(String text) {
        JButton button = new JButton(text);
        button.setAlignmentX(Component.CENTER_ALIGNMENT);
        button.setPreferredSize(new Dimension(250, 60));
        button.setMaximumSize(new Dimension(250, 60));
        button.setFont(new Font("Arial", Font.BOLD, 22));
        button.setBackground(new Color(65, 105, 225));
        button.setForeground(Color.WHITE);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));
        return button;
    }

    // לבדיקה עצמאית
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new WelcomeWindow().setVisible(true));
    }
}
package UI;

import client.ChatClient;

import javax.swing.*;
import java.awt.*;

/**
 * חלון פתיחה (Welcome) של היישום.
 * מציג כפתורי Login ו-Register ומעביר ללשוניות המתאימות.
 * מפעיל מופע של ChatClient עבור האינטראקציה עם השרת.
 */
public class WelcomeWindow extends JFrame {

    private final ChatClient client = new ChatClient();  // לקוח לשימוש בשאר החלונות

    /**
     * קונסטרקטור:
     * - מגדיר כותרת ומאפייני מסגרת
     * - בונה layout עם כפתורי Login ו-Register
     */
    public WelcomeWindow() {
        setTitle("Welcome");
        setSize(900, 900);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        // פאנל ראשי עם רקע בהיר
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(new Color(240, 248, 255));

        // כותרת עליונה
        JLabel titleLabel = new JLabel("Welcome to ChatX");
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 40));
        titleLabel.setHorizontalAlignment(SwingConstants.CENTER);
        titleLabel.setBorder(BorderFactory.createEmptyBorder(80, 0, 40, 0));
        panel.add(titleLabel, BorderLayout.NORTH);

        // פאנל מרכזי עם כפתורים
        JPanel centerPanel = new JPanel();
        centerPanel.setOpaque(false);
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));

        JButton loginButton = createMainButton("Login");
        JButton registerButton = createMainButton("Register");

        // מאזינים לכפתורי ניווט
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

    /**
     * יוצר כפתור עיקרי עם עיצוב אחיד
     * @param text הטקסט שיופיע על הכפתור
     * @return JButton מעוצב
     */
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

    /**
     * נקודת כניסה לבדיקה עצמאית של החלון
     * @param args ארגומנטים (לא בשימוש)
     */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new WelcomeWindow().setVisible(true));
    }
}

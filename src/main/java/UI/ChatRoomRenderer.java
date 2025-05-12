package UI;

import model.ChatMember;
import model.ChatRoom;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.UUID;

public class ChatRoomRenderer extends JPanel implements ListCellRenderer<ChatRoom> {

    private final JLabel nameLabel;
    private final JLabel unreadLabel;
    private final JLabel statusIcon;

    private final UUID currentUserId;

    public ChatRoomRenderer(UUID id) {
        this.currentUserId = id;

        setLayout(new BorderLayout(10, 0));

        nameLabel = new JLabel();
        unreadLabel = new JLabel();
        statusIcon = new JLabel();

        nameLabel.setFont(new Font("Arial", Font.PLAIN, 16));
        unreadLabel.setFont(new Font("Arial", Font.PLAIN, 12));
        unreadLabel.setForeground(Color.GRAY);

        JPanel textPanel = new JPanel(new BorderLayout());
        textPanel.setOpaque(false);
        textPanel.add(nameLabel, BorderLayout.CENTER);
        textPanel.add(unreadLabel, BorderLayout.EAST);

        add(statusIcon, BorderLayout.WEST);
        add(textPanel, BorderLayout.CENTER);

        setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

    }

    @Override
    public Component getListCellRendererComponent(JList<? extends ChatRoom> list, ChatRoom chat, int index, boolean isSelected, boolean cellHasFocus) {
        nameLabel.setText(chat.getName());

        ChatMember member = chat.getMembers().get(currentUserId);

        int unreadMessages = member != null ? member.getUnreadMessages() : 0;
        boolean isActive = member != null && member.isActive();

        // אם יש הודעות שלא נקראו
        if (unreadMessages > 0) {
            nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD));
            unreadLabel.setText("(" + unreadMessages + ")");
        } else {
            nameLabel.setFont(nameLabel.getFont().deriveFont(Font.PLAIN));
            unreadLabel.setText("");
        }

        // אם הצ'אט פעיל
        if (isActive) {
            statusIcon.setIcon(createStatusIcon(Color.GREEN));
        } else {
            statusIcon.setIcon(createStatusIcon(Color.LIGHT_GRAY));
        }

        // צבעי בחירה
        if (isSelected) {
            setBackground(list.getSelectionBackground());
            nameLabel.setForeground(list.getSelectionForeground());
            unreadLabel.setForeground(list.getSelectionForeground());
        } else {
            setBackground(list.getBackground());
            nameLabel.setForeground(list.getForeground());
            unreadLabel.setForeground(Color.GRAY);
        }

        return this;
    }


    private Icon createStatusIcon(Color color) {
        int size = 10;
        Image image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = (Graphics2D) image.getGraphics();
        g2.setColor(color);
        g2.fillOval(0, 0, size, size);
        g2.dispose();
        return new ImageIcon(image);
    }
}

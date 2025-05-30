package UI;

import model.ChatMember;
import model.ChatRoom;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.UUID;

/**
 * Renderer מותאם אישית עבור פריטי ChatRoom ברשימת Swing (JList).
 * מציג שם צ'אט, מספר הודעות לא נקראו ואייקון סטטוס (פעיל/לא פעיל).
 */
public class ChatRoomRenderer extends JPanel implements ListCellRenderer<ChatRoom> {

    private final JLabel nameLabel;       // תווית לשם הצ'אט
    private final JLabel unreadLabel;     // תווית למספר הודעות לא נקראו
    private final JLabel statusIcon;      // אייקון שמצביע על סטטוס פעיל

    private final UUID currentUserId;     // מזהה המשתמש הנוכחי לצורך שליפת סטטוס הכרוך

    /**
     * קונסטרקטור:
     * @param id מזהה המשתמש ששייך לו הצ'אטים ברשימה
     */
    public ChatRoomRenderer(UUID id) {
        this.currentUserId = id;

        setLayout(new BorderLayout(10, 0));

        nameLabel = new JLabel();
        unreadLabel = new JLabel();
        statusIcon = new JLabel();

        nameLabel.setFont(new Font("Arial", Font.PLAIN, 16));
        unreadLabel.setFont(new Font("Arial", Font.PLAIN, 12));
        unreadLabel.setForeground(Color.GRAY);

        // פאנל טקסט הכולל שם וציון הודעות
        JPanel textPanel = new JPanel(new BorderLayout());
        textPanel.setOpaque(false);
        textPanel.add(nameLabel, BorderLayout.CENTER);
        textPanel.add(unreadLabel, BorderLayout.EAST);

        add(statusIcon, BorderLayout.WEST);
        add(textPanel, BorderLayout.CENTER);

        setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
    }

    /**
     * יוצר רכיב להצגה עבור כל ChatRoom בפנל הרשימה.
     * מגדיר טקסט, עיצוב גופן, צבע רקע ואייקון סטטוס.
     *
     * @param list הרכיב JList המזמין הצגה
     * @param chat אובייקט ChatRoom להצגה
     * @param index אינדקס הפריט ברשימה
     * @param isSelected האם הפריט נבחר
     * @param cellHasFocus האם הפריט בפוקוס
     * @return הרכיב המותאם להצגה
     */
    @Override
    public Component getListCellRendererComponent(JList<? extends ChatRoom> list,
                                                  ChatRoom chat,
                                                  int index,
                                                  boolean isSelected,
                                                  boolean cellHasFocus) {
        // שם הצ'אט
        nameLabel.setText(chat.getName());

        // שליפת סטטוס המשתמש בצ'אט
        ChatMember member = chat.getMembers().get(currentUserId);
        int unreadMessages = member != null ? member.getUnreadMessages() : 0;
        boolean isActive = member != null && member.isActive();

        // הדגשת גופן במידה ויש הודעות לא נקראו והצגת המונה
        if (unreadMessages > 0) {
            nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD));
            unreadLabel.setText("(" + unreadMessages + ")");
        } else {
            nameLabel.setFont(nameLabel.getFont().deriveFont(Font.PLAIN));
            unreadLabel.setText("");
        }

        // קביעת צבע האייקון לפי פעילות
        statusIcon.setIcon(createStatusIcon(isActive ? Color.GREEN : Color.LIGHT_GRAY));

        // עיצוב צבעי רקע וטקסט במצב בחירה
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

    /**
     * יוצר אייקון עיגול קטן בצבע נתון עבור סטטוס.
     *
     * @param color צבע המילוי של העיגול
     * @return Icon בצורת עיגול רדיוס קבוע
     */
    private Icon createStatusIcon(Color color) {
        int size = 10;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = image.createGraphics();
        g2.setColor(color);
        g2.fillOval(0, 0, size, size);
        g2.dispose();
        return new ImageIcon(image);
    }
}

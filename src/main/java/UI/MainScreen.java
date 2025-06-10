package UI;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Executors;

import client.ChatClient;
import client.ClientTokenRefresher;
import com.chatFlow.Chat.*;
import com.google.common.util.concurrent.ListenableFuture;
import model.*;
import client.ClientTokenRefresher.TokenRefreshListener;

/**
 * MainScreen הוא הממשק הראשי לאחר התחברות, המציג את רשימת חדרי הצ'אט של המשתמש,
 * מאפשר יצירת צ'אט חדש, צפייה בהזמנות, וניהול טוקן האימות.
 */
public class MainScreen extends JFrame {

    private final ChatClient client;
    private final User user;

    private DefaultListModel<ChatRoom> chatListModel;
    private JList<ChatRoom> chatList;
    private final String userId;

    private final ClientTokenRefresher tokenRefresher;
    private final JLabel tokenStatusLabel = new JLabel("Token OK");

    /**
     * בונה את המסך הראשי עם פרטי המשתמש והלקוח.
     * מגדיר את טוקן ריפחדש, מאזין לאירועי סגירה וטעינת צ'אטים.
     *
     * @param user האובייקט של המשתמש המחובר
     * @param client הלקוח לתקשורת עם השרת
     */
    public MainScreen(User user, ChatClient client) {
        this.client = client;
        // שליפת פרטי משתמש
        this.user = user;
        if (user == null) {
            JOptionPane.showMessageDialog(this,
                    "User not found or error from server.",
                        "Fatal Error", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
        this.userId = user.getId().toString();

        // מיישמים את ה־TokenRefreshListener
        TokenRefreshListener listener = new TokenRefreshListener() {
            @Override
            public void onBeforeTokenRefresh(int retryCount) {
                // אפשר לעדכן UI בבדיקת רענון
                SwingUtilities.invokeLater(() ->
                        tokenStatusLabel.setText("Refreshing token" + (retryCount>0 ? " (retry " + retryCount + ")" : ""))
                );
            }

            @Override
            public void onTokenRefreshed(String newToken) {
                // עדכון ה־User וה־status UI
                synchronized (user) {
                    client.setToken(newToken);
                }
                SwingUtilities.invokeLater(() ->
                        tokenStatusLabel.setText("Token refreshed at " + java.time.LocalTime.now().withNano(0))
                );
            }

            @Override
            public void onTokenRefreshRetry(int retryCount, long backoffMs) {
                SwingUtilities.invokeLater(() ->
                        tokenStatusLabel.setText("Refresh failed, retry " + retryCount + " in " + (backoffMs/1000) + "s")
                );
            }

            @Override
            public void onTokenRefreshFailed(String reason) {
                // כשל סופי – מוציאים את המשתמש למסך התחברות מחדש
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(
                            MainScreen.this,
                            "Session expired: " + reason + "\nPlease log in again.",
                            "Session Expired",
                            JOptionPane.WARNING_MESSAGE
                    );
                    safeLogout();
                });
            }
        };

        this.tokenRefresher = new ClientTokenRefresher(client, user, listener);
        tokenRefresher.start();

        setTitle("Chat Dashboard");
        setSize(1000, 700);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setLocationRelativeTo(null);

        // הוספת מאזין לאירועים לסגירת החלון
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                safeLogout();  // קריאה ל-safeLogout כאשר החלון נסגר
            }
        });

        initUI();
        loadUserChats(); // טען את הצ'אטים של המשתמש
    }

    /**
     * בונה את רכיבי הממשק הגרפי: כותרת, סטטוס טוקן, כפתורי פעולה ורשימת צ'אטים.
     */
    private void initUI() {
        JPanel mainPanel = new JPanel(new BorderLayout());

        // חלק עליון עם כותרת ומצב טוקן
        JPanel topPanel = new JPanel(new BorderLayout());

        JLabel header = new JLabel("Welcome, " + user.getUsername(), SwingConstants.CENTER);
        header.setFont(new Font("Arial", Font.BOLD, 22));

        // מציג סטטוס של רענון הטוקן (אופציונלי, אפשר להסתיר אם לא רוצים)
        tokenStatusLabel.setFont(new Font("Arial", Font.PLAIN, 12));
        tokenStatusLabel.setHorizontalAlignment(SwingConstants.CENTER);

        // מניחים שה־header בחלק עליון, וה־tokenStatusLabel מתחתיו
        topPanel.add(header, BorderLayout.CENTER);
        topPanel.add(tokenStatusLabel, BorderLayout.SOUTH);

        // כפתורי פעולה בצדדים
        JButton logoutButton = new JButton("🚪 התנתק");
        logoutButton.setFont(new Font("Arial", Font.PLAIN, 14));
        logoutButton.addActionListener(e -> safeLogout());
        topPanel.add(logoutButton, BorderLayout.WEST);

        JButton viewInvitesButton = new JButton("View Chat Invites");
        viewInvitesButton.setFont(new Font("Arial", Font.PLAIN, 14));
        viewInvitesButton.addActionListener(e -> showInvitationsDialog());
        topPanel.add(viewInvitesButton, BorderLayout.EAST);

        mainPanel.add(topPanel, BorderLayout.NORTH);

        // מרכז המסך: רשימת הצ'אטים
        chatListModel = new DefaultListModel<>();
        chatList = new JList<>(chatListModel);
        chatList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        chatList.setCellRenderer(new ChatRoomRenderer(user.getId()));
        JScrollPane scrollPane = new JScrollPane(chatList);
        scrollPane.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        mainPanel.add(scrollPane, BorderLayout.CENTER);

        // חיץ תחתון: כפתור יצירת צ'אט חדש
        JButton createChatButton = new JButton("Create New Chat");
        createChatButton.setFont(new Font("Arial", Font.PLAIN, 14));
        createChatButton.addActionListener(e -> handleCreateChat());
        createChatButton.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        mainPanel.add(createChatButton, BorderLayout.SOUTH);

        // מאזין לדאבל-קליק על צ'אט
        chatList.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    ChatRoom selected = chatList.getSelectedValue();
                    if (selected == null) return;

                    ChatRoom freshRoom = client.getChatRoomById(selected.getChatId().toString(), userId);
                    ChatWindow chatWindow = new ChatWindow(freshRoom, user, client);
                    chatWindow.addWindowListener(new WindowAdapter() {
                        @Override
                        public void windowClosed(WindowEvent e) {
                            loadUserChats();
                            MainScreen.this.setVisible(true);
                        }
                    });
                    MainScreen.this.setVisible(false);
                    chatWindow.setVisible(true);
                }
            }
        });

        add(mainPanel);
    }

    /**
     * טוען בצורה א-סינכרונית את רשימת חדרי הצ'אט מהשרת,
     * ממיין לפי זמן העדכון האחרון ומעדכן את הרשימה.
     */
    private void loadUserChats() {
        SwingUtilities.invokeLater(() -> {
            chatListModel.clear();
            try {
                ArrayList<ChatRoom> chats = (ArrayList<ChatRoom>) client.getUserChatRooms(userId);

                // מיון לפי הזמן שבו נשלחה ההודעה האחרונה בכל צ'אט
                sortChatRooms(chats);

                chats.forEach(chatListModel::addElement);

            } catch (Exception e) {
                e.printStackTrace();
                JOptionPane.showMessageDialog(this,
                        "אירעה שגיאה בעת טעינת הצ'אטים שלך.",
                        "שגיאה",
                        JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    /**
     * מטפל ביצירת צ'אט חדש: איסוף שם, הזמנת משתמשים, בניית בקשה ושליחה לסרבר.
     */
    private void handleCreateChat() {
        JTextField chatNameField = new JTextField();
        JTextField emailField = new JTextField();
        DefaultListModel<String> invitedEmails = new DefaultListModel<>();
        JList<String> invitedList = new JList<>(invitedEmails);

        JButton addButton = new JButton("Add Member");
        addButton.addActionListener(e -> {
            String email = emailField.getText().trim();
            if (!email.isEmpty()) {
                if(email.equalsIgnoreCase(user.getEmail())){
                    JOptionPane.showMessageDialog(this, "You can't invite yourself");
                    return;
                }

                User invitedUser;
                try {
                    invitedUser = client.getUserByEmail(email);
                } catch (Exception ex) {
                    ex.printStackTrace();
                    JOptionPane.showMessageDialog(this, "Failed to fetch user: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                if (invitedUser != null) {
                    if (!invitedEmails.contains(email)) {
                        invitedEmails.addElement(email);
                    } else {
                        JOptionPane.showMessageDialog(null, "User already added");
                    }
                } else {
                    JOptionPane.showMessageDialog(null, "User does not exist");
                }
                emailField.setText("");
            } else {
                JOptionPane.showMessageDialog(this, "Email can't be empty", "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        panel.add(new JLabel("Chat Name:"));
        chatNameField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        panel.add(chatNameField);
        panel.add(Box.createHorizontalStrut(10));

        panel.add(new JLabel("Invite Users by Email:"));
        emailField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

        JPanel emailPanel = new JPanel();
        emailPanel.setLayout(new BoxLayout(emailPanel, BoxLayout.X_AXIS));
        emailPanel.add(emailField);
        emailPanel.add(Box.createRigidArea(new Dimension(10, 0)));
        emailPanel.add(addButton);

        panel.add(emailPanel);
        panel.add(Box.createVerticalStrut(10));

        JScrollPane scrollPane = new JScrollPane(invitedList);
        scrollPane.setPreferredSize(new Dimension(300, 80));
        panel.add(scrollPane);

        int result = JOptionPane.showConfirmDialog(this, panel, "Create New Chat",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result == JOptionPane.OK_OPTION) {
            String chatName = chatNameField.getText().trim();
            if (chatName.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Chat name can't be empty", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            if(invitedEmails.isEmpty()){
                JOptionPane.showMessageDialog(this, "You must invite at least one user.", "Warning", JOptionPane.WARNING_MESSAGE);
                return;
            }

            new Thread(() -> {
                try {
                    // Step 1: Build list of user IDs
                    ArrayList<String> membersId = new ArrayList<>();
                    membersId.add(userId); // Add creator

                    for(int i=0; i<invitedEmails.size(); i++){
                        String email = invitedEmails.getElementAt(i);

                        User invitedUser;
                        try {
                            invitedUser = client.getUserByEmail(email);
                        } catch (Exception ex) {
                            ex.printStackTrace();
                            SwingUtilities.invokeLater(() ->
                                    JOptionPane.showMessageDialog(this, "Failed to fetch user: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE)
                            );
                            continue;
                        }
                        if(invitedUser != null){
                            membersId.add(invitedUser.getId().toString());
                        }
                    }

                    // Step 2: Send request to server using ListenableFuture

                    String token = client.getToken();
                    if(token == null || token.isEmpty()) {
                        System.err.println("Token is null or empty");
                        return;
                    }
                    CreateGroupRequest.Builder builder = CreateGroupRequest.newBuilder()
                            .setGroupName(chatName)
                            .addAdminsId(userId)
                            .setCreatorId(userId)
                            .setToken(token);

                    // הוספת שאר חברי הקבוצה
                    membersId.forEach(builder::addMembersId);

                    // קריאה אסינכרונית ליצירת הצ'אט
                    ListenableFuture<GroupChat> futureResponse = client.createGroupChat(builder.build());

                    // Handling the result asynchronously
                    futureResponse.addListener(() -> {
                        try {
                            // Make sure that the response is not closed already before attempting to access it
                            if (futureResponse.isDone()) {
                            GroupChat response = futureResponse.get();
                            if (response.getSuccess()) {
                                UUID chatId = UUID.fromString(response.getChatId());
                                ChatRoom chatRoom = client.getChatRoomById(chatId.toString(), userId);

                                if (chatRoom == null) {
                                    SwingUtilities.invokeLater(() ->
                                            JOptionPane.showMessageDialog(this, "Failed to load created chat from database", "Error", JOptionPane.ERROR_MESSAGE)
                                    );
                                    return;
                                }

                                SwingUtilities.invokeLater(() -> {
                                    try {
                                        loadUserChats();
                                        JOptionPane.showMessageDialog(this, "Group chat created successfully!", "Success", JOptionPane.INFORMATION_MESSAGE);

                                        if (chatRoom != null) {
                                            new ChatWindow(chatRoom, user, client).setVisible(true);

                                        } else {
                                            JOptionPane.showMessageDialog(this, "Failed to load chat window.", "Error", JOptionPane.ERROR_MESSAGE);
                                        }
                                    } catch (Exception ex) {
                                        ex.printStackTrace();
                                        JOptionPane.showMessageDialog(this, "Error while finalizing chat creation: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                                    }
                                });
                            } else {
                                SwingUtilities.invokeLater(() ->
                                        JOptionPane.showMessageDialog(this, "Failed to create chat: " + response.getMessage(), "Error", JOptionPane.ERROR_MESSAGE)
                                );
                            }
                                }
                        } catch (Exception ex) {
                            ex.printStackTrace();
                            SwingUtilities.invokeLater(() ->
                                    JOptionPane.showMessageDialog(this, "Error creating chat", "Error", JOptionPane.ERROR_MESSAGE)
                            );
                        }
                    }, Executors.newSingleThreadExecutor());
                } catch (Exception e) {
                    e.printStackTrace();
                    SwingUtilities.invokeLater(() ->
                            JOptionPane.showMessageDialog(this, "Database error while creating chat", "Error", JOptionPane.ERROR_MESSAGE)
                    );
                }
            }).start();
        }
    }

    /**
     * מציג דיאלוג עם הזמנות שצבר המשתמש,
     * כולל כפתורי ACCEPT ו-DECLINE לכל הזמנה ממתינה.
     */
    private void showInvitationsDialog() {
        JDialog dialog = new JDialog(this, "Chat Invitations", true);
        dialog.setSize(400, 400);
        dialog.setLocationRelativeTo(this);
        dialog.setLayout(new BorderLayout());

        JPanel contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JScrollPane scrollPane = new JScrollPane(contentPanel);
        dialog.add(scrollPane, BorderLayout.CENTER);

        // פונקציה רגילה במקום Runnable
        refreshInvitations(contentPanel, dialog);

        dialog.setVisible(true);
    }

    /**
     * מרענן את תצוגת ההזמנות בתוך הדיאלוג.
     *
     * @param contentPanel הפאנל המרכזי בדיאלוג
     * @param dialog הדיאלוג להצגת ההזמנות
     */
    private void refreshInvitations(JPanel contentPanel, JDialog dialog) {
        contentPanel.removeAll();

        try {

            ArrayList<Invite> invites = (ArrayList<Invite>) client.getUserInvites(userId);

            if (invites.isEmpty()) {
                JLabel noInvitesLabel = new JLabel("לא קיימות הזמנות");
                noInvitesLabel.setFont(new Font("Arial", Font.ITALIC, 16));
                noInvitesLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
                contentPanel.add(noInvitesLabel);
            } else {
                for (Invite invite : invites) {
                    // רק אם ההזמנה ממתינה (PENDING)
                    if (invite.getStatus() != InviteStatus.PENDING)
                        continue;

                    ChatRoom chatRoom;
                    try {
                        // שליפת פרטי הצ'אט
                        chatRoom = client.getChatRoomById(invite.getChatId().toString(), userId);

                        // בדוק אם הצ'אט קיים ואם המשתמש הוזמן
                        if (chatRoom == null) {
                            throw new RuntimeException("Server returned null room");
                        }
                    } catch (Exception ex) {
                        ex.printStackTrace();
                        SwingUtilities.invokeLater(() ->
                                JOptionPane.showMessageDialog(this, "Failed to load chat: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE)
                        );
                        return;
                    }


                    User inviterUser = client.getUserById(invite.getSenderId().toString());

                    JPanel inviteBox = new JPanel();
                    inviteBox.setLayout(new BoxLayout(inviteBox, BoxLayout.Y_AXIS));
                    inviteBox.setBorder(BorderFactory.createCompoundBorder(
                            BorderFactory.createLineBorder(Color.LIGHT_GRAY, 1),
                            BorderFactory.createEmptyBorder(10, 10, 10, 10)));
                    inviteBox.setBackground(Color.WHITE);

                    JLabel chatNameLabel = new JLabel("\uD83D\uDCAC צ'אט: " + chatRoom.getName());
                    chatNameLabel.setFont(new Font("Arial", Font.BOLD, 16));

                    JLabel inviterLabel = new JLabel("הוזמנת על ידי: " + inviterUser.getUsername());
                    inviterLabel.setFont(new Font("Arial", Font.PLAIN, 14));

                    JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));

                    // כפתור אישור ההזמנה
                    JButton acceptButton = new JButton("ACCEPT");
                    acceptButton.setBackground(new Color(8, 198, 46));
                    acceptButton.setForeground(Color.WHITE);
                    acceptButton.addActionListener(e -> handleInviteResponse(invite, chatRoom, InviteResponseStatus.ACCEPTED, contentPanel, dialog));

                    // כפתור דחיית ההזמנה
                    JButton declineButton = new JButton("DECLINE");
                    declineButton.setBackground(new Color(204, 0, 0));
                    declineButton.setForeground(Color.WHITE);
                    declineButton.addActionListener(e -> handleInviteResponse(invite, chatRoom, InviteResponseStatus.DECLINED, contentPanel, dialog));

                    buttonPanel.add(acceptButton);
                    buttonPanel.add(declineButton);

                    inviteBox.add(chatNameLabel);
                    inviteBox.add(inviterLabel);
                    inviteBox.add(Box.createVerticalStrut(8));
                    inviteBox.add(buttonPanel);

                    contentPanel.add(Box.createVerticalStrut(10));
                    contentPanel.add(inviteBox);
                }
            }

            loadUserChats();
            contentPanel.revalidate();
            contentPanel.repaint();

        } catch (Exception e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(dialog,
                    "אירעה תקלה בעת טעינת ההזמנות.",
                    "שגיאה",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * שולח תגובת משתמש להזמנה (ACCEPTED/DECLINED) ומרענן תצוגות.
     *
     * @param invite האובייקט של ההזמנה
     * @param chatRoom חדר הקשר להזמנה
     * @param status מצב התגובה (ACCEPTED/DECLINED)
     * @param contentPanel פאנל ההזמנות
     * @param dialog הדיאלוג
     */
    private void handleInviteResponse(Invite invite, ChatRoom chatRoom, InviteResponseStatus status, JPanel contentPanel, JDialog dialog) {
        try {
            InviteResponse inviteResponse = InviteResponse.newBuilder()
                    .setInviteId(invite.getInviteId().toString())
                    .setChatId(chatRoom.getChatId().toString())
                    .setInviterUserId(user.getId().toString())
                    .setStatus(status)
                    .build();

            // שליחה לשרת
            client.respondToInvite(inviteResponse).get();

            // עדכון הצ'אט וההזמנות
            SwingUtilities.invokeLater(() -> {
                try {
                    loadUserChats();

                    // רענן את ההזמנות
                    refreshInvitations(contentPanel, dialog);
                    JOptionPane.showMessageDialog(dialog, status == InviteResponseStatus.ACCEPTED ? "ההזמנה אושרה." : "ההזמנה נדחתה.");

                } catch (Exception ex) {
                    ex.printStackTrace();
                    JOptionPane.showMessageDialog(dialog, "אירעה תקלה בעת טעינת ההזמנות.", "שגיאה", JOptionPane.ERROR_MESSAGE);
                }
            });

        } catch (Exception ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(dialog, "שגיאה באישור ההזמנה.", "שגיאה", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * מתבצע בעת סגירת המסך או בקשת התנתקות:
     * שולח קריאה לשרת לניתוק המשתמש, עוצר טוקן ריפראש, וסוגר את כל החלונות.
     */
    private void safeLogout(){
        int confirm = JOptionPane.showConfirmDialog(this, "האם אתה בטוח שברצונך להתנתק ולעזוב את התוכנה?", "אישור יציאה", JOptionPane.YES_NO_OPTION);
        if(confirm == JOptionPane.YES_OPTION){
            try {
                // ניתוק המשתמש
                client.disconnectUser(user);

                // סגירת כל החלונות הפתוחים
                for (Window window : Window.getWindows()){
                    if(window != null)
                        window.dispose();
                }
                tokenRefresher.stop();

                SwingUtilities.invokeLater(() -> {
                    WelcomeWindow window = new WelcomeWindow();
                    window.setLocationRelativeTo(null); // מרכז החלון על המסך
                    window.setVisible(true);
                });

            } catch (Exception e){
                e.printStackTrace();
                JOptionPane.showMessageDialog(
                        this,
                        "שגיאה במהלך ההתנתקות",
                        "שגיאה",
                        JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    /**
     * ממיין רשימת חדרי צ'אט לפי זמן ההודעה האחרונה (או זמן יצירה.
     *
     * @param chats רשימת חדרי צ'אט
     */
    private void sortChatRooms(ArrayList<ChatRoom> chats) {
        chats.sort((c1, c2) -> {
            Instant t1 = c1.getLastMessageTime() != null ? c1.getLastMessageTime() : c1.getCreatedAt();
            Instant t2 = c2.getLastMessageTime() != null ? c2.getLastMessageTime() : c2.getCreatedAt();
            return t2.compareTo(t1);
        });
    }

}

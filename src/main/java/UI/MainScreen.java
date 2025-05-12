package UI;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Executors;

import client.ChatClient;
import com.chatFlow.Chat.*;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import model.*;

public class MainScreen extends JFrame {

    private final ChatClient client;
    private final User user;

    private DefaultListModel<ChatRoom> chatListModel;
    private JList<ChatRoom> chatList;
    private String userId;

    public MainScreen(User user, ChatClient client) {
        this.client = client;

        // שליפת פרטי משתמש
        this.user = user;
        if (user == null) {
            JOptionPane.showMessageDialog(this, "User not found or error from server.", "Fatal Error", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }

        this.userId = user.getId().toString();
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

    private void initUI() {
        JPanel mainPanel = new JPanel(new BorderLayout());

        JLabel header = new JLabel("Welcome, " + user.getUsername());
        header.setFont(new Font("Arial", Font.BOLD, 22));
        header.setHorizontalAlignment(SwingConstants.CENTER);

        chatListModel = new DefaultListModel<>();
        chatList = new JList<>(chatListModel);
        chatList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        chatList.setCellRenderer(new ChatRoomRenderer(user.getId()));

        JScrollPane scrollPane = new JScrollPane(chatList);
        scrollPane.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        mainPanel.add(scrollPane, BorderLayout.CENTER);

        JButton createChatButton = new JButton("Create New Chat");
        createChatButton.setFont(new Font("Arial", Font.PLAIN, 14));
        createChatButton.addActionListener(e -> handleCreateChat());
        createChatButton.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        mainPanel.add(createChatButton, BorderLayout.SOUTH);

        JButton logoutButton = new JButton("🚪 התנתק");
        logoutButton.setFont(new Font("Arial", Font.PLAIN, 14));
        logoutButton.addActionListener(e -> safeLogout());

        JButton viewInvitesButton = new JButton("View Chat Invites");
        viewInvitesButton.setFont(new Font("Arial", Font.PLAIN, 14));
        viewInvitesButton.addActionListener(e -> showInvitationsDialog());

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(header, BorderLayout.CENTER);
        topPanel.add(viewInvitesButton, BorderLayout.EAST);
        topPanel.add(logoutButton, BorderLayout.WEST);

        mainPanel.add(topPanel, BorderLayout.NORTH);

        chatList.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    ChatRoom selected = chatList.getSelectedValue();
                    if(selected == null) return;

                    ChatRoomRequest request = ChatRoomRequest.newBuilder()
                            .setChatId(selected.getChatId().toString())
                            .setToken(user.getAuthToken())
                            .setRequesterId(userId)
                            .build();
                    ChatRoom freshRoom = client.getChatRoomById(request);

                    new ChatWindow(freshRoom, user, client).setVisible(true);

                }
            }
        });

        add(mainPanel);
    }

    private void loadUserChats() {
        SwingUtilities.invokeLater(() -> {
            chatListModel.clear();
            try {
                UserIdRequest request = UserIdRequest.newBuilder()
                        .setUserId(userId)
                        .setToken(user.getAuthToken())
                        .build();

                ArrayList<ChatRoom> chats = client.getUserChatRooms(request);

                // מיון לפי הזמן שבו נשלחה ההודעה האחרונה בכל צ'אט
                chats.sort((c1, c2) -> {
                    Instant t1 = c1.getLastMessageTime() != null ? c1.getLastMessageTime() : c1.getCreatedAt();
                    Instant t2 = c2.getLastMessageTime() != null ? c2.getLastMessageTime() : c2.getCreatedAt();
                    return t2.compareTo(t1); // מהחדש לישן
                });

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

                UserEmailRequest request = UserEmailRequest.newBuilder()
                        .setEmail(email)
                        .setToken(user.getAuthToken())
                        .build();

                User invitedUser;
                try {
                    invitedUser = client.getUserByEmail(request);
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
                        UserEmailRequest request = UserEmailRequest.newBuilder()
                                .setEmail(email)
                                .setToken(user.getAuthToken())
                                .build();

                        User invitedUser = null;
                        try {
                            invitedUser = client.getUserByEmail(request);
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

                    String token = user.getAuthToken();
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
                                ChatRoomRequest request = ChatRoomRequest.newBuilder()
                                        .setChatId(chatId.toString())
                                        .setRequesterId(userId)
                                        .setToken(user.getAuthToken())
                                        .build();
                                ChatRoom chatRoom = client.getChatRoomById(request);

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

    private void refreshInvitations(JPanel contentPanel, JDialog dialog) {
        contentPanel.removeAll();

        try {
            UserIdRequest userRequest = UserIdRequest.newBuilder()
                    .setUserId(userId)
                    .setToken(user.getAuthToken())
                    .build();

            ArrayList<Invite> invites = client.getUserInvites(userRequest);

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

                    // יצירת בקשה לשליפת פרטי הצ'אט
                    ChatRoomRequest request = ChatRoomRequest.newBuilder()
                            .setChatId(invite.getChatId().toString())
                            .setToken(user.getAuthToken())
                            .setRequesterId(user.getId().toString())
                            .build();

                    ChatRoom chatRoom;
                    try {
                        // שליפת פרטי הצ'אט
                        chatRoom = client.getChatRoomById(request);

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

                    // יצירת בקשה לשליפת פרטי המוזמן
                    UserIdRequest inviter = UserIdRequest.newBuilder()
                            .setUserId(invite.getSenderId().toString())
                            .setToken(user.getAuthToken())
                            .build();
                    User inviterUser = client.getUserById(inviter);

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

    private void handleInviteResponse(Invite invite, ChatRoom chatRoom, InviteResponseStatus status, JPanel contentPanel, JDialog dialog) {
        try {
            InviteResponse inviteResponse = InviteResponse.newBuilder()
                    .setInviteId(invite.getInviteId().toString())
                    .setChatId(chatRoom.getChatId().toString())
                    .setInviterUserId(user.getId().toString())
                    .setStatus(status)
                    .setToken(user.getAuthToken())
                    .build();

            // שליחה לשרת
            client.respondToInvite(inviteResponse).get();

            // עדכון הצ'אט וההזמנות
            SwingUtilities.invokeLater(() -> {
                try {
                    loadUserChats();

                    // אם ההזמנה התקבלה, הצטרף לצ'אט
                    ChatRoomRequest chatRequest = ChatRoomRequest.newBuilder()
                            .setChatId(invite.getChatId().toString())
                            .setToken(user.getAuthToken())
                            .setRequesterId(user.getId().toString())
                            .build();

                    ChatRoom joinedChat = client.getChatRoomById(chatRequest);
                    if (joinedChat != null && status.equals(InviteStatus.ACCEPTED)) {
                        new ChatWindow(joinedChat, user, client).setVisible(true);
                    }

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

                System.exit(0);  // סיום התוכנית

            } catch (Exception e){
                e.printStackTrace();
                JOptionPane.showMessageDialog(this, "שגיאה במהלך ההתנתקות", "שגיאה", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

}

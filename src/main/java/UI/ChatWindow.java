package UI;

import client.ChatClient;
import client.ClientTokenRefresher;
import client.SignalingClient;
import com.chatFlow.Chat;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.ByteString;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import model.*;
import com.chatFlow.Chat.*;
import security.AES_GCM;

import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.BadLocationException;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.List;

import static security.AES_ECB.keySchedule;
import io.grpc.Context.CancellableContext;

public class ChatWindow extends JFrame {

    private CancellableContext subscriptionContext;
    private final BiConsumer<String, Boolean> callStatusListener;

    private final ChatRoom chatRoom;
    private final User user;
    private final ChatClient client;
    private final SignalingClient signalingClient;
    private final String chatRoomId;

    private JTextPane chatPane;
    private Style userStyle;
    private Style systemStyle;
    private JTextField inputField;
    private JButton videoCallButton;

    private final Set<UUID> shownMessageIds = new HashSet<>();
    private int currentOffset = 0;
    private static final int BLOCK_SIZE = 16;
    private boolean loading = false;
    private boolean allMessagesLoaded = false;

    private int currentKeyVersion ;
    private final Map<Integer, byte[][]> roundKeysByVersion = new ConcurrentHashMap<>();

    private final ClientTokenRefresher tokenRefresher;

    private static final DateTimeFormatter israelTime =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Jerusalem"));


    public ChatWindow(ChatRoom chatRoom, User user, ChatClient client) {
        this.user = user;
        this.chatRoom = chatRoom;
        this.client = client;

        // 2. מגדירים את ה-TokenRefreshListener (אם רוצים להציג סטטוס UI)
        ClientTokenRefresher.TokenRefreshListener listener = new ClientTokenRefresher.TokenRefreshListener() {
            @Override
            public void onBeforeTokenRefresh(int retryCount) {
                // לדוגמה לא מוסיפים UI בעדכון הזה
            }
            @Override
            public void onTokenRefreshed(String newToken) {
                synchronized (user) {
                    client.setToken(newToken);
                }
            }
            @Override
            public void onTokenRefreshRetry(int retryCount, long backoffMs) { }
            @Override
            public void onTokenRefreshFailed(String reason) {
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(
                            ChatWindow.this,
                            "Session expired: " + reason + "\nPlease log in again.",
                            "Session Expired",
                            JOptionPane.WARNING_MESSAGE
                    );
                    dispose();
                });
            }
        };

        // 3. יצירת ה-refresher עם כל הפרמטרים
        this.tokenRefresher = new ClientTokenRefresher(client, user, listener);
        tokenRefresher.start();

        this.chatRoomId = chatRoom.getChatId().toString();
        this.signalingClient = new SignalingClient(user.getId().toString());

        // קח את גרסת המפתח שהשרת הגדר
        this.currentKeyVersion = chatRoom.getCurrentKeyVersion();

        try {
            ensureKeyVersion(this.currentKeyVersion);
        } catch (Exception e) {
            e.printStackTrace();
        }


        // הגדרת ה-listener לקבלת עדכוני סטטוס שיחה (push)
        this.callStatusListener = (roomId, active) -> {
            // רק עבור ה-room שלנו
            if (!roomId.equals(chatRoomId)) return;
            SwingUtilities.invokeLater(() -> updateVideoCallButton(active));
        };

        signalingClient.addCallStatusListener(callStatusListener);
        signalingClient.connect();

        // Subscribe to live updates
        subscribeToNewMessages();

        setTitle("צ'אט: " + chatRoom.getName());
        setSize(900, 700);
        setLocationRelativeTo(null);

        SwingUtilities.invokeLater(this::initUI);

        // Load history
        SwingUtilities.invokeLater(this::loadChatHistory);

        // Refresh video button
        refreshVideoCallButton();

        this.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                dispose();
            }
        });

    }

    // תחילת ממשק המשתמש (UI)
    private void initUI() {
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        // כותרת
        JLabel titleLabel = new JLabel(chatRoom.getName(), SwingConstants.CENTER);
        titleLabel.setFont(new Font("Arial", Font.BOLD, 24));
        mainPanel.add(titleLabel, BorderLayout.NORTH);

        // אזור הודעות
        chatPane = new JTextPane();
        chatPane.setEditable(false);
        StyledDocument doc = chatPane.getStyledDocument();

        // Normal (user) style:
        userStyle = doc.addStyle("user", null);
        StyleConstants.setFontFamily(userStyle, "Arial");
        StyleConstants.setFontSize(userStyle, 16);
        StyleConstants.setForeground(userStyle, Color.BLACK);

        // System-message style:
        systemStyle = doc.addStyle("system", null);
        StyleConstants.setFontFamily(systemStyle, "Arial");
        StyleConstants.setFontSize(systemStyle, 18);
        StyleConstants.setForeground(systemStyle, new Color(30, 144, 255)); // DodgerBlue
        StyleConstants.setBold(systemStyle, true);
        StyleConstants.setAlignment(systemStyle, StyleConstants.ALIGN_CENTER);

        JScrollPane chatScrollPane = new JScrollPane(chatPane);
        mainPanel.add(chatScrollPane, BorderLayout.CENTER);

        // שורת קלט + כפתורי שליחה ווידאו
        JPanel inputPanel = new JPanel(new BorderLayout(5, 5));
        inputField = new JTextField();

        JButton sendButton = new JButton("שלח");
        sendButton.addActionListener(e -> sendMessage());

        videoCallButton = new JButton("📹 התחלת שיחה");
        videoCallButton.addActionListener(e -> handleVideoCall());

        inputPanel.add(inputField, BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);
        inputPanel.add(videoCallButton, BorderLayout.WEST);
        mainPanel.add(inputPanel, BorderLayout.SOUTH);

        // כפתור ניהול חברים
        JPanel topRightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton manageMembersButton = new JButton("⚙ הגדרות צ'אט");
        manageMembersButton.addActionListener(e -> openManageMembersDialog());
        topRightPanel.add(manageMembersButton);
        mainPanel.add(topRightPanel, BorderLayout.EAST);

        setContentPane(mainPanel);
        inputField.addActionListener(e -> sendMessage());
    }

    // טוען את ההיסטוריה של הצ'אט
    private void loadChatHistory() {
        new SwingWorker<List<Message>, Message>(){
            @Override
            protected List<Message> doInBackground() {
                if(loading || allMessagesLoaded)
                    return List.of();
                loading = true;
                try {
                    ChatHistoryRequest request = ChatHistoryRequest.newBuilder()
                            .setChatId(chatRoomId)
                            .setOffset(currentOffset)
                            .setLimit(100)
                            .setToken(client.getToken())
                            .setRequesterId(user.getId().toString())
                            .build();

                    ChatHistoryResponse response = client.getChatHistory(request);
                    return response.getMessagesList();
                } catch (Exception e) {
                    e.printStackTrace();
                    return Collections.emptyList(); // במקום לקרוס
                }
            }

            @Override
            protected void done() {
                try {
                    List<Message> messageList = get();
                    if(messageList.isEmpty()){
                        allMessagesLoaded = true;
                    } else {
                        for (Message message : messageList) {
                            int version = message.getKeyVersion();
                            loadRoundKeys(version);
                            processAndAppend(message);
                        }
                        currentOffset += messageList.size();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    JOptionPane.showMessageDialog(ChatWindow.this,
                            "שגיאה בטעינת היסטוריית הצ'אט: " + e.getMessage(),
                            "Error", JOptionPane.ERROR_MESSAGE);
                } finally {
                    loading = false;
                }
            }
        }.execute();
    }

    private void subscribeToNewMessages() {

        unsubscribe();

        subscriptionContext = Context.current().withCancellation();
        subscriptionContext.run(() ->
                client.subscribeMessages(
                        ChatSubscribeRequest.newBuilder()
                                .setChatId(chatRoomId)
                                .setToken(client.getToken())
                                .build(),
                        new StreamObserver<>() {
                            @Override
                            public void onNext(Message msg) {
                                SwingUtilities.invokeLater(() -> processAndAppend(msg));
                            }

                            @Override
                            public void onError(Throwable throwable) {
                                Status status = Status.fromThrowable(throwable);
                                if (status.getCode() == Status.Code.CANCELLED) {
                                    // retry subscribe after delay
                                    Executors.newSingleThreadScheduledExecutor()
                                            .schedule(ChatWindow.this::subscribeToNewMessages, 2, TimeUnit.SECONDS);
                                } else {
                                    SwingUtilities.invokeLater(() ->
                                            JOptionPane.showMessageDialog(
                                                    ChatWindow.this,
                                                    "Subscription failed: " + status,
                                                    "Error",
                                                    JOptionPane.ERROR_MESSAGE)
                                    );
                                }
                            }

                            @Override
                            public void onCompleted() {
                                System.out.println("Subscription completed");
                            }
                        }
                )
        );

    }

    private void unsubscribe() {
        if (subscriptionContext != null) {
            subscriptionContext.cancel(null);
            subscriptionContext = null;
        }
    }

    private void processAndAppend(Message message) {
        try {
            UUID messageId = UUID.fromString(message.getMessageId());
            int keyVersion = message.getKeyVersion();

            // 0) אם הגרסה שבה הוצפנה ההודעה שונה ממה שיש לנו כרגע
            if (keyVersion != currentKeyVersion) {

                // עדכון הגרסה
                currentKeyVersion = keyVersion;

                // נקה כל ה-roundKeys הישנים
                roundKeysByVersion.clear();

                // טען את הגרסה החדשה
                try {
                    ensureKeyVersion(currentKeyVersion);
                } catch (Exception e) {
                    // טיפול בשגיאה בטעינת המפתח
                    SwingUtilities.invokeLater(() ->
                            JOptionPane.showMessageDialog(
                                    ChatWindow.this,
                                    "שגיאה ברענון מפתח: " + e.getMessage(),
                                    "Error",
                                    JOptionPane.ERROR_MESSAGE
                            )
                    );
                }
            }

            // 1) דילוג על כפילויות
            if (!shownMessageIds.add(messageId)) {
                return;
            }

            // 2) פענוח
            byte[] decrypted;
            try {
                // First decryption attempt
                decrypted = decryptMessage(
                        messageId,
                        message.getCipherText().toByteArray(),
                        message.getTimestamp(),
                        keyVersion
                );
            } catch (SecurityException authEx) {
                // On auth failure, reload and retry once
                roundKeysByVersion.remove(keyVersion);
                ensureKeyVersion(keyVersion);
                decrypted = decryptMessage(
                        messageId,
                        message.getCipherText().toByteArray(),
                        message.getTimestamp(),
                        keyVersion
                );
            }

            // Build display text
            String content = new String(decrypted, StandardCharsets.UTF_8);
            String senderName = message.getIsSystem()
                    ? ""
                    : message.getSenderId().equals(user.getId().toString())
                    ? "אני"
                    : client.getUsernameById(message.getSenderId());
            String time = israelTime.format(Instant.ofEpochMilli(message.getTimestamp()));
            String formatted = message.getIsSystem()
                    ? content
                    : String.format("[%s] %s: %s\n", time, senderName, content);

            appendMessage(formatted, message.getIsSystem());

            // If system message indicating membership change, refresh keys & history
            if (message.getIsSystem() &&
                    (content.contains("ההזמנה אושרה") ||
                            content.contains("הזמנת") ||
                            content.contains("עזבת") ||
                            content.contains("הוסר") ||
                            content.contains("updated to"))) {
                new Thread(() -> {
                    try {

                        unsubscribe();
                        subscribeToNewMessages();

                        // Clear all loaded round-keys
                        roundKeysByVersion.clear();
                        // Reload current chat key version
                        currentKeyVersion = chatRoom.getCurrentKeyVersion();
                        ensureKeyVersion(currentKeyVersion);

                        SwingUtilities.invokeLater(() -> {
                            shownMessageIds.clear();
                            chatPane.setText("");
                            currentOffset = 0;
                            allMessagesLoaded = false;
                            loadChatHistory();
                        });
                    } catch (Exception e) {
                        SwingUtilities.invokeLater(() ->
                                JOptionPane.showMessageDialog(
                                        ChatWindow.this,
                                        "שגיאה ברענון המפתח: " + e.getMessage(),
                                        "Error",
                                        JOptionPane.ERROR_MESSAGE
                                )
                        );
                    }
                }).start();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void appendMessage(String text, boolean isSystem){
        StyledDocument doc = chatPane.getStyledDocument();
        Style style = isSystem ? systemStyle : userStyle;

        try {
            int start = doc.getLength();
            doc.insertString(start, text + "\n", style);
            doc.setParagraphAttributes(start, text.length() + 1, style, true);
            chatPane.setCaretPosition(doc.getLength());
        } catch (BadLocationException ignored){
            ignored.printStackTrace();
        }
    }

    // שליחת הודעה
    private void sendMessage() {
        String text = inputField.getText().trim();
        if (text.isEmpty()) return;
        inputField.setText("");

        new Thread(() -> {
            try {
                UUID msgId = UUID.randomUUID();
                long timeStamp = Instant.now().toEpochMilli();
                byte[] encryptedMessage = encryptMessage(
                        msgId,
                        text.getBytes(StandardCharsets.UTF_8),
                        timeStamp);

                Message message = Message.newBuilder()
                        .setMessageId(msgId.toString())
                        .setSenderId(user.getId().toString())
                        .setChatId(chatRoomId)
                        .setCipherText(ByteString.copyFrom(encryptedMessage))
                        .setTimestamp(timeStamp)
                        .setToken(client.getToken())
                        .setIsSystem(false)
                        .setStatus(Chat.MessageStatus.SENT)
                        .setKeyVersion(currentKeyVersion)
                        .build();

                shownMessageIds.add(msgId);
                SwingUtilities.invokeLater(() -> {
                    String formatted = String.format("[%s] %s: %s\n",
                            israelTime.format(Instant.ofEpochMilli(timeStamp)), "אני", text);
                    appendMessage(formatted, false);
                });

                // שליחה אסינכרונית של ההודעה
                ListenableFuture<ACK> future = client.sendMessage(message);

                // טיפול בתשובה עם callback
                Futures.addCallback(future, new FutureCallback<>() {
                    @Override
                    public void onSuccess(ACK ack) {
                        if (!ack.getSuccess()) {
                            // הודעה אם יש כישלון בהחזרת תשובה
                            System.out.println("השרת החזיר כישלון: " + ack.getMessage());
                        }
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        // טיפול בשגיאה אם משהו משתבש
                        t.printStackTrace();
                        JOptionPane.showMessageDialog(null, "שגיאה בשליחת ההודעה", "Error", JOptionPane.ERROR_MESSAGE);
                    }
                }, MoreExecutors.directExecutor());

            } catch (Exception e){
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(ChatWindow.this,
                                "Error: " + e.getMessage(),
                                "Error", JOptionPane.ERROR_MESSAGE));
            }
        }).start();
    }

    private void handleVideoCall() {
        new Thread(() -> {
            try {
                if(signalingClient.isConnected()) {
                    boolean isActive = signalingClient.checkCallStatus(chatRoomId);
                    if (isActive) {
                        signalingClient.joinCall(chatRoomId);
                        sendSystemAnnouncement("הצטרפת לשיחת וידאו");
                    } else {
                        signalingClient.startCall(chatRoomId);
                        sendSystemAnnouncement("התחלת שיחת וידאו");
                    }

                    SwingUtilities.invokeLater(() -> {
                        VideoCallWindow videoWindow = new VideoCallWindow(signalingClient, chatRoomId, user, client);
                        signalingClient.setVideoCallWindow(videoWindow);
                        videoWindow.setVisible(true);

                        videoWindow.addWindowListener(new WindowAdapter() {
                            @Override
                            public void windowClosed(WindowEvent e) {
                                refreshVideoCallButton();
                                sendSystemAnnouncement("סיימת את שיחת הווידאו");
                            }
                        });
                    });
                }

            } catch (Exception ignored) {
                ignored.printStackTrace();
            }
        }).start();
    }

    private void sendSystemAnnouncement(String text) {
        long timeStamp = Instant.now().toEpochMilli();
        UUID msgId = UUID.randomUUID();

        // 1. הצפנה
        byte[] encrypted = encryptMessage(msgId, text.getBytes(StandardCharsets.UTF_8), timeStamp);

        // 2. בניית ההודעה
        Message sys = Message.newBuilder()
                .setMessageId(msgId.toString())
                .setSenderId(user.getId().toString())
                .setChatId(chatRoomId)
                .setCipherText(ByteString.copyFrom(encrypted))
                .setTimestamp(timeStamp)
                .setToken(client.getToken())
                .setIsSystem(true)
                .setStatus(Chat.MessageStatus.SENT)
                .setKeyVersion(currentKeyVersion)
                .build();

        // 3. שליחה עם טיפול בתשובה
        ListenableFuture<ACK> future = client.sendMessage(sys);
        Futures.addCallback(future, new FutureCallback<>() {
            @Override public void onSuccess(ACK ack) {
                if(ack.getSuccess()){
                    shownMessageIds.add(msgId);
                    System.out.println("System message delivered: " + msgId);
                } else {
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                            ChatWindow.this,
                            "System message was rejected: " + ack.getMessage(),
                            "Error",
                            JOptionPane.ERROR_MESSAGE
                    ));
                }
            }
            @Override public void onFailure(Throwable t) {
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(
                                ChatWindow.this,
                                "שגיאה בשליחת הודעת מערכת: " + t.getMessage(),
                                "Error",
                                JOptionPane.ERROR_MESSAGE));
            }
        }, MoreExecutors.directExecutor());

        // 4. עדכון UI
        SwingUtilities.invokeLater(() -> appendMessage(text, true));
    }

    // מסנכרן את מצב הכפתור לפי סטטוס השיחה
    private void updateVideoCallButton(boolean active) {
        videoCallButton.setText(active ? "📹 הצטרף לשיחה קיימת" : "📹 התחלת שיחה");
        videoCallButton.setEnabled(true);
    }

    // מפעיל בדיקה א־סינכרונית (poll) של סטטוס השיחה
    private void refreshVideoCallButton() {
        new Thread(() -> {
            try {
                if(signalingClient.isConnected()) {
                    boolean isActive = signalingClient.checkCallStatus(chatRoomId);
                    SwingUtilities.invokeLater(() -> updateVideoCallButton(isActive));
                }
            } catch (Exception ignored) {
                ignored.printStackTrace();
            }
        }).start();
    }

    // מסך לניהול חברים
    private void openManageMembersDialog() {
        JDialog dialog = new JDialog(this, "ניהול משתתפים", true);
        dialog.setSize(400, 600);
        dialog.setLocationRelativeTo(this);

        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBorder(new EmptyBorder(5,5,5,5));
        dialog.add(panel);
        updateManageMembersPanel(panel, dialog);
        dialog.setVisible(true);
    }

    private void updateManageMembersPanel(JPanel panel, JDialog dialog) {
        new Thread(() -> {
            try {
                // 1) Fetch fresh room + members
                ChatRoom updatedRoom = client.getChatRoomById(chatRoomId, user.getId().toString());
                chatRoom.getMembers().clear();
                chatRoom.getMembers().putAll(updatedRoom.getMembers());
                boolean isAdmin = chatRoom.isAdmin(user.getId());

                SwingUtilities.invokeLater(() -> {
                    panel.removeAll();

                    // ----- top invite button -----
                    JButton inviteNew = new JButton("+");
                    inviteNew.setToolTipText("Invite a new user");
                    inviteNew.addActionListener(e -> {
                        String email = JOptionPane.showInputDialog(dialog, "Enter user email");
                        if (email != null && !email.isEmpty()) {
                            inviteUserByEmail(email);
                            updateManageMembersPanel(panel, dialog);
                        }
                    });
                    JPanel topBar = new JPanel(new FlowLayout(FlowLayout.RIGHT));
                    topBar.add(inviteNew);
                    panel.add(topBar, BorderLayout.NORTH);

                    // ----- members grid -----
                    JPanel list = new JPanel(new GridBagLayout());
                    GridBagConstraints gbc = new GridBagConstraints();
                    gbc.insets = new Insets(5,5,5,5);
                    gbc.gridy = 0;

                    for (ChatMember member : chatRoom.getMembers().values()) {
                        String name = client.getUsernameById(member.getUserId().toString()) +
                                (member.getUserId().equals(user.getId()) ? " (you)" : "");

                        // column 0: username
                        gbc.gridx = 0;
                        gbc.anchor = GridBagConstraints.WEST;
                        list.add(new JLabel(name), gbc);

                        // column 1: role
                        gbc.gridx = 1;
                        list.add(new JLabel(member.getRole().name()), gbc);

                        // column 2: actions (only for admins, and not self)
                        gbc.gridx = 2;
                        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
                        if (isAdmin && !member.getUserId().equals(user.getId())) {
                            // Promote/Demote
                            String btnLabel = member.getRole() == ChatRole.ADMIN ? "Demote" : "Promote";
                            JButton roleBtn = new JButton(btnLabel);
                            roleBtn.addActionListener(ev -> {
                                ChatRoles newRole = member.getRole() == ChatRole.ADMIN
                                        ? ChatRoles.MEMBER
                                        : ChatRoles.ADMIN;
                                changeUserRole(chatRoom.getChatId(), member.getUserId(), newRole,
                                        client.getUsernameById(member.getUserId().toString()));
                                updateManageMembersPanel(panel, dialog);
                            });
                            actions.add(roleBtn);

                            // Kick
                            JButton kickBtn = new JButton("Kick");
                            kickBtn.setForeground(Color.RED);
                            kickBtn.addActionListener(ev -> {
                                int res = JOptionPane.showConfirmDialog(dialog,
                                        "Remove this user from chat?",
                                        "Confirm",
                                        JOptionPane.YES_NO_OPTION);
                                if (res == JOptionPane.YES_OPTION) {
                                    removeUserFromGroup(
                                            chatRoom.getChatId(),
                                            member.getUserId(),
                                            client.getUsernameById(member.getUserId().toString()));
                                    updateManageMembersPanel(panel, dialog);
                                }
                            });
                            actions.add(kickBtn);
                        }
                        list.add(actions, gbc);
                        gbc.gridy++;
                    }

                    panel.add(new JScrollPane(list), BorderLayout.CENTER);

                    // ----- bottom leave button -----
                    JButton leave = new JButton("🚪 עזוב את הצ'אט");
                    leave.addActionListener(e -> {
                        dialog.dispose();
                        leaveChat();
                    });
                    JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.CENTER));
                    bottomBar.add(leave);
                    panel.add(bottomBar, BorderLayout.SOUTH);
                    panel.revalidate();
                    panel.repaint();
                });
            } catch (Exception e) {
                e.printStackTrace();
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(dialog,
                                "Error loading members: " + e.getMessage(),
                                "Error", JOptionPane.ERROR_MESSAGE)
                );
            }
        }).start();
    }

    private void inviteUserByEmail(String email) {
        if (email == null || email.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Email can't be empty", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        Executors.newSingleThreadExecutor().submit(() -> {
            try {
                // יצירת בקשה לשליחת הזמנה למייל של המשתמש
                User invitedUser = client.getUserByEmail(email);

                InviteRequest inviteRequest = InviteRequest.newBuilder()
                        .setInviteId(UUID.randomUUID().toString())  // מזהה ייחודי להזמנה
                        .setChatId(chatRoomId)  // מזהה חדר הצ'אט
                        .setAdminId(user.getId().toString())  // מזהה המנהל המשלח את ההזמנה
                        .setInvitedUserId(invitedUser.getId().toString())  // פה אנחנו שמים את המייל, אבל במקרה שלך צריך ID של המשתמש
                        .setTimestamp(Instant.now().toEpochMilli())  // זמן שליחת ההזמנה
                        .setToken(client.getToken())  // טוקן האימות של המשתמש
                        .build();

                // שליחת הבקשה בצורה אסינכרונית
                ListenableFuture<ACK> future = client.inviteUser(inviteRequest);

                // טיפול בתוצאה של שליחת ההזמנה
                Futures.addCallback(future, new FutureCallback<>() {
                    @Override
                    public void onSuccess(ACK ack) {
                        String msg = ack.getSuccess() ? "Invitation sent: " + email : "Failed: " + ack.getMessage();
                        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                                ChatWindow.this, msg, "Info",
                                ack.getSuccess() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.ERROR_MESSAGE));
                        sendSystemAnnouncement("הזמנת " + email + (ack.getSuccess() ? " נשלחה" : " נכשלה"));
                    }

                    @Override
                    public void onFailure(Throwable throwable) {
                        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                                ChatWindow.this,
                                "Error inviting: " + throwable.getMessage(),
                                "Error", JOptionPane.ERROR_MESSAGE));
                    }
                }, MoreExecutors.directExecutor());


            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                        ChatWindow.this,
                        "Error fetching user: " + e.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE));
            }
        });

    }

    private void changeUserRole(UUID chatRoomId, UUID targetUserId, ChatRoles newRole, String username) {
        Executors.newSingleThreadExecutor().submit(() -> {
            // שליחת בקשה לשינוי תפקיד
            ChangeUserRoleRequest changeUserRoleRequest = ChangeUserRoleRequest.newBuilder()
                    .setChatId(chatRoomId.toString())
                    .setRequesterId(user.getId().toString()) // המשתמש שמבצע את הפעולה (המנהל)
                    .setTargetId(targetUserId.toString())   // המשתמש שמבצעים עליו את השינוי
                    .setNewRole(newRole)                     // התפקיד החדש
                    .setToken(client.getToken())          // טוקן האימות של המנהל
                    .build();

            // שליחה לשרת לשינוי התפקיד
            ListenableFuture<ACK> future = client.changeUserRole(changeUserRoleRequest);
            Futures.addCallback(future, new FutureCallback<>() {
                @Override
                public void onSuccess(ACK ack) {
                    // שליחת הודעת מערכת על השינוי בצ'אט
                    String txt = username + (ack.getSuccess() ? " updated to " + newRole : " role change failed");
                    sendSystemAnnouncement(txt);
                }

                @Override
                public void onFailure(Throwable t) {
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                            ChatWindow.this,
                            "Error changing role: " + t.getMessage(),
                            "Error", JOptionPane.ERROR_MESSAGE));
                }
            }, MoreExecutors.directExecutor());

        });
    }

    private void removeUserFromGroup(UUID chatRoomId, UUID targetUserId, String username) {
        // שליחת בקשה להסרת המשתמש מהקבוצה
        RemoveUserRequest removeUserRequest = RemoveUserRequest.newBuilder()
                .setChatId(chatRoomId.toString())
                .setAdminId(user.getId().toString())
                .setTargetUserId(targetUserId.toString())
                .setToken(client.getToken())
                .build();

        // שליחה לשרת בצורה אסינכרונית
        ListenableFuture<ACK> future = client.removeUserFromGroup(removeUserRequest);
        Futures.addCallback(future, new FutureCallback<>() {
            @Override
            public void onSuccess(ACK ack) {
                if(ack.getSuccess()) {
                    // 1. רענון אובייקט ה־chatRoom מהשרת
                    ChatRoom updated = client.getChatRoomById(chatRoomId.toString(), user.getId().toString());

                    if (updated != null) {
                        chatRoom.getMembers().clear();
                        chatRoom.getMembers().putAll(updated.getMembers());
                        currentKeyVersion = updated.getCurrentKeyVersion();
                    }

                    // 2. נקה round-keys ומטעין את הגרסה המעודכנת
                    roundKeysByVersion.clear();
                    try {
                        loadRoundKeys(currentKeyVersion);
                    } catch (Exception e) {
                        SwingUtilities.invokeLater(() ->
                                JOptionPane.showMessageDialog(
                                        ChatWindow.this,
                                        "שגיאה ברענון המפתח: " + e.getMessage(),
                                        "Error",
                                        JOptionPane.ERROR_MESSAGE
                                )
                        );
                    }

                    // 3. שליחת הודעת מערכת מוצפנת
                    sendSystemAnnouncement("המשתמש " + username + " הוסר מהקבוצה");

                    // 4. רענון ההיסטוריה
                    SwingUtilities.invokeLater(() -> {
                        shownMessageIds.clear();
                        chatPane.setText("");
                        currentOffset = 0;
                        allMessagesLoaded = false;
                        loadChatHistory();
                    });
                } else {
                    SwingUtilities.invokeLater(() ->
                            JOptionPane.showMessageDialog(
                                    ChatWindow.this,
                                    "הסרת המשתמש נכשלה: " + ack.getMessage(),
                                    "Error",
                                    JOptionPane.ERROR_MESSAGE
                            )
                    );
                }
            }

            @Override
            public void onFailure(Throwable t) {
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(
                                ChatWindow.this,
                                "שגיאה בהסרת המשתמש: " + t.getMessage(),
                                "Error",
                                JOptionPane.ERROR_MESSAGE
                        )
                );
            }
        }, MoreExecutors.directExecutor());
    }

    private void shutdownResources(){
        if(subscriptionContext != null) {
            subscriptionContext.cancel(null);
            subscriptionContext = null;
        }
        signalingClient.removeCallStatusListener(callStatusListener);
        signalingClient.shutdown();
    }

    @Override
    public void dispose(){
        shutdownResources();
        tokenRefresher.stop();
        super.dispose();
    }

    // עזיבה של הקבוצה
    private void leaveChat() {
        int confirm = JOptionPane.showConfirmDialog(
                this,
                "האם אתה בטוח שברצונך לעזוב את הקבוצה?",
                "אישור עזיבה",
                JOptionPane.YES_NO_OPTION
        );
        if (confirm == JOptionPane.YES_OPTION) {
            Executors.newSingleThreadExecutor().submit(() -> {
                LeaveGroupRequest request = LeaveGroupRequest.newBuilder()
                        .setToken(client.getToken())
                        .setUserId(user.getId().toString())
                        .setChatId(chatRoomId)
                        .build();

                // שליחה לשרת בצורה אסינכרונית
                ListenableFuture<ACK> future = client.leaveGroup(request);
                Futures.addCallback(future, new FutureCallback<>() {
                    @Override
                    public void onSuccess(ACK ack) {
                        SwingUtilities.invokeLater(() -> {
                            JOptionPane.showMessageDialog(
                                    ChatWindow.this,
                                    ack.getSuccess() ? "עזבת בהצלחה" : "עזיבה נכשלה" + ack.getMessage(),
                                    "Info",
                                    ack.getSuccess()
                                            ? JOptionPane.INFORMATION_MESSAGE
                                            : JOptionPane.ERROR_MESSAGE);
                            if (ack.getSuccess())
                                shutdownResources();
                        });
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                                ChatWindow.this,
                                "Error leaving chat: " + t.getMessage(),
                                "Error", JOptionPane.ERROR_MESSAGE));
                    }
                }, MoreExecutors.directExecutor());

            });
        }
    }

    // --- הצפנה ופענוח של הודעות ---

    /** טוען ממסד המפתח העדכני עבור גרסה מסוימת וחושב את ה-roundKeys */
    private void loadRoundKeys(int version) {
        if (roundKeysByVersion.containsKey(version))
            return;

        byte[] rawKey = client.getSymmetricKey(user.getId().toString(), chatRoomId, version);
        if (rawKey == null || rawKey.length != BLOCK_SIZE)
            throw new IllegalStateException("Invalid symmetric key for version " + version);

        byte[][] roundKeys = new byte[11][BLOCK_SIZE];
        roundKeys[0] = rawKey;
        keySchedule(roundKeys);
        roundKeysByVersion.put(version, roundKeys);

        Arrays.fill(rawKey, (byte)0);
    }

    private byte[] encryptMessage(UUID msgId, byte[] data, long timeStamp) {
        byte[][] round_keys = roundKeysByVersion.get(currentKeyVersion);
        if (round_keys == null)
            throw new IllegalStateException("No roundKeys for version " + currentKeyVersion);
        byte[] aad = generateAAD(msgId, timeStamp);
        return AES_GCM.encrypt(data, aad, round_keys);
    }

    private byte[] decryptMessage(UUID msgId, byte[] encryptedData, long timeStamp, int keyVersion) {
        loadRoundKeys(keyVersion);  // ווידוא שה-roundKeys קיימים
        byte[][] round_keys = roundKeysByVersion.get(keyVersion);
        byte[] aad = generateAAD(msgId, timeStamp);
        return AES_GCM.decrypt(encryptedData, aad, round_keys);
    }

    private byte[] generateAAD(UUID msgId, long timeStamp) {
        String AAD = chatRoomId + ":" + timeStamp + ":" + msgId;
        return AAD.getBytes(StandardCharsets.UTF_8);
    }

    private void ensureKeyVersion(int version){

        if (roundKeysByVersion.containsKey(version)) return;

        // 1) שואלים מהשרת את המפתח המוצפן עבור הגרסה הזאת
        byte[] rawKey = client.getSymmetricKey(user.getId().toString(), chatRoomId, version);
        if (rawKey == null)
            throw new IllegalStateException("No key from server");

        // 3) בניית ה־round-keys
        byte[][] roundKeys = new byte[11][BLOCK_SIZE];
        roundKeys[0] = rawKey;
        keySchedule(roundKeys);

        // 4) שמירתם במפה וניקוי הגולמי
        roundKeysByVersion.put(version, roundKeys);
        currentKeyVersion = version;

        // clean raw/private copy
        Arrays.fill(rawKey, (byte)0);
    }

}

package UI;

import client.ChatClient;
import client.SignalingClient;
import com.chatFlow.Chat;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import model.*;
import com.chatFlow.Chat.*;
import security.AES_GCM;

import java.util.function.BiConsumer;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
    private SignalingClient signalingClient;
    private final String chatRoomId;

    private JTextArea chatArea;
    private JTextField inputField;
    private JButton videoCallButton;

    private final Set<UUID> shownMessageIds = new HashSet<>();
    private int currentOffset = 0;
    private static final int BLOCK_SIZE = 16;
    private boolean loading = false;
    private boolean allMessagesLoaded = false;

    private byte[] symmetricKey;
    private boolean keyLoaded = false;
    private int loadAttempts = 0;
    private static final ZonedDateTime israelTime = Instant.now().atZone(ZoneId.of("Asia/Jerusalem"));


    public ChatWindow(ChatRoom chatRoom, User user, ChatClient client) {
        this.user = user;
        this.chatRoom = chatRoom;
        this.client = client;
        this.chatRoomId = chatRoom.getChatId().toString();

        this.signalingClient = new SignalingClient(user.getId().toString());

        // הגדרת ה-listener לקבלת עדכוני סטטוס שיחה (push)
        this.callStatusListener = (roomId, active) -> {
            // רק עבור ה-room שלנו
            if (!roomId.equals(chatRoomId)) return;
            SwingUtilities.invokeLater(() -> updateVideoCallButton(active));
        };

        signalingClient.addCallStatusListener(callStatusListener);

        signalingClient.connect();

        refreshVideoCallButton();

        // הגדרת חלון הוידאו
        setTitle("צ'אט: " + chatRoom.getName());
        setSize(900, 700);
        setLocationRelativeTo(null);

        initUI();
        loadChatHistory();
        subscribeToNewMessages();
        refreshVideoCallButton();
        loadSymmetricKey();

        this.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                signalingClient.removeCallStatusListener(callStatusListener);
                signalingClient.shutdown();
                cancelSubscription();
                dispose();
            }
        });

    }

    // תחילת ממשק המשתמש (UI)
    private void initUI() {
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        JLabel titleLabel = new JLabel(chatRoom.getName(), SwingConstants.CENTER);
        titleLabel.setFont(new Font("Arial", Font.BOLD, 24));
        mainPanel.add(titleLabel, BorderLayout.NORTH);

        chatArea = new JTextArea();
        chatArea.setEditable(false);
        chatArea.setFont(new Font("Arial", Font.PLAIN, 16));
        chatArea.setLineWrap(true);

        JScrollPane chatScrollPane = new JScrollPane(chatArea);
        mainPanel.add(chatScrollPane, BorderLayout.CENTER);
        chatScrollPane.getVerticalScrollBar().addAdjustmentListener(e -> {
            if(!loading && !allMessagesLoaded && e.getValue() == 0){
                loadChatHistory();
            }
        });
        JPanel inputPanel = new JPanel(new BorderLayout(5, 5));
        inputField = new JTextField();
        JButton sendButton = new JButton("שלח");
        videoCallButton = new JButton("📹 התחלת שיחה");

        sendButton.addActionListener(e -> sendMessage());
        videoCallButton.addActionListener(e -> handleVideoCall());

        inputPanel.add(inputField, BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);
        inputPanel.add(videoCallButton, BorderLayout.WEST);

        mainPanel.add(inputPanel, BorderLayout.SOUTH);

        JPanel topRightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton manageMembersButton = new JButton("⚙ הגדרות צ'אט");
        manageMembersButton.addActionListener(e -> openManageMembersDialog());
        topRightPanel.add(manageMembersButton);

        mainPanel.add(topRightPanel, BorderLayout.EAST);

        setContentPane(mainPanel);

        inputField.addActionListener(e -> sendMessage());
    }

    private void loadSymmetricKey(){
        try {
            if(!keyLoaded){
                MemberRequest request = MemberRequest.newBuilder()
                        .setChatId(chatRoomId)
                        .setUserId(user.getId().toString())
                        .setToken(user.getAuthToken())
                        .build();

                this.symmetricKey = client.getSymmetricKey(request);

                if (this.symmetricKey == null || this.symmetricKey.length == 0) {
                    loadAttempts++;
                    JOptionPane.showMessageDialog(this, "שגיאה בטעינת המפתח הסימטרי", "שגיאה", JOptionPane.ERROR_MESSAGE);

                    if (loadAttempts >= 3) {
                        JOptionPane.showMessageDialog(this, "נכשלו שלוש ניסיונות לטעינת המפתח. הצ'אט ייסגר.", "שגיאה", JOptionPane.ERROR_MESSAGE);
                        this.dispose();
                    }

                    return;
                }
                keyLoaded = true;
                loadAttempts = 0;
            }
        } catch (Exception e){
            e.printStackTrace();
            JOptionPane.showMessageDialog(this, "שגיאה בטעינת המפתח הסימטרי", "שגיאה", JOptionPane.ERROR_MESSAGE);
        }
    }

    // טוען את ההיסטוריה של הצ'אט
    private void loadChatHistory() {
        if(loading || allMessagesLoaded) return;
        loading = true;

        try {
            if(!keyLoaded){
                loadSymmetricKey();
            }

            int PAGE_SIZE = 100;
            ChatHistoryRequest request = ChatHistoryRequest.newBuilder()
                    .setChatId(chatRoomId)
                    .setOffset(currentOffset)
                    .setLimit(PAGE_SIZE)
                    .setToken(user.getAuthToken())
                    .setRequesterId(user.getId().toString())
                    .build();

            ChatHistoryResponse response = client.getChatHistory(request);
            List<Message> messages = response.getMessagesList();

            if(messages.isEmpty()){
                allMessagesLoaded = true;
                return;
            }

            StringBuilder builder = new StringBuilder();
            for (Message message : messages) {
                UUID messageId = UUID.fromString(message.getMessageId());

                // דילוג על הודעה כפולה
                if (shownMessageIds.contains(messageId)) continue;
                shownMessageIds.add(messageId);

                byte[] decryptedMessage = decryptMessage(messageId, message.getCipherText().toByteArray(),message.getTimestamp());
                String content = new String(decryptedMessage, StandardCharsets.UTF_8);

                String senderName = message.getSenderId().equals(user.getId().toString()) ? "אני" : client.getUsernameById(message.getSenderId());
                String formattedMessage = String.format("[%s] %s: %s\n",
                        Instant.ofEpochMilli(message.getTimestamp()).toString().substring(11, 16),
                        senderName,
                        content);

                builder.append(formattedMessage);
            }

            // שמירה על אחוז הגלילה לפני הכנסת ההודעות
            JScrollBar scrollBar = ((JScrollPane) chatArea.getParent().getParent()).getVerticalScrollBar();
            int prevMax = scrollBar.getMaximum();
            int prevValue = scrollBar.getValue();
            double percentScrolled = prevMax == 0 ? 0 : (double) prevValue / prevMax;

            // הוספת ההודעות כאן
            chatArea.append(builder.toString());
            currentOffset += messages.size();

            // החזרת הגלילה למיקום המקורי (יחסי)
            SwingUtilities.invokeLater(() -> {
                int newMax = scrollBar.getMaximum();
                int newValue = (int) (newMax * percentScrolled);

            // הגבלת הערך לטווח התקני
            newValue = Math.max(0, Math.min(newMax - scrollBar.getVisibleAmount(), newValue));
            scrollBar.setValue(newValue);

            });

        } catch (Exception e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(this, "שגיאה בטעינת היסטוריית הצ'אט", "Error", JOptionPane.ERROR_MESSAGE);
        } finally {
            loading = false;
        }
    }

    private void subscribeToNewMessages() {
        client.subscribeMessages(
                ChatSubscribeRequest.newBuilder()
                        .setChatId(chatRoomId)
                        .setToken(user.getAuthToken())
                        .build(),
                new StreamObserver<Message>() {
                    @Override
                    public void onNext(Message msg) {
                        // פענוח ועידכון UI תמיד על ה־EDT:
                        byte[] decrypted = decryptMessage(
                                UUID.fromString(msg.getMessageId()),
                                msg.getCipherText().toByteArray(),
                                msg.getTimestamp()
                        );
                        String text = new String(decrypted, StandardCharsets.UTF_8);
                        String sender = msg.getSenderId().equals(user.getId().toString())
                                ? "אני" : client.getUsernameById(msg.getSenderId());
                        String time = Instant.ofEpochMilli(msg.getTimestamp())
                                .toString().substring(11,16);

                        SwingUtilities.invokeLater(() ->
                                chatArea.append(String.format("[%s] %s: %s\n", time, sender, text))
                        );
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        System.err.println("Subscription error: " + throwable.getMessage());
                    }

                    @Override
                    public void onCompleted() {
                        System.out.println("Subscription completed");
                    }
                }
        );
    }

    private void cancelSubscription() {
        if (subscriptionContext != null) {
            subscriptionContext.cancel(null);   // מבטל את ה־stream
            subscriptionContext = null;
        }
    }

    @Override
    public void dispose(){
        clearSymmetricKey();
        super.dispose();
    }

    // שליחת הודעה
    private void sendMessage() {
        String text = inputField.getText().trim();
        if (text.isEmpty()) return;

        try {
            // זהירות ממצב קצה – לא אמור לקרות
            UUID msgId = UUID.randomUUID();
            if(shownMessageIds.contains(msgId)) return;

            long timeStamp = Instant.now().toEpochMilli();

            // הצפנה עם המפתח הסימטרי לפני שליחה
            byte[] encryptedMessage = encryptMessage(msgId, text.getBytes(StandardCharsets.UTF_8), timeStamp);
            System.out.println("Msg: " + ByteString.copyFrom(encryptedMessage));
            // יצירת ההודעה
            Message message = Message.newBuilder()
                    .setMessageId(msgId.toString())
                    .setSenderId(user.getId().toString())
                    .setChatId(chatRoomId)
                    .setCipherText(ByteString.copyFrom(encryptedMessage))
                    .setTimestamp(timeStamp)
                    .setToken(user.getAuthToken())
                    .setIsSystem(false)
                    .setStatus(Chat.MessageStatus.SENT)
                    .build();

            // שליחה אסינכרונית של ההודעה
            ListenableFuture<ACK> future = client.sendMessage(message);

            // טיפול בתשובה עם callback
            Futures.addCallback(future, new FutureCallback<ACK>() {
                @Override
                public void onSuccess(ACK ack) {
                    if (ack.getSuccess()) {
                        // הוספת ההודעה להיסטוריית הצ'אט אם הצליחה
                        shownMessageIds.add(msgId);
                    } else {
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

            // איפוס שדה הטקסט
            inputField.setText("");

        } catch (Exception e) {
            // טיפול בשגיאות כלליות
            e.printStackTrace();
            JOptionPane.showMessageDialog(this, "שגיאה בשליחת ההודעה", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handleVideoCall() {
        new Thread(() -> {
            try {
                if(signalingClient.isConnected()) {
                    boolean isActive = signalingClient.checkCallStatus(chatRoomId);
                    if (isActive) {
                        signalingClient.joinCall(chatRoomId);
                    } else {
                        signalingClient.startCall(chatRoomId);
                    }

                    SwingUtilities.invokeLater(() -> {
                        VideoCallWindow videoWindow = new VideoCallWindow(signalingClient, chatRoomId, user, client);
                        signalingClient.setVideoCallWindow(videoWindow);
                        videoWindow.setVisible(true);

                        videoWindow.addWindowListener(new WindowAdapter() {
                            @Override
                            public void windowClosed(WindowEvent e) {
                                refreshVideoCallButton();
                            }
                        });
                    });
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    // מסנכרן את מצב הכפתור לפי סטטוס השיחה
    private void updateVideoCallButton(boolean active) {
        videoCallButton.setText(active
                ? "📹 הצטרף לשיחה קיימת"
                : "📹 התחלת שיחה"
        );
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
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void openManageMembersDialog() {
        JDialog dialog = new JDialog(this, "ניהול משתתפים", true);
        dialog.setSize(400, 600);
        dialog.setLocationRelativeTo(this);

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(5,5,5,5));

        updateManageMembersPanel(panel, dialog);

        JScrollPane scrollPane = new JScrollPane(panel);
        dialog.add(scrollPane);
        dialog.setVisible(true);
    }

    private void inviteUserByEmail(String email) {
        if (email.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Email can't be empty", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // יצירת בקשה לשליחת הזמנה למייל של המשתמש
        UserEmailRequest emailRequest = UserEmailRequest.newBuilder()
                .setEmail(email)
                .setToken(user.getAuthToken())  // טוקן האימות של המשתמש
                .build();

        User invitedUser;
        try {
            invitedUser = client.getUserByEmail(emailRequest);
        } catch (Exception ex) {
            ex.printStackTrace();
            SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(this, "Failed to fetch user: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE)
            );
            return;
        }

        InviteRequest inviteRequest = InviteRequest.newBuilder()
                .setInviteId(UUID.randomUUID().toString())  // מזהה ייחודי להזמנה
                .setChatId(chatRoomId)  // מזהה חדר הצ'אט
                .setAdminId(user.getId().toString())  // מזהה המנהל המשלח את ההזמנה
                .setInvitedUserId(invitedUser.getId().toString())  // פה אנחנו שמים את המייל, אבל במקרה שלך צריך ID של המשתמש
                .setTimestamp(Instant.now().toEpochMilli())  // זמן שליחת ההזמנה
                .setToken(user.getAuthToken())  // טוקן האימות של המשתמש
                .build();

        // שליחת הבקשה בצורה אסינכרונית
        ListenableFuture<ACK> future = client.inviteUser(inviteRequest);

        // טיפול בתוצאה של שליחת ההזמנה
        Futures.addCallback(future, new FutureCallback<ACK>() {
            @Override
            public void onSuccess(ACK ack) {
                if (ack.getSuccess()) {
                    JOptionPane.showMessageDialog(null, "הזמנה נשלחה בהצלחה למייל: " + email, "הצלחה", JOptionPane.INFORMATION_MESSAGE);
                } else {
                    JOptionPane.showMessageDialog(null, "הייתה שגיאה בשולחת ההזמנה למייל: " + email, "שגיאה", JOptionPane.ERROR_MESSAGE);
                }
            }

            @Override
            public void onFailure(Throwable t) {
                JOptionPane.showMessageDialog(null, "שגיאה בשליחת ההזמנה: " + t.getMessage(), "שגיאה", JOptionPane.ERROR_MESSAGE);
            }
        }, MoreExecutors.directExecutor());
    }

    private void changeUserRole(UUID chatRoomId, UUID targetUserId, ChatRoles newRole, String username) {
        // שליחת בקשה לשינוי תפקיד
        ChangeUserRoleRequest changeUserRoleRequest = ChangeUserRoleRequest.newBuilder()
                .setChatId(chatRoomId.toString())
                .setRequesterId(user.getId().toString()) // המשתמש שמבצע את הפעולה (המנהל)
                .setTargetId(targetUserId.toString())   // המשתמש שמבצעים עליו את השינוי
                .setNewRole(newRole)                     // התפקיד החדש
                .setToken(user.getAuthToken())          // טוקן האימות של המנהל
                .build();

        // שליחה לשרת לשינוי התפקיד
        ListenableFuture<ACK> future = client.changeUserRole(changeUserRoleRequest);
        Futures.addCallback(future, new FutureCallback<ACK>() {
            @Override
            public void onSuccess(ACK ack) {
                if (ack.getSuccess()) {
                    // שליחת הודעת מערכת על השינוי בצ'אט
                    Message systemMessage = Message.newBuilder()
                            .setMessageId(UUID.randomUUID().toString())
                            .setSenderId(user.getId().toString()) // או מזהה מיוחד עבור הודעות מערכת
                            .setChatId(chatRoomId.toString())
                            .setCipherText(ByteString.copyFrom((username + " קיבל תפקיד " + newRole).getBytes(StandardCharsets.UTF_8)))
                            .setTimestamp(Instant.now().toEpochMilli())
                            .setToken(user.getAuthToken())
                            .setIsSystem(true)  // הודעת מערכת
                            .setStatus(Chat.MessageStatus.SENT)
                            .build();

                    // שליחת הודעת המערכת לצ'אט
                    ListenableFuture<ACK> messageAck = client.sendMessage(systemMessage);
                    Futures.addCallback(messageAck, new FutureCallback<ACK>() {
                        @Override
                        public void onSuccess(ACK messageAckResult) {
                            if (messageAckResult.getSuccess()) {
                                chatArea.append("📢 " + username + " קיבל תפקיד " + newRole + "\n");
                            }
                        }

                        @Override
                        public void onFailure(Throwable t) {
                            JOptionPane.showMessageDialog(null, "שגיאה בשליחת הודעת המערכת: " + t.getMessage());
                        }
                    }, MoreExecutors.directExecutor());
                } else {
                    JOptionPane.showMessageDialog(null, "הייתה בעיה בשינוי התפקיד.", "שגיאה", JOptionPane.ERROR_MESSAGE);
                }
            }

            @Override
            public void onFailure(Throwable t) {
                JOptionPane.showMessageDialog(null, "שגיאה בשינוי תפקיד: " + t.getMessage(), "שגיאה", JOptionPane.ERROR_MESSAGE);
            }
        }, MoreExecutors.directExecutor());
    }

    private void removeUserFromGroup(UUID chatRoomId, UUID targetUserId, String username) {
        // שליחת בקשה להסרת המשתמש מהקבוצה
        RemoveUserRequest removeUserRequest = RemoveUserRequest.newBuilder()
                .setChatId(chatRoomId.toString())
                .setAdminId(user.getId().toString())
                .setTargetUserId(targetUserId.toString())
                .setToken(user.getAuthToken())
                .build();

        // שליחה לשרת בצורה אסינכרונית
        ListenableFuture<ACK> future = client.removeUserFromGroup(removeUserRequest);
        Futures.addCallback(future, new FutureCallback<ACK>() {
            @Override
            public void onSuccess(ACK ack) {
                if (ack.getSuccess()) {
                    // שליחת הודעת מערכת לצ'אט
                    Message systemMessage = Message.newBuilder()
                            .setMessageId(UUID.randomUUID().toString())
                            .setSenderId(user.getId().toString())
                            .setChatId(chatRoomId.toString())
                            .setCipherText(ByteString.copyFrom(("המשתמש " + username + " הוסר מהקבוצה").getBytes(StandardCharsets.UTF_8)))
                            .setTimestamp(Instant.now().toEpochMilli())
                            .setToken(user.getAuthToken())
                            .setIsSystem(true)
                            .setStatus(Chat.MessageStatus.SENT)
                            .build();

                    // שליחת הודעת המערכת לצ'אט
                    ListenableFuture<ACK> messageAck = client.sendMessage(systemMessage);
                    Futures.addCallback(messageAck, new FutureCallback<ACK>() {
                        @Override
                        public void onSuccess(ACK messageAckResult) {
                            if (messageAckResult.getSuccess()) {
                                chatArea.append("📢 " + username + " הוסר מהקבוצה\n");
                            }
                        }

                        @Override
                        public void onFailure(Throwable t) {
                            JOptionPane.showMessageDialog(null, "שגיאה בשליחת הודעת המערכת: " + t.getMessage());
                        }
                    }, MoreExecutors.directExecutor());
                } else {
                    JOptionPane.showMessageDialog(null, "הייתה בעיה בהסרת המשתמש מהקבוצה.", "שגיאה", JOptionPane.ERROR_MESSAGE);
                }
            }

            @Override
            public void onFailure(Throwable t) {
                JOptionPane.showMessageDialog(null, "שגיאה בהסרת המשתמש: " + t.getMessage(), "שגיאה", JOptionPane.ERROR_MESSAGE);
            }
        }, MoreExecutors.directExecutor());
    }

    private void leaveChat() {
        int confirm = JOptionPane.showConfirmDialog(this, "האם אתה בטוח שברצונך לעזוב את הקבוצה?", "אישור עזיבה", JOptionPane.YES_NO_OPTION);
        if (confirm == JOptionPane.YES_OPTION) {
            LeaveGroupRequest request = LeaveGroupRequest.newBuilder()
                    .setToken(user.getAuthToken())
                    .setUserId(String.valueOf(user.getId()))
                    .setChatId(chatRoomId)
                    .build();

            // שליחה לשרת בצורה אסינכרונית
            ListenableFuture<ACK> future = client.leaveGroup(request);
            Futures.addCallback(future, new FutureCallback<ACK>() {
                @Override
                public void onSuccess(ACK ack) {
                    if (ack.getSuccess()) {
                        JOptionPane.showMessageDialog(null, "עזבת את הקבוצה בהצלחה.", "יציאה", JOptionPane.INFORMATION_MESSAGE);
                        dispose();
                    } else {
                        JOptionPane.showMessageDialog(null, "התרחשה שגיאה במהלך העזיבה.", "שגיאה", JOptionPane.ERROR_MESSAGE);
                    }
                }

                @Override
                public void onFailure(Throwable t) {
                    JOptionPane.showMessageDialog(null, "שגיאה במהלך העזיבה: " + t.getMessage(), "שגיאה", JOptionPane.ERROR_MESSAGE);
                }
            }, MoreExecutors.directExecutor());
        }
    }

    private void updateManageMembersPanel(JPanel panel, JDialog dialog) {
        new Thread(() -> {
            try {
                // שליחת בקשה לעדכון חדר הצ'אט
                ChatRoom updatedRoom = client.getChatRoomById(ChatRoomRequest.newBuilder()
                        .setChatId(chatRoomId)
                        .setRequesterId(user.getId().toString())
                        .setToken(user.getAuthToken())
                        .build());

                // עדכון רשימת חברי הצ'אט
                chatRoom.getMembers().clear();
                chatRoom.getMembers().putAll(updatedRoom.getMembers());

                SwingUtilities.invokeLater(() -> {
                    panel.removeAll();
                    boolean isAdmin = chatRoom.isAdmin(user.getId()); // בדיקה אם המשתמש הוא מנהל

                    // הצגת כל חברי הצ'אט
                    for (ChatMember member : chatRoom.getMembers().values()) {
                        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT));
                        row.setPreferredSize(new Dimension(300, 30));
                        String username = client.getUsernameById(member.getUserId().toString());
                        JLabel nameLabel = new JLabel(username + (member.getUserId().equals(user.getId()) ? " (אתה)" : ""));
                        row.add(nameLabel);

                        // אם המשתמש הוא מנהל, ניתן להוסיף את האפשרות להזמין משתמשים, לשנות הרשאות למשתמשים ולהסיר משתמשים
                        if (isAdmin && !member.getUserId().equals(user.getId())) {

                            // אפשרות לשלוח הזמנה למייל אם המנהל רוצה להוסיף משתמשים חדשים
                            JButton inviteButton = new JButton("שלח הזמנה");
                            inviteButton.addActionListener(e -> {
                                String email = JOptionPane.showInputDialog(dialog, "הזן את האימייל של המשתמש");
                                if (email != null && !email.isEmpty()) {
                                    inviteUserByEmail(email); // קריאה לשליחת ההזמנה למייל
                                }
                            });

                            // כפתור לשינוי תפקיד (Promote / Demote)
                            JButton roleButton = new JButton(member.getRole() == ChatRole.ADMIN ? "Demote" : "Promote");
                            boolean promote = roleButton.getText().equals("Promote");
                            ChatRoles newRole = promote ? ChatRoles.ADMIN : ChatRoles.MEMBER;

                            roleButton.addActionListener(e -> {
                                changeUserRole(chatRoom.getChatId(), member.getUserId(), newRole, client.getUsernameById(member.getUserId().toString()));
                                updateManageMembersPanel(panel, dialog);
                            });

                            // כפתור להוציא משתמש מהצ'אט (Kick)
                            JButton kickButton = new JButton("Kick");
                            kickButton.setForeground(Color.RED);
                            kickButton.addActionListener(e -> {
                                int confirm = JOptionPane.showConfirmDialog(dialog, "להסיר את המשתמש מהצ'אט?", "אישור", JOptionPane.YES_NO_OPTION);
                                if (confirm == JOptionPane.YES_OPTION) {
                                    removeUserFromGroup(chatRoom.getChatId(), member.getUserId(), client.getUsernameById(member.getUserId().toString()));
                                    updateManageMembersPanel(panel, dialog);
                                }
                            });

                            row.add(inviteButton);
                            row.add(roleButton);
                            row.add(kickButton);
                        }

                        panel.add(row);
                    }

                    // כפתור עזיבה מהצ'אט
                    panel.add(Box.createVerticalStrut(20));
                    JButton leaveButton = new JButton("🚪 עזוב את הצ'אט");
                    leaveButton.setAlignmentX(Component.CENTER_ALIGNMENT);
                    leaveButton.addActionListener(e -> {
                        dialog.dispose();
                        leaveChat();
                    });

                    panel.add(leaveButton);
                    panel.revalidate();
                    panel.repaint();
                });
            } catch (Exception e) {
                e.printStackTrace();
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(dialog, "שגיאה בעת טעינת רשימת המשתמשים: " + e.getMessage(), "שגיאה", JOptionPane.ERROR_MESSAGE)
                );
            }
        }).start();
    }

    private void clearSymmetricKey() {
        if(symmetricKey != null){
            Arrays.fill(symmetricKey, (byte) 0);  // ניקוי זיכרון
            symmetricKey = null;
        }
        keyLoaded = false;
    }

    private byte[] encryptMessage(UUID msgId, byte[] data, long timeStamp) {
        byte[][] round_keys = new byte[11][BLOCK_SIZE];
        round_keys[0] = symmetricKey;
        keySchedule(round_keys);
        return AES_GCM.encrypt(data, generateAAD(msgId, timeStamp), round_keys);
    }

    private byte[] decryptMessage(UUID msgId, byte[] encryptedData, long timeStamp){
        if(symmetricKey == null || symmetricKey.length == 0)
            throw new IllegalStateException("Symmetric key is not loaded or invalid.");

        try {
            byte[][] round_keys = new byte[11][BLOCK_SIZE];
            round_keys[0] = symmetricKey;
            keySchedule(round_keys);
            byte[] encrypted = encryptedData;
            System.out.println("🔑 symmetricKey: " + bytesToHex(symmetricKey));
            System.out.println("🆔 msgId:      " + msgId);
            System.out.println("⏱ timestamp:  " + timeStamp);
            byte[] aad = generateAAD(msgId, timeStamp);
            System.out.println("📋 AAD:        " + new String(aad, StandardCharsets.UTF_8));
            System.out.println("🔐 cipherLen: " + encrypted.length);
            System.out.println("🔍 cipher[hex]:" + bytesToHex(encrypted));

            // עכשיו נסו לפענח
            return AES_GCM.decrypt(encrypted, aad, round_keys);

        } catch (Exception e) {
            e.printStackTrace();
            throw new SecurityException("Authentication failed. Data may have been tampered with.");
        }
    }

    private byte[] generateAAD(UUID msgId, long timeStamp) {

        String AAD = chatRoomId
                + ":" + timeStamp
                + ":" + msgId;
        return AAD.getBytes(StandardCharsets.UTF_8);
    }

    private static String bytesToHex(byte[] arr) {
        StringBuilder sb = new StringBuilder();
        for (byte b : arr) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

}
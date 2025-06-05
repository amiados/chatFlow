    package client;

    import com.google.common.util.concurrent.ListenableFuture;
    import io.grpc.ManagedChannel;
    import io.grpc.Status;
    import io.grpc.StatusRuntimeException;

    import com.chatFlow.Chat.*;
    import com.chatFlow.chatGrpc.chatBlockingStub;
    import com.chatFlow.chatGrpc.chatFutureStub;
    import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
    import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
    import io.grpc.stub.StreamObserver;
    import model.*;
    import org.slf4j.Logger;
    import org.slf4j.LoggerFactory;
    import utils.ChannelManager;

    import java.io.File;
    import java.time.Instant;
    import java.util.*;
    import java.util.concurrent.atomic.AtomicReference;
    import java.util.function.Supplier;
    import java.util.stream.Collectors;

    import com.chatFlow.chatGrpc.chatStub;

    import javax.net.ssl.SSLException;

    import static com.chatFlow.chatGrpc.*;

    /**
     * מחלקת Client אחידה לקריאות gRPC עם ניהול אוטומטי של טוקן (atomic),
     * ביצוע retry במקרה של UNAUTHENTICATED וללא תלות ב-UI.
     */
    public class ChatClient {
        /** לוגר להדפסת אירועים וטעויות */
        private static final Logger log = LoggerFactory.getLogger(ChatClient.class);

        /** stub חסימתית לקריאות סינכרוניות */
        private final chatBlockingStub blockingStub;
        /** stub עתידית לקריאות אסינכרוניות שמחזירות Future */
        private final chatFutureStub futureStub;
        /** stub אסינכרוני לקריאות Streaming */
        private final chatStub asyncStub;

        /** DAO לניהול משתמשים מקומי */
        private final UserDAO userDAO = new UserDAO();

        /** המשתמש הנוכחי שאוחסן בפנים המחלקה */
        private User user;
        /** כתובת השרת */
        private static final String SERVER_ADDRESS = "localhost";
        /** פורט השרת */
        private static final int SERVER_PORT = 50051;
        /** קובץ תעודת TLS של השרת */
        private static final File TRUST_CERT_COLLECTION = new File("certs/server.crt");

        /** מקור אמת יחיד לטוקן הפעיל */
        private final AtomicReference<String> tokenRef = new AtomicReference<>();

        /**
         * בונה מופע חדש של ChatClient ומאתחל ערוץ מאובטח.
         */
        public ChatClient() {
            ManagedChannel channel = buildSecureChannel();
            this.blockingStub = newBlockingStub(channel);
            this.futureStub = newFutureStub(channel);
            this.asyncStub = newStub(channel);
        }

        /**
         * יוצר ManagedChannel מאובטח באמצעות TLS.
         * @return ManagedChannel מחובר לשרת בסביבת TLS
         */
        private ManagedChannel buildSecureChannel() {
            try {
                return NettyChannelBuilder
                        .forAddress(SERVER_ADDRESS, SERVER_PORT)
                        .sslContext(GrpcSslContexts.forClient()
                                .trustManager(TRUST_CERT_COLLECTION)
                                .build())
                        .build();
            } catch (SSLException e) {
                throw new RuntimeException("Failed to set up TLS channel", e);
            }
        }

        /**
         * מריץ קריאת RPC עם retry אוטומטי במקרה של UNAUTHENTICATED.
         * אם מקבלים UNAUTHENTICATED - מנסה לרענן את הטוקן ומריץ קריאה נוספת.
         * @param rpcCall פונקציית סופר שמבצעת את קריאת ה-RPC
         * @param <T> סוג התוצאה שמוחזרת מהקריאה
         * @return התוצאה של ה-RPC במידה והתקבלה בהצלחה
         */
        private <T> T withAuthRefresh(Supplier<T> rpcCall) {
            try {
                return rpcCall.get();
            } catch (StatusRuntimeException e) {
                if (e.getStatus().getCode() == Status.Code.UNAUTHENTICATED) {
                    log.info("Received UNAUTHENTICATED, attempting token refresh...");

                    // 1) רענון טוקן
                    RefreshTokenRequest refreshTokenRequest = RefreshTokenRequest.newBuilder()
                            .setToken(getToken())
                            .build();

                    RefreshTokenResponse refreshTokenResponse = blockingStub.refreshToken(refreshTokenRequest);
                    if (refreshTokenResponse.getSuccess()) {
                        tokenRef.set(refreshTokenResponse.getNewToken());
                        log.info("Token refreshed successfully");
                        // 2) קריאה חוזרת
                        return rpcCall.get();
                    } else {
                        log.error("Token refresh failed: {}", refreshTokenResponse.getMessage());
                        // אם השרת סירב לרענון הטוקן: זריקה עם UNAUTHENTICATED
                        throw new StatusRuntimeException(
                                Status.UNAUTHENTICATED.withDescription("Refresh failed: " + refreshTokenResponse.getMessage())
                        );
                    }
                }
                throw e;
            }
        }

        // -- ניהול חיבור לשרת

        /**
         * סוגר את החיבור לערוץ gRPC.
         */
        public void shutdown() {
            ChannelManager.getInstance().shutdown();
        }

        // --- Authentication APIs ---

        /**
         * מבצע הרשמה בשרת.
         * @param request בקשת RegisterRequest עם פרטי ההרשמה
         * @return ConnectionResponse עם סטטוס ותיאור תוצאה
         */
        public ConnectionResponse register(RegisterRequest request) {
            try {
                return blockingStub.register(request);
            } catch (Exception e) {
                return ConnectionResponse.newBuilder()
                        .setSuccess(false)
                        .setMessage("שגיאה פנימית: " + e.getMessage())
                        .build();
            }
        }

        /**
         * מבצע התחברות בשרת.
         * @param request בקשת LoginRequest עם אישורי התחברות
         * @return ConnectionResponse עם סטטוס ותיאור תוצאה
         */
        public ConnectionResponse login(LoginRequest request) {
            try {
                return blockingStub.login(request);
            } catch (Exception e) {
                return ConnectionResponse.newBuilder()
                        .setSuccess(false)
                        .setMessage("שגיאה פנימית: " + e.getMessage())
                        .build();
            }
        }

        /**
         * מאמת קוד OTP בהרשמה.
         * @param request בקשת VerifyOtpRequest עם טוקן וקוד ה-OTP
         * @return ConnectionResponse עם סטטוס ותיאור תוצאה
         */
        public ConnectionResponse verifyRegisterOtp(VerifyOtpRequest request) {
            try {
                return blockingStub.verifyRegisterOtp(request);
            } catch (Exception e) {
                return ConnectionResponse.newBuilder()
                        .setSuccess(false)
                        .setMessage("שגיאה פנימית: " + e.getMessage())
                        .build();
            }
        }

        /**
         * מאמת קוד OTP בהתחברות.
         * @param request בקשת VerifyOtpRequest עם טוקן וקוד ה-OTP
         * @return ConnectionResponse עם סטטוס ותיאור תוצאה
         */
        public ConnectionResponse verifyLoginOtp(VerifyOtpRequest request) {
            try {
                return blockingStub.verifyLoginOtp(request);
            } catch (Exception e) {
                return ConnectionResponse.newBuilder()
                        .setSuccess(false)
                        .setMessage("שגיאה פנימית: " + e.getMessage())
                        .build();
            }
        }

        /**
         * רענון טוקן (גישה ידנית) - נגיש רק במידת הצורך.
         */
        public RefreshTokenResponse refreshToken(RefreshTokenRequest request) {
            return blockingStub.refreshToken(request);
        }

        // --- Chat operations ---

        /**
         * שולח הודעה באופן אסינכרוני עם ניהול טוקן.
         * @param message אובייקט Message להכנסה
         * @return Future עם ACK שכולל הצלחה/כשלון
         */
        public ListenableFuture<ACK> sendMessage(Message message) {
            validateMessage(message);
            return withAuthRefresh(() -> futureStub.sendMessage(
                    message.toBuilder().setToken(getToken()).build()
            ));
        }

        /**
         * נרשם לזרם הודעות חדשות בצ'אט.
         * @param request בקשת ChatSubscribeRequest עם chatId
         * @param observer StreamObserver לטיפול באירועי onNext/onError/onCompleted
         */
        public void subscribeMessages(ChatSubscribeRequest request, StreamObserver<Message> observer) {
            ChatSubscribeRequest withToken = request.toBuilder()
                    .setToken(getToken())
                    .build();
            asyncStub.subscribeMessages(withToken, observer);
        }

        /**
         * מקבל היסטוריית צ'אט מסונכרנת.
         * @param request בקשת ChatHistoryRequest עם chatId
         * @return ChatHistoryResponse עם רשימת הודעות
         */
        public ChatHistoryResponse getChatHistory(ChatHistoryRequest request) {
            if (request.getChatId().isEmpty())
                throw new IllegalArgumentException("Chat ID required");

            ChatHistoryRequest withToken = request.toBuilder()
                    .setToken(getToken())
                    .build();
            return withAuthRefresh(() -> blockingStub.getChatHistory(withToken));
        }

        /**
         * מאתר חדר צ'אט לפי מזהה.
         * @param chatId מזהה החדר כמחרוזת
         * @param requesterId מזהה המבקש
         * @return אובייקט ChatRoom ממופה
         */
        public ChatRoom getChatRoomById(String chatId, String requesterId) {
            ChatRoomResponse resp = withAuthRefresh(() ->
                    blockingStub.getChatRoom(ChatRoomRequest.newBuilder()
                            .setChatId(chatId)
                            .setRequesterId(requesterId)
                            .setToken(getToken())
                            .build())
            );
            return mapToChatRoom(resp);
        }

        /**
         * מביא את רשימת חדרי הצ'אט של משתמש נתון.
         * @param userId מזהה המשתמש
         * @return List של ChatRoom
         */
        public List<ChatRoom> getUserChatRooms(String userId) {
            ChatRoomResponseList list = withAuthRefresh(() ->
                    blockingStub.getUserChatRooms(UserIdRequest.newBuilder()
                            .setUserId(userId)
                            .setToken(getToken())
                            .build())
            );
            return list.getRoomsList().stream()
                    .map(this::mapToChatRoom)
                    .collect(Collectors.toList());
        }

        /**
         * שולף מפתח סימטרי עבור chat לפי גרסת מפתח.
         */
        public byte[] getSymmetricKey(String userId, String chatId, int keyVersion) {
            MemberRequest req = MemberRequest.newBuilder()
                    .setUserId(userId)
                    .setChatId(chatId)
                    .setKeyVersion(keyVersion)
                    .setToken(getToken())
                    .build();
            SymmetricKey resp = withAuthRefresh(() -> blockingStub.getSymmetricKey(req));
            return resp.getSymmetricKey().toByteArray();
        }

        /**
         * מביא את רשימת ההזמנות בצ'אט המשתייכות למשתמש.
         */
        public List<Invite> getUserInvites(String userId) {
            InviteListResponse resp = withAuthRefresh(() ->
                    blockingStub.getUserInvites(UserIdRequest.newBuilder()
                            .setUserId(userId)
                            .setToken(getToken())
                            .build())
            );
            return resp.getInvitesList().stream()
                    .map(proto -> new Invite(
                            UUID.fromString(proto.getInviteId()),
                            UUID.fromString(proto.getChatId()),
                            UUID.fromString(proto.getSenderId()),
                            UUID.fromString(proto.getInvitedUserId()),
                            Instant.ofEpochMilli(proto.getTimestamp()),
                            InviteStatus.valueOf(proto.getStatus().name()),
                            proto.getEncryptedKey().toByteArray(),
                            proto.getKeyVersion()))
                    .collect(Collectors.toList());
        }

        /**
         * יוצר קבוצת צ'אט חדשה.
         */
        public ListenableFuture<GroupChat> createGroupChat(CreateGroupRequest request) {
            CreateGroupRequest withToken = request.toBuilder()
                    .setToken(getToken())
                    .build();
            return futureStub.createGroupChat(withToken);
        }

        /**
         * שולח הזמנת משתמש להצטרפות לצ'אט קבוצתי.
         */
        public ListenableFuture<ACK> inviteUser(InviteRequest request) {
            InviteRequest withToken = request.toBuilder()
                    .setToken(getToken())
                    .build();
            return futureStub.inviteUser(withToken);
        }

        /**
         * משיב להזמנת צ'אט נכנסת.
         */
        public ListenableFuture<ACK> respondToInvite(InviteResponse inviteResponse) {
            InviteResponse withToken = inviteResponse.toBuilder()
                    .setToken(getToken())
                    .build();
            return futureStub.respondToInvite(withToken);
        }

        /**
         * מסיר משתמש מקבוצה.
         */
        public ListenableFuture<ACK> removeUserFromGroup(RemoveUserRequest request) {
            RemoveUserRequest withToken = request.toBuilder()
                    .setToken(getToken())
                    .build();
            return futureStub.removeUserFromGroup(withToken);
        }

        /**
         * עזיבת קבוצה על ידי המשתמש.
         */
        public ListenableFuture<ACK> leaveGroup(LeaveGroupRequest request) {
            LeaveGroupRequest withToken = request.toBuilder()
                    .setToken(getToken())
                    .build();
            return futureStub.leaveGroup(withToken);
        }

        /**
         * מנתק משתמש מהשרת.
         */
        public ListenableFuture<ACK> disconnectUser(User user) {
            DisconnectRequest disconnectRequest = DisconnectRequest.newBuilder()
                    .setUserId(user.getId().toString())
                    .setToken(getToken())
                    .build();
            return futureStub.disconnectUser(disconnectRequest);
        }

        /**
         * משנה את תפקיד המשתמש בקבוצת צ'אט.
         */
        public ListenableFuture<ACK> changeUserRole(ChangeUserRoleRequest request) {
            ChangeUserRoleRequest withToken = request.toBuilder()
                    .setToken(getToken())
                    .build();
            return futureStub.changeUserRole(withToken);
        }

        /**
         * מקבל משתמש לפי אימייל.
         */
        public User getUserByEmail(String email) {
            try {
                UserResponse response = withAuthRefresh(() ->
                        blockingStub.getUserByEmail(UserEmailRequest.newBuilder()
                                .setEmail(email)
                                .setToken(getToken())
                                .build())
                );
                if (response.getSuccess())
                    return mapToUser(response);

            } catch (Exception e) {
                log.warn("getUserByEmail failed", e);
            }
            return null;
        }

        /**
         * מקבל משתמש לפי מזהה.
         */
        public User getUserById(String userId) {
            try {
                UserResponse response = withAuthRefresh(() ->
                        blockingStub.getUserById(UserIdRequest.newBuilder()
                                .setUserId(userId)
                                .setToken(getToken())
                                .build())
                );
                if (response.getSuccess())
                    return mapToUser(response);

            } catch (Exception e) {
                log.warn("getUserById failed", e);
            }
            return null;
        }

        /**
         * מחזיר את הפרטים של המשתמש הנוכחי לפי הטוקן.
         */
        public User getCurrentUser() {
            try {
                UserResponse resp = withAuthRefresh(() ->
                        blockingStub.getCurrentUser(VerifyTokenRequest.newBuilder()
                                .setToken(getToken())
                                .build())
                );
                if (resp.getSuccess())
                    return mapToUser(resp);
            } catch (Exception e) {
                log.warn("getCurrentUser failed", e);
            }
            return null;
        }

        // --- Helpers ---

        /**
         * בודק תקינות של אובייקט Message לפני השליחה.
         * זורק IllegalArgumentException במקרה של payload שגוי.
         */
        private void validateMessage(Message m) {
            if (m == null
                    || m.getMessageId().isEmpty()
                    || m.getSenderId().isEmpty()
                    || m.getChatId().isEmpty()
                    || m.getCipherText().isEmpty()) {
                throw new IllegalArgumentException("Invalid message payload");
            }
        }

        /**
         * ממפה תוצאת ChatRoomResponse לאובייקט ChatRoom פנימי.
         */
        private ChatRoom mapToChatRoom(ChatRoomResponse r) {
            ChatRoom room = new ChatRoom(
                    UUID.fromString(r.getChatId()),
                    r.getName(),
                    UUID.fromString(r.getOwnerId()),
                    Instant.parse(r.getCreatedAt()),
                    r.getFolderId(),
                    new HashMap<>()
            );
            room.setCurrentKeyVersion(r.getKeyVersion());
            r.getMembersList().forEach(info -> {
                try {
                    room.addMember(new ChatMember(
                            room.getChatId(),
                            UUID.fromString(info.getUserId()),
                            Enum.valueOf(model.ChatRole.class, info.getRole()),
                            room.getCreatedAt(),
                            InviteStatus.ACCEPTED
                    ));
                } catch (Exception e) {
                    log.warn("Failed to map member {}", info.getUserId(), e);
                }
            });
            return room;
        }

        /**
         * ממפה UserResponse לאובייקט User פנימי.
         */
        private User mapToUser(UserResponse r) {
            return new User(
                    UUID.fromString(r.getUserId()),
                    r.getUsername(),
                    r.getEmail(),
                    null,
                    r.getPublicKey().isEmpty() ? null : Base64.getDecoder().decode(r.getPublicKey()),
                    null,
                    r.getN().isEmpty() ? null : Base64.getDecoder().decode(r.getN()),
                    0,
                    null
            );
        }

        /**
         * מחלץ שם משתמש לפי מזהה מקומי בעזרת UserDAO.
         */
        public String getUsernameById(String userId) {
            try {
                User userById = userDAO.getUserById(UUID.fromString(userId));
                return userById != null ? userById.getUsername() : "משתמש לא ידוע";
            } catch (Exception e) {
                return "משתמש לא ידוע";
            }
        }

        /**
         * קובע את המשתמש הנוכחי במחלקה.
         */
        public void setUser(User user) {
            this.user = user;
        }

        /**
         * מחזיר את המשתמש הנוכחי.
         */
        public User getUser() {
            return user;
        }

        /**
         * קובע את הטוקן הנוכחי לשימוש בקריאות הבאות.
         */
        public void setToken(String token) {
            tokenRef.set(token);
        }

        /**
         * מחזיר את הטוקן הנוכחי from tokenRef.
         */
        public String getToken() {
            return tokenRef.get();
        }
    }
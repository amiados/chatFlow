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
 * Client אחד לכל הקריאות ל־gRPC, עם token מנוהל אוטומטית (atomic),
 * retry-on-unauthenticated, וללא תלות ב־UI.
 */
public class ChatClient {
    private static final Logger log = LoggerFactory.getLogger(ChatClient.class);

    private final chatBlockingStub blockingStub;
    private final chatFutureStub futureStub;
    private final chatStub asyncStub;

    private final UserDAO userDAO = new UserDAO();

    private User user;
    private static final String SERVER_ADDRESS = "localhost";
    private static final int SERVER_PORT = 50051;
    private static final File TRUST_CERT_COLLECTION =  new File("certs/server.crt");

    /** מקור אמת יחיד לטוקן הפעיל */
    private final AtomicReference<String> tokenRef = new AtomicReference<>();

    public ChatClient() {
        ManagedChannel channel = buildSecureChannel();
        this.blockingStub = newBlockingStub(channel);
        this.futureStub = newFutureStub(channel);
        this.asyncStub = newStub(channel);
    }

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
     * מוציא קריאה עם retry אוטומטי ב־UNAUTHENTICATED.
     * אם מתקבל UNAUTHENTICATED – קורא לרענון הטוקן ומנסה שוב פעם אחת.
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

                RefreshTokenResponse refreshTokenResponse  = blockingStub.refreshToken(refreshTokenRequest);
                if (refreshTokenResponse.getSuccess()) {
                    tokenRef.set(refreshTokenResponse.getNewToken());
                    log.info("Token refreshed successfully");
                    return rpcCall.get();
                } else {
                    log.error("Token refresh failed: {}", refreshTokenResponse.getMessage());

                    // אם השרת סירב לרענון הטוקן: זרוק Authentication failure
                    throw new StatusRuntimeException(
                            Status.UNAUTHENTICATED.withDescription("Refresh failed: " + refreshTokenResponse.getMessage())
                    );
                }
            }
            throw e;
        }
    }

    // -- סגירת החיבור
    public void shutdown(){
        ChannelManager.getInstance().shutdown();
    }

    // --- Authentication APIs ---

    // -- הרשמה
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

    // -- התחברות
    public ConnectionResponse login(LoginRequest request){
        try {
            return blockingStub.login(request);
        } catch (Exception e) {
            return ConnectionResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("שגיאה פנימית: " + e.getMessage())
                    .build();
        }
    }

    // -- אימות OTP בהרשמה
    public ConnectionResponse verifyRegisterOtp(VerifyOtpRequest request){
        try {
            return blockingStub.verifyRegisterOtp(request);
        } catch (Exception e) {
            return ConnectionResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("שגיאה פנימית: " + e.getMessage())
                    .build();
        }
    }

    // -- אימות OTP בהתחברות
    public ConnectionResponse verifyLoginOtp(VerifyOtpRequest request){
        try {
            return blockingStub.verifyLoginOtp(request);
        } catch (Exception e) {
            return ConnectionResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("שגיאה פנימית: " + e.getMessage())
                    .build();
        }
    }

    // Exposed only if you need to call manually
    public RefreshTokenResponse refreshToken(RefreshTokenRequest request) {
        return blockingStub.refreshToken(request);
    }

    // --- Chat operations ---

    // -- שליחת הודעה
    public ListenableFuture<ACK> sendMessage(Message message) {
        validateMessage(message);
        return withAuthRefresh(() -> futureStub.sendMessage(
                message.toBuilder().setToken(getToken()).build()
        ));
    }

    /**
     * נרשמים לקבלת זרם הודעות חדשות בחדר.
     * @param request עם chatId וטוקן
     * @param observer ה־StreamObserver שמטפל ב־onNext/onError/onCompleted
     */
    public void subscribeMessages(ChatSubscribeRequest request, StreamObserver<Message> observer) {
        ChatSubscribeRequest withToken = request.toBuilder()
                .setToken(getToken())
                .build();
        asyncStub.subscribeMessages(withToken, observer);
    }


    // -- היסטוריית צ'אט
    public ChatHistoryResponse getChatHistory(ChatHistoryRequest request) {
        if (request.getChatId().isEmpty())
            throw new IllegalArgumentException("Chat ID required");

        ChatHistoryRequest withToken = request.toBuilder()
                .setToken(getToken())
                .build();
        return withAuthRefresh(() -> blockingStub.getChatHistory(withToken));
    }

    // -- קבלת חדר צ'אט לפי מזהה
    public ChatRoom getChatRoomById(String chatId, String requesterId){
        ChatRoomResponse resp = withAuthRefresh(() ->
                blockingStub.getChatRoom(ChatRoomRequest.newBuilder()
                        .setChatId(chatId)
                        .setRequesterId(requesterId)
                        .setToken(getToken())
                        .build())
        );
        return mapToChatRoom(resp);
    }

    // -- קבלת חדרי צ'אט של משתמש
    public List<ChatRoom> getUserChatRooms(String userId){
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

    // מתודה לשליפת המפתח הסימטרי
    public byte[] getSymmetricKey(String userId, String chatId, int keyVersion){
        MemberRequest req = MemberRequest.newBuilder()
                .setUserId(userId)
                .setChatId(chatId)
                .setKeyVersion(keyVersion)
                .setToken(getToken())
                .build();
        SymmetricKey resp = withAuthRefresh(() -> blockingStub.getSymmetricKey(req));
        return resp.getSymmetricKey().toByteArray();
    }

    // -- קבלת הזמנות צ'אט של משתמש
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
                        proto.getEncryptedKey().toByteArray()))
                .collect(Collectors.toList());
    }

    // -- יצירת קבוצה
    public ListenableFuture<GroupChat> createGroupChat(CreateGroupRequest request) {
        CreateGroupRequest withToken = request.toBuilder()
                .setToken(getToken())
                .build();
        return futureStub.createGroupChat(withToken);
    }

    // -- שליחת הזמנה
    public ListenableFuture<ACK> inviteUser(InviteRequest request) {
        InviteRequest withToken = request.toBuilder()
                .setToken(getToken())
                .build();
        return futureStub.inviteUser(withToken);
    }

    public ListenableFuture<ACK> respondToInvite(InviteResponse inviteResponse) {
        InviteResponse withToken = inviteResponse.toBuilder()
                .setToken(getToken())
                .build();
        return futureStub.respondToInvite(withToken);
    }

    public ListenableFuture<ACK> removeUserFromGroup(RemoveUserRequest request) {
        RemoveUserRequest withToken = request.toBuilder()
                .setToken(getToken())
                .build();
        return futureStub.removeUserFromGroup(withToken);
    }

    public ListenableFuture<ACK> leaveGroup(LeaveGroupRequest request) {
        LeaveGroupRequest withToken = request.toBuilder()
                .setToken(getToken())
                .build();
        return futureStub.leaveGroup(withToken);
    }

    public ListenableFuture<ACK> disconnectUser(User user) {
        DisconnectRequest disconnectRequest = DisconnectRequest.newBuilder()
                .setUserId(user.getId().toString())
                .setToken(getToken())
                .build();
        return futureStub.disconnectUser(disconnectRequest);
    }


    public ListenableFuture<ACK> changeUserRole(ChangeUserRoleRequest request) {
        ChangeUserRoleRequest withToken = request.toBuilder()
                .setToken(getToken())
                .build();
        return futureStub.changeUserRole(withToken);
    }

    // -- קבלת משתמש לפי אימייל
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

    // -- קבלת משתמש לפי מזהה
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

    // -- קבלת משתמש נוכחי לפי טוקן
    public User getCurrentUser(){
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

    private void validateMessage(Message m) {
        if (m == null
                || m.getMessageId().isEmpty()
                || m.getSenderId().isEmpty()
                || m.getChatId().isEmpty()
                || m.getCipherText().isEmpty()) {
            throw new IllegalArgumentException("Invalid message payload");
        }
    }

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

    public String getUsernameById(String userId) {
        try {
            User userById = userDAO.getUserById(UUID.fromString(userId));
            return userById != null ? userById.getUsername() : "משתמש לא ידוע";
        } catch (Exception e) {
            return "משתמש לא ידוע";
        }
    }

    public void setUser(User user) {
        this.user = user;
    }
    public User getUser() {
        return user;
    }

    public void setToken(String token) {
        tokenRef.set(token);
    }

    public String getToken() {
        return tokenRef.get();
    }
}

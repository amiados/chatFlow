package client;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListenableFutureTask;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;

import com.chatFlow.Chat.*;
import com.chatFlow.chatGrpc.chatBlockingStub;
import com.chatFlow.chatGrpc.chatFutureStub;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import model.*;
import utils.ChannelManager;

import java.io.File;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;

import com.chatFlow.chatGrpc.chatStub;

import javax.net.ssl.SSLException;

import static com.chatFlow.chatGrpc.*;

public class ChatClient {

    private final chatBlockingStub blockingStub;
    private final chatFutureStub futureStub;
    private final chatStub asyncStub;

    private final UserDAO userDAO = new UserDAO();

    private static final String SERVER_ADDRESS = "localhost";
    private static final int SERVER_PORT = 50051;
    private static final File TRUST_CERT_COLLECTION =  new File("certs/server.crt");

    public ChatClient() {
        ManagedChannel channel;
        try {
            channel = NettyChannelBuilder
                .forAddress(SERVER_ADDRESS, SERVER_PORT)
                .sslContext(GrpcSslContexts.forClient()
                        .trustManager(TRUST_CERT_COLLECTION)
                        .build()
                )
                .build();
        } catch (SSLException e) {
            throw new RuntimeException("Failed to set up TLS channel", e);
        }

        this.blockingStub = newBlockingStub(channel);
        this.futureStub = newFutureStub(channel);
        this.asyncStub = newStub(channel);
    }

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

    // -- שליחת הודעה
    public ListenableFuture<ACK> sendMessage(Message message) {
        try {

            if (message == null){
                throw new IllegalArgumentException("Message cannot be null");
            }

            if(message.getMessageId().isEmpty() || message.getSenderId().isEmpty() ||
            message.getChatId().isEmpty() || message.getCipherText().isEmpty()){
                throw new IllegalArgumentException("Required fields are missing in the message");
            }

            return futureStub.sendMessage(message);

        } catch (Exception e) {
            System.err.println("Error sending message: " + e.getMessage());
            return Futures.immediateFailedFuture(e);
        }
    }

    /**
     * נרשמים לקבלת זרם הודעות חדשות בחדר.
     * @param request עם chatId וטוקן
     * @param observer ה־StreamObserver שמטפל ב־onNext/onError/onCompleted
     */
    public void subscribeMessages(ChatSubscribeRequest request, StreamObserver<Message> observer) {
        asyncStub.subscribeMessages(request, observer);
    }

    // -- היסטוריית צ'אט
    public ChatHistoryResponse getChatHistory(ChatHistoryRequest request) {
        try {

            if (request.getChatId().isEmpty() || request.getToken().isEmpty()) {
                throw new IllegalArgumentException("Chat ID and Token are required");
            }

            return blockingStub.getChatHistory(request);
        } catch (Exception e) {
            System.err.println("Failed to get chat history: " + e.getMessage());
            return ChatHistoryResponse.newBuilder().build();
        }
    }

    // -- קבלת חדר צ'אט לפי מזהה
    public ChatRoom getChatRoomById(ChatRoomRequest request){
        try {

            if (request.getChatId().isEmpty() || request.getToken().isEmpty()) {
                throw new IllegalArgumentException("Chat ID and Token are required");
            }

            ChatRoomResponse response = blockingStub.getChatRoom(request);

            if (response == null || response.getChatId().isEmpty()) {
                throw new IllegalStateException("ChatRoom not found or empty response");
            }


            ChatRoom chatRoom = new ChatRoom(
                    UUID.fromString(response.getChatId()),
                    response.getName(),
                    UUID.fromString(response.getOwnerId()),
                    Instant.parse(response.getCreatedAt()),
                    response.getFolderId(),
                    null
            );

            for (ChatMemberInfo info : response.getMembersList()) {
                try {
                    chatRoom.addMember(new ChatMember(
                            chatRoom.getChatId(),
                            UUID.fromString(info.getUserId()),
                            ChatRole.valueOf(info.getRole()),
                            chatRoom.getCreatedAt(),
                            InviteStatus.ACCEPTED,
                            null
                    ));
                } catch (Exception e) {
                    System.err.println("Failed to add member: " + e.getMessage());
                }
            }

            return chatRoom;
        } catch (Exception e) {
            System.err.println("Failed to get chat room: " + e.getMessage());
            return null; // ניתן לשקול החזרת אובייקט ChatRoom ריק עם שדה שגיאה
        }
    }

    // מתודה לשליפת המפתח הסימטרי
    public byte[] getSymmetricKey(MemberRequest request){
        try {
            SymmetricKey response = blockingStub.getSymmetricKey(request);
            return response.getSymmetricKey().toByteArray();
        } catch (Exception e){
            e.printStackTrace();
            return null;
        }
    }

    // -- קבלת חדרי צ'אט של משתמש
    public ArrayList<ChatRoom> getUserChatRooms(UserIdRequest request){
        ChatRoomResponseList responseList = blockingStub.getUserChatRooms(request);
        ArrayList<ChatRoom> chatRooms = new ArrayList<>();

        for(ChatRoomResponse protoRoom : responseList.getRoomsList()){
            UUID chatId = UUID.fromString(protoRoom.getChatId());
            String name = protoRoom.getName();
            UUID ownerId = UUID.fromString(protoRoom.getOwnerId());
            Instant createdAt = Instant.parse(protoRoom.getCreatedAt());
            String folderId =  protoRoom.getFolderId();
            HashMap<UUID, ChatMember> members = new HashMap<>();
            for (ChatMemberInfo info : protoRoom.getMembersList()) {
                UUID memberId = UUID.fromString(info.getUserId());
                ChatRole role = ChatRole.valueOf(info.getRole());

                ChatMember member = new ChatMember(chatId, memberId, role, createdAt, InviteStatus.ACCEPTED, null);
                members.put(memberId, member);
            }

            ChatRoom room = new ChatRoom(chatId, name, ownerId, createdAt, folderId, members);
            chatRooms.add(room);
        }
        return chatRooms;
    }

    // -- קבלת משתמש לפי אימייל
    public User getUserByEmail(UserEmailRequest request) {
        try {
            UserResponse response = blockingStub.getUserByEmail(request);
            if (response.getSuccess()) {
                return new User(
                        UUID.fromString(response.getUserId()),
                        response.getUsername(),
                        response.getEmail(),
                        null,
                        response.getPublicKey().isEmpty() ? null : Base64.getDecoder().decode(response.getPublicKey()),
                        null,
                        response.getN().isEmpty() ? null : Base64.getDecoder().decode(response.getN())
                );
            }
        } catch (StatusRuntimeException e) {
            e.printStackTrace();
        }
        return null;
    }

    // -- קבלת משתמש לפי מזהה
    public User getUserById(UserIdRequest request) {
        try {
            UserResponse response = blockingStub.getUserById(request);
            if (response.getSuccess()) {
                return new User(
                        UUID.fromString(response.getUserId()),
                        response.getUsername(),
                        response.getEmail(),
                        null,
                        response.getPublicKey().isEmpty() ? null : Base64.getDecoder().decode(response.getPublicKey()),
                        null,
                        response.getN().isEmpty() ? null : Base64.getDecoder().decode(response.getN())
                );
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    // -- קבלת הזמנות צ'אט של משתמש
    public ArrayList<Invite> getUserInvites(UserIdRequest request) {
        InviteListResponse response = blockingStub.getUserInvites(request);
        ArrayList<Invite> invites = new ArrayList<>();

        for(ProtoInvite proto : response.getInvitesList()){
            invites.add(new Invite(
                    UUID.fromString(proto.getInviteId()),
                    UUID.fromString(proto.getChatId()),
                    UUID.fromString(proto.getSenderId()),
                    UUID.fromString(proto.getInvitedUserId()),
                    Instant.ofEpochMilli(proto.getTimestamp()),
                    InviteStatus.valueOf(proto.getStatus().name()),
                    proto.getEncryptedKey().toByteArray()
            ));
        }
        return invites;
    }

    // -- קבלת משתמש נוכחי לפי טוקן
    public UserResponse getCurrentUser(VerifyTokenRequest request){
        return blockingStub.getCurrentUser(request);
    }

    // -- יצירת קבוצה
    public ListenableFuture<GroupChat> createGroupChat(CreateGroupRequest request) {
        try {
            ListenableFuture<GroupChat> futureResponse = futureStub.createGroupChat(request);

            // הוספת טיפול ב-Listener כאשר הקריאה אסינכרונית
            futureResponse.addListener(() -> {
                try {
                    // Ensure the response is available
                    if (!futureResponse.isDone()) {
                        return;
                    }

                    GroupChat response = futureResponse.get();  // Wait for response

                    if (response.getSuccess()) {
                        // If the chat creation is successful, handle it
                        System.out.println("Group chat created: " + response.getChatId());
                    } else {
                        // Handle failure to create the group chat
                        System.out.println("Failed to create group chat: " + response.getMessage());
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                    // Handle error from the asynchronous operation
                    System.out.println("Error while processing createGroupChat response");
                }
            }, Executors.newSingleThreadExecutor());

            return futureResponse;

        } catch (StatusRuntimeException e) {
            // Handle the gRPC error
            System.out.println("gRPC error: " + e.getStatus().getDescription());
            return ListenableFutureTask.create(() -> GroupChat.newBuilder()
                    .setSuccess(false)
                    .setMessage("gRPC error: " + e.getStatus().getDescription())
                    .build());
        } catch (Exception ex) {
            // Handle unexpected errors
            return ListenableFutureTask.create(() -> GroupChat.newBuilder()
                    .setSuccess(false)
                    .setMessage("Unexpected error: " + ex.getMessage())
                    .build());
        }
    }

    // -- שליחת הזמנה
    public ListenableFuture<ACK> inviteUser(InviteRequest request) {
        try {
            return futureStub.inviteUser(request);
        } catch (StatusRuntimeException e) {
            return ListenableFutureTask.create(() -> ACK.newBuilder()
                    .setSuccess(false)
                    .setMessage("gRPC error: " + e.getStatus().getDescription())
                    .build());
        } catch (Exception ex) {
            return ListenableFutureTask.create(() -> ACK.newBuilder()
                    .setSuccess(false)
                    .setMessage("Unexpected error: " + ex.getMessage())
                    .build());
        }
    }

    public ListenableFuture<ACK> respondToInvite(InviteResponse inviteResponse) {
        return futureStub.respondToInvite(inviteResponse);
    }

    public ListenableFuture<ACK> changeUserRole(ChangeUserRoleRequest request) {
        return futureStub.changeUserRole(request);
    }

    public String getUsernameById(String userId) {
        try {
            User user = userDAO.getUserById(UUID.fromString(userId));
            return user != null ? user.getUsername() : "משתמש לא ידוע";
        } catch (Exception e) {
            return "משתמש לא ידוע";
        }
    }

    public ListenableFuture<ACK> removeUserFromGroup(RemoveUserRequest request) {
        return futureStub.removeUserFromGroup(request);
    }

    public ListenableFuture<ACK> leaveGroup(LeaveGroupRequest request) {
        return futureStub.leaveGroup(request);
    }

    public ListenableFuture<ACK> disconnectUser(User user) {
        DisconnectRequest disconnectRequest = DisconnectRequest.newBuilder()
                .setUserId(user.getId().toString())
                .setToken(user.getAuthToken())
                .build();
        return futureStub.disconnectUser(disconnectRequest);
    }

    // -- סגירת החיבור
    public void shutdown(){
        ChannelManager.getInstance().shutdown();
    }
}

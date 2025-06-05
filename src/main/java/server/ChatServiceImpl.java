package server;

import com.chatFlow.chatGrpc;
import com.chatFlow.Chat.*;
import com.google.common.cache.Cache;
import com.google.protobuf.ByteString;
import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import io.grpc.Status;


import model.*;
import model.MessageStatus;
import security.AES_GCM;
import security.PasswordHasher;
import security.RSA;
import security.Token;
import utils.*;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import static security.AES_ECB.keyGenerator;
import static security.AES_ECB.keySchedule;

/**
 * מימוש שירות ChatService על גבי gRPC המספק את תפקודי הצ'אט הכוללים:
 *  • הרשמה, אימות והתחברות עם OTP
 *  • ניהול Session עם Token
 *  • ניהול והפצת הודעות מוצפנות
 *  • ניהול הזמנות והצטרפות לצ'אט
 *  • יצירת קבוצות, שינוי תפקידים, הוסרה ועזיבה
 *  • אחזור היסטוריית הודעות  ופרטי צ'אט
 */
public class ChatServiceImpl extends chatGrpc.chatImplBase {

    private static final Logger logger = Logger.getLogger(ChatServiceImpl.class.getName());

    // מנהלי מצב
    private final ConnectionManager connectionManager;
    private final Cache<String, OTP_Entry> otpCache;
    private final Cache<String, User> pendingRegistrations;
    private final Cache<String, User> pendingUsers;

    // מנויים להודעות לכל צ'אט
    private final Map<UUID, Map<UUID, StreamObserver<Message>>> subscribers = new ConcurrentHashMap<>();

    // קבועים לניהול נעילות חוזרות
    private static final int BLOCK_SIZE = 16;
    private static final int MAX_FAILED_ATTEMPTS = 3;
    private static final int LOCK_DURATION_MINUTES = 10;

    // DAO לגישה לנתונים
    private final UserDAO userDAO;
    private final MessageDAO messageDAO;
    private final InviteDAO inviteDAO;
    private final ChatRoomDAO chatRoomDAO;
    private final ChatMemberKeyDAO chatMemberKeyDAO;

    /**
     * קונסטרקטור של שירות הצ'אט
     * @param userDAO DAO למשתמשים
     * @param chatRoomDAO DAO לצ'אט רום
     * @param messageDAO DAO להודעות
     * @param inviteDAO DAO להזמנות
     * @param chatMemberKeyDAO DAO למפתחות צ'אט
     * @param connectionManager מנהל החיבורים והסשנים
     * @param otpCache מטמון OTP
     * @param pendingRegistrations מטמון רישומים בהמתנה
     * @param pendingUsers מטמון התחברויות בהמתנה
     */
    public ChatServiceImpl(UserDAO userDAO, ChatRoomDAO chatRoomDAO, MessageDAO messageDAO, InviteDAO inviteDAO, ChatMemberKeyDAO chatMemberKeyDAO
            , ConnectionManager connectionManager
            , Cache<String, OTP_Entry> otpCache, Cache<String, User> pendingRegistrations
            , Cache<String, User> pendingUsers) {
        this.userDAO = userDAO;
        this.chatRoomDAO = chatRoomDAO;
        this.messageDAO = messageDAO;
        this.inviteDAO = inviteDAO;
        this.chatMemberKeyDAO = chatMemberKeyDAO;
        this.connectionManager = connectionManager;
        this.otpCache = otpCache;
        this.pendingRegistrations = pendingRegistrations;
        this.pendingUsers = pendingUsers;
    }


    /**
     * טיפול בהרשמה ראשונית: בדיקה, שליחת OTP, שמירת משתמש בהמתנה
     */
    @Override
    public void register(RegisterRequest request, StreamObserver<ConnectionResponse> responseObserver) {
        try {
            // שליפת הנתונים שהמשתמש הזין
            String username = request.getUsername();
            String email = request.getEmail();
            String password = request.getPassword();

            // 1) basic validation
            ValidationResult vr = User.validate(username, email, password);
            if (!vr.isValid()) {
                respondConnection(responseObserver, false,
                        "Invalid registration data",
                        null,
                        null,
                        vr.getMessages(),
                        null);
                return;
            }

            // 2) email uniqueness
            if (isEmailTaken(email)) {
                logger.warning("Attempted registration with existing email: " + email);
                respondConnection(responseObserver, false,
                        "Invalid registration data",
                        null,
                        null,
                        List.of("Invalid input"),
                        null);
                return;
            }

            // הנפקת פאד חד פעמי ושליחה במייל לאימייל שהתקבל מהמשתמש (3
            String otp = EmailSender.generateOTP();

            if (!EmailSender.sendOTP(email, otp)) {
                respondConnection(responseObserver, false,
                        "Failed to send OTP",
                        null,
                        null,
                        null,
                        null);
                return;
            }

            // שיוך הפאד לאימייל
            otpCache.put(email, new OTP_Entry(email, otp));

            // יצירת משתמש חדש והקמת session key
            User newUser = new User(username, email, password);

            // שמירת המשתמש עם הסיסמה (כדי לפענח מפתח פרטי) ושיוך עם אימייל
            pendingRegistrations.put(email,newUser);

            respondConnection(responseObserver, true,
                    "OTP sent—valid for 5 minutes",
                    null,
                    null,
                    null,
                    null);
        } catch (Exception e) {
            logger.severe("Register error: " + e.getMessage());
            respondConnection(responseObserver, false,
                    "Failed to register user, please try again later",
                    null,
                    null,
                    List.of("Unexpected error"),
                    null);
        }
    }

    /**
     * טיפול בהתחברות בסיסית: אימות סיסמה, נעילה/פתיחה, שליחת OTP
     */
    @Override
    public void login(LoginRequest request, StreamObserver<ConnectionResponse> responseObserver) {
        String email = request.getEmail();
        String password = request.getPassword();

        // 1) basic validation
        ValidationResult vr = User.validate(email, password);
        if (!vr.isValid()) {
            respondConnection(responseObserver, false,
                    "Invalid login data",
                    null,
                    null,
                    vr.getMessages(),
                    null);
            return;
        }

        // 2) fetch & verify credentials
        User user;
        try {
            // שלב 2: שליפת המשתמש מהמסד
            user = userDAO.getUserByEmail(email);
        } catch (SQLException | IOException e) {
            logger.severe("DB error fetching user: " + e);
            respondConnection(responseObserver, false,
                    "Server error",
                    null,
                    null,
                    List.of("Please try again later"),
                    null);
            return;
        }
        if (user == null) {
            respondConnection(responseObserver, false, "Invalid credentials", null, null, List.of("Authentication failed"), null);
            return;
        }

        if (user.isLocked()) {
            responseObserver.onError(Status.PERMISSION_DENIED
                    .withDescription("Account locked until " + user.getLockUntil())
                    .asRuntimeException());
            return;
        }

        if (!PasswordHasher.verify(password, user.getPasswordHash()) || !user.isVerified()) {
            // הגדלת ניסיונות כושלים
            int fails = user.getFailedLogins() + 1;
            user.setFailedLogins(fails);
            if (fails >= MAX_FAILED_ATTEMPTS) {
                user.setLockUntil(Instant.now().plus(LOCK_DURATION_MINUTES, ChronoUnit.MINUTES));
            }
            userDAO.updateUser(user);
            respondConnection(responseObserver, false, "Invalid credentials", null, null, List.of("Authentication failed"), null);
            return;
        }

        user.setFailedLogins(0);
        user.setLockUntil(null);

        // שלב 3: יצירת session key עבור המשתמש
        try {
            // שלב 5: שליחת OTP למייל
            String otp = EmailSender.generateOTP();
            if (!EmailSender.sendOTP(email, otp)) {
                respondConnection(responseObserver, false,
                        "Failed to send OTP",
                        null,
                        null,
                        null,
                        null);
                return;
            }

            otpCache.put(email, new OTP_Entry(email, otp));
            pendingUsers.put(email, user); // נשמור את המשתמש להתחברות לאחר אימות

            respondConnection(responseObserver, true,
                    "OTP sent to your email",
                    null,
                    null,
                    null,
                    null);
        } catch (Exception e) {
            logger.severe("Login session creation error: " + e.getMessage());
            respondConnection(responseObserver, false,
                    "Failed to create session",
                    null,
                    null,
                    List.of("Unexpected error"),
                    null);
        }
    }

    /**
     * אימות OTP להרשמה
     */
    @Override
    public void verifyRegisterOtp(VerifyOtpRequest request, StreamObserver<ConnectionResponse> responseObserver) {
        verifyOtp(request, responseObserver, pendingRegistrations, true);
    }

    /**
     * אימות OTP להתחברות
     */
    @Override
    public void verifyLoginOtp(VerifyOtpRequest request, StreamObserver<ConnectionResponse> responseObserver) {
        verifyOtp(request, responseObserver, pendingUsers, false);
    }

    private void verifyOtp(VerifyOtpRequest request, StreamObserver<ConnectionResponse> responseObserver, Cache<String, User> userCache, boolean isRegistration) {
        String email = request.getEmail();
        String otp = request.getOtp();

        OTP_Entry entry = otpCache.getIfPresent(email);
        User user = userCache.getIfPresent(email);

        // בדיקת תקינות של ה-OTP ושל קיום המשתמש
        if (entry == null || user == null || !entry.isValid(otp)) {
            respondConnection(responseObserver, false,
                    "Invalid or expired OTP",
                    null,
                    null,
                    null,
                    null);
            return;
        }

        try {
            // יצירת טוקן סשן חדש
            Token sessionToken = new Token(user);
            user.setOnline(true);
            user.setLastLogin(Instant.now());

            // אם יש כבר סשן קיים, נבצע ניתוק
            if (connectionManager.isConnected(user.getId())) {
                connectionManager.disconnectUser(user.getId(), "Reconnecting due to OTP verification");
            }


            // ביצוע פעולה שונה לפי אם מדובר בהרשמה או התחברות
            if (isRegistration) {
                // אם מדובר בהרשמה, אנו מאמתים את המשתמש ומאחסנים אותו במאגר
                user.setVerified(true);
                userDAO.createUser(user);  // יצירת משתמש חדש במסד הנתונים
            } else {
                // אם מדובר בהתחברות, מעדכנים את מצב המשתמש
                userDAO.updateUserLoginState(user);  // עדכון מצב התחברות במסד הנתונים
            }

            // הוספת session פעיל
            boolean sessionAdded = connectionManager.addActiveSession(user, sessionToken.getToken(), responseObserver);
            if (!sessionAdded) {
                respondConnection(responseObserver, false,
                        "Failed to add active session",
                        null,
                        null,
                        List.of("Could not add session"),
                        null);
                return;
            }

            // מחיקת המידע על ה-OTP
            otpCache.invalidate(email);
            userCache.invalidate(email);

            // שליחה של תגובת הצלחה
            respondConnection(responseObserver, true,
                    (isRegistration ? "Registration complete" : "Login successful"),
                    sessionToken.getToken(),
                    user.getUsername(),
                    null,
                    user.getId().toString());
        } catch (Exception e) {
            // טיפול בשגיאות בלתי צפויות
            logger.severe("OTP verification error: " + e.getMessage());
            respondConnection(responseObserver, false,
                    "Internal server error",
                    null,
                    null,
                    List.of("Unexpected error"),
                    null);
        }
    }

    /**
     * החזרת פרטי המשתמש הנוכחי לפי טוקן
     */
    @Override
    public void getCurrentUser(VerifyTokenRequest request, StreamObserver<UserResponse> responseObserver) {
        String token = request.getToken();
        if (!Token.verifyToken(token)) {
            responseObserver.onError(Status.UNAUTHENTICATED
                    .withDescription("Invalid token").asRuntimeException());
            return;
        }

        User user = connectionManager.getUserByToken(token);

        if (user == null) {
            responseObserver.onError(Status.UNAUTHENTICATED
                    .withDescription("User not found for token").asRuntimeException());
            return;
        }

        try {
            UserResponse response = UserResponse.newBuilder()
                    .setUserId(user.getId().toString())
                    .setUsername(user.getUsername())
                    .setEmail(user.getEmail())
                    .setPublicKey(Base64.getEncoder().encodeToString(user.getPublicKey()))
                    .setN(Base64.getEncoder().encodeToString(user.getN()))
                    .setSuccess(true)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e){
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Error retrieving user data")
                    .withCause(e)
                    .asRuntimeException());
        }
    }

    /**
     * ניתוק משתמש פעיל
     */
    @Override
    public void disconnectUser(DisconnectRequest request, StreamObserver<ACK> responseObserver) {
        String userIdStr = request.getUserId();
        String token = request.getToken();

        if (token.isEmpty() || userIdStr.isEmpty()) {
            responseObserver.onNext(ACK.newBuilder()
                    .setSuccess(false)
                    .setMessage("Missing token or user ID")
                    .build());
            responseObserver.onCompleted();
            return;
        }

        // בדיקת תקינות טוקן
        if (!Token.verifyToken(token)) {
            responseObserver.onError(
                    Status.UNAUTHENTICATED
                            .withDescription("Invalid or expired token")
                            .asRuntimeException()
            );
            return;
        }
        try {
            UUID userId = UUID.fromString(userIdStr);

            // נסיר את המשתמש אם מחובר
            connectionManager.removeUserFromConnected(userId);

            User user = userDAO.getUserById(userId);

            if(user == null){
                responseObserver.onNext(ACK.newBuilder()
                        .setSuccess(false)
                        .setMessage("User not found")
                        .build());
                responseObserver.onCompleted();
                return;
            }
            user.setOnline(false);
            user.setLastLogin(Instant.now());
            userDAO.updateUserLoginState(user);

            // שליחת תגובה ללקוח
            responseObserver.onNext(ACK.newBuilder()
                    .setSuccess(true)
                    .setMessage("User disconnected successfully")
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e){
            e.printStackTrace();
            responseObserver.onError(
                    Status.INTERNAL
                            .withDescription("Failed to disconnect user")
                            .withCause(e)
                            .asRuntimeException()
            );
        }
    }

    /**
     * שליחת הודעה בצ'אט: אימות טוקן, בדיקת חברות, שמירה, דחיפה למנויים
     */
    @Override
    public void sendMessage(Message request, StreamObserver<ACK> responseObserver) {
        try {
            // 1. אימות טוקן
            String token = request.getToken();
            if (!Token.verifyToken(token)) {
                throw Status.UNAUTHENTICATED.withDescription("Invalid token").asRuntimeException();
            }

            // שליפת שדה הבעלים שמופיע בטוקן
            UUID tokenUserId = Token.extractUserId(token);
            UUID senderId = UUID.fromString(request.getSenderId());

            //בדיקה שהטוקן שסופק שייך למי ששלח את הבקשה
            if (!tokenUserId.equals(senderId)) {
                throw Status.PERMISSION_DENIED.withDescription("Sender ID mismatch").asRuntimeException();
            }

            // 2. בדיקת חברות בצ'אט
            UUID chatId = UUID.fromString(request.getChatId());
            ChatRoom chatRoom = chatRoomDAO.getChatRoomById(chatId);

            // בדיקה שהשולח חבר בצאט
            if (chatRoom == null || !chatRoom.isMember(senderId)) {
                throw Status.PERMISSION_DENIED.withDescription("User not member of chat").asRuntimeException();
            }

            // 3. שמירת ההודעה ב-DB
            Messages message = new Messages(
                    UUID.fromString(request.getMessageId()),
                    chatId,
                    senderId,
                    request.getCipherText().toByteArray(),
                    Instant.ofEpochMilli(request.getTimestamp()),
                    MessageStatus.SENT,
                    request.getIsSystem(),
                    chatRoom.getCurrentKeyVersion()
            );

            messageDAO.saveMessage(message);
            chatRoomDAO.updateLastMessageTime(chatId, message.getTimestamp());

            // 4. עדכון ספירת ההודעות שלא נקראו
            for (ChatMember member : chatRoom.getMembers().values()) {
                if (!member.getUserId().equals(senderId)) {
                    member.incrementUnreadMessages();
                    chatRoomDAO.updateUnreadMessages(chatId, member.getUserId(), member.getUnreadMessages());
                }
            }

            // 5. השב ל-sender
            responseObserver.onNext(ACK.newBuilder().setSuccess(true).build());
            responseObserver.onCompleted();

            // 6. דחיפת ההודעה לכל ה-subscribers
            Map<UUID, StreamObserver<Message>> userObservers = subscribers.get(chatId);
            if (userObservers != null) {
                Message pushed = Message.newBuilder(request)
                        .setKeyVersion(chatRoom.getCurrentKeyVersion())
                        .build();

                for (Map.Entry<UUID, StreamObserver<Message>> entry : userObservers.entrySet()) {
                    UUID userId = entry.getKey();
                    if (!userId.equals(senderId)) {
                        try {
                            entry.getValue().onNext(pushed);
                        } catch (Exception e) {
                            // אם המנוי קרס, צרפו אותו להסרת מנוי
                            entry.getValue().onError(e);
                        }
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
            responseObserver.onError(
                    Status.INTERNAL
                            .withDescription("Error sending message: " + e.getMessage())
                            .asRuntimeException()
            );
        }
    }

    /**
     * הזמנת משתמש לצ'אט: אימות טוקן, הרשאות, הצפנת מפתח, יצירת Invite
     */
    @Override
    public void inviteUser(InviteRequest request, StreamObserver<ACK> responseObserver) {

        // אימות טוקן - בדיקה שתקין
        String token = request.getToken();
        if (!Token.verifyToken(token)) {
            responseObserver.onError(Status.UNAUTHENTICATED
                    .withDescription("Unauthorized: Invalid or expired token")
                    .asRuntimeException());
            return;
        }

        try {
            // שלב 1: המרות ואימותים
            UUID chatId;
            UUID invitedUserId;
            UUID adminId;
            try {
                chatId = UUID.fromString(request.getChatId());
                invitedUserId = UUID.fromString(request.getInvitedUserId());
                adminId = UUID.fromString(request.getAdminId());
            } catch (IllegalArgumentException e) {
                response(responseObserver, false, "Invalid UUID format");
                return;
            }

            Instant sentAt = Instant.ofEpochMilli(request.getTimestamp());

            // בדיקה אם המזמין והמוזמן קיימים במערכת
            User inviter = userDAO.getUserById(adminId);
            User invited = userDAO.getUserById(invitedUserId);
            if (inviter == null || invited == null) {
                response(responseObserver, false, "Inviter or Invited user does not exist");
                return;
            }

            // שליפת המזהה של המשתמש שאליו שייך הטוקן
            UUID userIdFromToken = Token.extractUserId(token);

            // בדיקה אם הטוקן שייך למי שיצר את הבקשה (והצא'ט)
            if (!userIdFromToken.equals(adminId)) {
                responseObserver.onError(
                        Status.UNAUTHENTICATED
                                .withDescription("Token user ID does not match request creator")
                                .asRuntimeException()
                );
                return;
            }

            // שלב 2: בדיקה אם כבר יש הזמנה פתוחה
            if (inviteDAO.isInviteExist(chatId, invitedUserId)) {
                System.err.println("Invite failed: reason: Already invited");
                response(responseObserver, false, "Invite already exists");
                return;
            }

            // שלב 3: בדיקה אם המזמין הוא ADMIN
            ChatRoom chatRoom = chatRoomDAO.getChatRoomById(chatId);
            if (chatRoom == null || !chatRoom.isAdmin(adminId)) {
                response(responseObserver, false, "Only admin can invite users");
                return;
            }

            // שלב 4: בדיקה אם המוזמן כבר חבר בצא'ט
            if (chatRoom.isMember(invitedUserId)) {
                response(responseObserver, false, "User already a member");
                return;
            }

            // המפתח הסימטרי שמור מוצפן עם המפתח הציבורי של המזמין(request.getEncryptedKey().toByteArray())
            // צריך לפענח עם המפתח הפרטי שלו ולשלוח מוצפן עם המפתח הציבורי של המוזמן

            // שליפת המפתח הפרטי
            byte[] privateKey = inviter.getPrivateKey();

            // פענוח המפתח הסימטרי של הקבוצה
            byte[] groupKey = RSA.decrypt(
                    request.getEncryptedKey().toByteArray(),
                    new BigInteger(privateKey),
                    new BigInteger(invited.getN()));

            // איפוס המפתח הפרטי
            Arrays.fill(privateKey, (byte) 0);

            // המפתח הסימטרי של הצאט מוצפן מוצפן במפתח הציבורי וN של המטרה
            byte[] encryptedKey = RSA.encrypt(
                    groupKey,
                    new BigInteger(invited.getPublicKey()),
                    new BigInteger(invited.getN()));
            Arrays.fill(groupKey, (byte) 0);

            // יצירת ההזמה
            Invite invite = new Invite(
                    chatId,
                    adminId,
                    invitedUserId,
                    sentAt,
                    InviteStatus.PENDING,
                    encryptedKey,
                    chatRoom.getCurrentKeyVersion()
            );
            System.out.println("Trying to create invite for Chat: " + chatId + ", User: " + invitedUserId);

            // הודעת אישור במקרה שההזמנה נוצרה
            if (inviteDAO.createInvite(invite)) {
                response(responseObserver, true, "Invite sent");
            } else {
                response(responseObserver, false, "Invite failed");
            }
        } catch (SQLException e) {
            response(responseObserver, false, "SQL error while creating invite: " + e.getMessage());
        }
    }

    /**
     * תגובה להזמנה: קבלה/דחייה, עדכון סטטוס, הוספת חבר, הודעת מערכת
     */
    @Override
    public void respondToInvite(InviteResponse request, StreamObserver<ACK> responseObserver) {
        // שלב 1: אימות טוקן
        String token = request.getToken();
        if (!Token.verifyToken(token)) {
            responseObserver.onError(Status.UNAUTHENTICATED
                    .withDescription("Unauthorized: Invalid or expired token")
                    .asRuntimeException());
            return;
        }

        UUID tokenUserId = Token.extractUserId(token);
        UUID invitedUserId;

        try {
            invitedUserId = tokenUserId;
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.UNAUTHENTICATED
                    .withDescription("Unauthorized: Invalid token user")
                    .asRuntimeException());
            return;
        }

        try {
            UUID inviteId = UUID.fromString(request.getInviteId());
            UUID chatId = UUID.fromString(request.getChatId());

            // שלב 2: בדיקת קיום ההזמנה
            Invite invite = inviteDAO.getInviteById(inviteId);
            if (invite == null) {
                response(responseObserver, false, "Invite does not exist");
                return;
            }

            // שלב 3: בדיקת זהות המשתמש המגיב
            if (!invite.getReceiverId().equals(invitedUserId)) {
                response(responseObserver, false, "Only the invited user can respond to the invite");
                return;
            }

            // שלב 4: עדכון הסטטוס של ההזמנה
            InviteStatus responseStatus = InviteStatus.valueOf(request.getStatus().toString());
            inviteDAO.updateInviteStatus(chatId, invitedUserId, responseStatus);

            String systemText = null;
            // שלב 5: אם ההזמנה התקבלה, הוסף את המשתמש לצ'אט
            if (responseStatus == InviteStatus.ACCEPTED) {
                ChatRoom chatRoom = chatRoomDAO.getChatRoomById(chatId);
                if (chatRoom == null) {
                    response(responseObserver, false, "Chat not found");
                    return;
                }

                User invitedUser = userDAO.getUserById(invitedUserId);
                if (invitedUser == null) {
                    response(responseObserver, false, "Invited user not found");
                    return;
                }

                // הוספת המשתמש כחבר בצ'אט
                chatRoomDAO.addMember(invite.getSenderId(), chatRoom, invitedUserId);

                chatMemberKeyDAO.insertKey(
                        chatId,
                        invitedUserId,
                        invite.getKeyVersion(),
                        invite.getEncryptedKey()
                );

                // עדכון המשתמש בהצטרפות לצ'אט
                invitedUser.addChat(chatId);
                userDAO.updateUser(invitedUser);
                DriverService.shareFolderWithUser(chatRoomDAO.getChatRoomById(chatId).getFolderId(), invitedUser.getEmail());
                systemText = "ההזמנה אושרה: " + invitedUser.getUsername() + " הצטרף לקבוצה";
            } else {
                systemText = "ההזמנה " + (responseStatus == InviteStatus.DECLINED ? "נדחתה" : "עדכנה ל-" + responseStatus);
            }

            // שליחת תשובה חיובית
            response(responseObserver, true, "Invite status updated to " + responseStatus);

            Map<UUID, StreamObserver<Message>> userObservers = subscribers.get(chatId);

            if (userObservers != null && systemText != null) {
                // בונים את המסר
                Message sys = Message.newBuilder()
                        .setMessageId(UUID.randomUUID().toString())
                        .setSenderId(request.getInviterUserId())
                        .setChatId(chatId.toString())
                        .setCipherText(ByteString.copyFrom(systemText.getBytes(StandardCharsets.UTF_8)))
                        .setTimestamp(Instant.now().toEpochMilli())
                        .setIsSystem(true)
                        .setStatus(com.chatFlow.Chat.MessageStatus.SENT)
                        .setKeyVersion(invite.getKeyVersion())
                        .build();

                // דוחף לכל המנויים
                for (StreamObserver<Message> obs : userObservers.values()) {
                    try {
                        obs.onNext(sys);
                    } catch (Exception ignore) {
                        // מנוי מת — אפשר להסיר אותו
                    }
                }
            }
        } catch (Exception e) {
            // טיפול בשגיאות
            System.err.println("Error in respondToInvite: " + e.getMessage());
            response(responseObserver, false, "Internal server error");
        }
    }

    /**
     * נרשמ/ת למנוי הודעות: אימות טוקן, בדיקת חברות, רישום ה-stream
     */
    @Override
    public void subscribeMessages(ChatSubscribeRequest request, StreamObserver<Message> responseObserver) {

        // 1. אימות טוקן
        String token = request.getToken();
        if (!Token.verifyToken(token)) {
            responseObserver.onError(
                    Status.UNAUTHENTICATED
                            .withDescription("Invalid or missing token")
                            .asRuntimeException()
            );
            return;
        }

        UUID userId = Token.extractUserId(token);

        // 2. המרת chatId ובדיקת חברות בחדר
        UUID chatId  = UUID.fromString(request.getChatId());
        ChatRoom room;
        try {
            room = chatRoomDAO.getChatRoomById(chatId);
        } catch (SQLException e) {
            responseObserver.onError(
                    Status.INTERNAL
                            .withDescription("Database error: " + e.getMessage())
                            .withCause(e)
                            .asRuntimeException()
            );
            return;
        }

        if (room == null || !room.isMember(userId)) {
            responseObserver.onError(Status.PERMISSION_DENIED.asRuntimeException());
            return;
        }

        // 3. רישום ה-StreamObserver למנויים
        subscribers
                .computeIfAbsent(chatId, id -> new ConcurrentHashMap<>())
                .put(userId, responseObserver);

        // הסרת המנוי אוטומטית כשלקוח נותק
        Context.current().addListener(ctx -> {
            Map<UUID, StreamObserver<Message>> map = subscribers.get(chatId);
            if (map != null) {
                map.remove(userId);
            }
        }, Runnable::run);

    }

    /**
     * אחזור היסטוריית הודעות עם pagination על בסיס הודעות שלא נקראו
     */
    @Override
    public void getChatHistory(ChatHistoryRequest request, StreamObserver<ChatHistoryResponse> responseObserver) {
        // אימות טוקן - בדיקה שתקין
        String token = request.getToken();
        if (!Token.verifyToken(token)) {
            responseObserver.onError(Status.UNAUTHENTICATED
                    .withDescription("Unauthorized: Invalid or expired token")
                    .asRuntimeException());
            return;
        }

        // שליפת שדה הבעלים שמופיע בטוקן
        UUID tokenUserId = Token.extractUserId(token);
        UUID requesterId;

        //בדיקה שהמשתמש תקין
        try {
            requesterId = UUID.fromString(request.getRequesterId());
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("Unauthorized: Invalid sender Id")
                    .asRuntimeException());
            return;
        }

        //בדיקה שהטוקן שסופק שייך למי ששלח את הבקשה
        if (!tokenUserId.equals(requesterId)) {
            responseObserver.onError(Status.PERMISSION_DENIED
                    .withDescription("Unauthorized:Token does not match sender")
                    .asRuntimeException());
            return;
        }

        // המרת מזהה לצורך שליפה
        UUID chatUUID;
        try {
            chatUUID = UUID.fromString(request.getChatId());
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                            .withDescription("Invalid chatId format")
                            .asRuntimeException()
            );
            return;
        }

        try {
            ChatRoom chatRoom = chatRoomDAO.getChatRoomById(chatUUID);

            if (chatRoom == null || !chatRoom.isMember(requesterId)) {
                responseObserver.onError(Status.PERMISSION_DENIED
                        .withDescription("Unauthorized: User not member of the chat")
                        .asRuntimeException());
                return;
            }

            ChatMember member = chatRoom.getMembers().get(requesterId);
            if (member == null) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("Chat member not found")
                        .asRuntimeException());
                return;
            }

            int totalMessages = messageDAO.countMessagesInChat(chatUUID);
            int unread = member.getUnreadMessages();
            int limit = request.getLimit(); // לרוב 100
            int offset = unread > limit ?
                    totalMessages - unread : // יש הרבה שלא נקראו - מביאים מההתחלה שלהם
                    Math.max(0, totalMessages - limit); // אין הרבה שלא נקראו - מביאים את האחרונות

            List<Messages> chatMessages = messageDAO.getMessagesByChatId(chatUUID, requesterId, limit, offset);

            // מיון בסדר עולה לפי זמן
            chatMessages.sort(Comparator.comparing(Messages::getTimestamp));

            ChatHistoryResponse.Builder historyBuilder = ChatHistoryResponse.newBuilder();

            for (Messages msg : chatMessages) {
                Message message = Message.newBuilder()
                        .setMessageId(msg.getMessageId().toString())
                        .setSenderId(msg.getSenderId().toString())
                        .setChatId(msg.getChatId().toString())
                        .setCipherText(ByteString.copyFrom(msg.getContent()))
                        .setTimestamp(msg.getTimestamp().toEpochMilli())
                        .setToken(request.getToken())
                        .setStatus(com.chatFlow.Chat.MessageStatus.valueOf(msg.getStatus().name()))
                        .setIsSystem(msg.getIsSystem())
                        .setKeyVersion(msg.getKeyVersion())
                        .build();
                historyBuilder.addMessages(message);
            }

            // אפס את המונה רק אם המשתמש קיבל את כל ההודעות שלא נקראו
            if (unread <= limit) {
                member.clearUnreadMessages();
                chatRoomDAO.updateUnreadMessages(chatUUID, requesterId, 0);
            }

            // שלח ללקוח
            responseObserver.onNext(historyBuilder.build());
            responseObserver.onCompleted();

        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Failed to retrieve messages: " + e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        }
    }

    /**
     * יצירת צ'אט קבוצתי: אימות טוקן, בדיקת שם, יצירת מפתח, Google Drive, שמירה והזמנות
     */
    @Override
    public void createGroupChat(CreateGroupRequest request, StreamObserver<GroupChat> responseObserver) {
        try {
            // 1. אימות טוקן
            String token = request.getToken();
            if (!Token.verifyToken(token)) {
                responseObserver.onError(Status.UNAUTHENTICATED
                        .withDescription("Invalid or expired token")
                        .asRuntimeException()
                );
                return;
            }

            // 2. בדיקת יוצר הקבוצה
            UUID creatorId = UUID.fromString(request.getCreatorId());
            User creator = userDAO.getUserById(creatorId);

            if (creator == null) {
                respondFailure(responseObserver, "Creator user not found");
                return;
            }

            // בדיקה אם הטוקן שייך למי שיצר את הבקשה (והצא'ט)
            if (!Token.extractUserId(token).equals(creatorId)) {
                responseObserver.onError(Status.PERMISSION_DENIED
                        .withDescription("Token does not match creator")
                        .asRuntimeException()
                );
                return;
            }

            // 3. שם הקבוצה
            String groupName = request.getGroupName().trim();
            if (groupName.isEmpty() || groupName.length() > 100) {
                respondFailure(responseObserver, "Invalid group name");
                return;
            }

            // 4. מזהה חדש לחדר
            UUID chatId = UUID.randomUUID();

            // 5. יצירת מפתח סימטרי
            byte[] SymmetricKey = keyGenerator();

            // 6. יצירת תיקייה בגוגל דרייב
            String folderId;
            try {
                folderId = DriverService.createGroupFolder(groupName);
            } catch (Exception e){
                responseObserver.onError(Status.INTERNAL
                        .withDescription("Failed to create Google Drive folder: " + e.getMessage())
                        .asRuntimeException());
                return;
            }

            // 7. שמירת ChatRoom ו-ChatMember היוצר
            ChatRoom chatRoom = new ChatRoom(chatId, groupName, creatorId, Instant.now(), folderId, null);
            chatRoom.setCurrentKeyVersion(1);
            chatRoomDAO.createChatRoom(chatRoom);

            chatRoomDAO.addCreator(creatorId, chatRoom);

            // 8. הצפנת ושמירת מפתח היוצר בטבלת ChatMemberKeys
            {
                byte[] publicKey = creator.getPublicKey();
                byte[] n = creator.getN();
                byte[] encryptedKey = RSA.encrypt(
                        SymmetricKey,
                        new BigInteger(1, publicKey),
                        new BigInteger(1, n)
                );
                chatMemberKeyDAO.insertKey(
                        chatId,
                        creatorId,
                        1,
                        encryptedKey);
                Arrays.fill(encryptedKey, (byte) 0);
            }

            // 9. הוספת היוצר במודל בזיכרון ועדכון משתמש
            chatRoom.addMember(new ChatMember(chatId, creatorId, ChatRole.ADMIN, chatRoom.getCreatedAt(), InviteStatus.ACCEPTED));
            creator.addChat(chatId);
            userDAO.updateUser(creator);

            // 10. הזמנת שאר המשתמשים
            for(String memberIdStr : request.getMembersIdList()){
                UUID memberId = UUID.fromString(memberIdStr);

                if (memberId.equals(creatorId)) continue;

                User invitedUser = userDAO.getUserById(memberId);
                if (invitedUser == null) continue;

                byte[] encKey;
                try {
                    byte[] publicKey = invitedUser.getPublicKey();
                    byte[] n = invitedUser.getN();
                    encKey = RSA.encrypt(SymmetricKey,
                            new BigInteger(1, publicKey),
                            new BigInteger(1, n)
                    );
                    Invite invite = new Invite(
                            chatId,
                            creatorId,
                            memberId,
                            Instant.now(),
                            InviteStatus.PENDING,
                            encKey,
                            1
                    );
                    inviteDAO.createInvite(invite);
                    Arrays.fill(encKey, (byte)0);
                } catch (Exception ignore) {
                    // במקרה של שגיאה בהצפנה/יצירת ההזמנה ניתן להתקדם הלאה
                }
            }

            // 11. ניקוי המפתח הסימטרי מהזיכרון
            Arrays.fill(SymmetricKey, (byte) 0);

            // 12. תגובה ללקוח
            GroupChat response = GroupChat.newBuilder()
                    .setSuccess(true)
                    .setGroupName(groupName)
                    .setChatId(chatId.toString())
                    .setMessage("Group chat created successfully")
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            responseObserver.onError(
                    Status.INTERNAL
                            .withDescription("Server error: " + e.getMessage())
                            .asRuntimeException()
            );

        }
    }

    /**
     * הסרת משתמש מתוך קבוצה (admin בלבד)
     */
    @Override
    public void removeUserFromGroup(RemoveUserRequest request, StreamObserver<ACK> responseObserver) {
        try {
            // שליפת הטוקן מהבקשה
            String token = request.getToken();
            // אימות טוקן - בדיקה אם הוא תקף ונכון
            if (!Token.verifyToken(token)) {
                responseObserver.onError(
                        Status.UNAUTHENTICATED
                                .withDescription("Invalid or expired token")
                                .asRuntimeException()
                );
                return;
            }
            // שליפת המזהה של המצרף הצא'ט
            UUID adminId = UUID.fromString(request.getAdminId());

            // שליפת המזהה של המשתמש שאליו שייך הטוקן
            UUID userIdFromToken = Token.extractUserId(token);

            // בדיקה אם הטוקן שייך למי שיצר את הבקשה (והצא'ט)
            if (!userIdFromToken.equals(adminId)) {
                responseObserver.onError(
                        Status.UNAUTHENTICATED
                                .withDescription("Token user ID does not match request creator")
                                .asRuntimeException()
                );
                return;
            }
            // שליפת הפרטים מהבקשה
            UUID chatId = UUID.fromString(request.getChatId());
            UUID targetId = UUID.fromString(request.getTargetUserId());

            // שליפת הצא'ט מBD
            ChatRoom chatRoom = chatRoomDAO.getChatRoomById(chatId);

            // בדיקה שהצא'ט קיים
            if (chatRoom == null) {
                response(responseObserver, false, "Chat not found");
                return;
            }

            // בדיקה שהמבקש הוא ADMIN (בעל הרשאות גישה)
            if (!chatRoom.isAdmin(adminId)) {
                response(responseObserver, false, "Only admins can remove members");
                return;
            }

            // בדיקה שהמטרה נמצאת ברשימה המשתתפים של הצא'ט
            if (!chatRoom.isMember(targetId)) {
                response(responseObserver, false, "User is not a member");
                return;
            }

            // הגנה: מניעת מצב שבו אין ADMIN
            if (chatRoom.isAdmin(targetId)) {
                long adminCount = chatRoom.getMembers().values().stream()
                        .filter(m -> m.getRole() == ChatRole.ADMIN)
                        .count();
                if (adminCount <= 1) {
                    response(responseObserver, false, "Cannot remove the last admin");
                    return;
                }
            }

            // 1. שליפת המשתמש (לפני הסרה)
            User targetUser = userDAO.getUserById(targetId);
            if (targetUser == null) {
                response(responseObserver, false, "Target user not found");
                return;
            }

            // 2. גיבוי תיקייה
            String archiveFolderId = DriverService.createPrivateFolderForUser(targetUser.getEmail(), chatRoom.getName());
            DriverService.copyFilesFromFolder(chatRoom.getFolderId(), archiveFolderId);
            DriverService.removeUserFromFolder(chatRoom.getFolderId(), targetUser.getEmail());

            // 3.הסרה מהחדר וה-DB
            chatRoom.removeMember(adminId, targetId);
            chatRoomDAO.removeMember(adminId, chatId, targetId);
            targetUser.removeChat(chatId);
            userDAO.updateUser(targetUser);

            // כולם צריכים להחליף סיסמא כדי למנוע "עבודה מבפנים"
            regenerateGroupKey(chatRoom);

            response(responseObserver, true, "User removed from the group. Everyone have the updated key");

        } catch (Exception e) {
            response(responseObserver, false, "Failed to remove user: " + e.getMessage());
        }
    }

    /**
     * עזיבת קבוצה על ידי המשתמש ללא צורך ב-admin
     */
    @Override
    public void leaveGroup(LeaveGroupRequest request, StreamObserver<ACK> responseObserver) {
        String token = request.getToken();
        if (!Token.verifyToken(token)) {
            responseObserver.onError(Status.UNAUTHENTICATED.withDescription("Invalid token").asRuntimeException());
            return;
        }

        UUID tokenUserId = Token.extractUserId(token);
        UUID userId = UUID.fromString(request.getUserId());
        UUID chatId = UUID.fromString(request.getChatId());

        if (!tokenUserId.equals(userId)) {
            responseObserver.onError(Status.PERMISSION_DENIED.withDescription("User can only leave on their behalf").asRuntimeException());
            return;
        }
        try {
            ChatRoom chatRoom = chatRoomDAO.getChatRoomById(chatId);
            if (chatRoom == null || !chatRoom.isMember(userId)) {
                response(responseObserver, false, "Chat not found or user not a member");
                return;
            }

            User user = userDAO.getUserById(userId);
            if (user == null) {
                response(responseObserver, false, "User not found");
                return;
            }

            // 1) ארכיון התיקייה
            String archiveFolderId = DriverService.createPrivateFolderForUser(user.getEmail(), chatRoom.getName());
            DriverService.copyFilesFromFolder(chatRoom.getFolderId(), archiveFolderId);
            DriverService.removeUserFromFolder(chatRoom.getFolderId(), user.getEmail());

            // 2) רענון מפתח לפני הסרת המשתמש
            regenerateGroupKey(chatRoom);

            // 3) הסרת המשתמש מן הצ׳אט והמסד בלי דרישת אדמין
            chatRoom.getMembers().remove(userId);
            chatRoomDAO.removeMember(userId, chatId, userId);

            user.removeChat(chatId);
            userDAO.updateUser(user);

            response(responseObserver,
                    true,
                    "User left the group"
            );
        } catch (Exception e){
            response(responseObserver,
                    false,
                    "Server error: " + e.getMessage()
            );
        }
    }

    /**
     * שינוי תפקיד משתמש בקבוצה (admin בלבד)
     */
    @Override
    public void changeUserRole(ChangeUserRoleRequest request, StreamObserver<ACK> responseObserver) {
        String token = request.getToken();
        if (!Token.verifyToken(token)) {
            responseObserver.onError(Status.UNAUTHENTICATED.withDescription("Invalid token").asRuntimeException());
            return;
        }

        UUID requesterId = UUID.fromString(request.getRequesterId());
        UUID targetId = UUID.fromString(request.getTargetId());
        UUID chatId = UUID.fromString(request.getChatId());
        String newRole = request.getNewRole().name();

        // אימות טוקן תואם למבקש
        if (!requesterId.equals(Token.extractUserId(token))) {
            responseObserver.onError(Status.PERMISSION_DENIED.withDescription("Invalid user").asRuntimeException());
            return;
        }
        try {
            ChatRoom chatRoom = chatRoomDAO.getChatRoomById(chatId);
            if (chatRoom == null || !chatRoom.isAdmin(requesterId)) {
                response(responseObserver, false, "Only admins can change roles");
                return;
            }

            if (!chatRoom.isMember(targetId)) {
                response(responseObserver, false, "Target user is not a member");
                return;
            }

            if (requesterId.equals(targetId)) {
                response(responseObserver, false, "You cannot change your own role");
                return;
            }

            ChatRole requestedRole = ChatRole.fromString(newRole);
            // בדיקה שלא מורידים את המנהל האחרון
            if (requestedRole == ChatRole.MEMBER) {
                long adminCount = chatRoom.getMembers().values().stream()
                        .filter(m -> m.getRole() == ChatRole.ADMIN)
                        .count();
                // אם המשתמש הוא המנהל היחיד, נאסור הורדה
                if (adminCount <= 1 && chatRoom.isAdmin(targetId)) {
                    response(responseObserver, false, "Cannot demote the only admin");
                    return;
                }
            }

            // בצוע העדכון בזיכרון וב־DB
            chatRoom.getMembers().get(targetId).setRole(ChatRole.fromString(newRole));
            chatRoomDAO.updateRole(requesterId, chatId, targetId, newRole);

            response(responseObserver, true, "Role changed to " + newRole);

        } catch (Exception e) {
            response(responseObserver, false, "Server error: " + e.getMessage());
        }
    }

    /**
     * רענון טוקן: אימות טוקן קיים, יצירת טוקן חדש והחלפה
     */
    @Override
    public void refreshToken(RefreshTokenRequest request, StreamObserver<RefreshTokenResponse> responseObserver) {

        // 1) אימות טוקן
        String oldToken = request.getToken();
        if(!Token.verifyToken(oldToken)) {
            responseObserver.onNext(RefreshTokenResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Old token invalid or expired")
                    .build());
            responseObserver.onCompleted();
            return;
        }

        // 2) מצא את המשתמש
        UUID userId = Token.extractUserId(oldToken);
        User user;
        try {
            user = userDAO.getUserById(userId);
        } catch (SQLException e) {
            responseObserver.onNext(RefreshTokenResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Internal server error")
                    .build());
            responseObserver.onCompleted();
            return;
        }

        if(user == null) {
            responseObserver.onNext(RefreshTokenResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("User not found")
                    .build());
            responseObserver.onCompleted();
            return;
        }

        // 3) צור טוקן חדש, שמור ב־DB וב־ConnectionManager
        Token newToken = new Token(user);
        connectionManager.updateAuthToken(userId, oldToken, newToken.getToken());

        // 4) החזר ללקוח
        responseObserver.onNext(
                RefreshTokenResponse.newBuilder()
                .setSuccess(true)
                .setNewToken(newToken.getToken())
                .build());
        responseObserver.onCompleted();
    }

    // -------------------------------------------------------------------------------------------------

    /**
     * אחזור פרטי צ'אט: אימות טוקן, בדיקת חברות, החזרת מבנה ChatRoomResponse
     */
    @Override
    public void getChatRoom(ChatRoomRequest request, StreamObserver<ChatRoomResponse> responseObserver) {
        try {
            String token = request.getToken();
            if (!Token.verifyToken(token)) {
                responseObserver.onError(Status.UNAUTHENTICATED
                        .withDescription("Invalid or expired token")
                        .asRuntimeException());
                return;
            }

            UUID requesterId = UUID.fromString(request.getRequesterId());
            UUID tokenId = Token.extractUserId(token);

            if (!tokenId.equals(requesterId)) {
                responseObserver.onError(Status.PERMISSION_DENIED
                        .withDescription("Token mismatch")
                        .asRuntimeException());
                return;
            }

            UUID chatId = UUID.fromString(request.getChatId());
            ChatRoom chatRoom = chatRoomDAO.getChatRoomById(chatId);

            if (chatRoom == null) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("Chat not found")
                        .asRuntimeException());
                return;
            }

            // שליפת חבר הצ'אט (המשתמש ששולף את הצ'אט)
            ChatMember requester = chatRoomDAO.getChatMember(chatId, requesterId);

            // אם המשתמש אינו חבר בצ'אט
            if(requester == null){
                // בדיקת הזמנה PENDING
                Invite invite = inviteDAO.getInvite(chatId, requesterId);

                if (invite == null || invite.getStatus() != InviteStatus.PENDING) {
                    responseObserver.onError(Status.PERMISSION_DENIED
                            .withDescription("Requester is not a member of the chat and has no pending invite")
                            .asRuntimeException());
                    return;
                }

                // במקרה של הזמנה ממתינה, נאפשר לראות מידע בסיסי על הצ'אט
                ChatRoomResponse.Builder builder = ChatRoomResponse.newBuilder()
                        .setChatId(chatRoom.getChatId().toString())
                        .setName(chatRoom.getName())
                        .setOwnerId(chatRoom.getCreatedBy().toString())
                        .setCreatedAt(chatRoom.getCreatedAt().toString());

                responseObserver.onNext(builder.build());
                responseObserver.onCompleted();
                return;
            }

            // אם המשתמש חבר בצ'אט, הוא מקבל את כל פרטי הצ'אט
            ChatRoomResponse.Builder builder = ChatRoomResponse.newBuilder()
                    .setChatId(chatRoom.getChatId().toString())
                    .setName(chatRoom.getName())
                    .setOwnerId(chatRoom.getCreatedBy().toString())
                    .setCreatedAt(chatRoom.getCreatedAt().toString())
                    .setFolderId(chatRoom.getFolderId())
                    .setKeyVersion(chatRoom.getCurrentKeyVersion());

            for (ChatMember member : chatRoom.getMembers().values()) {
                builder.addMembers(ChatMemberInfo.newBuilder()
                        .setUserId(member.getUserId().toString())
                        .setRole(member.getRole().name())
                        .setInviteStatus(member.getInviteStatus().name())
                        .setJoinDate(member.getJoinDate().toEpochMilli())
                        .setUnreadMessages(member.getUnreadMessages())
                        .setActive(member.isActive())
                        .build());
            }

            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();

        } catch (Exception e){
            e.printStackTrace();
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Server error: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    /**
     * אחזור מפתח סימטרי מפורש למשתמש
     */
    @Override
    public void getSymmetricKey(MemberRequest request, StreamObserver<SymmetricKey> responseObserver){
        try {
            String token = request.getToken();
            if (!Token.verifyToken(token)) {
                responseObserver.onError(Status.UNAUTHENTICATED
                        .withDescription("Invalid or expired token")
                        .asRuntimeException());
                return;
            }

            UUID requesterId = UUID.fromString(request.getUserId());
            UUID userId = Token.extractUserId(token);
            if (!userId.equals(requesterId)) {
                responseObserver.onError(Status.PERMISSION_DENIED
                        .withDescription("Token mismatch")
                        .asRuntimeException());
                return;
            }
            UUID chatId = UUID.fromString(request.getChatId());
            ChatRoom chatRoom = chatRoomDAO.getChatRoomById(chatId);

            if (chatRoom == null) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("Chat not found")
                        .asRuntimeException());
                return;
            }

            // שליפת חבר הצ'אט (המשתמש ששולף את הצ'אט)
            ChatMember requester;
            try {
                requester = chatRoomDAO.getChatMember(chatId, requesterId);
                if (requester == null) {
                    responseObserver.onError(Status.PERMISSION_DENIED
                            .withDescription("Requester is not a member of the chat")
                            .asRuntimeException());
                    return;
                }
            } catch (SQLException e) {
                responseObserver.onError(Status.INTERNAL
                        .withDescription("Database error while fetching chat member")
                        .withCause(e)
                        .asRuntimeException());
                return;
            } catch (Exception e) {
                responseObserver.onError(Status.PERMISSION_DENIED
                        .withDescription("Requester is not found")
                        .asRuntimeException());
                return;
            }

            if (!chatRoom.isMember(requesterId) && !requester.canAccess()) {
                responseObserver.onError(Status.PERMISSION_DENIED
                        .withDescription("User not a member of this chat")
                        .asRuntimeException());
                return;
            }
            User user = userDAO.getUserById(requesterId);
            int version = request.getKeyVersion();
            byte[] encryptedSymmetricKey = chatMemberKeyDAO.getEncryptedKey(chatId, userId, version);  // המפתח המוצפן שנשלח מהשרת

            if (encryptedSymmetricKey == null || encryptedSymmetricKey.length == 0) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("No symmetric key found for the specified version")
                        .asRuntimeException());
                return;
            }

            byte[] privateKey = user.getPrivateKey();
            byte[] n = user.getN();
            byte[] fullDecrypted;
            try {
                fullDecrypted = RSA.decrypt(
                        encryptedSymmetricKey,
                        new BigInteger(1, privateKey),
                        new BigInteger(1, n)
                );
            } catch (Exception e) {
                responseObserver.onError(Status.INTERNAL
                        .withDescription("Failed to decrypt symmetric key")
                        .asRuntimeException());
                return;
            }
            byte[] symmetricKey = Arrays.copyOfRange(
                    fullDecrypted,
                    fullDecrypted.length - BLOCK_SIZE,
                    fullDecrypted.length
            );

            Arrays.fill(privateKey, (byte)0);
            Arrays.fill(n, (byte)0);

            SymmetricKey.Builder symmetricKeyBuilder = SymmetricKey.newBuilder();
            symmetricKeyBuilder.setSymmetricKey(ByteString.copyFrom(symmetricKey));

            // ניקוי המפתח הסימטרי לאחר שליחה
            Arrays.fill(fullDecrypted, (byte) 0);  // מוודא שהמפתח לא נשאר בזיכרון

            // שליחת התשובה עם המפתח המפוענח
            responseObserver.onNext(symmetricKeyBuilder.build());
            responseObserver.onCompleted();

        } catch (Exception e){
            e.printStackTrace();
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Server error: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    /**
     * איתור משתמש לפי אימייל (לבודק קיום)
     */
    @Override
    public void getUserByEmail(UserEmailRequest request, StreamObserver<UserResponse> responseObserver) {
        String token = request.getToken();
        if (!Token.verifyToken(token)) {
            responseObserver.onError(Status.UNAUTHENTICATED
                    .withDescription("Invalid or expired token")
                    .asRuntimeException());
            return;
        }

        try {
            User user = userDAO.getUserByEmail(request.getEmail());
            if (user == null) {
                responseObserver.onNext(UserResponse.newBuilder()
                        .setSuccess(false)
                        .build());
            } else {
                UserResponse response = UserResponse.newBuilder()
                        .setUserId(user.getId().toString())
                        .setEmail(user.getEmail())
                        .setUsername(user.getUsername())
                        .setSuccess(true)
                        .build();
                responseObserver.onNext(response);
            }
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Failed to retrieve user")
                    .withCause(e)
                    .asRuntimeException());
        }
    }

    /**
     * איתור משתמש לפי מזהה
     */
    @Override
    public void getUserById(UserIdRequest request, StreamObserver<UserResponse> responseObserver) {
        String token = request.getToken();

        // שלב 1: אימות טוקן
        if (!Token.verifyToken(token)) {
            responseObserver.onError(Status.UNAUTHENTICATED
                    .withDescription("Invalid or expired token")
                    .asRuntimeException());
            return;
        }

        try {
            User user = userDAO.getUserById(UUID.fromString(request.getUserId()));
            if (user == null) {
                responseObserver.onNext(UserResponse.newBuilder().setSuccess(false).build());
            } else {
                UserResponse response = UserResponse.newBuilder()
                        .setUserId(user.getId().toString())
                        .setEmail(user.getEmail())
                        .setUsername(user.getUsername())
                        .setSuccess(true)
                        .build();
                responseObserver.onNext(response);
            }
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Failed to retrieve user")
                    .withCause(e)
                    .asRuntimeException());
        }
    }

    /**
     * רשימת ההזמנות של משתמש
     */
    @Override
    public void getUserInvites(UserIdRequest request, StreamObserver<InviteListResponse> responseObserver) {
        if (!Token.verifyToken(request.getToken())) {
            responseObserver.onError(Status.UNAUTHENTICATED
                    .withDescription("Unauthorized: Invalid token")
                    .asRuntimeException());
            return;
        }

        UUID userId;
        try {
            userId = UUID.fromString(request.getUserId());
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("Invalid UUID format")
                    .asRuntimeException());
            return;
        }
        try {
            // שליפת כל ההזמנות של המשתמש
            ArrayList<Invite> invites = inviteDAO.getUserInvites(userId);
            if (invites.isEmpty()) {
                // אם אין הזמנות, שלח הודעת תשובה ריקה
                responseObserver.onNext(InviteListResponse.getDefaultInstance());
                responseObserver.onCompleted();
                return;
            }

            // אם יש הזמנות, בנה את התשובה
            InviteListResponse.Builder builder = InviteListResponse.newBuilder();
            for (Invite invite : invites) {
                ProtoInvite protoInvite = ProtoInvite.newBuilder()
                        .setInviteId(invite.getInviteId().toString())
                        .setChatId(invite.getChatId().toString())
                        .setSenderId(invite.getSenderId().toString())
                        .setInvitedUserId(invite.getReceiverId().toString())
                        .setStatus(InviteResponseStatus.valueOf(invite.getStatus().name()))
                        .setTimestamp(invite.getSentAt().toEpochMilli())
                        .build();
                builder.addInvites(protoInvite);
            }
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Failed to get invites")
                    .withCause(e)
                    .asRuntimeException());
        }
    }

    /**
     * רשימת הצ'אטים של משתמש
     */
    @Override
    public void getUserChatRooms(UserIdRequest request, StreamObserver<ChatRoomResponseList> responseObserver) {
        String token = request.getToken();
        if (!Token.verifyToken(token)) {
            responseObserver.onError(Status.UNAUTHENTICATED.withDescription("Invalid token").asRuntimeException());
            return;
        }

        UUID tokenUserId = Token.extractUserId(token);
        UUID userId = UUID.fromString(request.getUserId());

        if (!tokenUserId.equals(userId)) {
            responseObserver.onError(Status.PERMISSION_DENIED.withDescription("User can only leave on their behalf").asRuntimeException());
            return;
        }

        try {
            List<ChatRoom> rooms = chatRoomDAO.getAllChatRooms(userId);

            ChatRoomResponseList.Builder responseListBuilder = ChatRoomResponseList.newBuilder();

            for (ChatRoom room : rooms) {
                ChatRoomResponse.Builder roomBuilder = ChatRoomResponse.newBuilder()
                        .setChatId(room.getChatId().toString())
                        .setName(room.getName())
                        .setOwnerId(room.getCreatedBy().toString())
                        .setCreatedAt(room.getCreatedAt().toString())
                        .setFolderId(room.getFolderId())
                        .setKeyVersion(room.getCurrentKeyVersion());

                for (ChatMember member : room.getMembers().values()) {
                    roomBuilder.addMembers(ChatMemberInfo.newBuilder()
                            .setUserId(member.getUserId().toString())
                            .setRole(member.getRole().name())
                            .setInviteStatus(member.getInviteStatus().name())
                            .setJoinDate(member.getJoinDate().toEpochMilli())
                            .setUnreadMessages(member.getUnreadMessages())
                            .setActive(member.isActive())
                            .build());
                }

                responseListBuilder.addRooms(roomBuilder.build());
            }

            responseObserver.onNext(responseListBuilder.build());
            responseObserver.onCompleted();

        } catch (Exception e) {
            e.printStackTrace();
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Failed to load user chat rooms")
                    .withCause(e)
                    .asRuntimeException());
        }
    }

    /**
     * בניית תגובה פשוטה של ACK
     */
    private void response(StreamObserver<ACK> responseObserver, boolean success, String message) {
        responseObserver.onNext(
                ACK.newBuilder()
                        .setSuccess(success)
                        .setMessage(message)
                        .build()
        );
        responseObserver.onCompleted();
    }

    /**
     * יצירת מפתח סימטרי חדש לצ'אט באמצעות AES-GCM-128
     */
    private byte[] generateSymmetricKey(UUID chatId, UUID creatorId, byte[] raw) {
        // יצירת המפתח הסימטרי של הצאט באמצעות AES-GCM-128
        byte[][] roundKeys = new byte[11][BLOCK_SIZE];
        roundKeys[0] = raw;
        keySchedule(roundKeys);
        byte[] aad = (chatId.toString() + creatorId + LocalDateTime.now()).getBytes(StandardCharsets.UTF_8);
        byte[] cipher = AES_GCM.encrypt(roundKeys[0], aad, roundKeys);
        for (byte[] rk : roundKeys)
            Arrays.fill(rk, (byte)0);
        return cipher;

    }

    /**
     * רענון מפתח קבוצתי כאשר משתמש מוסר/עזב
     */
    private void regenerateGroupKey(ChatRoom chatRoom) throws Exception {
        UUID chatId = chatRoom.getChatId();

        // 0. העלאת גרסת המפתח
        int newVersion = chatRoom.getCurrentKeyVersion() + 1;
        chatRoom.setCurrentKeyVersion(newVersion);
        chatRoomDAO.updateKeyVersion(chatId, newVersion);

        byte[] raw = keyGenerator();
        for(ChatMember member : chatRoom.getMembers().values()){
            User user = userDAO.getUserById(member.getUserId());
            byte[] encryptedKey =  RSA.encrypt(
                    raw,
                    new BigInteger(1, user.getPublicKey()),
                    new BigInteger(1, user.getN())
            );
            chatMemberKeyDAO.insertKey(
                    chatId,
                    user.getId(),
                    newVersion,
                    encryptedKey);
        }
    }

    private void respondFailure(StreamObserver<GroupChat> responseObserver, String message) {
        GroupChat response = GroupChat.newBuilder()
                .setSuccess(false)
                .setMessage(message)
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    private boolean isEmailTaken(String email) {
        try {
            return userDAO.getUserByEmail(email) != null;
        } catch (SQLException | IOException e) {
            logger.severe("Failed to check if email is taken: " + e.getMessage());
            return true; // במקרה של שגיאה, נניח שהאימייל תפוס כדי למנוע הרשמה כפולה
        }
    }

    private void respondConnection(StreamObserver<ConnectionResponse> observer,
                                   boolean success, String message, String token, String username, List<String> errors, String userId) {

        ConnectionResponse.Builder builder = ConnectionResponse.newBuilder()
                .setSuccess(success)
                .setMessage(message);

        if (username != null) builder.setUsername(username);
        if (token != null) builder.setToken(token);
        if (userId != null) builder.setUserId(userId);
        if (errors != null && !errors.isEmpty()) builder.addAllErrors(errors);

        observer.onNext(builder.build());
        observer.onCompleted();
    }
}

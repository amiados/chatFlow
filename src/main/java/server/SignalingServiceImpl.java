package server;

import com.chatFlow.signaling.*;
import io.grpc.stub.StreamObserver;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * מימוש שירות Signaling עבור WebRTC באמצעות gRPC.
 *  • מנהל חיבורים של לקוחות לפי userId
 *  • מנהל חדרי שיחה (chatRoom) והרשאות קריאה/כתיבה
 *  • שולח מסרים רגילים ושליטתיים (Control)
 */
public class SignalingServiceImpl extends WebRTCSignalingGrpc.WebRTCSignalingImplBase {

    /** מיפוי userId ל-StreamObserver של הלקוח */
    private final Map<String, StreamObserver<SignalingMessage>> connectedClients =
            new ConcurrentHashMap<>();

    /** מיפוי chatRoomId לסט userIds שמחוברים לחדר */
    private final Map<String, Set<String>> chatRooms = new ConcurrentHashMap<>();

    /** מיפוי chatRoomId למצב קריאה: האם שיחה פעילה */
    private final Map<String, Boolean> activeCalls = new ConcurrentHashMap<>();

    /**
     * בדיקת סטטוס השיחה בחדר נתון
     * @param request פרטי הבקשה המכילים chatRoomId
     * @param responseObserver משיב ה-gRPC
     */
    @Override
    public void checkCallStatus(CheckCallStatusRequest request,
                                StreamObserver<CheckCallStatusResponse> responseObserver) {
        String chatRoomId = request.getChatRoomId();
        boolean active = activeCalls.getOrDefault(chatRoomId, false);

        CheckCallStatusResponse response = CheckCallStatusResponse.newBuilder()
                .setActive(active)
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    /**
     * נקודת כניסה ל-stream דו כיווני של מסרים
     * @param responseObserver המשיב ללקוח
     * @return StreamObserver לטיפול בהודעות נכנסות
     */
    @Override
    public StreamObserver<SignalingMessage> signaling(
            StreamObserver<SignalingMessage> responseObserver) {
        return new StreamObserver<SignalingMessage>() {

            private String userId; // מאוחסן לאחר הודעה ראשונה

            /**
             * נקרא בעת קבלת הודעה מהלקוח
             * מזהה משתמש, מוסיף לחדר, מטפל במסרים רגילים ושליטתיים
             */
            @Override
            public void onNext(SignalingMessage message) {
                if (userId == null) {
                    userId = message.getFromUserId();
                    connectedClients.put(userId, responseObserver);
                    System.out.println("משתמש התחבר: " + userId);
                }

                String chatRoomId = message.getChatRoomId();
                chatRooms.computeIfAbsent(chatRoomId,
                        id -> ConcurrentHashMap.newKeySet()).add(userId);

                if (message.hasControl()) {
                    handleControlMessage(message.getControl(), chatRoomId, userId);
                } else {
                    broadcastToRoom(chatRoomId, userId, message);
                }
            }

            /**
             * נקרא בעת שגיאה בחיבור
             * מסיר את המשתמש מהמיפויים ומנקה חדרים ריקים
             */
            @Override
            public void onError(Throwable t) {
                System.err.println("שגיאה מחיבור של: " + userId + " -> " + t.getMessage());
                if (userId != null) {
                    connectedClients.remove(userId);
                    removeUserFromAllRooms(userId);
                    cleanupEmptyCalls();
                }
            }

            /**
             * נקרא כאשר הלקוח סוגר את ה-stream
             * מנקה את המשאבים ומסיים את ה-responseObserver
             */
            @Override
            public void onCompleted() {
                System.out.println("חיבור נסגר: " + userId);
                if (userId != null) {
                    connectedClients.remove(userId);
                    removeUserFromAllRooms(userId);
                    cleanupEmptyCalls();
                }
                responseObserver.onCompleted();
            }
        };
    }

    /**
     * טיפול בהודעות שליטה (start/join/leave)
     */
    private void handleControlMessage(ControlMessage control, String chatRoomId, String senderId) {
        switch (control.getType()) {
            case START_CALL:
                if (!activeCalls.getOrDefault(chatRoomId, false)) {
                    activeCalls.put(chatRoomId, true);
                    System.out.println("משתמש " + senderId + " התחיל שיחה בחדר " + chatRoomId);
                    broadcastControlToRoom(chatRoomId, senderId, ControlType.START_CALL);
                }
                break;
            case JOIN_CALL:
                if (activeCalls.getOrDefault(chatRoomId, false)) {
                    System.out.println("משתמש " + senderId + " הצטרף לשיחה בחדר " + chatRoomId);
                    broadcastControlToRoom(chatRoomId, senderId, ControlType.JOIN_CALL);
                }
                break;
            case LEAVE_CALL:
                System.out.println("משתמש " + senderId + " עזב את השיחה בחדר " + chatRoomId);
                removeUserFromRoom(chatRoomId, senderId);
                broadcastControlToRoom(chatRoomId, senderId, ControlType.LEAVE_CALL);
                if (chatRooms.getOrDefault(chatRoomId, Collections.emptySet()).isEmpty()) {
                    activeCalls.remove(chatRoomId);
                    System.out.println("סגירת שיחה בחדר " + chatRoomId);
                }
                break;
        }
    }

    /**
     * שידור הודעת signaling רגילה לכל חברי החדר חוץ מהשולח
     */
    private void broadcastToRoom(String chatRoomId, String senderId, SignalingMessage message) {
        Set<String> members = chatRooms.getOrDefault(chatRoomId, Collections.emptySet());
        for (String memberId : members) {
            if (!memberId.equals(senderId)) {
                StreamObserver<SignalingMessage> observer = connectedClients.get(memberId);
                if (observer != null) observer.onNext(message);
            }
        }
    }

    /**
     * שידור הודעת שליטה (ControlType) לכל חברי החדר
     */
    private void broadcastControlToRoom(String chatRoomId, String senderId, ControlType type) {
        SignalingMessage ctrlMsg = SignalingMessage.newBuilder()
                .setFromUserId(senderId)
                .setChatRoomId(chatRoomId)
                .setControl(ControlMessage.newBuilder().setType(type).build())
                .build();
        broadcastToRoom(chatRoomId, senderId, ctrlMsg);
    }

    /**
     * הסרת משתמש מחדר מסוים
     */
    private void removeUserFromRoom(String chatRoomId, String userId) {
        Set<String> members = chatRooms.get(chatRoomId);
        if (members != null) {
            members.remove(userId);
            if (members.isEmpty()) chatRooms.remove(chatRoomId);
        }
    }

    /**
     * הסרת משתמש מכל חדרי השיחה
     */
    private void removeUserFromAllRooms(String userId) {
        for (Set<String> members : chatRooms.values()) {
            members.remove(userId);
        }
    }

    /**
     * ניקוי רשומות של שיחות ללא משתתפים
     */
    private void cleanupEmptyCalls() {
        activeCalls.entrySet().removeIf(entry ->
                chatRooms.getOrDefault(entry.getKey(), Collections.emptySet()).isEmpty()
        );
    }
}

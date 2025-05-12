package server;

import com.chatFlow.signaling.*;
import io.grpc.stub.StreamObserver;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SignalingServiceImpl extends WebRTCSignalingGrpc.WebRTCSignalingImplBase {

    // userId -> StreamObserver שלו
    private final Map<String, StreamObserver<SignalingMessage>> connectedClients = new ConcurrentHashMap<>();

    // chatRoomId -> סט של userIds שמחוברים
    private final Map<String, Set<String>> chatRooms = new ConcurrentHashMap<>();

    // chatRoomId -> האם קיימת שיחה פעילה
    private final Map<String, Boolean> activeCalls = new ConcurrentHashMap<>();

    @Override
    public void checkCallStatus(CheckCallStatusRequest request, StreamObserver<CheckCallStatusResponse> responseObserver) {
        String chatRoomId = request.getChatRoomId();
        boolean active = activeCalls.getOrDefault(chatRoomId, false);

        CheckCallStatusResponse response = CheckCallStatusResponse.newBuilder()
                .setActive(active)
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }


    @Override
    public StreamObserver<SignalingMessage> signaling(StreamObserver<SignalingMessage> responseObserver) {
        return new StreamObserver<SignalingMessage>() {

            private String userId;

            @Override
            public void onNext(SignalingMessage message) {
                if (userId == null) {
                    // זיהוי המשתמש בשיחה הראשונה
                    userId = message.getFromUserId();
                    connectedClients.put(userId, responseObserver);
                    System.out.println("משתמש התחבר: " + userId);
                }

                String chatRoomId = message.getChatRoomId();

                // להבטיח שגם המשתמש רשום לחדר
                chatRooms.computeIfAbsent(chatRoomId, id -> ConcurrentHashMap.newKeySet()).add(userId);

                if (message.hasControl()) {
                    // הודעת שליטה (התחלת שיחה, הצטרפות, יציאה)
                    handleControlMessage(message.getControl(), chatRoomId, userId);
                } else {
                    // הודעת signaling רגילה (Offer / Answer / Candidate)
                    broadcastToRoom(chatRoomId, userId, message);
                }
            }

            @Override
            public void onError(Throwable t) {
                System.err.println("שגיאה מחיבור של: " + userId + " -> " + t.getMessage());
                if (userId != null) {
                    connectedClients.remove(userId);
                    removeUserFromAllRooms(userId);
                    cleanupEmptyCalls();
                }
            }

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

    private void handleControlMessage(ControlMessage control, String chatRoomId, String senderId) {
        switch (control.getType()) {
            case START_CALL:
                if (activeCalls.getOrDefault(chatRoomId, false)) {
                    System.out.println("שיחה כבר קיימת בחדר: " + chatRoomId);
                } else {
                    activeCalls.put(chatRoomId, true);
                    System.out.println("משתמש " + senderId + " התחיל שיחה בחדר " + chatRoomId);
                    broadcastControlToRoom(chatRoomId, senderId, ControlType.START_CALL);
                }
                break;
            case JOIN_CALL:
                if (activeCalls.getOrDefault(chatRoomId, false)) {
                    System.out.println("משתמש " + senderId + " הצטרף לשיחה בחדר " + chatRoomId);
                    broadcastControlToRoom(chatRoomId, senderId, ControlType.JOIN_CALL);
                } else {
                    System.out.println("אין שיחה פעילה להצטרף אליה בחדר " + chatRoomId);
                }
                break;
            case LEAVE_CALL:
                System.out.println("משתמש " + senderId + " עזב את השיחה בחדר " + chatRoomId);
                removeUserFromRoom(chatRoomId, senderId);
                broadcastControlToRoom(chatRoomId, senderId, ControlType.LEAVE_CALL);

                if (chatRooms.getOrDefault(chatRoomId, Collections.emptySet()).isEmpty()) {
                    activeCalls.remove(chatRoomId);
                    System.out.println("כל המשתמשים עזבו. סגירת שיחה בחדר " + chatRoomId);
                }
                break;
        }
    }

    private void broadcastToRoom(String chatRoomId, String senderId, SignalingMessage message) {
        Set<String> members = chatRooms.getOrDefault(chatRoomId, Collections.emptySet());
        for (String memberId : members) {
            if (!memberId.equals(senderId)) {
                StreamObserver<SignalingMessage> targetObserver = connectedClients.get(memberId);
                if (targetObserver != null) {
                    targetObserver.onNext(message);
                }
            }
        }
    }

    private void broadcastControlToRoom(String chatRoomId, String senderId, ControlType controlType) {
        Set<String> members = chatRooms.getOrDefault(chatRoomId, Collections.emptySet());
        ControlMessage controlMessage = ControlMessage.newBuilder()
                .setType(controlType)
                .build();

        SignalingMessage signalingMessage = SignalingMessage.newBuilder()
                .setFromUserId(senderId)
                .setChatRoomId(chatRoomId)
                .setControl(controlMessage)
                .build();

        for (String memberId : members) {
            if (!memberId.equals(senderId)) {
                StreamObserver<SignalingMessage> targetObserver = connectedClients.get(memberId);
                if (targetObserver != null) {
                    targetObserver.onNext(signalingMessage);
                }
            }
        }
    }

    private void removeUserFromRoom(String chatRoomId, String userId) {
        Set<String> members = chatRooms.get(chatRoomId);
        if (members != null) {
            members.remove(userId);
            if (members.isEmpty()) {
                chatRooms.remove(chatRoomId);
            }
        }
    }

    private void removeUserFromAllRooms(String userId) {
        for (Set<String> members : chatRooms.values()) {
            members.remove(userId);
        }
    }

    private void cleanupEmptyCalls() {
        activeCalls.entrySet().removeIf(entry -> {
            String chatRoomId = entry.getKey();
            return chatRooms.getOrDefault(chatRoomId, Collections.emptySet()).isEmpty();
        });
    }

}

package client;

import java.io.File;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import UI.VideoCallWindow;
import com.chatFlow.signaling.*;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import utils.ChannelManager;

import javax.imageio.ImageIO;
import javax.net.ssl.SSLException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * מחלקה המייצגת לקוח gRPC לתקשורת WebRTC Signaling
 * מנהלת את פתיחת החיבור לשרת, שליחת ועדכון מסגרות וידאו, אודיו,
 * וכן שליטה על הסטטוס של השיחה (התחלה, הצטרפות, עזיבה).
 */
public class SignalingClient {

    /** ערוץ gRPC לתקשורת */
    private final ManagedChannel channel;
    /** Stub אסינכרוני של WebRTCSignaling */
    private final WebRTCSignalingGrpc.WebRTCSignalingStub asyncStub;
    /** סטרים דו-כיווני לקבלת והעברת הודעות Signaling */
    private StreamObserver<SignalingMessage> signalingStream;
    /** מזהה המשתמש הנוכחי */
    private final String userId;
    /** חלון ממשק וידאו
     * (נשלח לו ועדכונים של מסגרות וידאו ואודיו) */
    private VideoCallWindow videoCallWindow;

    /** רשימת מאזינים לשינויים בסטטוס השיחה */
    private final List<BiConsumer<String,Boolean>> callStatusListeners = new CopyOnWriteArrayList<>();

    /** כתובת השרת לשירות Signaling */
    private static final String SERVER_ADDRESS = "localhost";
    /** פורט השרת לשירות Signaling */
    private static final int SERVER_PORT = 50052;
    /** קובץ התעודה לשימוש ב־TLS */
    private static final File TRUST_CERT_COLLECTION = new File("certs/server.crt");

    /**
     * בונה לקוח Signaling חדש.
     * מגדיר SSL/TLS ומאתחל את ה־stub האסינכרוני.
     *
     * @param userId המזהה הייחודי של המשתמש
     * @throws RuntimeException במקרה של כשל ב־SSL
     */
    public SignalingClient(String userId) {
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
        this.userId = userId;
        this.asyncStub = WebRTCSignalingGrpc.newStub(channel);
    }

    /**
     * מוסיף מאזין לשינויים בסטטוס השיחה (התחלה/עזיבה).
     *
     * @param listener פונקציה המקבלת את מזהה החדר והסטטוס (פעיל/לא)
     */
    public void addCallStatusListener(BiConsumer<String,Boolean> listener) {
        callStatusListeners.add(listener);
    }

    /**
     * מסיר מאזין לשינויים בסטטוס השיחה.
     *
     * @param listener המאזין להסרה
     */
    public void removeCallStatusListener(BiConsumer<String,Boolean> listener) {
        callStatusListeners.remove(listener);
    }

    /**
     * מגדיר את חלון וידאו אליו יישלחו מסגרות וידאו ואודיו.
     *
     * @param videoCallWindow האובייקט המטפל בתצוגת מדיה
     */
    public void setVideoCallWindow(VideoCallWindow videoCallWindow) {
        this.videoCallWindow = videoCallWindow;
    }

    /**
     * פותח את החיבור לשרת ומתחיל להאזין להודעות Signaling.
     * מטפל במסגרות וידאו, אודיו, ובאירועי שליטה (start/join/leave).
     */
    public void connect() {
        signalingStream = asyncStub.signaling(new StreamObserver<>() {
            @Override
            public void onNext(SignalingMessage value) {

                // 1) עדכון מסגרת וידאו בחלון אם קיימת
                if (value.hasVideoFrame() && videoCallWindow != null) {
                    byte[] frameBytes = value.getVideoFrame().toByteArray();
                    videoCallWindow.updateVideo(value.getFromUserId(), frameBytes);
                }
                // 2) ניגון אודיו נכנס
                if (value.hasAudioChunk() && videoCallWindow != null) {
                    byte[] audioBytes = value.getAudioChunk().getAudioData().toByteArray();
                    videoCallWindow.playIncomingAudio(audioBytes);
                }

                // 3) עדכון מאזינים על אירועי שליטה
                if (value.hasControl()) {
                    ControlType type = value.getControl().getType();
                    String room = value.getChatRoomId();
                    boolean active = (type == ControlType.START_CALL || type == ControlType.JOIN_CALL);
                    if (type == ControlType.LEAVE_CALL) {
                        active = false;
                    }
                    for (BiConsumer<String, Boolean> l : callStatusListeners) {
                        l.accept(room, active);
                    }
                }
            }

            @Override
            public void onError(Throwable t) {
                System.err.println("שגיאה: " + t.getMessage());
            }

            @Override
            public void onCompleted() {
                System.out.println("החיבור לשרת הסתיים.");
            }
        });
    }

    /**
     * שולח מסגרת וידאו לשרת.
     *
     * @param frame המסגרת לתיעוד
     * @param chatRoomId מזהה חדר הצ'אט
     */
    public void sendVideoFrame(BufferedImage frame, String chatRoomId) {
        if (frame == null || signalingStream == null) return;

        try {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            ImageIO.write(frame, "jpg", byteArrayOutputStream);
            byte[] bytes = byteArrayOutputStream.toByteArray();
            byteArrayOutputStream.close();

            if (bytes.length == 0) return;

            SignalingMessage message = SignalingMessage.newBuilder()
                    .setFromUserId(userId)
                    .setChatRoomId(chatRoomId)
                    .setVideoFrame(ByteString.copyFrom(bytes))
                    .setVideoTimestamp(System.nanoTime())
                    .build();

            signalingStream.onNext(message);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * שולח מקטע אודיו לשרת.
     *
     * @param audioData נתוני האודיו בבייטים
     * @param chatRoomId מזהה חדר הצ'אט
     */
    public void sendAudioFrame(byte[] audioData, String chatRoomId) {
        if (audioData == null || audioData.length < 2) return;

        if (signalingStream == null) {
            System.err.println("שגיאה: לא מחובר לשרת signaling");
            return;
        }
        SignalingMessage message = SignalingMessage.newBuilder()
                .setFromUserId(userId)
                .setChatRoomId(chatRoomId)
                .setAudioChunk(AudioChunk.newBuilder()
                        .setAudioData(ByteString.copyFrom(audioData))
                        .build())
                .setAudioTimestamp(System.nanoTime())
                .build();
        signalingStream.onNext(message);
    }

    /**
     * שולח הודעת התחלת שיחה לשרת.
     *
     * @param chatRoomId מזהה חדר הצ'אט
     */
    public void startCall(String chatRoomId) {
        signalingStream.onNext(
                SignalingMessage.newBuilder()
                        .setFromUserId(userId)
                        .setChatRoomId(chatRoomId)
                        .setControl(ControlMessage.newBuilder()
                                .setType(ControlType.START_CALL)
                                .build())
                        .build()
        );
    }

    /**
     * שולח בקשה להצטרפות לשיחה לשרת.
     *
     * @param chatRoomId מזהה חדר הצ'אט
     */
    public void joinCall(String chatRoomId) {
        signalingStream.onNext(
                SignalingMessage.newBuilder()
                        .setFromUserId(userId)
                        .setChatRoomId(chatRoomId)
                        .setControl(ControlMessage.newBuilder()
                                .setType(ControlType.JOIN_CALL)
                                .build())
                        .build()
        );
    }

    /**
     * שולח הודעת עזיבת שיחה לסטרים ומסיים אותו.
     *
     * @param chatRoomId מזהה חדר הצ'אט
     */
    public void leaveCall(String chatRoomId) {
        SignalingMessage message = SignalingMessage.newBuilder()
                .setFromUserId(userId)
                .setChatRoomId(chatRoomId)
                .setControl(ControlMessage.newBuilder()
                        .setType(ControlType.LEAVE_CALL)
                        .build())
                .build();

        sendSignalingMessage(message);

        if (signalingStream != null) {
            signalingStream.onCompleted();
        }
    }

    /**
     * שולח הודעת Signaling כללית אם היא אינה ריקה.
     *
     * @param message אובייקט ההודעה
     */
    public void sendSignalingMessage(SignalingMessage message) {
        if (!message.hasVideoFrame() &&
                !message.hasAudioChunk() &&
                !message.hasControl()) {
            System.err.println("⚠️ Ignoring empty signaling message");
            return;
        }
        signalingStream.onNext(message);

    }

    /**
     * שואל את השרת לגבי סטטוס השיחה הנוכחי עבור חדר מסוים.
     *
     * @param chatRoomId מזהה חדר הצ'אט
     * @return true אם השיחה פעילה, false אחרת
     */
    public boolean checkCallStatus(String chatRoomId) {

        if (!isConnected()) {
            System.err.println("Cannot check call status: channel is closed");
            return false;
        }
        try {
            WebRTCSignalingGrpc.WebRTCSignalingBlockingStub blockingStub = WebRTCSignalingGrpc.newBlockingStub(channel);
            CheckCallStatusRequest request = CheckCallStatusRequest.newBuilder()
                    .setChatRoomId(chatRoomId)
                    .build();
            CheckCallStatusResponse response = blockingStub.checkCallStatus(request);
            return response.getActive();
        } catch (io.grpc.StatusRuntimeException e){
            if (e.getStatus().getCode() == io.grpc.Status.Code.UNAVAILABLE) {
                System.err.println("Call status unavailable: " + e.getMessage());
                return false;
            }
            throw e;
        }
    }

    /**
     * מחזיר את מזהה המשתמש.
     *
     * @return המזהה שנשמר בבנאי
     */
    public String getUserId() {
        return userId;
    }

    /**
     * בודק אם הערוץ (channel) פעיל (לא נסגר).
     *
     * @return true אם מחובר, false אם נסגר או בתהליך סגירה
     */
    public boolean isConnected(){
        return !channel.isShutdown() && !channel.isTerminated();
    }

    /**
     * סוגר את החיבור לשרת ומחכה לסגירה נקייה.
     */
    public void shutdown() {
        if (channel != null && !channel.isShutdown()) {
            channel.shutdown();
        }
        try {
            channel.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e){
            channel.shutdown();
            Thread.currentThread().interrupt();
        }
    }
}

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
 * A gRPC client for WebRTC signaling.
 * SSLException is caught internally and rethrown as RuntimeException.
 */
public class SignalingClient {

    private final ManagedChannel channel;
    private final WebRTCSignalingGrpc.WebRTCSignalingStub asyncStub;
    private StreamObserver<SignalingMessage> signalingStream;
    private final String userId;
    private VideoCallWindow videoCallWindow;

    private final List<BiConsumer<String,Boolean>> callStatusListeners = new CopyOnWriteArrayList<>();

    private static final String SERVER_ADDRESS = "localhost";
    private static final int SERVER_PORT = 50052;
    private static final File TRUST_CERT_COLLECTION =  new File("certs/server.crt");

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

    // 2. מתודות הרשמה/הסרה למאזינים
    public void addCallStatusListener(BiConsumer<String,Boolean> listener) {
        callStatusListeners.add(listener);
    }
    public void removeCallStatusListener(BiConsumer<String,Boolean> listener) {
        callStatusListeners.remove(listener);
    }

    public void setVideoCallWindow(VideoCallWindow videoCallWindow) {
        this.videoCallWindow = videoCallWindow;
    }

    public void connect() {
        signalingStream = asyncStub.signaling(new StreamObserver<>() {
            @Override
            public void onNext(SignalingMessage value) {

                // 1) וידאו/אודיו כרגיל
                if (value.hasVideoFrame() && videoCallWindow != null) {
                    byte[] frameBytes = value.getVideoFrame().toByteArray();
                    videoCallWindow.updateVideo(value.getFromUserId(), frameBytes);
                }
                if (value.hasAudioChunk() && videoCallWindow != null) {
                    byte[] audioBytes = value.getAudioChunk().getAudioData().toByteArray();
                    videoCallWindow.playIncomingAudio(audioBytes);
                }

                // 2) דוח אירועי שליטה ל־listeners
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
                    .setVideoFrame(com.google.protobuf.ByteString.copyFrom(bytes))
                    .build();

            signalingStream.onNext(message);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

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
                .build();
        signalingStream.onNext(message);
    }

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

    public void sendSignalingMessage(SignalingMessage message) {
        if (!message.hasVideoFrame() &&
                !message.hasAudioChunk() &&
                !message.hasControl()) {
            System.err.println("⚠️ Ignoring empty signaling message");
            return;
        }
        signalingStream.onNext(message);

    }

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

    public String getUserId() {
        return userId;
    }

    public boolean isConnected(){
        return !channel.isShutdown() && !channel.isTerminated();
    }

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

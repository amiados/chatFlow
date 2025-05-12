package client;

import UI.VideoCallWindow;
import com.chatFlow.signaling.*;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class SignalingClient {

    private final ManagedChannel channel;
    private final WebRTCSignalingGrpc.WebRTCSignalingStub asyncStub;
    private StreamObserver<SignalingMessage> signalingStream;
    private final String userId;
    private VideoCallWindow videoCallWindow;

    public SignalingClient(String serverAddress, int serverPort, String userId) {
        this.userId = userId;
        this.channel = ManagedChannelBuilder.forAddress(serverAddress, serverPort)
                .usePlaintext()
                .build();
        this.asyncStub = WebRTCSignalingGrpc.newStub(channel);
    }

    public void setVideoCallWindow(VideoCallWindow videoCallWindow) {
        this.videoCallWindow = videoCallWindow;
    }

    public void connect() {
        signalingStream = asyncStub.signaling(new StreamObserver<>() {
            @Override
            public void onNext(SignalingMessage value) {
                if (value.hasVideoFrame() && videoCallWindow != null) {
                    byte[] frameBytes = value.getVideoFrame().toByteArray();
                    videoCallWindow.updateVideo(value.getFromUserId(), frameBytes);
                }
                if (value.hasAudioChunk() && videoCallWindow != null) {
                    byte[] audioBytes = value.getAudioChunk().getAudioData().toByteArray();
                    videoCallWindow.playIncomingAudio(audioBytes);
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

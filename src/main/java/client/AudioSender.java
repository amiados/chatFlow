package client;

import io.github.jaredmdobson.concentus.OpusApplication;

import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusException;

import javax.sound.sampled.*;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

public class AudioSender {

    private final SignalingClient signalingClient;
    private final String chatRoomId;
    private TargetDataLine microphone;
    private final AtomicBoolean recording = new AtomicBoolean(false);
    private final AtomicBoolean muted = new AtomicBoolean(false);
    private Thread recordingThread;
    private final OpusEncoder encoder;

    public AudioSender(SignalingClient signalingClient, String chatRoomId) throws OpusException{
        this.signalingClient = signalingClient;
        this.chatRoomId = chatRoomId;
        this.encoder = new OpusEncoder(16000, 1, OpusApplication.OPUS_APPLICATION_VOIP);

    }

    public void start() {
        recordingThread = new Thread(() -> {
            try {
                AudioFormat format = new AudioFormat(16000.0f, 16, 1, true, false);
                microphone = AudioSystem.getTargetDataLine(format);
                microphone.open(format);
                microphone.start();

                byte[] buffer = new byte[320];
                recording.set(true);

                while (recording.get()) {
                    if(muted.get()){
                        Thread.sleep(20); // אם במיוט, לא שולחים כלום, רק ממתינים
                        continue;
                    }
                    int bytesRead = microphone.read(buffer, 0, buffer.length);
                    if (bytesRead > 0) {
                        byte[] toSend = Arrays.copyOf(buffer, bytesRead);
                        onAudioChunk(toSend);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if(microphone != null){
                    microphone.stop();
                    microphone.close();
                }
            }
        });
        recordingThread.start();
    }

    public void stop() {
        recording.set(false);
        if(recordingThread != null){
            recordingThread.interrupt();
            try {
                recordingThread.join(200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void mute() {
        muted.set(true);
    }

    public void unmute() {
        muted.set(false);
    }

    public boolean isMuted() {
        return muted.get();
    }

    /**
     * מקודד כל חתיכת PCM ל-Opus ושולח דרך SignalingClient
     */
    protected void onAudioChunk(byte[] chunk) {
        try {
            int samples = chunk.length / 2;
            byte[] encoded = new byte[4096];
            int len = encoder.encode(
                    chunk, 0,
                    samples,
                    encoded, 0,
                    encoded.length
            );
            byte[] toSend = Arrays.copyOf(encoded, len);
            signalingClient.sendAudioFrame(toSend, chatRoomId);
        } catch (OpusException e) {
            e.printStackTrace();
        }
    }

}

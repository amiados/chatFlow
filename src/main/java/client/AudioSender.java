package client;

import com.chatFlow.signaling.*;
import javax.sound.sampled.*;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

public class AudioSender {

    private final SignalingClient signalingClient;
    private final String chatRoomId;
    private TargetDataLine microphone;
    private final AtomicBoolean recording = new AtomicBoolean(false);
    private final AtomicBoolean muted = new AtomicBoolean(false);
    private Thread recordingThread;
    private final ByteArrayOutputStream rawAudioStream = new ByteArrayOutputStream();

    public AudioSender(SignalingClient signalingClient, String chatRoomId){
        this.signalingClient = signalingClient;
        this.chatRoomId = chatRoomId;
    }

    public void start() {
        recordingThread = new Thread(() -> {
            try {
                AudioFormat format = new AudioFormat(16000.0f, 16, 1, true, false);
                microphone = AudioSystem.getTargetDataLine(format);
                microphone.open(format);
                microphone.start();

                byte[] buffer = new byte[320]; // 20ms frame (16kHz * 16bit * Mono)
                recording.set(true);

                while (recording.get()) {
                    if(muted.get()){
                        Thread.sleep(50); // אם במיוט, לא שולחים כלום, רק ממתינים
                        continue;
                    }
                    int bytesRead = microphone.read(buffer, 0, buffer.length);
                    if (bytesRead > 0) {
                        byte[] toSend = new byte[bytesRead];
                        System.arraycopy(buffer, 0, toSend, 0, bytesRead);
                        rawAudioStream.write(toSend);
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

    protected void onAudioChunk(byte[] chunk) {
        signalingClient.sendAudioFrame(chunk, chatRoomId);
    }

    public byte[] getFullAudio() {
        return rawAudioStream.toByteArray();
    }
}

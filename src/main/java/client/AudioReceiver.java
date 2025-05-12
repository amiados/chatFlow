package client;

import io.github.jaredmdobson.concentus.OpusDecoder;
import javax.sound.sampled.*;

public class AudioReceiver {

    private SourceDataLine speakers;
    private final OpusDecoder decoder;

    public AudioReceiver() throws Exception{
        // אתחול פורמט רמקולים
        AudioFormat format = new AudioFormat(16000.0f, 16, 1, true, false);
        speakers = AudioSystem.getSourceDataLine(format);
        speakers.open(format);
        speakers.start();

        decoder = new OpusDecoder(16000, 1);
    }

    public void playAudio(byte[] compressedAudio) {
        try {
            byte[] pcmBuffer = new byte[960 * 2]; // 960 samples * 2 bytes

            int decodedSamples = decoder.decode(compressedAudio, 0, compressedAudio.length, pcmBuffer, 0, 960, false);

            if (decodedSamples > 0) {
                speakers.write(pcmBuffer, 0, decodedSamples * 2);
            }
        } catch (Exception e){
            e.printStackTrace();
        }
    }

    public void close() {
        if (speakers != null) {
            speakers.stop();
            speakers.close();
        }
    }
}

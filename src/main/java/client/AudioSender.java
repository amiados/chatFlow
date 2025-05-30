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

/**
 * מחלקה האחראית על הקלטת קול מהמיקרופון, קידוד שלו ל-Opus ושליחתו דרך SignalingClient
 */
public class AudioSender {

    /**
     * ממשק לשליחת הנתונים לשרת ההתקשרות
     */
    private final SignalingClient signalingClient;

    /**
     * מזהה חדר הצ'אט שאליו מתבצעת השליחה
     */
    private final String chatRoomId;

    /**
     * שורת נתונים לקבלת קול מהמערכת
     */
    private TargetDataLine microphone;

    /**
     * מצב הקלטה: בדיקה האם להמשיך להקליט
     */
    private final AtomicBoolean recording = new AtomicBoolean(false);

    /**
     * האם הווליום במיוט: במקרה זה לא שולחים נתונים
     */
    private final AtomicBoolean muted = new AtomicBoolean(false);

    /**
     * הת'רד שמבצע את הלולאה של ההקלטה
     */
    private Thread recordingThread;

    /**
     * אנקודר Opus עבור קידוד נתוני ה-PCM
     */
    private final OpusEncoder encoder;

    /**
     * בונה מחלקה חדשה עם המזהה של חדר הצ'אט ו-SignalingClient
     *
     * @param signalingClient האובייקט האחראי על שליחת המסגרות לשרת
     * @param chatRoomId        המזהה של חדר הצ'אט שאליו שולחים את המסגרות
     * @throws OpusException במידה ויש בעיה באתחול האנקודר
     */
    public AudioSender(SignalingClient signalingClient, String chatRoomId) throws OpusException {
        this.signalingClient = signalingClient;
        this.chatRoomId = chatRoomId;
        // אתחול אנקודר בעזרת קצב דגימה 16kHz, ערוץ יחיד, למערכת VoIP
        this.encoder = new OpusEncoder(16000, 1, OpusApplication.OPUS_APPLICATION_VOIP);
    }

    /**
     * פותח ת'רד חדש שמתחיל להקליט את האודיו
     */
    public void start() {
        recordingThread = new Thread(() -> {
            try {
                // הגדרת פורמט האודיו: 16kHz, 16 ביט, מונו, signed, little-endian
                AudioFormat format = new AudioFormat(16000.0f, 16, 1, true, false);
                microphone = AudioSystem.getTargetDataLine(format);
                microphone.open(format);
                microphone.start();

                byte[] buffer = new byte[320];
                recording.set(true);

                while (recording.get()) {
                    if (muted.get()) {
                        // במיוט, נדלג על שליחת אודיו ונמתין
                        Thread.sleep(20);
                        continue;
                    }
                    // קריאה של נתוני קול לבאפר
                    int bytesRead = microphone.read(buffer, 0, buffer.length);
                    if (bytesRead > 0) {
                        // העתקת הבייטים במדויק למה שנקרא והעברתם לקידוד
                        byte[] toSend = Arrays.copyOf(buffer, bytesRead);
                        onAudioChunk(toSend);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                // עצירת המיקרופון וסגירתו
                if (microphone != null) {
                    microphone.stop();
                    microphone.close();
                }
            }
        });
        recordingThread.start();
    }

    /**
     * מפסיק את ההקלטה ועוצר את הת'רד
     */
    public void stop() {
        recording.set(false);
        if (recordingThread != null) {
            recordingThread.interrupt();
            try {
                // המתנה קלה עד לסגירת הת'רד
                recordingThread.join(200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * מפעיל מצב מיוט: לא שולח אודיו
     */
    public void mute() {
        muted.set(true);
    }

    /**
     * כיבוי מצב מיוט: מתחילים לשלוח אודיו מחדש
     */
    public void unmute() {
        muted.set(false);
    }

    /**
     * בודק האם כרגע במצב מיוט
     *
     * @return true אם במיוט, false אחרת
     */
    public boolean isMuted() {
        return muted.get();
    }

    /**
     * מקודד כל חתיכת PCM ל-Opus ושולח דרך SignalingClient
     *
     * @param chunk מערך בתים הכולל נתוני PCM לקריאה
     */
    protected void onAudioChunk(byte[] chunk) {
        try {
            // חישוב מספר דגימות מתוך אורך הבאפר של PCM (2 בתים לדגימה)
            int samples = chunk.length / 2;
            byte[] encoded = new byte[4096];
            int len = encoder.encode(
                    chunk, 0,
                    samples,
                    encoded, 0,
                    encoded.length
            );
            // חיתוך התוצאות לאורך תקין ושליחתו
            byte[] toSend = Arrays.copyOf(encoded, len);
            signalingClient.sendAudioFrame(toSend, chatRoomId);
        } catch (OpusException e) {
            e.printStackTrace();
        }
    }

}

package client;

import io.github.jaredmdobson.concentus.OpusDecoder;
import javax.sound.sampled.*;

/**
 * מחלקה שאחראית על קבלת זרם קול דחוס (Opus),
 * פענוחו והשמעתו דרך רמקולים מקומיים.
 */
public class AudioReceiver {

    // נתיב לזרם השמע לרמקולים
    private SourceDataLine speakers;
    // אובייקט לפענוח פורמט Opus
    private final OpusDecoder decoder;

    /**
     * בונה את AudioReceiver:
     * - מגדיר את פורמט השמע (16kHz, 16 סיביות, חד ערוצי)
     * - פותח ומפעיל את קו הרמקולים
     * - מאתחל את מפענח ה-Opus
     *
     * @throws Exception במידה ויש בעיה באתחול קו השמע או המפענח
     */
    public AudioReceiver() throws Exception {
        // אתחול פורמט רמקולים: 16 קילו־הרץ, 16 סיביות, ערוץ אחד, signed, little-endian
        AudioFormat format = new AudioFormat(16000.0f, 16, 1, true, false);
        speakers = AudioSystem.getSourceDataLine(format);
        speakers.open(format);
        speakers.start();

        // אתחול מפענח Opus עם אותם פרמטרים (16kHz, ערוץ אחד)
        decoder = new OpusDecoder(16000, 1);
    }

    /**
     * מפענח נתוני שמע דחוסים בפורמט Opus ומשמיע אותם.
     *
     * @param compressedAudio מערך בתים של שמע דחוס
     */
    public void playAudio(byte[] compressedAudio) {
        try {
            // לוח זיכרון לפלט PCM: 960 דגימות * 2 בתים לדגימה
            byte[] pcmBuffer = new byte[960 * 2];

            // פענוח השמע --> מחזיר כמות דגימות מפוענחות
            int decodedSamples = decoder.decode(
                    compressedAudio, 0, compressedAudio.length,
                    pcmBuffer, 0, 960, false
            );

            if (decodedSamples > 0) {
                // חישוב מספר הבתים לכתיבה: דגימות * 2 בתים
                int bytesToWrite = decodedSamples * 2;
                // חישוב השהייה כדי לסנכרן את ההשמעה (בננו־שניות)
                long nanos = (long)(decodedSamples / 16000.0 * 1_000_000_000);

                // כתיבה אל הרמקולים
                speakers.write(pcmBuffer, 0, bytesToWrite);
                // השהייה כדי לשמור על קצב נכון
                Thread.sleep(nanos / 1_000_000, (int)(nanos % 1_000_000));
            }
        } catch (Exception e) {
            // הדפסת שגיאות לפלט שגיאות
            e.printStackTrace();
        }
    }

    /**
     * סוגר את קו הרמקולים ומשחרר את המשאבים.
     */
    public void close() {
        if (speakers != null) {
            // עצירת ההשמעה
            speakers.stop();
            // סגירה ושחרור משאבים
            speakers.close();
        }
    }
}

package utils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CallRecorder מנהלת הקלטה של שיחת וידאו ואודיו מלאה:
 * - מקליטה פריימי וידאו הרצויים (של כל המשתתפים) כקבצי JPEG
 * - מקליטה חתיכות אודיו (PCM) של כל המשתתפים
 * - בסיום משלבת את הווידאו והאודיו לקובץ MP4 באמצעות FFmpeg
 */
public class CallRecorder {
    // ספרייה זמנית לשמירת פריימי הווידאו
    private final Path framesDir;
    // אוגר בייטים של האודיו (PCM) מכל המשתתפים
    private final ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream();
    // מונה פריימים
    private final AtomicInteger frameCounter = new AtomicInteger(0);
    // שם קובץ היצוא הסופי
    private final String outputFilename;
    // קובץ זמני לאודיו גולמי (PCM)
    private final Path audioTempFile;
    private Process ffmpegProcess;

    /**
     * בונה מופע חדש עם שם הפלט הרצוי.
     * יוצר תיקיית עבודה תחת recordings/ לזיהוי לפי זמן.
     */
    public CallRecorder(String outputFilename){
        this.outputFilename = outputFilename;
        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path baseDir = Path.of("recordings", timestamp);
            Files.createDirectories(baseDir);
            this.framesDir = baseDir.resolve("frames");
            Files.createDirectories(framesDir);
            this.audioTempFile = baseDir.resolve("audio.pcm");
        } catch (IOException e){
            throw new RuntimeException("Failed to initialize CallRecorder directories", e);
        }
    }

    /**
     * אתחול לפני הקלטה - כאן ניתן להפעיל FFmpeg אם רוצים הקלטת live
     */
    public void start() {
        System.out.println("CallRecorder: start recording setup complete");
    }

    /**
     * קוראת פריים וידאו (כל משתתף) ושומרת כ-JPEG בתיקייה.
     */
    public void recordVideoFrame(String userId, BufferedImage frame){
        try {
            int n = frameCounter.incrementAndGet();
            // שם קובץ עם מספר פריים: frame00001.jpg
            Path frameFile = framesDir.resolve(String.format("frame%05d.jpg", n));
            try (OutputStream os = Files.newOutputStream(frameFile)) {
                ImageIO.write(frame, "jpg", os);
            }
        } catch (IOException e) {
            System.err.println("Error recording video frame");
            e.printStackTrace();
        }
    }

    /**
     * מקבלת חתיכת אודיו (PCM גולמי) ושומרת במאגר פנימי.
     */
    public void recordAudioChunk(byte[] pcmChunk) {
        try {
            audioBuffer.write(pcmChunk);
        } catch (IOException e) {
            System.err.println("Error recording audio chunk");
            e.printStackTrace();
        }
    }

    /**
     * מפסיקה הקלטה:
     * 1. כותבת את כל ה-PMC הזמני לקובץ audio.pcm
     * 2. מריצה FFmpeg למיזוג frames + audio.pcm -> MP4
     * 3. מוחקת קבצים זמניים בתיקיית ההקלטה
     */
    public void stop(boolean shouldMerge) throws IOException, InterruptedException {
        try {
            // 1. כתיבת PCM לקובץ
            Files.write(audioTempFile, audioBuffer.toByteArray());

            if(shouldMerge) {
                // 2. בניית פקודת FFmpeg עם דחיסה גבוהה
                ProcessBuilder pb = new ProcessBuilder(
                        "ffmpeg", // קריאה להריץ דחיסה מסוג FFMPEG
                        "-y", // מאפשר לבצע כתיבה גם אם קיים קובץ בעל אותו שם

                        // וידאו:
                        "-framerate", "30", // מקור הוידאו ייקרא 30 פרימיים בשנייה
                        "-i", framesDir.resolve("frame%05d.jpg").toString(), // קליטת תמונות ברצף לפי תבנית

                        // אודיו:
                        "-f", "s16le",
                        "-ar", "16000", // 16000 דגימות בשנייה
                        "-ac", "1", // מספר הערוצים
                        "-i", audioTempFile.toString(),

                        // ← שינינו כאן:
                        "-c:v", "libvpx-vp9",     // קידוד באמצעות מודל קודק VP9
                        "-crf", "28",           // רמת איכות אחרי דחיסה. בין 18 (איכות גובה יותר -> קובץ גדול יותר) ל30 (איכות נמוכה יותר -> קובץ קטן יותר)
                        "-preset", "medium",  // גביית משאבים (מהירות קידוד לעומת איכות). בין ultraFast (הכי מהיר, איכות נמוכה) ל-verySlow (הכי איטי, איכות גבוהה)
                        "-vf", "scale=640:-2",  // רוחב 640px, יחס זהה

                        "-c:a", "aac",  // קידוד אודיו ל-AAC
                        "-b:a", "64k",  // תזרים אודיו של 64 קילו-ביט לשנייה

                        outputFilename // שם הקוב. שהפלט ייכתב אליו בסוף התהליך
                );

                pb.redirectErrorStream(true);
                ffmpegProcess = pb.start();

                try (BufferedReader r = new BufferedReader(new InputStreamReader(ffmpegProcess.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        System.out.println(line);
                    }
                }

                ffmpegProcess.waitFor();
            }

        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Error during call recording stop", e);
        }
    }

    /**
     * מנקה קבצים זמניים: framesDir, audioTempFile, ו־outputFilename (אם קיים)
     */
    public void cleanUp() throws IOException {
        // מקדם למחיקת frames
        if (Files.exists(framesDir)) {
            Files.walk(framesDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
        }

        // מחיקת PCM זמני
        if (Files.exists(audioTempFile)) {
            Files.delete(audioTempFile);
        }

        // מחיקת MP4 אם נוצר
        Path mp4 = Path.of(outputFilename);
        if (Files.exists(mp4)) {
            Files.delete(mp4);
        }
    }
    /**
     * @return שם הקובץ הסופי (MP4) של ההקלטה
     */
    public String getOutputFilename() {
        return outputFilename;
    }
}

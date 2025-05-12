package utils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
    public void stop() throws IOException, InterruptedException {
        try {
            // 1. כתיבת PCM לקובץ
            Files.write(audioTempFile, audioBuffer.toByteArray());

            // 2. בניית פקודת FFmpeg
            // -framerate 20: קצב פריימים של הווידאו
            // -f s16le -ar 16000 -ac 1: פורמט אודיו PCM 16kHz מונו
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg",
                    "-y",
                    "-framerate", "20",
                    "-i", framesDir.resolve("frame%05d.jpg").toString(),
                    "-f", "s16le",
                    "-ar", "16000",
                    "-ac", "1",
                    "-i", audioTempFile.toString(),
                    "-c:v", "libx264",
                    "-pix_fmt", "yuv420p",
                    outputFilename
            );
            pb.redirectErrorStream(true);
            ffmpegProcess = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(ffmpegProcess.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null)  System.out.println(line);
            }
            ffmpegProcess.waitFor();

            // 3. ניקוי קבצים זמניים
            Files.list(framesDir).forEach(f -> {
                try {
                    Files.deleteIfExists(f);
                } catch (IOException ignored) {
                }
            });
            Files.deleteIfExists(framesDir);
            Files.deleteIfExists(audioTempFile);
        } catch (IOException | InterruptedException e){
            throw new RuntimeException("Error during call recording stop", e);
        }
    }

    /**
     * @return שם הקובץ הסופי (MP4) של ההקלטה
     */
    public String getOutputFilename() {
        return outputFilename;
    }
}

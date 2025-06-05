package UI;

import client.ChatClient;
import client.AudioReceiver;
import client.AudioSender;
import client.SignalingClient;
import com.github.sarxos.webcam.Webcam;
import io.github.jaredmdobson.concentus.OpusException;
import model.User;
import utils.CallRecorder;
import utils.DynamicResolutionManager;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.api.client.http.FileContent;
import utils.GoogleDriveInitializer;
import model.ChatRoom;

import java.io.File;

/**
 * חלון שיחה בווידאו הכולל סטרימינג של וידאו ואודיו,
 * תמיכה בשיתוף מסך, ניהול רסולוציה דינמית,
 * והרשאות לביצוע דיווח והקלטה לשמירה/העלאה ל-Drive.
 */
public class VideoCallWindow extends JFrame {

    private final SignalingClient signalingClient; // לקוח signaling לניהול WebRTC
    private final ChatClient client; // לקוח נתוני צ'אט
    private final String chatRoomId; // מזהה חדר הצ'אט
    private final String myUserId; // מזהה המשתמש הנוכחי

    // מפה של senderId ל־JLabel שאליו מעדכנים את התמונה
    private final Map<String, JLabel> videoLabels = new ConcurrentHashMap<>();
    private final JPanel videoPanel = new JPanel(new GridLayout(1,1,10,10));

    // התצוגה של המצלמה המקומית (תמיד קיימת)
    private JLabel localScreenLabel;

    private AudioSender audioSender;
    private AudioReceiver audioReceiver;

    // מנהל ההקלטה המתקדמת (וידאו + אודיו)
    private CallRecorder recorder;
    private volatile boolean streaming = true;
    private volatile boolean screenSharing = false;

    private JButton muteButton;
    private JButton shareScreenButton;

    private final DynamicResolutionManager resolutionManager = new DynamicResolutionManager();

    private Thread screenThread;

    // קביעת Frame Rate של שיתוף מסך וסטרימינג: 30 FPS
    public final long FPS = 1000L / 30;

    /**
     * קונסטרקטור:
     * מאתחל הקלטה, UI ותחילת סטרימינג
     * @param signalingClient לקוח signaling
     * @param chatRoomId מזהה חדר
     * @param user אובייקט המשתמש
     * @param client לקוח ChatClient
     */
    public VideoCallWindow(SignalingClient signalingClient, String chatRoomId, User user, ChatClient client) {
        this.signalingClient = signalingClient;
        this.chatRoomId = chatRoomId;
        this.myUserId = user.getId().toString();
        this.client = client;

        initRecorder();
        initUI();
        startStreaming();
    }

    /**
     * אתחול ההקלטה לקובץ MP4 עם חותמת זמן
     */
    private void initRecorder() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String outputFile = "recordings/rec_" + timestamp + ".mp4";
        recorder = new CallRecorder(outputFile);
        recorder.start();
    }

    /**
     * בניית ממשק המשתמש: אזור וידאו, כפתורים לשליטה ויציאה
     */
    private void initUI() {

        setTitle("וידאו צ'אט - " + chatRoomId);
        setSize(1200, 900);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        // יוצרים JLabel ייחודי למצלמה המקומית
        localScreenLabel = new JLabel("מצלמה מקומית", SwingConstants.CENTER);
        localScreenLabel.setPreferredSize(new Dimension(320, 240));
        localScreenLabel.setBorder(BorderFactory.createLineBorder(Color.BLACK));

        // מוסיפים את ה־localScreenLabel למפה ול־videoPanel
        videoPanel.add(localScreenLabel);
        videoLabels.put(myUserId, localScreenLabel);

        JScrollPane scrollPane = new JScrollPane(videoPanel);
        mainPanel.add(scrollPane, BorderLayout.CENTER);

        JPanel controls = new JPanel(new FlowLayout());
        muteButton = new JButton("🔇 השתק");
        JButton leaveButton = new JButton("🚪 עזוב שיחה");
        shareScreenButton = new JButton("📺 שיתוף מסך");

        muteButton.addActionListener(e -> toggleMute());
        leaveButton.addActionListener(e -> leaveCall());
        shareScreenButton.addActionListener(e -> toggleScreenShare());

        controls.add(muteButton);
        controls.add(leaveButton);
        controls.add(shareScreenButton);
        mainPanel.add(controls, BorderLayout.SOUTH);

        setContentPane(mainPanel);

        // אם סוגרים את ה־JFrame באמצע השיחה, נדאג לנקות את המשאבים
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                streaming = false;
                screenSharing = false;
            }
        });
    }

    /**
     * התחלת סטרימינג של וידאו ואודיו
     */
    private void startStreaming(){
        startVideoStreaming();
        startAudioStreaming();
    }

    /**
     * קריאת וידאו מהמצלמה המקומית ושידור וידאו לשאר המשתתפים
     */
    private void startVideoStreaming() {
        new Thread(() -> {
            Webcam webcam = Webcam.getDefault();
            if (webcam == null) {
                System.err.println("מצלמה לא זמינה");
                return;
            }

            webcam.setViewSize(new Dimension(640, 480));
            webcam.open();

            // מחשבים את מרווח הזמן בין פריים לפריים (בננו־שניות)
            final long FRAME_INTERVAL_NANOS = 1_000_000_000L / 30;
            long lastFrameTime = System.nanoTime();

            while (streaming && webcam.isOpen()) {
                long now = System.nanoTime();
                long elapsed = now - lastFrameTime;

                if (elapsed >= FRAME_INTERVAL_NANOS) {
                    lastFrameTime = now;
                    try {

                        BufferedImage frame = webcam.getImage();
                        if (frame != null && !screenSharing) {
                            int targetWidth = resolutionManager.getTargetWidth();
                            int targetHeight = resolutionManager.getTargetHeight();
                            BufferedImage resized = resizeImage(frame, targetWidth, targetHeight);

                            signalingClient.sendVideoFrame(resized, chatRoomId);
                            updateVideo(myUserId, resized);
                            recorder.recordVideoFrame(myUserId, resized);
                        }

                    } catch (Exception e) {
                        e.printStackTrace();
                        break;
                    }
                } else {
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            webcam.close();
        }, "CameraStreamThread").start();
    }

    /**
     * התחלת שידור אודיו מהמיקרופון - שליחה וניגון
     */
    private void startAudioStreaming() {
        try {
            audioSender = new AudioSender(signalingClient, chatRoomId) {
                @Override
                protected void onAudioChunk(byte[] chunk) {
                    super.onAudioChunk(chunk); // שולח
                    recorder.recordAudioChunk(chunk);  // הוספת הקלטת אודיו
                }
            };
        } catch (OpusException e) {
            System.err.println("שגיאה באתחול AudioSender (Opus): " + e.getMessage());
            e.printStackTrace();
            return;
        }
        try {
            audioReceiver = new AudioReceiver();
        } catch (Exception e){
            System.err.println("שגיאה באתחול AudioReceiver");
            e.printStackTrace();
            return;
        }

        audioSender.start();
    }

    /**
     * עדכון תצוגת הווידאו כאשר מתקבל פריים חדש
     */
    public void updateVideo(String senderId, BufferedImage img) {
        SwingUtilities.invokeLater(() -> {

            //  אם זה המארח עצמו והוא משתף מסך, לא מעדכנים תווית
            if (senderId.equals(myUserId) && screenSharing) {
                return;
            }

            JLabel label = videoLabels.computeIfAbsent(senderId, id -> {
                JLabel lbl = new JLabel("משתמש חדש", SwingConstants.CENTER);
                lbl.setPreferredSize(new Dimension(320, 240));
                lbl.setBorder(BorderFactory.createLineBorder(Color.BLACK));
                videoPanel.add(lbl);
                refreshLayout();
                return lbl;
            });

            if (!senderId.equals(myUserId) && isReceivingScreenFrom(senderId)) {
                label.setPreferredSize(new Dimension(videoPanel.getWidth(), videoPanel.getHeight()));
            } else {
                // אחרת (וידאו רגיל), נשאר בגודל 320×240
                label.setPreferredSize(new Dimension(320, 240));
            }
            label.setIcon(new ImageIcon(img));
            label.setText(null);
            videoPanel.revalidate();
            videoPanel.repaint();
        });
    }

    /**
     * עדכון וידאו ממערך בתים (byte[])
     */
    public void updateVideo(String senderId, byte[] frameBytes) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(frameBytes));
            updateVideo(senderId, img);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * ניגון קטעי אודיו נכנסים
     */
    public void playIncomingAudio(byte[] audioData) {
        if(audioReceiver != null) {
            audioReceiver.playAudio(audioData);
        }
    }

    /**
     * התאמת layout של הלייבלים לאחר הוספה / הסרה
     */
    private void refreshLayout() {
        int count = Math.max(1, videoLabels.size());
        int cols = count <= 2 ? count : 3;
        int rows = (int) Math.ceil((double) count / cols);
        videoPanel.setLayout(new GridLayout(rows, cols, 10, 10));
        videoPanel.revalidate();
        videoPanel.repaint();
    }

    /**
     * מתג השתקה ויצור לחצן מתאים
     */
    private void toggleMute() {
        if (audioSender.isMuted()) {
            audioSender.unmute();
            muteButton.setText("🔇 השתק");
        } else {
            audioSender.mute();
            muteButton.setText("🔈 בטל השתקה");
        }
    }

    /**
     * עזיבת השיחה וסיום החלון
     */
    private void leaveCall() {
        int confirm = JOptionPane.showConfirmDialog(this, "לצאת מהשיחה?", "יציאה", JOptionPane.YES_NO_OPTION);
        if (confirm == JOptionPane.YES_OPTION) {
            dispose();
        }
    }

    /**
     * מתג שיתוף מסך והפעלת שידור מסך
     */
    private void toggleScreenShare() {
        screenSharing = !screenSharing;

        if (screenSharing) {
            JOptionPane.showMessageDialog(
                    this,
                    "אתה מציג כעת את המסך שלך. לחץ שוב על הכפתור כדי להפסיק.",
                    "שיתוף מסך",
                    JOptionPane.INFORMATION_MESSAGE
            );

            shareScreenButton.setText("⛔ עצור שיתוף");
            startScreenSharing();
        } else {
            shareScreenButton.setText("📺 שיתוף מסך");

            // כשעוצרים: נסיר את הלייבל של השיתוף
            SwingUtilities.invokeLater(() -> {
                JLabel lbl = videoLabels.remove(myUserId);
                if (lbl != null) {
                    videoPanel.remove(lbl);
                }

                localScreenLabel = new JLabel("מצלמה מקומית", SwingConstants.CENTER);
                localScreenLabel.setPreferredSize(new Dimension(320, 240));
                localScreenLabel.setBorder(BorderFactory.createLineBorder(Color.BLACK));
                videoLabels.put(myUserId, localScreenLabel);
                videoPanel.add(localScreenLabel);
                refreshLayout();
            });

            screenSharing = false;
            if (screenThread != null) {
                try {
                    screenThread.join(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }


    /**
     * סטרימינג של קפטורות מסך דרך Robot ושידור
     */
    private void startScreenSharing() {
        screenThread = new Thread(() -> {
            try {
                Robot robot = new Robot();
                Rectangle screenRect = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());

                while (screenSharing) {
                    long start = System.currentTimeMillis();

                    BufferedImage screenCapture = robot.createScreenCapture(screenRect);
                    int targetWidth = resolutionManager.getTargetWidth();
                    int targetHeight = resolutionManager.getTargetHeight();
                    BufferedImage resized = resizeImage(
                            screenCapture,
                            targetWidth,
                            targetHeight
                    );

                    // שליחה דרך ה־SignalingClient
                    signalingClient.sendVideoFrame(resized, chatRoomId);

                    // הקלטה אם רוצים
                    recorder.recordVideoFrame(myUserId, resized);

                    // maintain approx 30fps
                    long duration = System.currentTimeMillis() - start;
                    long sleep = FPS - duration;
                    if (sleep > 0) {
                        try { Thread.sleep(sleep); }
                        catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }, "ScreenShareThread");
        screenThread.start();
    }

    /**
     * ניקוי משאבים בסיום החלון: עצירת סטרימינג, הקלטות והעלאה
     */
    @Override
    public void dispose() {
        streaming = false;
        screenSharing = false;

        // סיום שידורי וידאו ואודיו
        if (audioSender != null) audioSender.stop();
        if (audioReceiver != null) audioReceiver.close();
        if(screenThread != null){
            try {
                screenThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        try {
            // שאלה לשמירה: אם המשתמש בוחר "כן" -> נבצע מיזוג והעלאה, אחרת נמחק הכל
            int choice = JOptionPane.showConfirmDialog(this,
                    "האם ברצונך לשמור את ההקלטה ב-Google Drive?",
                    "שמור הקלטה",
                    JOptionPane.YES_NO_OPTION);
            boolean uploadToDrive = (choice == JOptionPane.YES_OPTION);

            // עצירת ההקלטה עם החלטה אם למזג לקובץ MP4
            recorder.stop(uploadToDrive);

            if (uploadToDrive) {
                String recordedFile = recorder.getOutputFilename();

                ChatRoom chatRoom = client.getChatRoomById(chatRoomId, myUserId);
                String folderId = chatRoom.getFolderId();

                if (folderId != null && !folderId.isBlank()) {
                    File fileToUpload = new File(recordedFile);
                    com.google.api.services.drive.model.File fileMetadata = new com.google.api.services.drive.model.File();
                    fileMetadata.setName(fileToUpload.getName());
                    fileMetadata.setParents(java.util.List.of(folderId));

                    FileContent mediaContent = new FileContent("video/mp4", fileToUpload);

                    GoogleDriveInitializer.getOrCreateDriveService()
                            .files()
                            .create(fileMetadata, mediaContent)
                            .setFields("id, size")
                            .execute();

                } else {
                    System.out.println("אין folderId – ההקלטה לא הועלתה.");
                }
            }
            // המשתמש לא רוצה לשמור -> נמחק את כל הקבצים הזמניים
            recorder.cleanUp();

        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            signalingClient.leaveCall(chatRoomId);
            signalingClient.setVideoCallWindow(null);
            signalingClient.shutdown();
        } catch (Exception e){
            System.out.println("שגיאה בסגירת signalingClient: " + e.getMessage());
        }
        super.dispose();
    }

    /**
     * שינוי גודל תמונה בבסיס יעד נתון
     */
    private BufferedImage resizeImage(BufferedImage originalImage, int targetWidth, int targetHeight) {
        Image resultingImage = originalImage.getScaledInstance(targetWidth, targetHeight, Image.SCALE_SMOOTH);
        BufferedImage outputImage = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);

        Graphics2D g2d = outputImage.createGraphics();
        g2d.drawImage(resultingImage, 0, 0, null);
        g2d.dispose();

        return outputImage;
    }

    /**
     * שיטה עזרית שבודקת האם הפריים הנכנס הוא פריימי שיתוף מסך של
     * משתמש אחר (ולא הווידאו הרגיל). בהנחה שהמשתתף ששולח פריימי
     * עם senderId == myUserId בזמן שיתוף, אצל כל שמקבל אותו screenSharing=false,
     * אז נזהה את זה כ"פריימי שיתוף מסך".
     */
    private boolean isReceivingScreenFrom(String senderId) {
        return !senderId.equals(myUserId) && !screenSharing;
    }

}

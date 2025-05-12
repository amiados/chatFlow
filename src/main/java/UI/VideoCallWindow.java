package UI;

import client.ChatClient;
import com.chatFlow.Chat.*;
import client.AudioReceiver;
import client.AudioSender;
import client.SignalingClient;
import com.github.sarxos.webcam.Webcam;
import model.User;
import utils.CallRecorder;
import utils.DynamicResolutionManager;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
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


public class VideoCallWindow extends JFrame {

    private final SignalingClient signalingClient;
    private final ChatClient client;
    private final String chatRoomId;
    private final String myUserId;
    private final User user;

    private final Map<String, JLabel> videoLabels = new ConcurrentHashMap<>();
    private final JPanel videoPanel = new JPanel(new GridLayout(1, 1, 10, 10));

    private AudioSender audioSender;
    private AudioReceiver audioReceiver;

    // מנהל ההקלטה המתקדמת (וידאו + אודיו)
    private CallRecorder recorder;
    private volatile boolean streaming = true;

    private JButton muteButton;

    private final DynamicResolutionManager resolutionManager = new DynamicResolutionManager();

    public VideoCallWindow(SignalingClient signalingClient, String chatRoomId, User user, ChatClient client) {
        this.signalingClient = signalingClient;
        this.chatRoomId = chatRoomId;
        this.myUserId = user.getId().toString();
        this.user = user;
        this.client = client;

        initRecorder();
        initUI();
        startStreaming();
    }

    // אתחול מקליט
    private void initRecorder() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String outputFile = "recordings/rec_" + timestamp + ".mp4";
        recorder = new CallRecorder(outputFile);
        recorder.start();
    }

    /**
     * בניית ממשק המשתמש
     */
    private void initUI() {

        setTitle("וידאו צ'אט - " + chatRoomId);
        setSize(1200, 900);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        JScrollPane scrollPane = new JScrollPane(videoPanel);
        mainPanel.add(scrollPane, BorderLayout.CENTER);

        JPanel controls = new JPanel(new FlowLayout());
        muteButton = new JButton("🔇 השתק");
        JButton leaveButton = new JButton("🚪 עזוב שיחה");

        muteButton.addActionListener(e -> toggleMute());
        leaveButton.addActionListener(e -> leaveCall());

        controls.add(muteButton);
        controls.add(leaveButton);
        mainPanel.add(controls, BorderLayout.SOUTH);

        setContentPane(mainPanel);
    }

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

            while (streaming && webcam.isOpen()) {
                try {
                    long start = System.currentTimeMillis();

                    BufferedImage frame = webcam.getImage();
                    if (frame != null) {
                        int targetWidth = resolutionManager.getTargetWidth();
                        int targetHeight = resolutionManager.getTargetHeight();
                        BufferedImage resized = resizeImage(frame, targetWidth, targetHeight);

                        signalingClient.sendVideoFrame(resized, chatRoomId);
                        updateVideo(myUserId, resized);
                        recorder.recordVideoFrame(myUserId, resized);
                    }

                    long duration = System.currentTimeMillis() - start;
                    resolutionManager.adjustResolution(duration);

                    Thread.sleep(50); // 20FPS
                } catch (Exception e) {
                    e.printStackTrace();
                    break;
                }
            }

            webcam.close();
        }).start();
    }

    /**
     * התחלת שידור אודיו מהמיקרופון - שליחה וניגון
     */
    private void startAudioStreaming() {
        audioSender = new AudioSender(signalingClient, chatRoomId){
            @Override
            protected void onAudioChunk(byte[] chunk) {
                super.onAudioChunk(chunk); // שולח
                recorder.recordAudioChunk(chunk);  // הוספת הקלטת אודיו
            }
        };
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
            JLabel label = videoLabels.computeIfAbsent(senderId, id -> {
                JLabel lbl = new JLabel("משתמש חדש", SwingConstants.CENTER);
                lbl.setPreferredSize(new Dimension(320, 240));
                lbl.setBorder(BorderFactory.createLineBorder(Color.BLACK));
                videoPanel.add(lbl);
                refreshLayout();
                return lbl;
            });
            label.setIcon(new ImageIcon(img));
            label.setText(null);
        });
    }

    public void updateVideo(String senderId, byte[] frameBytes) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(frameBytes));
            updateVideo(senderId, img);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    /**
     * הפעלת AudioReceiver לנגינת קלט אודיו
     */
    public void playIncomingAudio(byte[] audioData) {
        if(audioReceiver != null) {
            audioReceiver.playAudio(audioData);
        }
    }

    private void refreshLayout() {
        int count = Math.max(1, videoLabels.size());
        int cols = count <= 2 ? count : 3;
        int rows = (int) Math.ceil((double) count / cols);
        videoPanel.setLayout(new GridLayout(rows, cols, 10, 10));
        videoPanel.revalidate();
        videoPanel.repaint();
    }


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
     * נקרא כשמסיימים את חלון השיחה: מפסיק שידור וכל ההקלטות
     */
    @Override
    public void dispose() {
        streaming = false;

        // סיום שידורי וידאו ואודיו
        if (audioSender != null) audioSender.stop();
        if (audioReceiver != null) audioReceiver.close();

        try {
            recorder.stop();
            String recordedFile = recorder.getOutputFilename();
            System.out.println("Recording saved to: " + recordedFile);

            int choice = JOptionPane.showConfirmDialog(this, "האם ברצונך לשמור את ההקלטה ב-Google Drive?", "שמור הקלטה", JOptionPane.YES_NO_OPTION);
            boolean uploadToDrive = (choice == JOptionPane.YES_OPTION);

            if (uploadToDrive) {
                ChatRoomRequest chatRequest = ChatRoomRequest.newBuilder()
                        .setChatId(chatRoomId)
                        .setToken(user.getAuthToken())
                        .setRequesterId(myUserId)
                        .build();

                ChatRoom chatRoom = client.getChatRoomById(chatRequest);
                String folderId = chatRoom.getFolderId();

                if (folderId != null && !folderId.isBlank()) {
                    File fileToUpload = new File(recordedFile);
                    com.google.api.services.drive.model.File fileMetadata = new com.google.api.services.drive.model.File();
                    fileMetadata.setName(fileToUpload.getName());
                    fileMetadata.setParents(java.util.List.of(folderId));

                    FileContent mediaContent = new FileContent("video/mp4", fileToUpload);

                    com.google.api.services.drive.model.File uploadedFile = GoogleDriveInitializer.getOrCreateDriveService()
                            .files()
                            .create(fileMetadata, mediaContent)
                            .setFields("id")
                            .execute();

                    System.out.println("הקלטה הועלתה ל-Drive בהצלחה. קובץ ID: " + uploadedFile.getId());
                } else {
                    System.out.println("אין folderId – ההקלטה לא הועלתה.");
                }
            }

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

    private BufferedImage resizeImage(BufferedImage originalImage, int targetWidth, int targetHeight) {
        Image resultingImage = originalImage.getScaledInstance(targetWidth, targetHeight, Image.SCALE_SMOOTH);
        BufferedImage outputImage = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);

        Graphics2D g2d = outputImage.createGraphics();
        g2d.drawImage(resultingImage, 0, 0, null);
        g2d.dispose();

        return outputImage;
    }
}

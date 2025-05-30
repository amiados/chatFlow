package utils;

import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.*;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * שירות לניהול תיקיות וקבצים ב-Google Drive:
 * יצירת תיקיות, שיתוף הרשאות, העתקת קבצים וניהול הרשאות משתמש.
 */
public class DriverService {
    private static final Drive driveService = GoogleDriveInitializer.getOrCreateDriveService();
    private static final int MAX_RETRIES = 3;
    private static final long BACKOFF_MS = 500;

    /**
     * יוצר תיקיה חדשה ב-Drive בשם groupName.
     * @param groupName השם לתיקיה
     * @return מזהה התיקיה שנוצרה
     * @throws IOException במקרה של שגיאת רשת או הרשאה
     */
    public static String createGroupFolder(String groupName) throws IOException {
        File fileMetadata = new File();
        fileMetadata.setName(groupName);
        fileMetadata.setMimeType("application/vnd.google-apps.folder");

        File folder = driveService.files()
                .create(fileMetadata)
                .setFields("id")
                .execute();

        return folder.getId();
    }

    /**
     * משתף תיקיה עם משתמש אם לא שותפה עדיין.
     * @param folderId מזהה התיקיה
     * @param userEmail כתובת המייל של המשתמש
     * @throws IOException במקרה של שגיאת API
     */
    public static void shareFolderWithUser(String folderId, String userEmail) throws IOException {
        Set<String> existing = listPermissionEmails(folderId);
        if (existing.contains(userEmail)) {
            return; // כבר שותפה
        }
        Permission permission = new Permission()
                .setType("user")
                .setRole("commenter")
                .setEmailAddress(userEmail);

        driveService.permissions()
                .create(folderId, permission)
                .setSendNotificationEmail(false)
                .execute();
    }

    /**
     * יוצר תיקיה פרטית לארכיון למשתמש ומעניק הרשאת "reader".
     * @param userEmail כתובת המייל של המשתמש
     * @param groupName שם הקבוצה לצורך שם התיקיה
     * @return מזהה התיקיה שנוצרה
     * @throws IOException במקרה של שגיאה
     */
    public static String createPrivateFolderForUser(String userEmail, String groupName) throws IOException {
        String folderName = "Archive_" + groupName + "_" + userEmail;
        File metadata = new File();
        metadata.setName(folderName);
        metadata.setMimeType("application/vnd.google-apps.folder");

        File folder = driveService.files()
                .create(metadata)
                .setFields("id")
                .execute();

        Permission permission = new Permission()
                .setType("user")
                .setRole("reader")
                .setEmailAddress(userEmail);

        driveService.permissions()
                .create(folder.getId(), permission)
                .execute();

        return folder.getId();
    }

    /**
     * מעתיק את כל הקבצים מתיקיית מקור לתיקיית יעד, כולל pagination ו-retry.
     * @param sourceFolderId התיקיה המקורית
     * @param destinationFolderId התיקיה היעד
     * @throws IOException במקרה של שגיאת Drive
     */
    public static void copyFilesFromFolder(String sourceFolderId, String destinationFolderId) throws IOException {
        String pageToken = null;
        do {
            FileList files = driveService.files().list()
                    .setQ("'" + sourceFolderId + "' in parents and trashed = false")
                    .setFields("nextPageToken, files(id, name)")
                    .setPageSize(1000)
                    .setPageToken(pageToken)
                    .execute();

            for (File file : files.getFiles()) {
                File copied = new File()
                        .setName(file.getName())
                        .setParents(List.of(destinationFolderId));
                executeWithRetry(() -> driveService.files().copy(file.getId(), copied).execute());
            }
            pageToken = files.getNextPageToken();
        } while (pageToken != null);
    }

    /**
     * מסיר הרשאת משתמש מתיקיה.
     * @param folderId מזהה התיקיה
     * @param userEmail כתובת המייל להסרה
     * @throws IOException במקרה של שגיאה
     */
    public static void removeUserFromFolder(String folderId, String userEmail) throws IOException {
        PermissionList perms = driveService.permissions()
                .list(folderId)
                .execute();
        for (Permission p : perms.getPermissions()) {
            if (userEmail.equals(p.getEmailAddress())) {
                driveService.permissions().delete(folderId, p.getId()).execute();
                break;
            }
        }
    }

    // --- Helpers ---

    /**
     * מחזיר את כל כתובות המייל של מי שיש לו הרשאות בתיקיה.
     */
    private static Set<String> listPermissionEmails(String folderId) throws IOException {
        PermissionList perms = driveService.permissions().list(folderId).execute();
        Set<String> emails = new HashSet<>();
        for (Permission p : perms.getPermissions()) {
            emails.add(p.getEmailAddress());
        }
        return emails;
    }

    @FunctionalInterface
    private interface DriveOperation {
        void run() throws IOException;
    }

    /**
     * מפעיל פעולה עם retry אוטומטי עד MAX_RETRIES ו-backoff.
     */
    private static void executeWithRetry(DriveOperation op) throws IOException {
        int attempts = 0;
        while (true) {
            try {
                op.run();
                return;
            } catch (IOException e) {
                if (++attempts >= MAX_RETRIES) throw e;
                try { TimeUnit.MILLISECONDS.sleep(BACKOFF_MS); } catch (InterruptedException ignored) {}
            }
        }
    }

    /**
     * דוגמה להפעלה מקומית של פונקציות.
     */
    public static void main(String[] args) {
        try {
            FileList result = driveService.files().list()
                    .setPageSize(10)
                    .setFields("files(id, name)")
                    .execute();
            System.out.println("Top 10 files:");
            for (File file : result.getFiles()) {
                System.out.printf("%s (%s)%n", file.getName(), file.getId());
            }

            String demoFolder = createGroupFolder("DemoFolder");
            System.out.println("Created folder: " + demoFolder);
            shareFolderWithUser(demoFolder, "user@example.com");
            System.out.println("Shared with user@example.com");
        } catch (IOException e) {
            System.err.println("Error in DriverService main: " + e.getMessage());
            e.printStackTrace();
        }
    }
}

package utils;

import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.*;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class DriverService {
    private static final Drive driveService = GoogleDriveInitializer.getOrCreateDriveService(); // מחלקה שמחזירה Drive מאומת
    private static final int MAX_RETRIES = 3;
    private static final long BACKOFF_MS = 500;

    /**
     * Creates a new folder in Drive with the given name.
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
     * Shares the folder with the given user email if not already shared.
     */
    public static void shareFolderWithUser(String folderId, String userEmail) throws IOException {
        Set<String> existing = listPermissionEmails(folderId);
        if(existing.contains(userEmail)){
            return; // already shared
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
     * Creates a private archive folder for a user and grants reader permission.
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

        // שתף עם המשתמש
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
     * Copies all files from source folder to destination folder, handling pagination and simple retries.
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
                //driveService.files().copy(file.getId(), copied).execute();
            }
            pageToken = files.getNextPageToken();
        } while (pageToken != null);
    }

    /**
     * Removes the given user's permission from the folder.
     */
    public static void removeUserFromFolder(String folderId, String userEmail) throws IOException {
        PermissionList permissions = driveService.permissions()
                .list(folderId)
                .execute();
        for (Permission permission : permissions.getPermissions()) {
            if (userEmail.equals(permission.getEmailAddress())) {
                driveService.permissions().delete(folderId, permission.getId()).execute();
                break;
            }
        }
    }

    // --- Helpers ---
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

    public static void main(String[] args) {
        try {
            // List first 10 files
            FileList result = driveService.files().list()
                    .setPageSize(10)
                    .setFields("files(id, name)")
                    .execute();
            System.out.println("Top 10 files:");
            for (File file : result.getFiles()) {
                System.out.printf("%s (%s)%n", file.getName(), file.getId());
            }

            // Create and share a demo folder
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
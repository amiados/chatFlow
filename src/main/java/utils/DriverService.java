package utils;

import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.*;

import java.io.IOException;
import java.util.List;

public class DriverService {
    private static final Drive driveService = GoogleDriveInitializer.getOrCreateDriveService(); // מחלקה שמחזירה Drive מאומת

    public static String createGroupFolder(String groupName) throws IOException {
        File fileMetadata = new File();
        fileMetadata.setName(groupName);
        fileMetadata.setMimeType("application/vnd.google-apps.folder");

        File folder = driveService.files().create(fileMetadata)
                .setFields("id")
                .execute();

        return folder.getId();
    }

    public static void shareFolderWithUser(String folderId, String userEmail) throws IOException {
        Permission permission = new Permission()
                .setType("user")
                .setRole("commenter")
                .setEmailAddress(userEmail);

        driveService.permissions().create(folderId, permission)
                .setSendNotificationEmail(false)
                .execute();
    }

    //  יצירת תיקיית "היסטוריה" אישית
    public static String createPrivateFolderForUser(String userEmail, String groupName) throws IOException {
        String folderName = "Archive_" + groupName + "_" + userEmail;

        File metadata = new File();
        metadata.setName(folderName);
        metadata.setMimeType("application/vnd.google-apps.folder");

        File folder = driveService.files().create(metadata)
                .setFields("id")
                .execute();

        // שתף עם המשתמש
        Permission permission = new Permission()
                .setType("user")
                .setRole("reader")
                .setEmailAddress(userEmail);

        driveService.permissions().create(folder.getId(), permission).execute();

        return folder.getId();
    }

    // העתקת קבצים מהתיקייה הראשית
    public static void copyFilesFromFolder(String sourceFolderId, String destinationFolderId) throws IOException {
        FileList files = driveService.files().list()
                .setQ("'" + sourceFolderId + "' in parents and trashed = false")
                .setFields("files(id, name)")
                .execute();

        for (File file : files.getFiles()) {
            File copied = new File();
            copied.setName(file.getName());
            copied.setParents(List.of(destinationFolderId));

            driveService.files().copy(file.getId(), copied).execute();
        }
    }

    // הסרת ההרשאות מהתיקייה הראשית
    public static void removeUserFromFolder(String folderId, String userEmail) throws IOException {
        PermissionList permissions = driveService.permissions().list(folderId).execute();
        for (Permission permission : permissions.getPermissions()) {
            if (userEmail.equals(permission.getEmailAddress())) {
                driveService.permissions().delete(folderId, permission.getId()).execute();
                break;
            }
        }
    }

}

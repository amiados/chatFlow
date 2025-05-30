package utils;

import com.google.api.client.auth.oauth2.TokenResponseException;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

public class GoogleDriveInitializer {

    private static final String APPLICATION_NAME = "ChatFlow Drive Integration";
    private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
    private static final String TOKENS_DIRECTORY_PATH = "tokens";
    private static final List<String> SCOPES = List.of(DriveScopes.DRIVE_FILE);
    private static final String CREDENTIALS_FILE_PATH = "/credentials.json";
    private static Drive driveService = null;

    /**
     * מחזיר מופע Drive מוכן לשימוש: יוצר או מרענן אישורי OAuth2 במידת הצורך.
     * @return מופע Drive מאומת
     */
    public static Drive getOrCreateDriveService() {
        try {
            if (driveService == null || isTokenExpired(getCredentials())) {
                driveService = createDriveService();
            }
        } catch (IOException | GeneralSecurityException e) {
            throw new RuntimeException("Failed to initialize Google Drive: " + e.getMessage(), e);
        }
        return driveService;
    }

    /**
     * יוצר מופע Drive חדש עם אישורים תקפים (כולל עדכון טוקן במקרה הצורך).
     * @throws IOException במקרה של שגיאת רשת
     * @throws GeneralSecurityException במקרה של שגיאת אבטחה
     */
    public static Drive createDriveService() throws IOException, GeneralSecurityException{
        final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
        Credential credential = getCredentials();

        // אם הטוקן עומד לפוג, חידוש הטוקן
        if(isTokenExpired(credential)){
            System.out.println("Token expired. Attempting to refresh...");
            credential = refreshToken(credential);
        }

        // בנה את ה-Drive Service עם הטוקן החדש אם היה צורך בחידוש
        return new Drive.Builder(HTTP_TRANSPORT, JSON_FACTORY, credential)
                .setApplicationName(APPLICATION_NAME)
                .build();

    }

    /**
     * בודק האם טוקן הגישה פג תוקף.
     */
    private static boolean isTokenExpired(Credential credential){
        if(credential == null) return true;
        Long expirationTimeMillis = credential.getExpirationTimeMilliseconds();
        return expirationTimeMillis == null || System.currentTimeMillis() >= expirationTimeMillis;
    }

    /**
     * מרענן את טוקן הגישה באמצעות טוקן רענון, או מאתחל מחדש אם הרענון נכשל.
     */
    private static Credential refreshToken(Credential credential) throws IOException, GeneralSecurityException {
        if (credential.getRefreshToken() == null) {
            // אין כלל refresh token – נריץ authorize כדי לחדש הכול
            return authorize();
        }
        try {
            return credential.refreshToken()
                    ? credential
                    : authorize();
        } catch (TokenResponseException e) {
            // אם ה־refresh token בוטל או פג – נזרוק מאגר ונחייב re-consent
            deleteStoredCredential();
            return authorize();
        }
    }

    /**
     * מוחק קבצי טוקן ישנים כדי לכרות Refresh Token פג.
     */
    private static void deleteStoredCredential() {
        try {
            java.io.File tokensDir = new java.io.File(TOKENS_DIRECTORY_PATH);
            if (tokensDir.exists()) {
                for (java.io.File file : tokensDir.listFiles()) {
                    file.delete();
                }
            }
        } catch (Exception ex) {
            System.err.println("Failed to delete stored credentials: " + ex.getMessage());
        }
    }

    /**
     * מבצע authorization חדש ושומר את האישורים במאגר.
     */
    private static Credential authorize() throws IOException, GeneralSecurityException {
        GoogleAuthorizationCodeFlow flow = getFlow();
        return new AuthorizationCodeInstalledApp(flow, new LocalServerReceiver()).authorize("user");
    }

    /**
     * טוען או יוצר אישורי OAuth2 מהמאגר.
     */
    private static Credential getCredentials() throws IOException, GeneralSecurityException {
        InputStream in = GoogleDriveInitializer.class.getResourceAsStream(CREDENTIALS_FILE_PATH);
        if (in == null) {
            throw new IOException("Resource not found: " + CREDENTIALS_FILE_PATH);
        }

        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));

        FileDataStoreFactory storeFactory =
                new FileDataStoreFactory(new java.io.File(TOKENS_DIRECTORY_PATH));

        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                JSON_FACTORY,
                clientSecrets,
                SCOPES)
                .setDataStoreFactory(storeFactory)
                .setAccessType("offline")
                .setApprovalPrompt("force")
                .build();

        Credential cred = flow.loadCredential("user");
        if (cred == null) {
            // אם אין credential שמור, נאשר את המשתמש בפעם הראשונה
            cred = new AuthorizationCodeInstalledApp(flow, new LocalServerReceiver()).authorize("user");
        }

        return cred;
    }

    /**
     * בונה ומחזיר GoogleAuthorizationCodeFlow לאתחול או חידוש tokens.
     */
    private static GoogleAuthorizationCodeFlow getFlow() throws IOException, GeneralSecurityException {
        InputStream in = GoogleDriveInitializer.class.getResourceAsStream(CREDENTIALS_FILE_PATH);
        if (in == null) {
            throw new IOException("Resource not found: " + CREDENTIALS_FILE_PATH);
        }

        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));

        return new GoogleAuthorizationCodeFlow.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                JSON_FACTORY,
                clientSecrets,
                SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(new java.io.File(TOKENS_DIRECTORY_PATH)))
                .setAccessType("offline")
                .setApprovalPrompt("force") // Always requests a new refresh token
                .build();
    }

    /**
     * דוגמת שימוש בקריאת Drive והצגת עשרת הקבצים הראשונים.
     */
    public static void main(String[] args) {
        try {
            Drive drive = getOrCreateDriveService();

            // List the first 10 files in the user's Drive
            FileList result = drive.files().list()
                    .setPageSize(10)
                    .setFields("files(id, name)")
                    .execute();

            System.out.println("Files:");
            for (File file : result.getFiles()) {
                System.out.printf("%s (%s)%n", file.getName(), file.getId());
            }

        } catch (Exception e) {
            System.err.println("Error in Google Drive demo: " + e.getMessage());
            e.printStackTrace();
        }
    }


}
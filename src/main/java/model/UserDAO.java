package model;

import security.Token;

import java.io.IOException;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

/**
 * UserDAO היא מחלקת גישה למסד נתונים (Data Access Object) שמנהלת את פעולות השאילתה והעדכון
 * על טבלת המשתמשים במערכת. היא מספקת CRUD מלא, וכן יכולות אימות וניהול מצב התחברות.
 */
public class UserDAO {

    /**
     * יוצר מופע חדש של UserDAO עם חיבור למסד הנתונים.
     */
    public UserDAO() {
    }

    /**
     * יוצר משתמש חדש בטבלת המשתמשים במסד הנתונים.
     * @param user אובייקט מסוג User עם נתוני המשתמש.
     * @return true אם ההוספה הצליחה, אחרת false.
     * @throws SQLException אם מתרחשת שגיאה במסד הנתונים.
     */
    public boolean createUser(User user) throws SQLException    {
        String sql = """
        INSERT INTO Users 
        (Id, Username, PasswordHash, Email, Verified, Online, AuthToken, LastLogin, PublicKey, PrivateKey, N)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, user.getId().toString());
            stmt.setString(2, user.getUsername());
            stmt.setString(3, user.getPasswordHash());
            stmt.setString(4, user.getEmail());
            stmt.setBoolean(5, user.isVerified());
            stmt.setBoolean(6, user.isOnline());
            stmt.setString(7, user.getAuthToken());
            stmt.setTimestamp(8, Timestamp.from(user.getLastLogin()));
            stmt.setBytes(9, user.getPublicKey());
            stmt.setBytes(10, user.getPrivateKey());
            stmt.setBytes(11,user.getN());
            return stmt.executeUpdate() > 0;
        }
    }


    /**
     * מחזיר את המשתמש לפי מזהה ייחודי (UUID).
     * @param userId מזהה המשתמש.
     * @return אובייקט User אם נמצא, אחרת null.
     * @throws SQLException אם מתרחשת שגיאה במסד הנתונים.
     */
    public User getUserById(UUID userId) throws SQLException {
        return getUser("SELECT * FROM Users WHERE Id = ?", userId.toString());
    }

    /**
     * מחפש משתמש לפי כתובת דוא"ל.
     * @param email כתובת האימייל של המשתמש.
     * @return אובייקט User אם נמצא, אחרת null.
     * @throws SQLException אם מתרחשת שגיאה במסד הנתונים.
     * @throws IOException אם מתרחשת שגיאה בקריאה.
     */
    public User getUserByEmail(String email) throws SQLException, IOException {
        return getUser("SELECT * FROM Users WHERE Email = ?", email);
    }

    // מתודת עזר למציאה
    private User getUser(String sql, Object param) throws SQLException {
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, param.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? mapUser(rs) : null;
            }
        }
    }

    /**
     * מעדכן את פרטי המשתמש במסד הנתונים. רק שדות שאינם null יעודכנו.
     * @param user האובייקט המכיל את הערכים החדשים.
     * @return true אם העדכון הצליח, אחרת false.
     */
    public boolean updateUser(User user) {
        StringBuilder query = new StringBuilder("UPDATE Users SET ");
        ArrayList<Object> params = new ArrayList<>();

        if (user.getUsername() != null) {
            query.append("Username = ?, ");
            params.add(user.getUsername());
        }
        if (user.getPasswordHash() != null) {
            query.append("PasswordHash = ?, ");
            params.add(user.getPasswordHash());
        }
        if (user.getEmail() != null) {
            query.append("Email = ?, ");
            params.add(user.getEmail());
        }

        query.append("Verified = ?, ");
        params.add(user.isVerified());

        query.append("Online = ?, ");
        params.add(user.isOnline());

        if (user.getAuthToken() != null) {
            query.append("AuthToken = ?, ");
            params.add(user.getAuthToken());
        }
        if (user.getLastLogin() != null) {
            query.append("LastLogin = ?, ");
            params.add(user.getLastLogin());
        }

        // מחיקת פסיק מיותר בסוף השאילתה
        if (params.isEmpty()) {
            throw new IllegalArgumentException("No fields to update");
        }

        query.setLength(query.length() - 2);

        // הוספת תנאי WHERE כדי לעדכן רק משתמש מסוים
        query.append(" WHERE Id = ?");
        params.add(user.getId());

        // ביצוע השאילתה עם הפרמטרים שנאספו
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query.toString())) {

            for (int i = 0; i < params.size(); i++) {
                Object param = params.get(i);

                if (param instanceof UUID) {
                    stmt.setString(i + 1, param.toString());
                } else if (param instanceof Instant) {
                    stmt.setTimestamp(i + 1, Timestamp.from((Instant) param));
                } else if (param instanceof Boolean) {
                    stmt.setBoolean(i + 1, (Boolean) param);
                } else {
                    stmt.setObject(i + 1, param);
                }
            }

            return stmt.executeUpdate() > 0;
        }catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * מעדכן את הטוקן, סטטוס ההתחברות וזמן ההתחברות האחרון.
     * @param user אובייקט המשתמש לעדכון.
     * @return true אם הצליח, אחרת false.
     * @throws SQLException אם מתרחשת שגיאה במסד הנתונים.
     */
    public boolean updateUserLoginState(User user) throws SQLException {
        String sql = "UPDATE Users SET AuthToken = ?, Online = ?, LastLogin = ? WHERE Id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, user.getAuthToken());
            stmt.setBoolean(2, user.isOnline());
            stmt.setTimestamp(3, Timestamp.from(user.getLastLogin()));
            stmt.setObject(4, user.getId());
            return stmt.executeUpdate() > 0;
        }
    }

    /**
     * מעדכן את הטוקן של המשתמש בלבד.
     * @param user המשתמש לעדכון.
     * @param token טוקן חדש.
     * @return true אם הצליח, אחרת false.
     * @throws SQLException אם מתרחשת שגיאה במסד הנתונים.
     */
    public boolean updateToken(User user, Token token) throws SQLException {
        String sql = "UPDATE Users SET AuthToken = ? WHERE Id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, token.getToken());
            stmt.setObject(2, user.getId());
            return stmt.executeUpdate() > 0;
        }

    }

    /**
     * ממפה תוצאה ממסד הנתונים לאובייקט מסוג User.
     * @param rs תוצאת השאילתה.
     * @return אובייקט מסוג User.
     * @throws SQLException אם מתרחשת שגיאה בגישה לנתונים.
     */
    public static User mapUser(ResultSet rs) throws SQLException {
        String idString = rs.getString("Id");
        UUID id = idString != null ? UUID.fromString(idString) : null;
        String username = rs.getString("Username");
        String email = rs.getString("Email");
        String passwordHash = rs.getString("PasswordHash");
        boolean verified = rs.getBoolean("Verified");
        boolean online = rs.getBoolean("Online");
        String authToken = rs.getString("AuthToken");
        Timestamp lastLoginTs = rs.getTimestamp("LastLogin");
        Instant lastLogin = lastLoginTs != null ? lastLoginTs.toInstant() : null;
        byte[] publicKey = rs.getBytes("PublicKey");
        byte[] privateKey = rs.getBytes("PrivateKey");
        byte[] N = rs.getBytes("N");

        User user = new User(id, username, email, passwordHash, publicKey, privateKey, N);
        user.setVerified(verified);
        user.setOnline(online);
        user.setAuthToken(authToken);
        user.setLastLogin(lastLogin);

        return user;
    }

    /**
     * בודק האם המשתמש מחובר כרגע למערכת.
     * @param user המשתמש לבדיקה.
     * @return true אם מחובר, אחרת false.
     * @throws SQLException אם מתרחשת שגיאה במסד הנתונים.
     */
    public boolean isUserOnline(User user) throws SQLException {
        String sql = "SELECT Online FROM Users WHERE Id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, user.getId());
            try (ResultSet rs = stmt.executeQuery()){
                if (rs.next()) {
                    return rs.getBoolean("Online");
                }
            }
        }
        return false;
    }

    /**
     * בודק האם המשתמש מאומת (verified).
     * @param user המשתמש לבדיקה.
     * @return true אם מאומת, אחרת false.
     * @throws SQLException אם מתרחשת שגיאה במסד הנתונים.
     */
    public boolean isUserVerified(User user) throws SQLException {
        String sql = "SELECT Verified FROM Users WHERE Id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, user.getId());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getBoolean("Verified");
                }
            }
        }
        return false;
    }

    /**
     * שולף את טוקן ההתחברות של המשתמש לפי מזהה.
     * @param userId מזהה המשתמש.
     * @return טוקן אם נמצא, אחרת null.
     * @throws SQLException אם מתרחשת שגיאה במסד הנתונים.
     */
    public String getTokenByUserId(UUID userId) throws SQLException {
        String sql = "SELECT AuthToken FROM Users WHERE id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("AuthToken");
                }
            }
        }
        return null;
    }

    /**
     * מחזיר את המפתח הציבורי וה-N (מודולו) של המשתמש.
     * @param userId מזהה המשתמש.
     * @return מערך בגודל 2 עם המפתח הציבורי וה-N, או null אם לא נמצא.
     * @throws SQLException אם מתרחשת שגיאה במסד הנתונים.
     */
    public KeyPair getPublicKeyAndN(UUID userId) throws SQLException {
        String sql = "SELECT PublicKey, N FROM Users WHERE id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    byte[] publicKey = rs.getBytes("PublicKey");
                    byte[] N = rs.getBytes("N");
                    return new KeyPair(publicKey, N);
                }
            }
        }
        return null;
    }

    public static class KeyPair {
        private byte[] publicKey;
        private byte[] N;

        public KeyPair(byte[] publicKey, byte[] N) {
            this.publicKey = publicKey;
            this.N = N;
        }

        public byte[] getPublicKey() {
            return publicKey;
        }

        public byte[] getN() {
            return N;
        }
    }


}

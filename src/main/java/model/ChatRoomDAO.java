package model;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;


/**
 * DAO עבור ניהול חדרי צ'אט במסד הנתונים.
 * אחראי על פעולות CRUD מול הטבלאות Chats ו-ChatMembers.
 */
public class ChatRoomDAO {
    /**
     * יוצר מופע חדש של ChatRoomDAO.
     */
    public ChatRoomDAO(){
    }

    /**
     * יוצר חדר צ'אט חדש במסד הנתונים.
     *
     * @param chatRoom אובייקט ChatRoom עם נתוני החדר
     * @throws SQLException במקרה של שגיאה במסד הנתונים
     */
    public void createChatRoom(ChatRoom chatRoom) throws SQLException {
        String sql = """
    INSERT INTO Chats (Id, Name, CreatedAt, CreatedBy, FolderId, LastMessageTime)
    VALUES (?, ?, ?, ?, ?, ?)
    """;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, chatRoom.getChatId());
            stmt.setString(2, chatRoom.getName());
            stmt.setTimestamp(3, Timestamp.from(chatRoom.getCreatedAt()));
            stmt.setObject(4, chatRoom.getCreatedBy());
            stmt.setString(5, chatRoom.getFolderId());
            stmt.setTimestamp(6, Timestamp.from(Instant.now()));
            stmt.executeUpdate();
        }
    }

    /**
     * מאחזר חדר צ'אט לפי מזהה.
     * @param chatId מזהה החדר
     * @return אובייקט ChatRoom עם המידע, או null אם לא קיים
     * @throws SQLException במקרה של שגיאה במסד הנתונים
     */
    public ChatRoom getChatRoomById(UUID chatId) throws SQLException {
        String chatQuery = "SELECT * FROM Chats WHERE Id = ?";
        String membersQuery = "SELECT * FROM ChatMembers WHERE ChatId = ?";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement chatStmt = conn.prepareStatement(chatQuery);
             PreparedStatement membersStmt = conn.prepareStatement(membersQuery)) {

            chatStmt.setObject(1, chatId);
            try (ResultSet rs = chatStmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }

                String name = rs.getString("Name");
                Instant createdAt = rs.getTimestamp("CreatedAt").toInstant();
                UUID createdBy = UUID.fromString(rs.getString("CreatedBy"));
                String folderId = rs.getString("FolderId");

                Timestamp lastMessageTimestamp  = rs.getTimestamp("LastMessageTime");
                Instant lastMessageTime = (lastMessageTimestamp  != null) ? lastMessageTimestamp.toInstant() : Instant.now();

                ChatRoom chatRoom = new ChatRoom(chatId, name, createdBy, createdAt, folderId, null);
                chatRoom.setLastMessageTime(lastMessageTime);

                membersStmt.setObject(1, chatId);
                try (ResultSet memberRs = membersStmt.executeQuery()) {
                    while (memberRs.next()) {
                        UUID userId = UUID.fromString(memberRs.getString("UserId"));
                        ChatRole role = ChatRole.valueOf(memberRs.getString("Role").toUpperCase());
                        Instant joinDate = memberRs.getTimestamp("JoinDate").toInstant();
                        InviteStatus inviteStatus = InviteStatus.valueOf(memberRs.getString("InviteStatus"));
                        byte[] encryptedKey = memberRs.getBytes("EncryptedPersonalGroupKey");
                        int unreadMessages = memberRs.getInt("UnreadMessages");

                        ChatMember member = new ChatMember(chatId, userId, role, joinDate, inviteStatus, encryptedKey);
                        member.setUnreadMessages(unreadMessages);
                        chatRoom.getMembers().put(userId, member);
                    }
                }

                return chatRoom;
            }
        }
    }

    /**
     * מאחזר את כל חדרי הצ'אט בהם חבר משתמש מסוים.
     * @param userId מזהה המשתמש
     * @return רשימת חדרים
     * @throws SQLException במקרה של שגיאה במסד הנתונים
     */
    public ArrayList<ChatRoom> getAllChatRooms(UUID userId) throws SQLException {
        ArrayList<ChatRoom> chatRooms = new ArrayList<>();
        String sql = """
        SELECT C.* FROM Chats C
        JOIN ChatMembers Cm ON Cm.ChatId = C.Id
        WHERE Cm.UserId = ?
        ORDER BY
        CASE WHEN C.LastMessageTime IS NULL THEN 1 ELSE 0 END,
        C.LastMessageTime DESC
        """;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    chatRooms.add(mapChatRoom(rs));
                }
            }
        }
        return chatRooms;
    }

    /**
     * משנה את שם החדר. מותר רק לחברים בחדר.
     * @param user מזהה המשתמש שמבצע את השינוי
     * @param chatId מזהה החדר
     * @param newName השם החדש
     * @return true אם השם שונה בהצלחה
     * @throws SQLException, SecurityException, IllegalArgumentException
     */
    public boolean renameChat(UUID user, UUID chatId, String newName) throws SQLException {
        // רק חברים בצאט יכולים לשנות את שם הצאט
        if(!isMember(user, chatId)) {
            throw new SecurityException("Only members of the chat can rename the chat.");
        }
        // בדיקה אם השם חוקי
        if (newName == null || newName.trim().isEmpty() || !newName.matches("^[a-zA-Z0-9א-ת _-]{1,50}$")) {
            throw new IllegalArgumentException("Invalid chat name format.");
        }
        String sql = "UPDATE Chats SET Name = ? WHERE Id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, newName);
            stmt.setObject(2, chatId);
            return stmt.executeUpdate() > 0;
        }
    }

    /**
     * מוסיף חבר חדש לחדר. מותר רק למנהלים.
     *
     * @param adminId      מזהה המנהל
     * @param chatRoom     החדר
     * @param targetUserId המשתמש שיתווסף
     * @param symmetricKey המפתח הסימטרי של הקבוצה (מוצפן עם המפתח הציבורי של המטרה)
     * @throws SQLException, SecurityException, IllegalStateException
     */
    public void addMember(UUID adminId, ChatRoom chatRoom, UUID targetUserId, byte[] symmetricKey) throws SQLException {
        UUID chatId = chatRoom.getChatId();
        // רק מנהל יכול להוסיף משתמש
        if (!isAdmin(adminId, chatId)) {
            throw new SecurityException("Only admins can add members from the chat.");
        }
        // לבדוק אם המשתמש קיים בצאט
        if (isMember(targetUserId, chatId)) {
            throw new IllegalStateException("User is already a member of the chat.");
        }

        String sql = """
        INSERT INTO ChatMembers
            (UserId, ChatId, Role, JoinDate, InviteStatus, EncryptedPersonalGroupKey) VALUES
            (?, ?, ?, GETDATE(), ?, ?)
        """;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, targetUserId);
            stmt.setObject(2, chatId);
            stmt.setString(3, ChatRole.MEMBER.name()); // ניתן לשנות לפי הצורך
            stmt.setString(4, InviteStatus.PENDING.name());
            stmt.setBytes(5, symmetricKey);
            stmt.executeUpdate();
        }
    }

    /**
     * מוסיף את יוצר החדר כ-ADMIN. מיועד לשימוש רק בזמן יצירת החדר הראשונית.
     * לא דורש בדיקת הרשאות.
     * זורק שגיאה אם כבר קיים ADMIN בצ'אט.
     *
     * @param chatRoom     החדר
     * @param targetId     המשתמש שיתווסף
     * @param symmetricKey המפתח הסימטרי של הקבוצה (מוצפן עם המפתח הציבורי של המטרה)
     */
    public void addCreator(UUID targetId, ChatRoom chatRoom, byte[] symmetricKey) throws SQLException {

        // בדיקה חכמה - לוודא שהצ'אט באמת ריק
        if (countMembers(chatRoom.getChatId()) > 0) {
            throw new SecurityException("Cannot add creator to an existing chat");
        }

        String sql = """
        INSERT INTO ChatMembers
            (UserId, ChatId, Role, JoinDate, InviteStatus, EncryptedPersonalGroupKey) VALUES
            (?, ?, ?, GETDATE(), ?, ?)
        """;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, targetId);
            stmt.setObject(2, chatRoom.getChatId());
            stmt.setString(3, ChatRole.ADMIN.name()); // ניתן לשנות לפי הצורך
            stmt.setString(4, InviteStatus.ACCEPTED.name());
            stmt.setBytes(5, symmetricKey);
            stmt.executeUpdate();
        }
    }

    /**
     * מסיר חבר מחדר. מותר רק למנהלים.
     *
     * @param adminId      מזהה המנהל
     * @param chatId       מזהה החדר
     * @param targetUserId המשתמש שיוסר
     * @throws SQLException, SecurityException, IllegalStateException
     */
    public void removeMember(UUID adminId, UUID chatId, UUID targetUserId) throws SQLException {
        // רק מנהל יכול להסיר משתמש
        if (!isAdmin(adminId, chatId)) {
            throw new SecurityException("Only admins can remove members from the chat.");
        }
        // לבדוק אם המשתמש קיים בצאט
        if (!isMember(targetUserId, chatId)) {
            throw new IllegalStateException("User isn't a member of the chat.");
        }
        String sql = "DELETE FROM ChatMembers WHERE UserId = ? AND ChatId = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)){
            stmt.setObject(1, targetUserId);
            stmt.setObject(2, chatId);
            stmt.executeUpdate();
        }
    }

    /**
     * מעדכן את תפקיד המשתמש בצ'אט. מותר רק למנהלים.
     * לא ניתן להוריד הרשאות של המנהל היחיד.
     *
     * @param adminId מזהה המנהל
     * @param chatId  מזהה החדר
     * @param userId  מזהה המשתמש whose role will be updated
     * @param newRole התפקיד החדש (Admin / Member)
     * @throws SQLException, SecurityException, IllegalStateException
     */
    public void updateRole(UUID adminId, UUID chatId, UUID userId, String newRole) throws SQLException {
        if (!isAdmin(adminId, chatId)) {
            throw new SecurityException("Only admins can update the role of members in the chat.");
        }
        // למנוע הורדת הרשאות של המשתמש הארון שהוא ADMIN
        if (newRole.equalsIgnoreCase("member")) {
            ArrayList<User> admins = getAllAdmins(chatId);
            if (admins.size() <= 1 && admins.get(0).getId().equals(userId)) {
                throw new IllegalStateException("Cannot demote the only admin in the chat.");
            }
        }

        String sql = "UPDATE ChatMembers SET Role = ? WHERE ChatId = ? AND UserId = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, newRole);
            stmt.setObject(2, chatId);
            stmt.setObject(3, userId);
            stmt.executeUpdate();
        }
    }

    /**
     * מאחזר חבר בצ'אט.
     * @param chatId מזהה החדר
     * @return מפת מזהה לחבר צ'אט
     * @throws SQLException במקרה של שגיאה במסד הנתונים
     */
    public ChatMember getChatMember(UUID chatId, UUID userId) throws SQLException {

        String sql = """

        SELECT CM.Role, CM.JoinDate, CM.InviteStatus, CM.EncryptedPersonalGroupKey, CM.UnreadMessages
        FROM ChatMembers CM
        JOIN Users U ON CM.UserId = U.Id
        WHERE CM.ChatId = ? AND U.Id = ?
        """;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, chatId);
            stmt.setObject(2, userId);
            try(ResultSet rs = stmt.executeQuery()) {

                if(!rs.next())
                    return null;

                ChatRole role = ChatRole.fromString(rs.getString("Role"));
                Instant joinDate = rs.getTimestamp("JoinDate").toInstant();
                InviteStatus inviteStatus = InviteStatus.valueOf(rs.getString("InviteStatus"));
                byte[] encryptedKey = rs.getBytes("EncryptedPersonalGroupKey");
                int unreadMessages = rs.getInt("UnreadMessages");
                ChatMember chatMember = new ChatMember(chatId, userId, role, joinDate, inviteStatus, encryptedKey);
                chatMember.setUnreadMessages(unreadMessages);
                return chatMember;
            }
        }
    }

    /**
     * מחזיר את כל המשתמשים שהם מנהלים בצ'אט.
     * @param chatId מזהה החדר
     * @return רשימת משתמשים בתפקיד Admin
     * @throws SQLException במקרה של שגיאה במסד הנתונים
     */
    public ArrayList<User> getAllAdmins(UUID chatId) throws SQLException{
        ArrayList<User> admins = new ArrayList<>();
        String sql = """
        SELECT U.*
        FROM Users U
        JOIN ChatMembers CM ON U.Id = CM.UserId
        WHERE CM.ChatId = ? AND CM.Role = 'Admin' 
        """;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, chatId);
            try(ResultSet rs = stmt.executeQuery()){
                while (rs.next()) {

                    admins.add(UserDAO.mapUser(rs)); // שימוש ב-UserDAO למיפוי
                }
            }
        }
        return admins;
    }

    /**
     * ממפה שורת ResultSet לאובייקט ChatRoom.
     * משמש עבור קריאות של getAllChatRooms.
     * @param rs תוצאת השאילתה
     * @return אובייקט ChatRoom
     * @throws SQLException במקרה של כשל בקריאה
     */
    private ChatRoom mapChatRoom(ResultSet rs) throws SQLException {
        UUID id = UUID.fromString(rs.getString("Id"));
        String name = rs.getString("Name");
        UUID createdBy = UUID.fromString(rs.getString("CreatedBy"));
        Instant createdAt = rs.getTimestamp("CreatedAt").toInstant();
        String folderId = rs.getString("FolderId");
        Timestamp lastMessageTime = rs.getTimestamp("LastMessageTime");
        ChatRoom chatRoom = new ChatRoom(id, name, createdBy, createdAt, folderId, null);
        if (lastMessageTime != null) {
            chatRoom.setLastMessageTime(lastMessageTime.toInstant());
        }
        return chatRoom;
    }

    // --- בדיקה אם המשתמש חבר בחדר ---
    public boolean isMember(UUID userId, UUID chatId) throws SQLException {
        String sql = "SELECT 1 FROM ChatMembers WHERE ChatId = ? AND UserId = ?";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, chatId);
            stmt.setObject(2, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    // --- בדיקה אם המשתמש מנהל ---
    public boolean isAdmin(UUID userId, UUID chatId) throws SQLException {
        String sql = "SELECT 1 FROM ChatMembers WHERE ChatId = ? AND UserId = ? AND Role = 'Admin'";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, chatId);
            stmt.setObject(2, userId);
            try (ResultSet rs = stmt.executeQuery()){
                return rs.next();
            }
        }
    }

    public boolean updateEncryptedKey(UUID chatId, UUID userId, byte[] encryptedKey) throws SQLException {
        String sql = "UPDATE ChatMembers SET EncryptedPersonalGroupKey = ? WHERE ChatId = ? AND UserId = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setBytes(1, encryptedKey);
            stmt.setObject(2, chatId);
            stmt.setObject(3, userId);
            return stmt.executeUpdate() > 0;
        }
    }

    public int getUnreadMessages(UUID chatId, UUID userId) throws SQLException {
        String sql = "SELECT UnreadMessages FROM ChatMembers WHERE ChatId = ? AND UserId = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, chatId);
            stmt.setObject(2, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("UnreadMessages");
                }
            }
        }
        return 0;
    }

    public void updateUnreadMessages(UUID chatId, UUID userId, int unreadMessages) throws SQLException {
        String sql = "UPDATE ChatMembers SET UnreadMessages = ? WHERE ChatId = ? AND UserId = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, unreadMessages);
            stmt.setObject(2, chatId);
            stmt.setObject(3, userId);
            stmt.executeUpdate();
        }
    }

    public int countMembers(UUID chatId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM ChatMembers WHERE ChatId = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, chatId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        return 0;
    }

    public boolean updateFolderId(UUID chatId, String folderId) throws SQLException {
        String sql = "UPDATE Chats SET FolderId = ? WHERE Id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, folderId);
            stmt.setObject(2, chatId);
            return stmt.executeUpdate() > 0;
        }
    }

    public void updateLastMessageTime(UUID chatId, Instant timestamp) throws SQLException {
        String sql = "UPDATE Chats SET LastMessageTime = ? WHERE Id = ?";

        try (Connection conn = DatabaseConnection.getInstance().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setTimestamp(1, Timestamp.from(timestamp));
            stmt.setObject(2, chatId);
            stmt.executeUpdate();
        }
    }
}

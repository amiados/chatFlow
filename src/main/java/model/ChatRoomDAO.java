package model;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

/**
 * DAO עבור ניהול חדרי צ'אט במסד הנתונים.
 * אחראי על פעולות CRUD בפאי הצ'אט (Chats) וכן על ניהול חברים בטבלת ChatMembers.
 */
public class ChatRoomDAO {

    /**
     * בונה מופע חדש של ChatRoomDAO.
     * שימוש ב-DatabaseConnection להשגת חיבור למסד הנתונים.
     */
    public ChatRoomDAO() {
    }

    /**
     * יוצר חדר צ'אט חדש.
     *
     * @param chatRoom אובייקט ChatRoom עם פרטי החדר (Id, Name, CreatedAt, CreatedBy, FolderId, CurrentKeyVersion)
     * @throws SQLException אם מתרחשת שגיאה במסד הנתונים
     */
    public void createChatRoom(ChatRoom chatRoom) throws SQLException {
        String sql = """
            INSERT INTO Chats
              (Id, Name, CreatedAt, CreatedBy, FolderId, LastMessageTime, CurrentKeyVersion)
            VALUES
              (?, ?, ?, ?, ?, ?, ?)
        """;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, chatRoom.getChatId());
            stmt.setString(2, chatRoom.getName());
            stmt.setTimestamp(3, Timestamp.from(chatRoom.getCreatedAt()));
            stmt.setObject(4, chatRoom.getCreatedBy());
            stmt.setString(5, chatRoom.getFolderId());
            stmt.setTimestamp(6, Timestamp.from(Instant.now()));
            stmt.setInt(7, chatRoom.getCurrentKeyVersion());
            stmt.executeUpdate();
        }
    }

    /**
     * מאחזר חדר צ'אט לפי מזהה.
     *
     * @param chatId UUID של החדר לשאילתה
     * @return אובייקט ChatRoom עם פרטי החדר וחברי החדר, או null אם לא קיים
     * @throws SQLException אם מתרחשת שגיאה במסד הנתונים
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

                // קריאת פרטי החדר
                String name = rs.getString("Name");
                Instant createdAt = rs.getTimestamp("CreatedAt").toInstant();
                UUID createdBy = UUID.fromString(rs.getString("CreatedBy"));
                String folderId = rs.getString("FolderId");
                Timestamp lastMsgTs = rs.getTimestamp("LastMessageTime");
                Instant lastMessageTime = (lastMsgTs != null) ? lastMsgTs.toInstant() : Instant.now();
                int keyVersion = rs.getInt("CurrentKeyVersion");

                ChatRoom chatRoom = new ChatRoom(chatId, name, createdBy, createdAt, folderId, null);
                chatRoom.setLastMessageTime(lastMessageTime);
                chatRoom.setCurrentKeyVersion(keyVersion);

                // הטענת חברי החדר
                membersStmt.setObject(1, chatId);
                try (ResultSet memberRs = membersStmt.executeQuery()) {
                    while (memberRs.next()) {
                        UUID userId = UUID.fromString(memberRs.getString("UserId"));
                        ChatRole role = ChatRole.valueOf(memberRs.getString("Role").toUpperCase());
                        Instant joinDate = memberRs.getTimestamp("JoinDate").toInstant();
                        InviteStatus inviteStatus = InviteStatus.valueOf(memberRs.getString("InviteStatus"));
                        int unread = memberRs.getInt("UnreadMessages");

                        ChatMember member = new ChatMember(chatId, userId, role, joinDate, inviteStatus);
                        member.setUnreadMessages(unread);
                        chatRoom.getMembers().put(userId, member);
                    }
                }

                return chatRoom;
            }
        }
    }

    /**
     * מאחזר את כל חדרי הצ'אט שבהם משתמש חבר.
     *
     * @param userId UUID של המשתמש
     * @return רשימת ChatRoom ממוינת לפי זמן ההודעה האחרונה
     * @throws SQLException אם מתרחשת שגיאה במסד הנתונים
     */
    public ArrayList<ChatRoom> getAllChatRooms(UUID userId) throws SQLException {
        ArrayList<ChatRoom> chatRooms = new ArrayList<>();
        String sql = """
            SELECT C.*
            FROM Chats C
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
     * משנה את שם החדר.
     * מותר רק לחברים בחדר.
     *
     * @param user מזהה המשתמש המבצע
     * @param chatId מזהה החדר
     * @param newName השם החדש (1-50 תווים; אותיות, ספרות, רווח, מקף ותווים בעברית)
     * @return true אם השם עודכן, false אחרת
     * @throws SQLException אם מתרחשת שגיאה במסד הנתונים
     * @throws SecurityException אם המשתמש אינו חבר בחדר
     * @throws IllegalArgumentException אם פורמט השם אינו חוקי
     */
    public boolean renameChat(UUID user, UUID chatId, String newName) throws SQLException {
        if (!isMember(user, chatId)) {
            throw new SecurityException("Only members of the chat can rename the chat.");
        }
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
     * מוסיף חבר חדש לחדר (Invite).
     * מותר רק למנהלים.
     *
     * @param adminId מזהה המנהל המבצע
     * @param chatRoom אובייקט ChatRoom של החדר
     * @param targetUserId מזהה המשתמש שמוזמן
     * @throws SQLException אם מתרחשת שגיאה במסד הנתונים
     * @throws SecurityException אם המבצע אינו מנהל
     * @throws IllegalStateException אם המשתמש כבר חבר
     */
    public void addMember(UUID adminId, ChatRoom chatRoom, UUID targetUserId) throws SQLException {
        UUID chatId = chatRoom.getChatId();
        if (!isAdmin(adminId, chatId)) {
            throw new SecurityException("Only admins can add members from the chat.");
        }
        if (isMember(targetUserId, chatId)) {
            throw new IllegalStateException("User is already a member of the chat.");
        }
        String sql = """
            INSERT INTO ChatMembers
              (UserId, ChatId, Role, JoinDate, InviteStatus)
            VALUES
              (?, ?, ?, GETDATE(), ?)
        """;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, targetUserId);
            stmt.setObject(2, chatId);
            stmt.setString(3, ChatRole.MEMBER.name());
            stmt.setString(4, InviteStatus.PENDING.name());
            stmt.executeUpdate();
        }
    }

    /**
     * מוסיף את היוצר כ-ADMIN בחדר.
     * מיועד רק ליצירה ראשונית (ChatRoom חדש).
     *
     * @param targetId מזהה היוצר
     * @param chatRoom אובייקט ChatRoom של החדר
     * @throws SQLException אם מתרחשת שגיאה במסד הנתונים
     * @throws SecurityException אם כבר קיימים חברים אחרים בחדר
     */
    public void addCreator(UUID targetId, ChatRoom chatRoom) throws SQLException {
        if (countMembers(chatRoom.getChatId()) > 0) {
            throw new SecurityException("Cannot add creator to an existing chat");
        }
        String sql = """
            INSERT INTO ChatMembers
              (UserId, ChatId, Role, JoinDate, InviteStatus)
            VALUES
              (?, ?, ?, GETDATE(), ?)
        """;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, targetId);
            stmt.setObject(2, chatRoom.getChatId());
            stmt.setString(3, ChatRole.ADMIN.name());
            stmt.setString(4, InviteStatus.ACCEPTED.name());
            stmt.executeUpdate();
        }
    }

    /**
     * מסיר חבר מהצ'אט.
     * מותר רק למנהלים.
     *
     * @param adminId מזהה המנהל המבצע
     * @param chatId מזהה החדר
     * @param targetUserId מזהה המשתמש להסרה
     * @throws SQLException אם מתרחשת שגיאה במסד הנתונים
     * @throws SecurityException אם המבצע אינו מנהל
     * @throws IllegalStateException אם המשתמש אינו חבר
     */
    public void removeMember(UUID adminId, UUID chatId, UUID targetUserId) throws SQLException {
        if (!isAdmin(adminId, chatId)) {
            throw new SecurityException("Only admins can remove members from the chat.");
        }
        if (!isMember(targetUserId, chatId)) {
            throw new IllegalStateException("User isn't a member of the chat.");
        }
        String sql = "DELETE FROM ChatMembers WHERE UserId = ? AND ChatId = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, targetUserId);
            stmt.setObject(2, chatId);
            stmt.executeUpdate();
        }
    }

    /**
     * מעדכן את תפקיד המשתמש בצ'אט.
     * לא מאפשר להוריד את מנהל היחיד.
     *
     * @param adminId מזהה המנהל המבצע
     * @param chatId מזהה החדר
     * @param userId מזהה המשתמש לשינוי תפקידו
     * @param newRole תפקיד חדש ("Admin" או "Member")
     * @throws SQLException אם מתרחשת שגיאה במסד הנתונים
     * @throws SecurityException אם המבצע אינו מנהל
     * @throws IllegalStateException אם מנסים להפוך את המנהל היחיד למשתמש
     */
    public void updateRole(UUID adminId, UUID chatId, UUID userId, String newRole) throws SQLException {
        if (!isAdmin(adminId, chatId)) {
            throw new SecurityException("Only admins can update the role of members in the chat.");
        }
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
     * מעדכן גרסה של מפתח הצפנה בחדר.
     *
     * @param chatId מזהה החדר
     * @param newVersion גרסת המפתח החדשה
     * @return true אם בוצע שינוי, false אחרת
     * @throws SQLException אם מתרחשת שגיאה במסד הנתונים
     */
    public boolean updateKeyVersion(UUID chatId, int newVersion) throws SQLException {
        String sql = "UPDATE Chats SET CurrentKeyVersion = ? WHERE Id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, newVersion);
            stmt.setObject(2, chatId);
            return stmt.executeUpdate() > 0;
        }
    }

    /**
     * מאחזר מידע על חבר בחדר.
     *
     * @param chatId מזהה החדר
     * @param userId מזהה המשתמש
     * @return ChatMember עם פרטי החברות בתוקף, או null אם לא קיים
     * @throws SQLException אם מתרחשת שגיאה במסד הנתונים
     */
    public ChatMember getChatMember(UUID chatId, UUID userId) throws SQLException {
        String sql = """
            SELECT CM.Role, CM.JoinDate, CM.InviteStatus, CM.UnreadMessages
            FROM ChatMembers CM
            JOIN Users U ON CM.UserId = U.Id
            WHERE CM.ChatId = ? AND U.Id = ?
        """;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, chatId);
            stmt.setObject(2, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return null;
                ChatRole role = ChatRole.fromString(rs.getString("Role"));
                Instant joinDate = rs.getTimestamp("JoinDate").toInstant();
                InviteStatus status = InviteStatus.valueOf(rs.getString("InviteStatus"));
                int unread = rs.getInt("UnreadMessages");
                ChatMember member = new ChatMember(chatId, userId, role, joinDate, status);
                member.setUnreadMessages(unread);
                return member;
            }
        }
    }

    /**
     * מחזיר רשימת מנהלים בחדר.
     *
     * @param chatId מזהה החדר
     * @return ArrayList של משתמשים בתפקיד Admin
     * @throws SQLException אם מתרחשת שגיאה במסד הנתונים
     */
    public ArrayList<User> getAllAdmins(UUID chatId) throws SQLException {
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
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    admins.add(UserDAO.mapUser(rs));
                }
            }
        }
        return admins;
    }

    /**
     * ממפה שורת תוצאה של ResultSet לאובייקט ChatRoom.
     * משמש ב-getAllChatRooms.
     *
     * @param rs ResultSet הנמצא בשורה פעילה
     * @return אובייקט ChatRoom עם פרטים ראשוניים
     * @throws SQLException אם מתרחשת שגיאה בקריאה
     */
    private ChatRoom mapChatRoom(ResultSet rs) throws SQLException {
        UUID id = UUID.fromString(rs.getString("Id"));
        String name = rs.getString("Name");
        UUID createdBy = UUID.fromString(rs.getString("CreatedBy"));
        Instant createdAt = rs.getTimestamp("CreatedAt").toInstant();
        String folderId = rs.getString("FolderId");
        Timestamp lastTs = rs.getTimestamp("LastMessageTime");
        int keyVer = rs.getInt("CurrentKeyVersion");
        ChatRoom chatRoom = new ChatRoom(id, name, createdBy, createdAt, folderId, null);
        if (lastTs != null) {
            chatRoom.setLastMessageTime(lastTs.toInstant());
            chatRoom.setCurrentKeyVersion(keyVer);
        }
        return chatRoom;
    }

    /**
     * בודק אם משתמש חבר בחדר.
     *
     * @param userId מזהה המשתמש
     * @param chatId מזהה החדר
     * @return true אם קיים רשומה בטבלת ChatMembers
     * @throws SQLException אם מתרחשת שגיאה במסד הנתונים
     */
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

    /**
     * בודק אם משתמש מנהל בצ'אט.
     *
     * @param userId מזהה המשתמש
     * @param chatId מזהה הצ'אט
     * @return true אם תפקידו Admin
     * @throws SQLException אם מתרחשת שגיאה במסד הנתונים
     */
    public boolean isAdmin(UUID userId, UUID chatId) throws SQLException {
        String sql = "SELECT 1 FROM ChatMembers WHERE ChatId = ? AND UserId = ? AND Role = 'Admin'";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, chatId);
            stmt.setObject(2, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * מחזיר את מספר ההודעות הלא נקראות של משתמש בחדר.
     *
     * @param chatId מזהה החדר
     * @param userId מזהה המשתמש
     * @return מספר ההודעות הלא נקראות
     * @throws SQLException אם מתרחשת שגיאה במסד הנתונים
     */
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

    /**
     * מעדכן את מספר ההודעות הלא נקראות של משתמש בחדר.
     *
     * @param chatId מזהה החדר
     * @param userId מזהה המשתמש
     * @param unreadMessages מספר ההודעות החדשות
     * @throws SQLException אם מתרחשת שגיאה במסד הנתונים
     */
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

    /**
     * סופר את מספר החברים בחדר.
     *
     * @param chatId מזהה החדר
     * @return מספר החברים
     * @throws SQLException אם מתרחשת שגיאה במסד הנתונים
     */
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

    /**
     * מעדכן את מזהה התיקייה של החדר (FolderId).
     *
     * @param chatId מזהה החדר
     * @param folderId מזהה התיקייה החדש
     * @return true אם עודכן
     * @throws SQLException אם מתרחשת שגיאה במסד הנתונים
     */
    public boolean updateFolderId(UUID chatId, String folderId) throws SQLException {
        String sql = "UPDATE Chats SET FolderId = ? WHERE Id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, folderId);
            stmt.setObject(2, chatId);
            return stmt.executeUpdate() > 0;
        }
    }

    /**
     * מעדכן את שדה זמן ההודעה האחרונה (LastMessageTime).
     *
     * @param chatId מזהה החדר
     * @param timestamp זמן ההודעה האחרון לעדכון
     * @throws SQLException אם מתרחשת שגיאה במסד הנתונים
     */
    public void updateLastMessageTime(UUID chatId, Instant timestamp) throws SQLException {
        String sql = "UPDATE Chats SET LastMessageTime = ? WHERE Id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setTimestamp(1, Timestamp.from(timestamp));
            stmt.setObject(2, chatId);
            stmt.executeUpdate();
        }
    }
}

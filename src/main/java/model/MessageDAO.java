package model;

import java.sql.*;
import java.util.ArrayList;
import java.util.UUID;

/**
 * Data Access Object (DAO) responsible for managing CRUD operations on the Messages table.
 * Supports pagination, status updates, and timestamp-based retrieval.
 */
public class MessageDAO {

    /**
     * Constructs a MessageDAO with the provided database connection manager.
     *
     */
    public MessageDAO() {
    }

    /**
     * Inserts a new message into the Messages table.
     *
     * @param message the Messages object containing message data.
     * @return true if the message was inserted successfully, false otherwise.
     * @throws SQLException if a database access error occurs.
     */
    public boolean saveMessage(Messages message) throws SQLException {
        String sql = """
        INSERT INTO Messages 
            (Id, ChatId, SenderId, Content, SentAt, Status, IsSystem, KeyVersion) VALUES 
            (?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setObject(1, message.getMessageId());
            stmt.setObject(2, message.getChatId());
            stmt.setObject(3, message.getSenderId());
            stmt.setBytes(4, message.getContent());
            stmt.setTimestamp(5, Timestamp.from(message.getTimestamp()));
            stmt.setString(6, message.getStatus().name());
            stmt.setBoolean(7, message.getIsSystem());
            stmt.setInt(8, message.getKeyVersion());
            return stmt.executeUpdate() > 0;
        }
    }

    /**
     * Retrieves a paginated list of messages for a specific chat.
     *
     * @param chatId the chat's UUID.
     * @param limit the maximum number of messages to fetch.
     * @param offset the number of messages to skip (for pagination).
     * @return a list of Messages ordered by SentAt descending.
     * @throws SQLException if a database access error occurs.
     */
    public ArrayList<Messages> getMessagesByChatId(UUID chatId, UUID userId, int limit, int offset) throws SQLException {
        String sql = """
        SELECT M.*
        FROM Messages M
        JOIN ChatMembers CM ON M.ChatId = CM.ChatId
        WHERE M.ChatId = ?
        AND CM.UserId = ?
        AND M.SentAt >= CM.JoinDate
        ORDER BY SentAt DESC
        OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
        """;
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setObject(1, chatId);
            stmt.setObject(2, userId);
            stmt.setInt(3, offset);
            stmt.setInt(4, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                ArrayList<Messages> messages = new ArrayList<>();
                while (rs.next()) {
                    messages.add(mapResultSetToMessage(rs));
                }
                return messages;
            }
        }
    }

    public ArrayList<Messages> getMessagesByChatId(UUID chatId, int limit, int offset) throws SQLException {
        String sql = """
        SELECT *
        FROM Messages 
        WHERE ChatId = ?
        ORDER BY SentAt DESC
        OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
        """;
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setObject(1, chatId);
            stmt.setInt(2, offset);
            stmt.setInt(3, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                ArrayList<Messages> messages = new ArrayList<>();
                while (rs.next()) {
                    messages.add(mapResultSetToMessage(rs));
                }
                return messages;
            }
        }
    }

    /**
     * Maps a result set row to a Messages object.
     *
     * @param rs the result set from a query.
     * @return a populated Messages object.
     * @throws SQLException if data access fails.
     */
    private Messages mapResultSetToMessage(ResultSet rs) throws SQLException {
        return new Messages(
                UUID.fromString(rs.getString("Id")),
                UUID.fromString(rs.getString("ChatId")),
                UUID.fromString(rs.getString("SenderId")),
                rs.getBytes("Content"),
                rs.getTimestamp("SentAt").toInstant(),
                MessageStatus.valueOf(rs.getString("Status")),
                rs.getBoolean("IsSystem"),
                rs.getInt("KeyVersion")
        );
    }

    /**
     * Retrieves messages sent after a specific timestamp in a chat.
     *
     * @param chatId the chat's UUID.
     * @param after only messages sent after this timestamp will be returned.
     * @return a list of Messages sorted by SentAt descending.
     * @throws SQLException if a database access error occurs.
     */
    public ArrayList<Messages> getMessagesAfter(UUID chatId, UUID userId, Timestamp after) throws SQLException {
        String sql = """
        SELECT M.*
        FROM Messages M
        JOIN ChatMembers CM ON M.ChatId = CM.ChatId
        WHERE M.ChatId = ?
          AND CM.UserId = ?
          AND M.SentAt > ?
          AND M.SentAt >= CM.JoinDate
        ORDER BY SentAt DESC
        FETCH FIRST ? ROWS ONLY
        """;
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setObject(1, chatId);
            stmt.setObject(2, userId);
            stmt.setTimestamp(3, after);
            try (ResultSet rs = stmt.executeQuery()) {
                ArrayList<Messages> messages = new ArrayList<>();
                while (rs.next()) {
                    messages.add(mapResultSetToMessage(rs));
                }
                return messages;
            }
        }
    }

    /**
     * Counts the number of messages in a specific chat.
     *
     * @param chatId the UUID of the chat.
     * @return total number of messages in the chat.
     * @throws SQLException if a database access error occurs.
     */
    public int countMessagesInChat(UUID chatId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM Messages WHERE ChatId = ?";
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setObject(1, chatId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
                return 0;
            }
        }
    }

    public boolean updateMessageStatus(UUID messageId, MessageStatus newStatus) throws SQLException {
        String sql = "UPDATE Messages SET Status = ? WHERE Id = ?";
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, newStatus.name());
            stmt.setObject(2, messageId);
            return stmt.executeUpdate() > 0;
        }
    }

    /**
     * מעדכן את תוכן ההודעה לאחר ריענון ההצפנה.
     * @return true אם עודכן בהצלחה
     */
    public boolean updateContent(UUID messageId, byte[] newCipher) throws SQLException {
        String sql = "UPDATE Messages SET Content = ? WHERE Id = ?";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setBytes(1, newCipher);
            ps.setObject(2, messageId);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * מעדכן את גרסת המפתח שעליה הודעה זו מוצפנת.
     * @return true אם עודכן בהצלחה
     */
    public boolean updateKeyVersion(UUID messageId, int newVersion) throws SQLException {
        String sql = "UPDATE Messages SET KeyVersion = ? WHERE Id = ?";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, newVersion);
            ps.setObject(2, messageId);
            return ps.executeUpdate() > 0;
        }
    }

    // -- פיצ'רים לעתיד --
    // הוספת מנגנון STATUS שיספק מידע על הודעה (נשלחה, התקבלה, נקראה)
    // סיפוק אנאליזה על הודעות/צ'אט
    // חיפוש הודעות בצ'אט לפי מזהה הודעה/מילות מפתח
}

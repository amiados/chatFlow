package model;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bridj.util.Pair;

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
            (Id, ChatId, SenderId, Content, SentAt, Status, IsSystem) VALUES 
            (?, ?, ?, ?, ?, ?, ?)
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
    public ArrayList<Messages> getMessagesByChatId(UUID chatId, int limit, int offset) throws SQLException {
        String sql = "SELECT * FROM Messages WHERE ChatId = ? ORDER BY SentAt DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
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
                rs.getBoolean("IsSystem")
        );
    }

    /**
     * Retrieves messages sent after a specific timestamp in a chat.
     *
     * @param chatId the chat's UUID.
     * @param after only messages sent after this timestamp will be returned.
     * @param limit maximum number of messages to return.
     * @return a list of Messages sorted by SentAt descending.
     * @throws SQLException if a database access error occurs.
     */
    public ArrayList<Messages> getMessagesAfter(UUID chatId, Timestamp after, int limit) throws SQLException {
        String sql = "SELECT * FROM Messages WHERE ChatId = ? AND SentAt > ? ORDER BY SentAt DESC FETCH FIRST ? ROWS ONLY";
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setObject(1, chatId);
            stmt.setTimestamp(2, after);
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
     * Deletes all messages belonging to a specific chat.
     *
     * @param chatId the UUID of the chat.
     * @return number of rows deleted.
     * @throws SQLException if a database access error occurs.
     */
    public int deleteMessagesByChatId(UUID chatId) throws SQLException {
        String sql = "DELETE FROM Messages WHERE ChatId = ?";
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setObject(1, chatId);
            return stmt.executeUpdate();
        }
    }

    /**
     * Deletes a specific message by its ID.
     *
     * @param messageId the UUID of the message to delete.
     * @return number of rows deleted (0 or 1).
     * @throws SQLException if a database access error occurs.
     */
    public int deleteMessageById(UUID messageId) throws SQLException {
        String sql = "DELETE FROM Messages WHERE Id = ?";
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setObject(1, messageId);
            return stmt.executeUpdate();
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
     * Retrieves all message IDs for a specific chat.
     * Used for batch re-encryption.
     */
    public List<UUID> streamAllMessageIds(UUID chatId) throws SQLException {
        // 1. מנסחים את ה-SQL: בוחרים רק את העמודה Id, ומסדרים לפי זמן השליחה (SentAt) בסדר עולה
        String sql = "SELECT Id FROM Messages WHERE ChatId = ? ORDER BY SentAt ASC";

        // 2. מייצרים מיכל לתוצאה
        List<UUID> ids = new ArrayList<>();

        // 3. פותחים קונקשן ו-PreparedStatement בתוך try‐with‐resources
        //    כדי שינוהלו אוטומטית הסגירה של המשאבים
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     sql,
                     ResultSet.TYPE_FORWARD_ONLY,    // Forward‐only: סגנון ResultSet זרימה חד–כיוונית
                     ResultSet.CONCUR_READ_ONLY)) {  // Read‐only: לא נשתמש בשינויים בתוך ה-ResultSet

            // 4. קושרים את הפרמטר לשאלה: מזהה השיחה
            ps.setObject(1, chatId);

            // 5. מגדירים fetchSize כדי שה־JDBC יביא את התוצאות בחתיכות (במקום לטעון הכל בזיכרון)
            ps.setFetchSize(500);

            // 6. מריצים את השאילתה ומשיגים ResultSet
            try (ResultSet rs = ps.executeQuery()) {
                // 7. עוברים על כל שורה
                while (rs.next()) {
                    // 8. מאחזר UUID ישירות (אם הדרייבר תומך), חוסך המרה מ-String
                    UUID id = rs.getObject("Id", UUID.class);
                    ids.add(id);
                }
            }
        }

        // 9. מחזירים את הרשימה של כל ה-UUID שנקלטו
        return ids;
    }

    /**
     * Fetches a page of (id, ciphertext) pairs for the given chat.
     */
    public List<Pair<UUID, byte[]>> fetchContentBatch(UUID chatId, int offset, int limit) throws SQLException {
        String sql = """
            SELECT Id, Content
              FROM Messages
             WHERE ChatId = ?
          ORDER BY SentAt ASC
             OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
        """;
        List<Pair<UUID, byte[]>> list = new ArrayList<>();
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, chatId);
            ps.setInt(2, offset);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new Pair<>(
                            UUID.fromString(rs.getString("Id")),
                            rs.getBytes("Content")
                    ));
                }
            }
        }
        return list;
    }

    /**
     * Retrieves a single Messages object by its ID.
     * Used for fetching old ciphertext during re-encryption.
     */
    public Messages getMessageById(UUID messageId) throws SQLException {
        String sql = "SELECT * FROM Messages WHERE Id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, messageId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return mapResultSetToMessage(rs);
            }
        }
    }

    /**
     * Updates the Content column for a batch of messages.
     */
    public void updateContentBatch(List<Pair<UUID, byte[]>> batch) throws SQLException {
        String sql = "UPDATE Messages SET Content = ? WHERE Id = ?";
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (var p : batch) {
                ps.setBytes(1, p.getValue());
                ps.setObject(2, p.getKey());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public boolean updateContent(UUID messageId, byte[] newContent) throws SQLException {
        String sql = "UPDATE Messages SET Content = ? WHERE Id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBytes(1, newContent);
            ps.setObject(2, messageId);
            return ps.executeUpdate() > 0;
        }
    }

    // -- פיצ'רים לעתיד --
    // הוספת מנגנון STATUS שיספק מידע על הודעה (נשלחה, התקבלה, נקראה)
    // סיפוק אנאליזה על הודעות/צ'אט
    // חיפוש הודעות בצ'אט לפי מזהה הודעה/מילות מפתח
}

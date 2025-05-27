package model;

import java.sql.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.UUID;

/**
 * Data Access Object (DAO) responsible for managing Invite records in the database.
 * Handles operations such as creation, retrieval, deletion, and status updates of chat invitations.
 */
public class InviteDAO {

    /**
     * Constructs an InviteDAO with a given DatabaseConnection.
     *
     */
    public InviteDAO() {}

    /**
     * Inserts a new invite into the database.
     *
     * @param invite the Invite object to be created
     * @return true if the invite was successfully created, false otherwise (e.g., constraint violation)
     * @throws SQLException if a database access error occurs
     */
    public boolean createInvite(Invite invite) throws SQLException {
        String sql = """
    INSERT INTO Invites
        (InviteId, ChatId, InviterId, InvitedId, SentAt, Status, EncryptedPersonalGroupKey)
        VALUES (?, ?, ?, ?, ?, ?, ?)
    """;

        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, invite.getInviteId().toString());
            stmt.setString(2, invite.getChatId().toString());
            stmt.setString(3, invite.getSenderId().toString());
            stmt.setString(4, invite.getReceiverId().toString());
            stmt.setTimestamp(5, Timestamp.from(invite.getSentAt()));
            stmt.setString(6, invite.getStatus().name());
            stmt.setBytes(7, invite.getEncryptedKey());

            int rowsInserted = stmt.executeUpdate();
            System.out.println("Rows inserted: " + rowsInserted);
            return rowsInserted > 0;
        } catch (SQLException e) {
            System.err.println("Failed to create invite: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Checks whether a pending invite already exists for the given chat and user.
     *
     * @param chatId     the ID of the chat
     * @param invitedId  the ID of the invited user
     * @return true if a pending invite exists, false otherwise
     * @throws SQLException if a database access error occurs
     */
    public boolean isInviteExist(UUID chatId, UUID invitedId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM Invites WHERE ChatId = ? AND InvitedId = ? AND Status = 'PENDING'";
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setObject(1, chatId);
            stmt.setObject(2, invitedId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    /**
     * Retrieves an invitation by chat and invited user ID.
     *
     * @param chatId     the ID of the chat
     * @param invitedId  the ID of the invited user
     * @return the Invite object, or null if not found
     * @throws SQLException if a database access error occurs
     */
    public Invite getInvite(UUID chatId, UUID invitedId) throws SQLException {
        String sql = "SELECT * FROM Invites WHERE ChatId = ? AND InvitedId = ?";
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setObject(1, chatId);
            stmt.setObject(2, invitedId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return mapResultSetToInvite(rs);
            }
            return null;
        }
    }

    /**
     * Retrieves an invitation by its unique invite ID.
     *
     * @param inviteId the ID of the invite
     * @return the Invite object, or null if not found
     * @throws SQLException if a database access error occurs
     */
    public Invite getInviteById(UUID inviteId) throws SQLException {
        String sql = "SELECT * FROM Invites WHERE InviteId = ?";
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setObject(1, inviteId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return mapResultSetToInvite(rs);
            }
            return null;
        }
    }

    /**
     * Deletes a specific invite based on chat and invited user ID.
     *
     * @param chatId     the ID of the chat
     * @param invitedId  the ID of the invited user
     * @return true if the invite was deleted, false otherwise
     * @throws SQLException if a database access error occurs
     */
    public boolean deleteInvite(UUID chatId, UUID invitedId) throws SQLException {
        String sql = "DELETE FROM Invites WHERE ChatId = ? AND InvitedId = ?";
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setObject(1, chatId);
            stmt.setObject(2, invitedId);
            return stmt.executeUpdate() > 0;
        }
    }

    /**
     * Updates the status of an existing invite.
     *
     * @param chatId     the ID of the chat
     * @param invitedId  the ID of the invited user
     * @param newStatus  the new status to be set
     * @return true if the status was updated successfully, false otherwise
     * @throws SQLException if a database access error occurs
     */
    public boolean updateInviteStatus(UUID chatId, UUID invitedId, InviteStatus newStatus) throws SQLException {
        String sql = "UPDATE Invites SET Status = ? WHERE ChatId = ? AND InvitedId = ?";
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, newStatus.name());
            stmt.setObject(2, chatId);
            stmt.setObject(3, invitedId);
            return stmt.executeUpdate() > 0;
        }
    }

    /**
     * Deletes all expired invites that have been in 'PENDING' status for more than 24 hours.
     *
     * @return number of deleted invites
     * @throws SQLException if a database access error occurs
     */
    public int deleteExpiredInvites() throws SQLException {
        String sql = "DELETE FROM Invites WHERE Status = 'PENDING' AND SentAt < ?";
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setTimestamp(1, Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS)));
            return stmt.executeUpdate();
        }
    }

    /**
     * מעדכן את כל ההזמנות שסטטוסן PENDING ונשלחו לפני cutoff ל־EXPIRED.
     * @param cutoff נקודת זמן–כל הזמנה ישנה ממנה תוקם
     * @return מספר ההזמנות שיומרו
     */
    public int expirePendingInvites(Instant cutoff) throws SQLException {
        String sql = """
        UPDATE Invites
        SET Status = 'EXPIRED'
        WHERE Status = 'PENDING'
          AND SentAt < ?
    """;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(cutoff));
            return ps.executeUpdate();
        }
    }

    /**
     * Retrieves all invites for a specific user (only relevant invites).
     * Can be used for analytics or notifications.
     *
     * @param userId the invited user ID
     * @return a list of Invite objects
     * @throws SQLException if a database access error occurs
     */
    public ArrayList<Invite> getUserInvites(UUID userId) throws SQLException {
        String sql = "SELECT * FROM Invites WHERE InvitedId = ? AND Status = 'PENDING'";
        ArrayList<Invite> invites = new ArrayList<>();
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setObject(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    invites.add(mapResultSetToInvite(rs));
                }
            }
        }
        return invites;
    }

    private Invite mapResultSetToInvite(ResultSet rs) throws SQLException {
        return new Invite(
                UUID.fromString(rs.getString("InviteId")),
                UUID.fromString(rs.getString("ChatId")),
                UUID.fromString(rs.getString("InviterId")),
                UUID.fromString(rs.getString("InvitedId")),
                rs.getTimestamp("SentAt").toInstant(),
                InviteStatus.valueOf(rs.getString("Status")),
                rs.getBytes("EncryptedPersonalGroupKey")
        );
    }
}

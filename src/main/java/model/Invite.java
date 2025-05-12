package model;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Represents an invitation to join a chat room.
 * Each invite includes sender, receiver, time of sending, encrypted symmetric key, and status.
 * Supports expiration logic and validation rules to prevent invalid invites.
 */
public class Invite {
    private final UUID inviteId;
    private final UUID chatId;
    private final UUID senderId;
    private final UUID receiverId;
    private final Instant sentAt;
    private InviteStatus status;
    private final byte[] encryptedKey;

    /**
     * Constructs a new Invite with default status (PENDING).
     *
     * @param chatId        The chat room ID.
     * @param senderId      The sender's user ID.
     * @param receiverId    The receiver's user ID.
     * @param sentAt        Timestamp of when the invite was sent.
     * @param encryptedKey  The encrypted symmetric key for the chat (for the receiver).
     * @throws IllegalArgumentException if any argument is null or if sender equals receiver.
     */
    public Invite(UUID inviteId, UUID chatId, UUID senderId, UUID receiverId, Instant sentAt, InviteStatus status, byte[] encryptedKey) {
        this.inviteId = inviteId;
        this.chatId = chatId;
        this.senderId = senderId;
        this.receiverId = receiverId;
        this.sentAt = sentAt;
        this.status = status != null ? status : InviteStatus.PENDING;
        this.encryptedKey = encryptedKey;
    }

    /**
     * Constructs a new Invite with a specified status.
     *
     * @param chatId        The chat room ID.
     * @param senderId      The sender's user ID.
     * @param receiverId    The receiver's user ID.
     * @param sentAt        Timestamp of when the invite was sent.
     * @param encryptedKey  The encrypted symmetric key for the chat (for the receiver).
     * @throws IllegalArgumentException if any argument is null or if sender equals receiver.
     */
    public Invite(UUID chatId, UUID senderId, UUID receiverId, Instant sentAt, InviteStatus status, byte[] encryptedKey) {
        this(UUID.randomUUID(), chatId, senderId, receiverId, sentAt, status, encryptedKey);
    }

    // --- Getters & Setter ---

    /**
     * @return The unique identifier of the invite.
     */
    public UUID getInviteId() { return inviteId; }

    /**
     * @return The ID of the chat room this invite relates to.
     */
    public UUID getChatId() { return chatId; }

    /**
     * @return The user ID of the sender of the invite.
     */
    public UUID getSenderId() { return senderId; }

    /**
     * @return The user ID of the receiver of the invite.
     */
    public UUID getReceiverId() { return receiverId; }

    /**
     * @return The timestamp when the invite was sent.
     */
    public Instant getSentAt() { return sentAt; }

    /**
     * @return The encrypted symmetric key for accessing the chat.
     */
    public byte[] getEncryptedKey() { return encryptedKey; }

    /**
     * @return The current status of the invite.
     */
    public InviteStatus getStatus() { return status; }

    /**
     * Updates the status of the invite.
     *
     * @param status New status to assign.
     */
    public void setStatus(InviteStatus status) {
        if (status != null)
            this.status = status;
    }

    // --- Methods ---

    /**
     * Checks if the invite has expired (i.e., more than 24 hours have passed since it was sent).
     *
     * @return true if expired, false otherwise.
     */
    public boolean isExpired() {
        return sentAt.plusSeconds(86400).isBefore(Instant.now());
    }
}

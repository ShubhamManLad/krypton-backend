package org.acme.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.panache.common.Parameters;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(
    name = "messages",
    indexes = {
        @Index(name = "idx_msg_conv_sent", columnList = "conversation_id, sent_at"),
        @Index(name = "idx_msg_client_id", columnList = "client_msg_id"),
        @Index(name = "idx_msg_status", columnList = "conversation_id, status")
    }
)
public class Message extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    public UUID id;

    @Column(name = "conversation_id", nullable = false)
    public UUID conversationId;

    @Column(name = "sender_id", nullable = false)
    public UUID senderId;

    /** Stores the encrypted payload (ciphertext). Column widened to support Base64-encoded encrypted images. */
    @Lob
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    public String content;

    @Column(name = "sent_at", nullable = false)
    public Instant sentAt;

    @Column(name = "client_msg_id", nullable = false)
    public String clientMsgId;

    @Column(name = "status", nullable = false)
    public String status = "SENT"; // SENT, DELIVERED, READ

    @Column(name = "delivered_at")
    public Instant deliveredAt;

    @Column(name = "read_at")
    public Instant readAt;

    // ── E2EE fields ──────────────────────────────────────────────────────────

    /** "text" or "image" — stored as-is from the sender, never inspected by the server */
    @Column(name = "message_type", nullable = false)
    public String messageType = "text";

    /** Base64-encoded AES-GCM Initialization Vector — relayed opaquely to the recipient */
    @Column(name = "iv", nullable = true, length = 512)
    public String iv;

    // ── Finders & Updates ───────────────────────────────────────────────────

    public static Message findByClientMsgId(String clientMsgId) {
        return find("clientMsgId", clientMsgId).firstResult();
    }

    public static List<Message> findByConversation(UUID conversationId, Instant before, int limit) {
        if (before != null) {
            return find("conversationId = :cid and sentAt < :before order by sentAt asc",
                    Parameters.with("cid", conversationId).and("before", before))
                    .page(0, limit)
                    .list();
        } else {
            return find("conversationId = :cid order by sentAt asc",
                    Parameters.with("cid", conversationId))
                    .page(0, limit)
                    .list();
        }
    }

    public static Message findLatestByConversation(UUID conversationId) {
        return find("conversationId = ?1 order by sentAt desc", conversationId).firstResult();
    }

    public static long countUnread(UUID conversationId, UUID userId) {
        return count("conversationId = ?1 and senderId != ?2 and status != 'READ'", conversationId, userId);
    }

    public static int markConversationRead(UUID conversationId, UUID readerId, Instant readAt) {
        return update("status = 'READ', readAt = ?1, deliveredAt = COALESCE(deliveredAt, ?1) where conversationId = ?2 and senderId != ?3 and status != 'READ'",
                readAt, conversationId, readerId);
    }

    public static int markMessagesDelivered(UUID conversationId, UUID recipientId, Instant deliveredAt) {
        return update("status = 'DELIVERED', deliveredAt = ?1 where conversationId = ?2 and senderId != ?3 and status = 'SENT'",
                deliveredAt, conversationId, recipientId);
    }
}

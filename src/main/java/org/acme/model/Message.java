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
        @Index(name = "idx_msg_client_id", columnList = "client_msg_id")
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

    @Column(name = "content", nullable = false, length = 4000)
    public String content;

    @Column(name = "sent_at", nullable = false)
    public Instant sentAt;

    @Column(name = "client_msg_id", nullable = false)
    public String clientMsgId;

    // ── Finders ──────────────────────────────────────────────────────────────

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
}

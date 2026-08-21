package org.acme.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(
    name = "conversations",
    uniqueConstraints = @UniqueConstraint(columnNames = {"user1_id", "user2_id"})
)
public class Conversation extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    public UUID id;

    @Column(name = "user1_id", nullable = false)
    public UUID user1Id;

    @Column(name = "user2_id", nullable = false)
    public UUID user2Id;

    @Column(name = "created_at", nullable = false)
    public java.time.Instant createdAt = java.time.Instant.now();

    // ── Finders ──────────────────────────────────────────────────────────────

    /**
     * Looks up the canonical conversation for a pair of users.
     * Caller must pass ids already in canonical order (user1Id < user2Id).
     */
    public static Conversation findByPair(UUID user1Id, UUID user2Id) {
        return find("user1Id = ?1 and user2Id = ?2", user1Id, user2Id).firstResult();
    }

    public static Conversation findByUsers(UUID u1, UUID u2) {
        UUID user1Id = u1.compareTo(u2) < 0 ? u1 : u2;
        UUID user2Id = u1.compareTo(u2) < 0 ? u2 : u1;
        return findByPair(user1Id, user2Id);
    }

    public static java.util.List<Conversation> findByUser(UUID userId) {
        return find("user1Id = ?1 or user2Id = ?1", userId).list();
    }

    /**
     * Returns true if the given userId is either participant in this conversation.
     */
    public boolean hasParticipant(UUID userId) {
        return user1Id.equals(userId) || user2Id.equals(userId);
    }
}

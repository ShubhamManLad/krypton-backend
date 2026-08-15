package org.acme.conversation;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.acme.model.Conversation;
import org.acme.model.Message;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class ConversationService {

    @Transactional
    public Conversation findOrCreate(UUID senderId, UUID recipientId) {
        UUID user1Id = senderId.compareTo(recipientId) < 0 ? senderId : recipientId;
        UUID user2Id = senderId.compareTo(recipientId) < 0 ? recipientId : senderId;

        Conversation conv = Conversation.findByPair(user1Id, user2Id);
        if (conv == null) {
            conv = new Conversation();
            conv.user1Id = user1Id;
            conv.user2Id = user2Id;
            conv.persist();
        }
        return conv;
    }

    public List<Message> getMessages(UUID conversationId, Instant before, int limit) {
        int effectiveLimit = limit > 0 ? limit : 50;
        return Message.findByConversation(conversationId, before, effectiveLimit);
    }
}

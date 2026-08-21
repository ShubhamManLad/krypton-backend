package org.acme.conversation;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.acme.model.Conversation;
import org.acme.model.Message;
import org.acme.model.User;

import java.time.Instant;
import java.util.*;

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
            conv.createdAt = Instant.now();
            conv.persist();
        }
        return conv;
    }

    public List<ConversationSummaryResponse> getUserConversations(UUID userId) {
        List<Conversation> conversations = Conversation.findByUser(userId);
        if (conversations.isEmpty()) {
            return Collections.emptyList();
        }

        List<ConversationSummaryItem> items = new ArrayList<>();

        for (Conversation conv : conversations) {
            UUID partnerId = conv.user1Id.equals(userId) ? conv.user2Id : conv.user1Id;
            User partnerUser = User.findById(partnerId);
            String partnerUsername = partnerUser != null ? partnerUser.username : "Unknown";

            ConversationSummaryResponse.PartnerInfo partnerInfo =
                    new ConversationSummaryResponse.PartnerInfo(partnerId.toString(), partnerUsername);

            Message latestMsg = Message.findLatestByConversation(conv.id);
            ConversationSummaryResponse.LastMessageInfo lastMsgInfo = null;
            Instant activityTime = conv.createdAt != null ? conv.createdAt : Instant.EPOCH;

            if (latestMsg != null) {
                lastMsgInfo = new ConversationSummaryResponse.LastMessageInfo(
                        latestMsg.id.toString(),
                        latestMsg.senderId.toString(),
                        latestMsg.content,
                        latestMsg.sentAt
                );
                if (latestMsg.sentAt != null) {
                    activityTime = latestMsg.sentAt;
                }
            }

            ConversationSummaryResponse summary = new ConversationSummaryResponse(
                    conv.id,
                    partnerInfo,
                    lastMsgInfo,
                    0
            );

            items.add(new ConversationSummaryItem(summary, activityTime));
        }

        // Sort conversations by latest activity DESC
        items.sort((a, b) -> b.activityTime.compareTo(a.activityTime));

        List<ConversationSummaryResponse> result = new ArrayList<>(items.size());
        for (ConversationSummaryItem item : items) {
            result.add(item.response);
        }
        return result;
    }

    public List<Message> getMessages(UUID conversationId, Instant before, int limit) {
        int effectiveLimit = limit > 0 ? limit : 50;
        return Message.findByConversation(conversationId, before, effectiveLimit);
    }

    public List<Message> getMessagesByPartner(UUID authenticatedUserId, UUID partnerId, Instant before, int limit) {
        Conversation conv = Conversation.findByUsers(authenticatedUserId, partnerId);
        if (conv == null) {
            return Collections.emptyList();
        }
        return getMessages(conv.id, before, limit);
    }

    private static class ConversationSummaryItem {
        final ConversationSummaryResponse response;
        final Instant activityTime;

        ConversationSummaryItem(ConversationSummaryResponse response, Instant activityTime) {
            this.response = response;
            this.activityTime = activityTime;
        }
    }
}

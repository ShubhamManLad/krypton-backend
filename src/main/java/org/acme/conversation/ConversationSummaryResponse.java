package org.acme.conversation;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConversationSummaryResponse {

    public UUID conversationId;
    public PartnerInfo partner;
    public LastMessageInfo lastMessage;
    public int unreadCount = 0;

    public static class PartnerInfo {
        public String id;
        public String username;

        public PartnerInfo() {}

        public PartnerInfo(String id, String username) {
            this.id = id;
            this.username = username;
        }
    }

    public static class LastMessageInfo {
        public String id;
        public String senderId;
        public String content;

        @JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC")
        public Instant sentAt;

        public String status; // SENT, DELIVERED, READ

        public LastMessageInfo() {}

        public LastMessageInfo(String id, String senderId, String content, Instant sentAt, String status) {
            this.id = id;
            this.senderId = senderId;
            this.content = content;
            this.sentAt = sentAt;
            this.status = status != null ? status : "SENT";
        }
    }

    public ConversationSummaryResponse() {}

    public ConversationSummaryResponse(UUID conversationId, PartnerInfo partner, LastMessageInfo lastMessage, int unreadCount) {
        this.conversationId = conversationId;
        this.partner = partner;
        this.lastMessage = lastMessage;
        this.unreadCount = unreadCount;
    }
}

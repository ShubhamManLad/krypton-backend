package org.acme.conversation;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.acme.model.Message;

import java.time.Instant;
import java.util.UUID;

public class MessageResponse {

    public String type = "message";
    public UUID id;
    public UUID conversationId;
    public UUID senderId;
    public String content;

    @JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC")
    public Instant sentAt;

    public String clientMsgId;

    public MessageResponse() {}

    public MessageResponse(Message message) {
        this.type = "message";
        this.id = message.id;
        this.conversationId = message.conversationId;
        this.senderId = message.senderId;
        this.content = message.content;
        this.sentAt = message.sentAt;
        this.clientMsgId = message.clientMsgId;
    }

    public MessageResponse(UUID id, UUID conversationId, UUID senderId, String content, Instant sentAt, String clientMsgId) {
        this.type = "message";
        this.id = id;
        this.conversationId = conversationId;
        this.senderId = senderId;
        this.content = content;
        this.sentAt = sentAt;
        this.clientMsgId = clientMsgId;
    }
}

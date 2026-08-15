package org.acme.websocket;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class OutgoingMessage {

    public String type;

    // For type="ack"
    public String clientMsgId;
    public UUID serverMsgId;

    // For type="message"
    public UUID id;
    public UUID conversationId;
    public UUID senderId;
    public String content;

    @JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC")
    public Instant sentAt;

    // For type="presence"
    public List<String> activeUsers;

    // For type="error"
    public String reason;

    public OutgoingMessage() {}

    public static OutgoingMessage ack(String clientMsgId, UUID serverMsgId) {
        OutgoingMessage msg = new OutgoingMessage();
        msg.type = "ack";
        msg.clientMsgId = clientMsgId;
        msg.serverMsgId = serverMsgId;
        return msg;
    }

    public static OutgoingMessage chatMessage(UUID id, UUID conversationId, UUID senderId, String content, Instant sentAt, String clientMsgId) {
        OutgoingMessage msg = new OutgoingMessage();
        msg.type = "message";
        msg.id = id;
        msg.conversationId = conversationId;
        msg.senderId = senderId;
        msg.content = content;
        msg.sentAt = sentAt;
        msg.clientMsgId = clientMsgId;
        return msg;
    }

    public static OutgoingMessage error(String reason) {
        OutgoingMessage msg = new OutgoingMessage();
        msg.type = "error";
        msg.reason = reason;
        return msg;
    }

    public static OutgoingMessage presence(List<String> activeUsers) {
        OutgoingMessage msg = new OutgoingMessage();
        msg.type = "presence";
        msg.activeUsers = activeUsers;
        return msg;
    }
}

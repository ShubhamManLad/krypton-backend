package org.acme.websocket;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class OutgoingMessage {

    public String type; // "ack", "recipient_offline", "message", "delivery_ack", "read", "user_online", "user_offline", "presence", "error"

    // For type="ack" or "recipient_offline"
    public String clientMsgId;
    public UUID serverMsgId;
    public UUID recipientId;

    // For type="message"
    public UUID id;
    public UUID senderId;
    public String content;

    @JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC")
    public Instant sentAt;

    public String status; // DELIVERED, SENT

    // For type="delivery_ack" or "read"
    public UUID messageId;
    public UUID readerId;
    public UUID partnerId;

    @JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC")
    public Instant timestamp;

    @JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC")
    public Instant readAt;

    // For type="presence", "user_online", "user_offline"
    public UUID userId;
    public String username;
    public List<?> activeUsers;

    // For type="error"
    public String reason;

    // ── E2EE fields ──────────────────────────────────────────────────────────
    /** "text" or "image" */
    public String messageType;

    /** Base64-encoded AES-GCM Initialization Vector */
    public String iv;

    public OutgoingMessage() {}

    public static OutgoingMessage ack(String clientMsgId, String status) {
        OutgoingMessage msg = new OutgoingMessage();
        msg.type = "ack";
        msg.clientMsgId = clientMsgId;
        msg.status = status != null ? status : "DELIVERED";
        return msg;
    }

    public static OutgoingMessage recipientOffline(UUID recipientId, String clientMsgId) {
        OutgoingMessage msg = new OutgoingMessage();
        msg.type = "recipient_offline";
        msg.recipientId = recipientId;
        msg.clientMsgId = clientMsgId;
        return msg;
    }

    public static OutgoingMessage relayMessage(UUID senderId, UUID recipientId, String content,
                                               String clientMsgId, String messageType, String iv, Instant sentAt) {
        OutgoingMessage msg = new OutgoingMessage();
        msg.type = "message";
        msg.senderId = senderId;
        msg.recipientId = recipientId;
        msg.content = content;
        msg.clientMsgId = clientMsgId;
        msg.messageType = messageType != null ? messageType : "text";
        msg.iv = iv;
        msg.sentAt = sentAt != null ? sentAt : Instant.now();
        msg.status = "DELIVERED";
        return msg;
    }

    public static OutgoingMessage deliveryAck(UUID senderId, String clientMsgId, Instant timestamp) {
        OutgoingMessage msg = new OutgoingMessage();
        msg.type = "delivery_ack";
        msg.senderId = senderId;
        msg.clientMsgId = clientMsgId;
        msg.timestamp = timestamp != null ? timestamp : Instant.now();
        return msg;
    }

    public static OutgoingMessage readReceipt(UUID readerId, UUID partnerId, String clientMsgId, UUID messageId, Instant readAt) {
        OutgoingMessage msg = new OutgoingMessage();
        msg.type = "read";
        msg.readerId = readerId;
        msg.partnerId = partnerId;
        msg.clientMsgId = clientMsgId;
        msg.messageId = messageId;
        msg.readAt = readAt != null ? readAt : Instant.now();
        return msg;
    }

    public static OutgoingMessage error(String reason) {
        OutgoingMessage msg = new OutgoingMessage();
        msg.type = "error";
        msg.reason = reason;
        return msg;
    }
}

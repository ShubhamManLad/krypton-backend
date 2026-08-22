package org.acme.websocket;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class OutgoingMessage {

    public String type; // "ack", "message", "message_status", "read_receipt", "presence", "error"

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

    // Status tracking for Double Checkmark (SENT, DELIVERED, READ)
    public String status;

    @JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC")
    public Instant deliveredAt;

    @JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC")
    public Instant readAt;

    // For type="message_status"
    public UUID messageId;

    @JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC")
    public Instant timestamp;

    // For type="read_receipt"
    public UUID readerId;

    // For type="presence"
    public List<?> activeUsers;

    // For type="error"
    public String reason;

    // ── E2EE fields ──────────────────────────────────────────────────────────
    /** "text" or "image" — forwarded opaquely; server never decrypts this */
    public String messageType;

    /** Base64-encoded AES-GCM Initialization Vector — required by recipient to decrypt */
    public String iv;

    public OutgoingMessage() {}

    public static OutgoingMessage ack(String clientMsgId, UUID serverMsgId, String status) {
        OutgoingMessage msg = new OutgoingMessage();
        msg.type = "ack";
        msg.clientMsgId = clientMsgId;
        msg.serverMsgId = serverMsgId;
        msg.status = status != null ? status : "SENT";
        return msg;
    }

    public static OutgoingMessage ack(String clientMsgId, UUID serverMsgId) {
        return ack(clientMsgId, serverMsgId, "SENT");
    }

    public static OutgoingMessage chatMessage(UUID id, UUID conversationId, UUID senderId, String content,
                                              Instant sentAt, String clientMsgId, String status,
                                              String messageType, String iv) {
        OutgoingMessage msg = new OutgoingMessage();
        msg.type = "message";
        msg.id = id;
        msg.conversationId = conversationId;
        msg.senderId = senderId;
        msg.content = content;
        msg.sentAt = sentAt;
        msg.clientMsgId = clientMsgId;
        msg.status = status != null ? status : "SENT";
        msg.messageType = messageType != null ? messageType : "text";
        msg.iv = iv;
        return msg;
    }

    public static OutgoingMessage messageStatus(UUID messageId, UUID conversationId, String status, Instant timestamp) {
        OutgoingMessage msg = new OutgoingMessage();
        msg.type = "message_status";
        msg.messageId = messageId;
        msg.conversationId = conversationId;
        msg.status = status;
        msg.timestamp = timestamp;
        return msg;
    }

    public static OutgoingMessage readReceipt(UUID conversationId, UUID readerId, Instant readAt) {
        OutgoingMessage msg = new OutgoingMessage();
        msg.type = "read_receipt";
        msg.conversationId = conversationId;
        msg.readerId = readerId;
        msg.readAt = readAt;
        return msg;
    }

    public static OutgoingMessage error(String reason) {
        OutgoingMessage msg = new OutgoingMessage();
        msg.type = "error";
        msg.reason = reason;
        return msg;
    }

    public static OutgoingMessage presence(List<?> activeUsers) {
        OutgoingMessage msg = new OutgoingMessage();
        msg.type = "presence";
        msg.activeUsers = activeUsers;
        return msg;
    }
}


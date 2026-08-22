package org.acme.conversation;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.acme.model.Message;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageResponse {

    public String type = "message";
    public UUID id;
    public UUID conversationId;
    public UUID senderId;
    public String content;

    @JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC")
    public Instant sentAt;

    public String clientMsgId;
    public String status = "SENT"; // SENT, DELIVERED, READ

    @JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC")
    public Instant deliveredAt;

    @JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC")
    public Instant readAt;

    // ── E2EE fields ──────────────────────────────────────────────────────────
    /** "text" or "image" — relayed as-is; server never inspects the encrypted content */
    public String messageType;

    /** Base64-encoded AES-GCM IV — required by the recipient to decrypt the content */
    public String iv;

    public MessageResponse() {}

    public MessageResponse(Message message) {
        this.type = "message";
        this.id = message.id;
        this.conversationId = message.conversationId;
        this.senderId = message.senderId;
        this.content = message.content;
        this.sentAt = message.sentAt;
        this.clientMsgId = message.clientMsgId;
        this.status = message.status != null ? message.status : "SENT";
        this.deliveredAt = message.deliveredAt;
        this.readAt = message.readAt;
        this.messageType = message.messageType != null ? message.messageType : "text";
        this.iv = message.iv;
    }

    public MessageResponse(UUID id, UUID conversationId, UUID senderId, String content, Instant sentAt,
                           String clientMsgId, String status, Instant deliveredAt, Instant readAt,
                           String messageType, String iv) {
        this.type = "message";
        this.id = id;
        this.conversationId = conversationId;
        this.senderId = senderId;
        this.content = content;
        this.sentAt = sentAt;
        this.clientMsgId = clientMsgId;
        this.status = status != null ? status : "SENT";
        this.deliveredAt = deliveredAt;
        this.readAt = readAt;
        this.messageType = messageType != null ? messageType : "text";
        this.iv = iv;
    }
}


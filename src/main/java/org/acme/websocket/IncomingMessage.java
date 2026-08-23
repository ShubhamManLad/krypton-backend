package org.acme.websocket;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

public class IncomingMessage {

    public String type; // "message", "read", "delivery_ack", "ping", "pong"
    public UUID recipientId;
    public UUID partnerId;
    public UUID conversationId;
    public UUID messageId;
    public String content;
    public String clientMsgId;

    // ── E2EE fields ──────────────────────────────────────────────────────────
    /** "text" or "image" — defaults to "text" if absent */
    public String messageType;

    /** Base64-encoded AES-GCM Initialization Vector, required by recipient to decrypt */
    public String iv;

    // ── Quoted Reply Pass-Through ────────────────────────────────────────────
    /** Optional client message ID of the original message being replied to */
    @JsonProperty("replyToClientMsgId")
    public String replyToClientMsgId;

    public IncomingMessage() {}
}

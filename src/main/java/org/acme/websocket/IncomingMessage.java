package org.acme.websocket;

import java.util.UUID;

public class IncomingMessage {

    public String type; // "message", "read", "delivery_ack"
    public UUID recipientId;
    public UUID conversationId;
    public UUID messageId;
    public String content;
    public String clientMsgId;

    public IncomingMessage() {}
}

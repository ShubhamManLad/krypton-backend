package org.acme.websocket;

import java.util.UUID;

public class IncomingMessage {

    public String type;
    public UUID recipientId;
    public String content;
    public String clientMsgId;

    public IncomingMessage() {}
}

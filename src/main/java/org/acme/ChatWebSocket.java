package org.acme;

import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@WebSocket(path = "/chat/{username}")
public class ChatWebSocket {

    private static final Logger LOG = Logger.getLogger(ChatWebSocket.class);

    @Inject
    WebSocketConnection connection;

    @OnOpen(broadcast = true)
    public String onOpen() {
        String username = connection.pathParam("username");
        LOG.info("User " + username + " connected.");
        return "User " + username + " joined the chat!";
    }

    @OnTextMessage(broadcast = true)
    public String onMessage(String message) {
        String username = connection.pathParam("username");
        LOG.info("Received message from " + username + ": " + message);
        return username + ": " + message;
    }

    @OnClose
    public void onClose() {
        String username = connection.pathParam("username");
        LOG.info("User " + username + " disconnected.");
    }
}

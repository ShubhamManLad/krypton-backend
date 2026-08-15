package org.acme.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.websockets.next.*;
import io.smallrye.jwt.auth.principal.JWTParser;
import io.smallrye.jwt.auth.principal.ParseException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.acme.conversation.ConversationService;
import org.acme.model.Conversation;
import org.acme.model.Message;
import org.acme.model.User;
import org.acme.presence.PresenceRegistry;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
@WebSocket(path = "/chat")
public class ChatWebSocket {

    private static final Logger LOG = Logger.getLogger(ChatWebSocket.class);

    @Inject
    JWTParser jwtParser;

    @Inject
    PresenceRegistry presenceRegistry;

    @Inject
    ConversationService conversationService;

    @Inject
    ObjectMapper objectMapper;

    // Track mapping from WebSocketConnection id to authenticated userId
    private final Map<String, UUID> connectionUserMap = new ConcurrentHashMap<>();

    @OnOpen
    public void onOpen(WebSocketConnection connection, HandshakeRequest handshakeRequest) {
        String token = extractToken(handshakeRequest);
        if (token == null || token.isBlank()) {
            LOG.warn("WebSocket connection rejected: missing token");
            closeUnauthorized(connection);
            return;
        }

        UUID userId;
        String username;
        try {
            JsonWebToken jwt = jwtParser.parse(token);
            String sub = jwt.getSubject();
            if (sub == null || sub.isBlank()) {
                throw new IllegalArgumentException("Missing sub in JWT");
            }
            userId = UUID.fromString(sub);
            username = jwt.getClaim("upn") != null ? jwt.getClaim("upn").toString() : jwt.getName();
            if (username == null || username.isBlank()) {
                User user = User.findById(userId);
                if (user != null) {
                    username = user.username;
                }
            }
        } catch (ParseException | IllegalArgumentException e) {
            LOG.warnf("WebSocket connection rejected: invalid token (%s)", e.getMessage());
            closeUnauthorized(connection);
            return;
        }

        // Register session
        connectionUserMap.put(connection.id(), userId);
        presenceRegistry.join(userId, username, connection);
        LOG.infof("User %s (%s) connected on WebSocket session %s", username, userId, connection.id());

        // Broadcast presence to ALL connected clients
        presenceRegistry.broadcastPresence();
    }

    @OnTextMessage
    @Transactional
    public void onTextMessage(String messageText, WebSocketConnection connection) {
        UUID senderId = connectionUserMap.get(connection.id());
        if (senderId == null) {
            sendError(connection, "Unauthorized session");
            return;
        }

        IncomingMessage incoming;
        try {
            incoming = objectMapper.readValue(messageText, IncomingMessage.class);
        } catch (Exception e) {
            sendError(connection, "Malformed JSON message");
            return;
        }

        // Validate message type
        if (incoming == null || !"message".equals(incoming.type)) {
            sendError(connection, "Invalid message type. Expected 'message'");
            return;
        }

        // Validate recipientId
        if (incoming.recipientId == null) {
            sendError(connection, "recipientId is required");
            return;
        }

        User recipient = User.findById(incoming.recipientId);
        if (recipient == null) {
            sendError(connection, "Recipient user not found");
            return;
        }

        // Validate content
        if (incoming.content == null || incoming.content.trim().isEmpty()) {
            sendError(connection, "Message content cannot be blank");
            return;
        }

        // Validate clientMsgId
        if (incoming.clientMsgId == null || incoming.clientMsgId.trim().isEmpty()) {
            sendError(connection, "clientMsgId is required");
            return;
        }

        try {
            // Find or create canonical conversation
            Conversation conversation = conversationService.findOrCreate(senderId, incoming.recipientId);

            // Check idempotency with clientMsgId
            Message existing = Message.findByClientMsgId(incoming.clientMsgId);
            if (existing != null) {
                // Skip insertion and re-send ack with existing server id
                sendAck(connection, existing.clientMsgId, existing.id);
                return;
            }

            // Persist new message
            Message newMessage = new Message();
            newMessage.conversationId = conversation.id;
            newMessage.senderId = senderId;
            newMessage.content = incoming.content;
            newMessage.sentAt = Instant.now();
            newMessage.clientMsgId = incoming.clientMsgId;
            newMessage.persist();

            // 1. Send ack ONLY to the sender
            sendAck(connection, incoming.clientMsgId, newMessage.id);

            // 2. Push full message to the recipient (if connected)
            Optional<WebSocketConnection> recipientConnOpt = presenceRegistry.connectionFor(incoming.recipientId);
            if (recipientConnOpt.isPresent()) {
                WebSocketConnection recipientConn = recipientConnOpt.get();
                if (recipientConn.isOpen()) {
                    OutgoingMessage pushMsg = OutgoingMessage.chatMessage(
                            newMessage.id,
                            conversation.id,
                            senderId,
                            newMessage.content,
                            newMessage.sentAt,
                            newMessage.clientMsgId
                    );
                    String pushJson = objectMapper.writeValueAsString(pushMsg);
                    recipientConn.sendText(pushJson).subscribe().with(
                            v -> {},
                            err -> LOG.warnf("Failed to push message to recipient %s: %s", incoming.recipientId, err.getMessage())
                    );
                }
            }
        } catch (Exception e) {
            LOG.error("Error processing message", e);
            sendError(connection, "Internal server error processing message");
        }
    }

    @OnClose
    public void onClose(WebSocketConnection connection) {
        UUID userId = connectionUserMap.remove(connection.id());
        if (userId != null) {
            presenceRegistry.leave(userId);
            LOG.infof("User %s disconnected from WebSocket session %s", userId, connection.id());
            presenceRegistry.broadcastPresence();
        }
    }

    private String extractToken(HandshakeRequest handshakeRequest) {
        if (handshakeRequest == null) return null;
        try {
            String query = handshakeRequest.query();
            if (query != null && !query.isBlank()) {
                if (query.startsWith("?")) {
                    query = query.substring(1);
                }
                for (String param : query.split("&")) {
                    String[] pair = param.split("=", 2);
                    if (pair.length == 2 && "token".equalsIgnoreCase(pair[0])) {
                        return URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void sendAck(WebSocketConnection connection, String clientMsgId, UUID serverMsgId) {
        try {
            OutgoingMessage ack = OutgoingMessage.ack(clientMsgId, serverMsgId);
            String json = objectMapper.writeValueAsString(ack);
            connection.sendText(json).subscribe().with(v -> {}, err -> LOG.warn("Failed to send ack: " + err.getMessage()));
        } catch (Exception e) {
            LOG.error("Failed to serialize ack", e);
        }
    }

    private void sendError(WebSocketConnection connection, String reason) {
        try {
            OutgoingMessage err = OutgoingMessage.error(reason);
            String json = objectMapper.writeValueAsString(err);
            connection.sendText(json).subscribe().with(v -> {}, e -> LOG.warn("Failed to send error: " + e.getMessage()));
        } catch (Exception e) {
            LOG.error("Failed to serialize error message", e);
        }
    }

    private void closeUnauthorized(WebSocketConnection connection) {
        try {
            connection.close(new CloseReason(4001, "Unauthorized"));
        } catch (Exception e) {
            connection.close();
        }
    }
}

package org.acme.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.websockets.next.*;
import io.smallrye.jwt.auth.principal.JWTParser;
import io.smallrye.jwt.auth.principal.ParseException;
import io.smallrye.mutiny.Uni;
import io.vertx.core.buffer.Buffer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.model.User;
import org.acme.presence.PresenceRegistry;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger LOG = LoggerFactory.getLogger(ChatWebSocket.class);

    @Inject
    JWTParser jwtParser;

    @Inject
    PresenceRegistry presenceRegistry;

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
            LOG.warn("WebSocket connection rejected: invalid token ({})", e.getMessage());
            closeUnauthorized(connection);
            return;
        }

        // Register session
        connectionUserMap.put(connection.id(), userId);
        presenceRegistry.join(userId, username, connection);
        LOG.info("User {} ({}) connected on WebSocket session {}", username, userId, connection.id());

        // 1. Send currently active online users list directly to this connected client
        presenceRegistry.sendActiveUsers(connection);

        // 2. Broadcast user_online event to all other active connected clients
        presenceRegistry.broadcastUserOnline(userId, username);
    }

    @OnPingMessage
    public Uni<Void> onPing(Buffer buffer, WebSocketConnection connection) {
        UUID userId = connectionUserMap.get(connection.id());
        if (userId != null) {
            presenceRegistry.recordHeartbeat(userId);
        }
        return connection.sendPong(buffer);
    }

    @OnPongMessage
    public void onPong(Buffer buffer, WebSocketConnection connection) {
        UUID userId = connectionUserMap.get(connection.id());
        if (userId != null) {
            presenceRegistry.recordHeartbeat(userId);
        }
    }

    @OnTextMessage
    public void onTextMessage(String messageText, WebSocketConnection connection) {
        UUID senderId = connectionUserMap.get(connection.id());
        if (senderId == null) {
            sendError(connection, "Unauthorized session");
            return;
        }

        // Record heartbeat on every inbound activity
        presenceRegistry.recordHeartbeat(senderId);

        IncomingMessage incoming;
        try {
            incoming = objectMapper.readValue(messageText, IncomingMessage.class);
        } catch (Exception e) {
            sendError(connection, "Malformed JSON message");
            return;
        }

        if (incoming == null || incoming.type == null) {
            sendError(connection, "Missing message type");
            return;
        }

        switch (incoming.type) {
            case "ping" -> handlePing(connection, senderId);
            case "pong" -> handlePong(senderId);
            case "message" -> handleChatMessage(incoming, senderId, connection);
            case "read" -> handleReadReceipt(incoming, senderId, connection);
            case "delivery_ack" -> handleDeliveryAck(incoming, senderId, connection);
            default -> sendError(connection, "Unknown message type: " + incoming.type);
        }
    }

    private void handlePing(WebSocketConnection connection, UUID senderId) {
        presenceRegistry.recordHeartbeat(senderId);
        try {
            OutgoingMessage pong = OutgoingMessage.pong();
            String json = objectMapper.writeValueAsString(pong);
            connection.sendText(json).subscribe().with(v -> {}, err -> LOG.warn("Failed to send pong: {}", err.getMessage()));
        } catch (Exception e) {
            LOG.error("Failed to serialize pong", e);
        }
    }

    private void handlePong(UUID senderId) {
        presenceRegistry.recordHeartbeat(senderId);
    }

    private void handleChatMessage(IncomingMessage incoming, UUID senderId, WebSocketConnection connection) {
        if (incoming.recipientId == null) {
            sendError(connection, "recipientId is required");
            return;
        }

        if (incoming.content == null || incoming.content.trim().isEmpty()) {
            sendError(connection, "Message content cannot be blank");
            return;
        }

        if (incoming.clientMsgId == null || incoming.clientMsgId.trim().isEmpty()) {
            sendError(connection, "clientMsgId is required");
            return;
        }

        try {
            Optional<WebSocketConnection> recipientConnOpt = presenceRegistry.connectionFor(incoming.recipientId);

            if (recipientConnOpt.isPresent() && recipientConnOpt.get().isOpen()) {
                WebSocketConnection recipientConn = recipientConnOpt.get();
                OutgoingMessage pushMsg = OutgoingMessage.relayMessage(
                        senderId,
                        incoming.recipientId,
                        incoming.content,
                        incoming.clientMsgId,
                        incoming.messageType,
                        incoming.iv,
                        Instant.now()
                );

                String json = objectMapper.writeValueAsString(pushMsg);

                // Asynchronously attempt to write to recipient's socket
                recipientConn.sendText(json).subscribe().with(
                        v -> {
                            // Successfully delivered to recipient: send ACK to sender
                            sendAck(connection, incoming.clientMsgId, "DELIVERED");
                        },
                        err -> {
                            // Socket write failure (EPIPE / ECONNRESET / dead socket)
                            LOG.warn("Failed to forward message to recipient {} ({}); cleaning up dead connection",
                                    incoming.recipientId, err.getMessage());

                            // Clean up dead recipient socket
                            cleanupDeadConnection(recipientConn, incoming.recipientId);

                            // Immediately notify sender so their local outbox holds the message
                            OutgoingMessage offlineMsg = OutgoingMessage.recipientOffline(incoming.recipientId, incoming.clientMsgId);
                            sendPush(connection, offlineMsg, senderId);
                        }
                );
            } else {
                // Recipient is offline: notify sender immediately
                OutgoingMessage offlineMsg = OutgoingMessage.recipientOffline(incoming.recipientId, incoming.clientMsgId);
                sendPush(connection, offlineMsg, senderId);
            }
        } catch (Exception e) {
            LOG.error("Error relaying message", e);
            sendError(connection, "Internal server error relaying message");
        }
    }

    private void handleReadReceipt(IncomingMessage incoming, UUID readerId, WebSocketConnection connection) {
        UUID targetUserId = incoming.recipientId != null ? incoming.recipientId : incoming.partnerId;
        if (targetUserId == null) {
            return;
        }

        try {
            Optional<WebSocketConnection> partnerConnOpt = presenceRegistry.connectionFor(targetUserId);
            if (partnerConnOpt.isPresent() && partnerConnOpt.get().isOpen()) {
                WebSocketConnection partnerConn = partnerConnOpt.get();
                OutgoingMessage readEvent = OutgoingMessage.readReceipt(
                        readerId,
                        incoming.partnerId,
                        incoming.clientMsgId,
                        incoming.messageId,
                        Instant.now()
                );
                String json = objectMapper.writeValueAsString(readEvent);
                partnerConn.sendText(json).subscribe().with(
                        v -> {},
                        err -> cleanupDeadConnection(partnerConn, targetUserId)
                );
            }
        } catch (Exception e) {
            LOG.error("Error relaying read receipt", e);
        }
    }

    private void handleDeliveryAck(IncomingMessage incoming, UUID recipientId, WebSocketConnection connection) {
        UUID targetUserId = incoming.recipientId != null ? incoming.recipientId : incoming.partnerId;
        if (targetUserId == null) {
            return;
        }

        try {
            Optional<WebSocketConnection> senderConnOpt = presenceRegistry.connectionFor(targetUserId);
            if (senderConnOpt.isPresent() && senderConnOpt.get().isOpen()) {
                WebSocketConnection senderConn = senderConnOpt.get();
                OutgoingMessage deliveryEvent = OutgoingMessage.deliveryAck(
                        recipientId,
                        incoming.clientMsgId,
                        Instant.now()
                );
                String json = objectMapper.writeValueAsString(deliveryEvent);
                senderConn.sendText(json).subscribe().with(
                        v -> {},
                        err -> cleanupDeadConnection(senderConn, targetUserId)
                );
            }
        } catch (Exception e) {
            LOG.error("Error relaying delivery ack", e);
        }
    }

    private void cleanupDeadConnection(WebSocketConnection conn, UUID userId) {
        try {
            connectionUserMap.remove(conn.id());
            conn.close(new CloseReason(1006, "Socket write failure / connection broken"));
        } catch (Throwable ignored) {}
        presenceRegistry.leave(userId);
        presenceRegistry.broadcastUserOffline(userId);
    }

    @OnClose
    public void onClose(WebSocketConnection connection) {
        UUID userId = connectionUserMap.remove(connection.id());
        if (userId != null) {
            presenceRegistry.leave(userId);
            LOG.info("User {} disconnected from WebSocket session {}", userId, connection.id());
            // Broadcast user_offline to all remaining active clients
            presenceRegistry.broadcastUserOffline(userId);
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

    private void sendAck(WebSocketConnection connection, String clientMsgId, String status) {
        try {
            OutgoingMessage ack = OutgoingMessage.ack(clientMsgId, status);
            String json = objectMapper.writeValueAsString(ack);
            connection.sendText(json).subscribe().with(v -> {}, err -> LOG.warn("Failed to send ack: {}", err.getMessage()));
        } catch (Exception e) {
            LOG.error("Failed to serialize ack", e);
        }
    }

    private void sendPush(WebSocketConnection connection, OutgoingMessage msg, UUID targetUserId) {
        try {
            String json = objectMapper.writeValueAsString(msg);
            connection.sendText(json).subscribe().with(
                    v -> {},
                    err -> LOG.warn("Failed to push to user {}: {}", targetUserId, err.getMessage())
            );
        } catch (Exception e) {
            LOG.error("Failed to serialize push message", e);
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

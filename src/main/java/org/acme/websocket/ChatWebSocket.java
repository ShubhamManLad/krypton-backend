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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
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
            LOG.warn("WebSocket connection rejected: invalid token ({})", e.getMessage());
            closeUnauthorized(connection);
            return;
        }

        // Register session
        connectionUserMap.put(connection.id(), userId);
        presenceRegistry.join(userId, username, connection);
        LOG.info("User {} ({}) connected on WebSocket session {}", username, userId, connection.id());

        // Broadcast presence to ALL connected clients
        presenceRegistry.broadcastPresence();

        // Mark any pending undelivered messages sent to this user as DELIVERED
        markPendingMessagesDeliveredOnConnect(userId);
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

        if (incoming == null || incoming.type == null) {
            sendError(connection, "Missing message type");
            return;
        }

        switch (incoming.type) {
            case "message" -> handleChatMessage(incoming, senderId, connection);
            case "read" -> handleReadReceipt(incoming, senderId, connection);
            case "delivery_ack" -> handleDeliveryAck(incoming, senderId, connection);
            default -> sendError(connection, "Unknown message type: " + incoming.type);
        }
    }

    private void handleChatMessage(IncomingMessage incoming, UUID senderId, WebSocketConnection connection) {
        if (incoming.recipientId == null) {
            sendError(connection, "recipientId is required");
            return;
        }

        User recipient = User.findById(incoming.recipientId);
        if (recipient == null) {
            sendError(connection, "Recipient user not found");
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
            Conversation conversation = conversationService.findOrCreate(senderId, incoming.recipientId);

            // Check idempotency with clientMsgId
            Message existing = Message.findByClientMsgId(incoming.clientMsgId);
            if (existing != null) {
                sendAck(connection, existing.clientMsgId, existing.id, existing.status);
                return;
            }

            // Check if recipient is currently online to set status
            Optional<WebSocketConnection> recipientConnOpt = presenceRegistry.connectionFor(incoming.recipientId);
            boolean isRecipientOnline = recipientConnOpt.isPresent() && recipientConnOpt.get().isOpen();

            Message newMessage = new Message();
            newMessage.conversationId = conversation.id;
            newMessage.senderId = senderId;
            newMessage.content = incoming.content;
            newMessage.sentAt = Instant.now();
            newMessage.clientMsgId = incoming.clientMsgId;
            newMessage.status = isRecipientOnline ? "DELIVERED" : "SENT";
            if (isRecipientOnline) {
                newMessage.deliveredAt = Instant.now();
            }
            newMessage.persist();

            // 1. Send ack to sender (with status DELIVERED or SENT)
            sendAck(connection, incoming.clientMsgId, newMessage.id, newMessage.status);

            // 2. Push message to recipient if online
            if (isRecipientOnline) {
                WebSocketConnection recipientConn = recipientConnOpt.get();
                OutgoingMessage pushMsg = OutgoingMessage.chatMessage(
                        newMessage.id,
                        conversation.id,
                        senderId,
                        newMessage.content,
                        newMessage.sentAt,
                        newMessage.clientMsgId,
                        newMessage.status
                );
                pushMsg.deliveredAt = newMessage.deliveredAt;
                sendPush(recipientConn, pushMsg, incoming.recipientId);
            }
        } catch (Exception e) {
            LOG.error("Error processing message", e);
            sendError(connection, "Internal server error processing message");
        }
    }

    private void handleReadReceipt(IncomingMessage incoming, UUID readerId, WebSocketConnection connection) {
        if (incoming.conversationId == null && incoming.messageId == null) {
            sendError(connection, "conversationId or messageId is required for read receipt");
            return;
        }

        try {
            UUID conversationId = incoming.conversationId;
            if (conversationId == null && incoming.messageId != null) {
                Message msg = Message.findById(incoming.messageId);
                if (msg != null) {
                    conversationId = msg.conversationId;
                }
            }

            if (conversationId == null) {
                sendError(connection, "Conversation not found");
                return;
            }

            Conversation conv = Conversation.findById(conversationId);
            if (conv == null || !conv.hasParticipant(readerId)) {
                sendError(connection, "Conversation not found or access denied");
                return;
            }

            Instant readAt = Instant.now();
            conversationService.markConversationAsRead(conversationId, readerId);

            // Notify the other participant if online
            UUID otherUserId = conv.user1Id.equals(readerId) ? conv.user2Id : conv.user1Id;
            Optional<WebSocketConnection> otherConnOpt = presenceRegistry.connectionFor(otherUserId);
            if (otherConnOpt.isPresent() && otherConnOpt.get().isOpen()) {
                OutgoingMessage readEvent = OutgoingMessage.readReceipt(conversationId, readerId, readAt);
                sendPush(otherConnOpt.get(), readEvent, otherUserId);
            }
        } catch (Exception e) {
            LOG.error("Error processing read receipt", e);
            sendError(connection, "Failed to process read receipt");
        }
    }

    private void handleDeliveryAck(IncomingMessage incoming, UUID recipientId, WebSocketConnection connection) {
        if (incoming.messageId == null && incoming.conversationId == null) {
            return;
        }

        try {
            if (incoming.messageId != null) {
                Message msg = Message.findById(incoming.messageId);
                if (msg != null && "SENT".equals(msg.status)) {
                    msg.status = "DELIVERED";
                    msg.deliveredAt = Instant.now();
                    msg.persist();

                    // Notify sender
                    Optional<WebSocketConnection> senderConnOpt = presenceRegistry.connectionFor(msg.senderId);
                    if (senderConnOpt.isPresent() && senderConnOpt.get().isOpen()) {
                        OutgoingMessage statusEvent = OutgoingMessage.messageStatus(msg.id, msg.conversationId, "DELIVERED", msg.deliveredAt);
                        sendPush(senderConnOpt.get(), statusEvent, msg.senderId);
                    }
                }
            } else if (incoming.conversationId != null) {
                conversationService.markConversationAsDelivered(incoming.conversationId, recipientId);
            }
        } catch (Exception e) {
            LOG.error("Error processing delivery ack", e);
        }
    }

    @Transactional
    public void markPendingMessagesDeliveredOnConnect(UUID userId) {
        try {
            List<Conversation> conversations = Conversation.findByUser(userId);
            Instant now = Instant.now();
            for (Conversation conv : conversations) {
                int updated = Message.markMessagesDelivered(conv.id, userId, now);
                if (updated > 0) {
                    UUID partnerId = conv.user1Id.equals(userId) ? conv.user2Id : conv.user1Id;
                    Optional<WebSocketConnection> partnerConnOpt = presenceRegistry.connectionFor(partnerId);
                    if (partnerConnOpt.isPresent() && partnerConnOpt.get().isOpen()) {
                        OutgoingMessage statusEvent = OutgoingMessage.messageStatus(null, conv.id, "DELIVERED", now);
                        sendPush(partnerConnOpt.get(), statusEvent, partnerId);
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to mark pending messages as delivered on connect: {}", e.getMessage());
        }
    }

    @OnClose
    public void onClose(WebSocketConnection connection) {
        UUID userId = connectionUserMap.remove(connection.id());
        if (userId != null) {
            presenceRegistry.leave(userId);
            LOG.info("User {} disconnected from WebSocket session {}", userId, connection.id());
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

    private void sendAck(WebSocketConnection connection, String clientMsgId, UUID serverMsgId, String status) {
        try {
            OutgoingMessage ack = OutgoingMessage.ack(clientMsgId, serverMsgId, status);
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

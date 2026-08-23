package org.acme.presence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class PresenceRegistry {

    private static final Logger LOG = Logger.getLogger(PresenceRegistry.class);

    public record UserSession(UUID userId, String username, WebSocketConnection connection) {}

    private final ConcurrentHashMap<UUID, UserSession> activeSessions = new ConcurrentHashMap<>();

    @Inject
    ObjectMapper objectMapper;

    public void join(UUID userId, String username, WebSocketConnection conn) {
        activeSessions.put(userId, new UserSession(userId, username, conn));
    }

    public void leave(UUID userId) {
        activeSessions.remove(userId);
    }

    public Optional<WebSocketConnection> connectionFor(UUID userId) {
        UserSession session = activeSessions.get(userId);
        return session != null ? Optional.ofNullable(session.connection()) : Optional.empty();
    }

    public Set<UUID> activeUserIds() {
        return Collections.unmodifiableSet(activeSessions.keySet());
    }

    public Collection<UserSession> activeSessions() {
        return Collections.unmodifiableCollection(activeSessions.values());
    }

    /**
     * Sends the list of all currently active users directly to a specific connected client.
     */
    public void sendActiveUsers(WebSocketConnection conn) {
        try {
            List<Map<String, String>> userList = activeSessions.values().stream()
                    .map(s -> Map.of(
                            "userId", s.userId().toString(),
                            "username", s.username() != null ? s.username() : ""
                    ))
                    .sorted(Comparator.comparing(m -> m.get("username")))
                    .toList();

            Map<String, Object> presenceMsg = Map.of(
                    "type", "presence",
                    "activeUsers", userList
            );

            String json = objectMapper.writeValueAsString(presenceMsg);
            if (conn.isOpen()) {
                conn.sendText(json).subscribe().with(v -> {}, err -> LOG.warnf("Failed to send active users: %s", err.getMessage()));
            }
        } catch (Exception e) {
            LOG.error("Failed to serialize active users list", e);
        }
    }

    /**
     * Broadcasts to all other connected clients that a user came online.
     */
    public void broadcastUserOnline(UUID userId, String username) {
        try {
            Map<String, Object> msg = Map.of(
                    "type", "user_online",
                    "userId", userId.toString(),
                    "username", username != null ? username : ""
            );
            String json = objectMapper.writeValueAsString(msg);

            for (UserSession session : activeSessions.values()) {
                if (!session.userId().equals(userId) && session.connection().isOpen()) {
                    session.connection().sendText(json).subscribe().with(
                            v -> {},
                            failure -> LOG.warnf("Failed to send user_online to %s: %s", session.userId(), failure.getMessage())
                    );
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to broadcast user_online", e);
        }
    }

    /**
     * Broadcasts to all other connected clients that a user went offline.
     */
    public void broadcastUserOffline(UUID userId) {
        try {
            Map<String, Object> msg = Map.of(
                    "type", "user_offline",
                    "userId", userId.toString()
            );
            String json = objectMapper.writeValueAsString(msg);

            for (UserSession session : activeSessions.values()) {
                if (!session.userId().equals(userId) && session.connection().isOpen()) {
                    session.connection().sendText(json).subscribe().with(
                            v -> {},
                            failure -> LOG.warnf("Failed to send user_offline to %s: %s", session.userId(), failure.getMessage())
                    );
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to broadcast user_offline", e);
        }
    }
}

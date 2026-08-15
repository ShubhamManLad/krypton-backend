package org.acme.presence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

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

    public void broadcastPresence() {
        try {
            List<Map<String, String>> userList = activeSessions.values().stream()
                    .map(s -> Map.of(
                            "userId", s.userId().toString(),
                            "username", s.username() != null ? s.username() : ""
                    ))
                    .sorted(Comparator.comparing(m -> m.get("username")))
                    .collect(Collectors.toList());

            Map<String, Object> presenceMsg = Map.of(
                    "type", "presence",
                    "activeUsers", userList
            );

            String json = objectMapper.writeValueAsString(presenceMsg);

            for (UserSession session : activeSessions.values()) {
                WebSocketConnection conn = session.connection();
                if (conn.isOpen()) {
                    conn.sendText(json).subscribe().with(
                            item -> {},
                            failure -> LOG.warnf("Failed to send presence to user %s: %s", session.userId(), failure.getMessage())
                    );
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to broadcast presence", e);
        }
    }
}

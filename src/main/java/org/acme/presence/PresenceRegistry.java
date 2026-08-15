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

    private final ConcurrentHashMap<UUID, WebSocketConnection> sessions = new ConcurrentHashMap<>();

    @Inject
    ObjectMapper objectMapper;

    public void join(UUID userId, WebSocketConnection conn) {
        sessions.put(userId, conn);
    }

    public void leave(UUID userId) {
        sessions.remove(userId);
    }

    public Optional<WebSocketConnection> connectionFor(UUID userId) {
        return Optional.ofNullable(sessions.get(userId));
    }

    public Set<UUID> activeUserIds() {
        return Collections.unmodifiableSet(sessions.keySet());
    }

    public void broadcastPresence() {
        try {
            List<String> activeUsers = sessions.keySet().stream()
                    .map(UUID::toString)
                    .sorted()
                    .collect(Collectors.toList());

            Map<String, Object> presenceMsg = Map.of(
                    "type", "presence",
                    "activeUsers", activeUsers
            );

            String json = objectMapper.writeValueAsString(presenceMsg);

            for (Map.Entry<UUID, WebSocketConnection> entry : sessions.entrySet()) {
                WebSocketConnection conn = entry.getValue();
                if (conn.isOpen()) {
                    conn.sendText(json).subscribe().with(
                            item -> {},
                            failure -> LOG.warnf("Failed to send presence to user %s: %s", entry.getKey(), failure.getMessage())
                    );
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to broadcast presence", e);
        }
    }
}

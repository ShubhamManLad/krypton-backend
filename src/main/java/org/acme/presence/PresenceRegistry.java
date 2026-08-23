package org.acme.presence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.WebSocketConnection;
import io.vertx.core.buffer.Buffer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

@ApplicationScoped
public class PresenceRegistry {

    private static final Logger LOG = Logger.getLogger(PresenceRegistry.class);

    private static final Duration HEARTBEAT_TIMEOUT = Duration.ofSeconds(45);
    private static final long REAPER_INTERVAL_SECONDS = 15;

    public record UserSession(
            UUID userId,
            String username,
            WebSocketConnection connection,
            AtomicReference<Instant> lastSeen
    ) {}

    private final ConcurrentHashMap<UUID, UserSession> activeSessions = new ConcurrentHashMap<>();
    private ScheduledExecutorService reaperExecutor;

    @Inject
    ObjectMapper objectMapper;

    void onStart(@Observes StartupEvent ev) {
        reaperExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "zombie-socket-reaper");
            t.setDaemon(true);
            return t;
        });
        reaperExecutor.scheduleWithFixedDelay(this::reapZombieSockets, REAPER_INTERVAL_SECONDS, REAPER_INTERVAL_SECONDS, TimeUnit.SECONDS);
        LOG.info("Zombie socket reaper started (heartbeat timeout: 45s, scan interval: 15s)");
    }

    void onStop(@Observes ShutdownEvent ev) {
        if (reaperExecutor != null) {
            reaperExecutor.shutdownNow();
        }
    }

    public void join(UUID userId, String username, WebSocketConnection conn) {
        activeSessions.put(userId, new UserSession(userId, username, conn, new AtomicReference<>(Instant.now())));
    }

    public void recordHeartbeat(UUID userId) {
        UserSession session = activeSessions.get(userId);
        if (session != null) {
            session.lastSeen().set(Instant.now());
        }
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
     * Periodic background task to detect half-open / zombie sockets and clean them up.
     */
    private void reapZombieSockets() {
        try {
            Instant now = Instant.now();
            List<UUID> deadUsers = new ArrayList<>();

            for (Map.Entry<UUID, UserSession> entry : activeSessions.entrySet()) {
                UUID userId = entry.getKey();
                UserSession session = entry.getValue();
                WebSocketConnection conn = session.connection();

                boolean isClosed = !conn.isOpen();
                boolean isTimedOut = now.isAfter(session.lastSeen().get().plus(HEARTBEAT_TIMEOUT));

                if (isClosed || isTimedOut) {
                    LOG.warnf("Reaping zombie/dead socket for user %s (isClosed=%s, timedOut=%s)", userId, isClosed, isTimedOut);
                    try {
                        conn.close(new CloseReason(1006, "Heartbeat timeout / zombie socket reaped"));
                    } catch (Throwable ignored) {}
                    deadUsers.add(userId);
                } else {
                    // Send lightweight ping frame (RFC 6455) to verify TCP liveness
                    try {
                        conn.sendPing(Buffer.buffer("probe")).subscribe().with(
                                v -> {},
                                err -> {
                                    LOG.warnf("Socket probe failed for user %s: %s (marking for reap)", userId, err.getMessage());
                                    try {
                                        conn.close(new CloseReason(1006, "Probe failure"));
                                    } catch (Throwable ignored) {}
                                    leave(userId);
                                    broadcastUserOffline(userId);
                                }
                        );
                    } catch (Throwable t) {
                        deadUsers.add(userId);
                    }
                }
            }

            for (UUID userId : deadUsers) {
                leave(userId);
                broadcastUserOffline(userId);
            }
        } catch (Throwable t) {
            LOG.error("Error during zombie socket reap cycle", t);
        }
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

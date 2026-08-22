package org.acme.user;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.acme.model.User;
import org.acme.presence.PresenceRegistry;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.*;

@Path("/users")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UserResource {

    @Inject
    PresenceRegistry presenceRegistry;

    @Inject
    JsonWebToken jwt;

    // ── User list endpoints ───────────────────────────────────────────────────

    /**
     * Returns all registered users with their real-time active/online status
     * and their E2EE public key (if registered).
     * Accessible via /users/active or /users
     */
    @GET
    @Path("/active")
    public Response getActiveUsers(@QueryParam("excludeSelf") @DefaultValue("false") boolean excludeSelf) {
        return getAllUsersWithStatus(excludeSelf);
    }

    @GET
    public Response getAllUsers(@QueryParam("excludeSelf") @DefaultValue("false") boolean excludeSelf) {
        return getAllUsersWithStatus(excludeSelf);
    }

    private Response getAllUsersWithStatus(boolean excludeSelf) {
        UUID callerId = getCallerId();

        Set<UUID> onlineIds = presenceRegistry.activeUserIds();
        List<User> users = User.listAll();

        UUID finalCallerId = callerId;
        List<Map<String, Object>> userList = users.stream()
                .filter(u -> !(excludeSelf && finalCallerId != null && u.id.equals(finalCallerId)))
                .map(u -> {
                    boolean isActive = onlineIds.contains(u.id);
                    Map<String, Object> map = new HashMap<>();
                    map.put("userId", u.id.toString());
                    map.put("username", u.username);
                    map.put("active", isActive);
                    map.put("status", isActive ? "online" : "offline");
                    // Include public key if available (null values omitted by caller's discretion)
                    if (u.publicKey != null) {
                        map.put("publicKey", u.publicKey);
                    }
                    if (finalCallerId != null && u.id.equals(finalCallerId)) {
                        map.put("self", true);
                    }
                    return map;
                })
                .sorted((a, b) -> {
                    // Sort online users first, then alphabetically by username
                    boolean aActive = (Boolean) a.get("active");
                    boolean bActive = (Boolean) b.get("active");
                    if (aActive != bActive) {
                        return aActive ? -1 : 1;
                    }
                    return ((String) a.get("username")).compareToIgnoreCase((String) b.get("username"));
                })
                .toList();

        return Response.ok(userList).build();
    }

    // ── E2EE Public Key endpoints ─────────────────────────────────────────────

    /**
     * Register or rotate the authenticated user's E2EE public key.
     *
     * PUT /users/me/public-key
     * Body: { "publicKey": "base64_encoded_public_key" }
     *
     * The server stores the key as an opaque string — it is never used for
     * any server-side cryptographic operation.
     */
    @PUT
    @Path("/me/public-key")
    @Transactional
    public Response updateMyPublicKey(Map<String, String> body) {
        UUID callerId = getCallerId();
        if (callerId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "Unauthorized"))
                    .build();
        }

        String publicKey = body != null ? body.get("publicKey") : null;
        if (publicKey == null || publicKey.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "publicKey is required"))
                    .build();
        }

        User user = User.findById(callerId);
        if (user == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "User not found"))
                    .build();
        }

        user.publicKey = publicKey.trim();
        user.persist();

        return Response.ok(Map.of(
                "userId", user.id.toString(),
                "username", user.username,
                "publicKey", user.publicKey
        )).build();
    }

    /**
     * Retrieve the E2EE public key of any registered user by their ID.
     *
     * GET /users/{userId}/public-key
     * Response: { "userId": "...", "publicKey": "..." }
     *
     * Used during key-exchange before establishing an encrypted session.
     */
    @GET
    @Path("/{userId}/public-key")
    public Response getPublicKey(@PathParam("userId") UUID userId) {
        UUID callerId = getCallerId();
        if (callerId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "Unauthorized"))
                    .build();
        }

        User user = User.findById(userId);
        if (user == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "User not found"))
                    .build();
        }

        if (user.publicKey == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Public key not registered for this user"))
                    .build();
        }

        return Response.ok(Map.of(
                "userId", user.id.toString(),
                "publicKey", user.publicKey
        )).build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UUID getCallerId() {
        if (jwt == null || jwt.getSubject() == null) return null;
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (Exception ignored) {
            return null;
        }
    }
}

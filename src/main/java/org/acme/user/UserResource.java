package org.acme.user;

import jakarta.inject.Inject;
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

    /**
     * Returns all registered users with their real-time active/online status.
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
        UUID callerId = null;
        if (jwt != null && jwt.getSubject() != null) {
            try {
                callerId = UUID.fromString(jwt.getSubject());
            } catch (Exception ignored) {}
        }

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
}

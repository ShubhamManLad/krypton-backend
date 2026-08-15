package org.acme.user;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.acme.model.User;
import org.acme.presence.PresenceRegistry;

import java.util.*;
import java.util.stream.Collectors;

@Path("/users")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UserResource {

    @Inject
    PresenceRegistry presenceRegistry;

    @GET
    @Path("/active")
    public Response getActiveUsers() {
        // Get list of active users directly from PresenceRegistry (avoiding DB queries)
        List<Map<String, String>> activeUsers = presenceRegistry.activeSessions().stream()
                .map(s -> Map.of(
                        "userId", s.userId().toString(),
                        "username", s.username() != null ? s.username() : "Unknown"
                ))
                .sorted(Comparator.comparing(m -> m.get("username")))
                .collect(Collectors.toList());

        return Response.ok(activeUsers).build();
    }

    @GET
    public Response getAllUsers() {
        // Fetch all registered users in the system without requiring JWT
        List<User> users = User.listAll();
        List<Map<String, Object>> userList = users.stream()
                .map(u -> {
                    boolean isActive = presenceRegistry.activeUserIds().contains(u.id);
                    Map<String, Object> map = new HashMap<>();
                    map.put("userId", u.id.toString());
                    map.put("username", u.username);
                    map.put("active", isActive);
                    return map;
                })
                .sorted(Comparator.comparing(m -> m.get("username").toString()))
                .collect(Collectors.toList());

        return Response.ok(userList).build();
    }
}

package org.acme.auth;

import io.quarkus.elytron.security.common.BcryptUtil;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.acme.model.User;

import java.util.Map;

@Path("/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuthResource {

    @Inject
    JwtService jwtService;

    @POST
    @Path("/register")
    @Transactional
    public Response register(AuthRequest request) {
        if (request == null || request.username == null || request.username.trim().isEmpty()
                || request.password == null || request.password.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Username and password are required"))
                    .build();
        }

        String username = request.username.trim();
        if (User.existsByUsername(username)) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "Username already taken"))
                    .build();
        }

        User user = new User();
        user.username = username;
        user.passwordHash = BcryptUtil.bcryptHash(request.password);
        user.persist();

        String token = jwtService.generateToken(user.id, user.username);
        return Response.ok(new AuthResponse(token, user.id, user.username)).build();
    }

    @POST
    @Path("/login")
    public Response login(AuthRequest request) {
        if (request == null || request.username == null || request.password == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "Invalid credentials"))
                    .build();
        }

        User user = User.findByUsername(request.username.trim());
        if (user == null || !BcryptUtil.matches(request.password, user.passwordHash)) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "Invalid credentials"))
                    .build();
        }

        String token = jwtService.generateToken(user.id, user.username);
        return Response.ok(new AuthResponse(token, user.id, user.username)).build();
    }
}

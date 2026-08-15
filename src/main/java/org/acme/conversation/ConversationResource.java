package org.acme.conversation;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.acme.model.Conversation;
import org.acme.model.Message;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Path("/conversations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ConversationResource {

    @Inject
    JsonWebToken jwt;

    @Inject
    ConversationService conversationService;

    @GET
    @Path("/{conversationId}/messages")
    @RolesAllowed("user")
    public Response getMessages(
            @PathParam("conversationId") UUID conversationId,
            @QueryParam("before") String beforeStr,
            @QueryParam("limit") @DefaultValue("50") int limit
    ) {
        if (jwt == null || jwt.getSubject() == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"error\":\"Unauthorized\"}")
                    .build();
        }

        UUID authenticatedUserId;
        try {
            authenticatedUserId = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"error\":\"Invalid user token\"}")
                    .build();
        }

        Conversation conversation = Conversation.findById(conversationId);
        if (conversation == null || !conversation.hasParticipant(authenticatedUserId)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity("{\"error\":\"Access denied to conversation\"}")
                    .build();
        }

        Instant before = null;
        if (beforeStr != null && !beforeStr.trim().isEmpty()) {
            try {
                before = Instant.parse(beforeStr.trim());
            } catch (Exception e) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("{\"error\":\"Invalid 'before' timestamp format. Use ISO-8601 (e.g. 2024-01-15T10:30:00Z)\"}")
                        .build();
            }
        }

        List<Message> messages = conversationService.getMessages(conversationId, before, limit);
        List<MessageResponse> responseList = messages.stream()
                .map(MessageResponse::new)
                .collect(Collectors.toList());

        return Response.ok(responseList).build();
    }
}

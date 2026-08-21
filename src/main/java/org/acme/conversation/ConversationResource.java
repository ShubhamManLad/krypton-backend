package org.acme.conversation;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.acme.model.Conversation;
import org.acme.model.Message;
import org.acme.model.User;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Path("/conversations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ConversationResource {

    @Inject
    JsonWebToken jwt;

    @Inject
    ConversationService conversationService;

    /**
     * List all conversations the authenticated user participates in,
     * sorted by latest message / activity DESC.
     */
    @GET
    @RolesAllowed("user")
    public Response getConversations() {
        UUID authenticatedUserId = getAuthenticatedUserId();
        if (authenticatedUserId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"error\":\"Unauthorized\"}")
                    .build();
        }

        List<ConversationSummaryResponse> conversations = conversationService.getUserConversations(authenticatedUserId);
        return Response.ok(conversations).build();
    }

    /**
     * Get or create a 1:1 conversation with a specific recipient.
     */
    @GET
    @Path("/with/{recipientId}")
    @RolesAllowed("user")
    public Response getOrCreateConversationWith(@PathParam("recipientId") UUID recipientId) {
        UUID authenticatedUserId = getAuthenticatedUserId();
        if (authenticatedUserId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"error\":\"Unauthorized\"}")
                    .build();
        }

        if (recipientId == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Recipient ID is required\"}")
                    .build();
        }

        User recipient = User.findById(recipientId);
        if (recipient == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"Recipient user not found\"}")
                    .build();
        }

        Conversation conv = conversationService.findOrCreate(authenticatedUserId, recipientId);
        ConversationDetailResponse response = new ConversationDetailResponse(
                conv.id,
                List.of(authenticatedUserId.toString(), recipientId.toString()),
                conv.createdAt
        );

        return Response.ok(response).build();
    }

    /**
     * Query messages directly by partner user ID.
     */
    @GET
    @Path("/messages")
    @RolesAllowed("user")
    public Response getMessagesByPartner(
            @QueryParam("partnerId") UUID partnerId,
            @QueryParam("before") String beforeStr,
            @QueryParam("limit") @DefaultValue("50") int limit
    ) {
        UUID authenticatedUserId = getAuthenticatedUserId();
        if (authenticatedUserId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"error\":\"Unauthorized\"}")
                    .build();
        }

        if (partnerId == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"'partnerId' query parameter is required\"}")
                    .build();
        }

        User partner = User.findById(partnerId);
        if (partner == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"Partner user not found\"}")
                    .build();
        }

        Instant before = parseBeforeTimestamp(beforeStr);
        if (beforeStr != null && !beforeStr.trim().isEmpty() && before == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Invalid 'before' timestamp format. Use ISO-8601 (e.g. 2024-01-15T10:30:00Z)\"}")
                    .build();
        }

        List<Message> messages = conversationService.getMessagesByPartner(authenticatedUserId, partnerId, before, limit);
        List<MessageResponse> responseList = messages.stream()
                .map(MessageResponse::new)
                .toList();

        return Response.ok(responseList).build();
    }

    /**
     * Query messages by conversation ID.
     */
    @GET
    @Path("/{conversationId}/messages")
    @RolesAllowed("user")
    public Response getMessages(
            @PathParam("conversationId") UUID conversationId,
            @QueryParam("before") String beforeStr,
            @QueryParam("limit") @DefaultValue("50") int limit
    ) {
        UUID authenticatedUserId = getAuthenticatedUserId();
        if (authenticatedUserId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"error\":\"Unauthorized\"}")
                    .build();
        }

        Conversation conversation = Conversation.findById(conversationId);
        if (conversation == null || !conversation.hasParticipant(authenticatedUserId)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity("{\"error\":\"Access denied to conversation\"}")
                    .build();
        }

        Instant before = parseBeforeTimestamp(beforeStr);
        if (beforeStr != null && !beforeStr.trim().isEmpty() && before == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Invalid 'before' timestamp format. Use ISO-8601 (e.g. 2024-01-15T10:30:00Z)\"}")
                    .build();
        }

        List<Message> messages = conversationService.getMessages(conversationId, before, limit);
        List<MessageResponse> responseList = messages.stream()
                .map(MessageResponse::new)
                .toList();

        return Response.ok(responseList).build();
    }

    private UUID getAuthenticatedUserId() {
        if (jwt == null || jwt.getSubject() == null) {
            return null;
        }
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Instant parseBeforeTimestamp(String beforeStr) {
        if (beforeStr == null || beforeStr.trim().isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(beforeStr.trim());
        } catch (Exception e) {
            return null;
        }
    }
}

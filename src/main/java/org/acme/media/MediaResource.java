package org.acme.media;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.acme.model.Media;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Path("/media")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MediaResource {

    private static final Logger LOG = LoggerFactory.getLogger(MediaResource.class);

    @Inject
    JsonWebToken jwt;

    public static class MediaUploadRequest {
        public String data;
        public String contentType;
        public String fileName;
    }

    public static class MediaUploadResponse {
        public UUID id;
        public UUID mediaId;
        public String url;
        public Long sizeBytes;
        public Instant createdAt;

        public MediaUploadResponse(UUID id, Long sizeBytes, Instant createdAt) {
            this.id = id;
            this.mediaId = id;
            this.url = "/media/" + id;
            this.sizeBytes = sizeBytes;
            this.createdAt = createdAt;
        }
    }

    public static class MediaResponse {
        public UUID id;
        public UUID uploaderId;
        public String data;
        public String contentType;
        public String fileName;
        public Long sizeBytes;
        public Instant createdAt;

        public MediaResponse(Media media) {
            this.id = media.id;
            this.uploaderId = media.uploaderId;
            this.data = media.data;
            this.contentType = media.contentType;
            this.fileName = media.fileName;
            this.sizeBytes = media.sizeBytes;
            this.createdAt = media.createdAt;
        }
    }

    /**
     * Upload an encrypted base64 image or file payload.
     * Stored in the database and returns a unique identifier (UUID).
     */
    @POST
    @RolesAllowed("user")
    @Transactional
    public Response uploadMedia(MediaUploadRequest request) {
        UUID authenticatedUserId = getAuthenticatedUserId();
        if (authenticatedUserId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "Unauthorized"))
                    .build();
        }

        if (request == null || request.data == null || request.data.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "'data' field containing Base64 content is required"))
                    .build();
        }

        try {
            Media media = new Media();
            media.uploaderId = authenticatedUserId;
            media.data = request.data.trim();
            media.contentType = request.contentType != null ? request.contentType.trim() : "image/jpeg";
            media.fileName = request.fileName;
            media.sizeBytes = (long) media.data.length();
            media.createdAt = Instant.now();

            media.persist();

            LOG.info("Media {} uploaded successfully by user {} (size: {} chars)", media.id, authenticatedUserId, media.sizeBytes);

            return Response.status(Response.Status.CREATED)
                    .entity(new MediaUploadResponse(media.id, media.sizeBytes, media.createdAt))
                    .build();
        } catch (Exception e) {
            LOG.error("Failed to save media upload", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to upload media: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Fetch the base64 media payload by its unique ID.
     */
    @GET
    @Path("/{id}")
    @RolesAllowed("user")
    public Response getMedia(@PathParam("id") UUID id) {
        UUID authenticatedUserId = getAuthenticatedUserId();
        if (authenticatedUserId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "Unauthorized"))
                    .build();
        }

        if (id == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Media ID is required"))
                    .build();
        }

        Media media = Media.findById(id);
        if (media == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Media not found"))
                    .build();
        }

        return Response.ok(new MediaResponse(media)).build();
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
}

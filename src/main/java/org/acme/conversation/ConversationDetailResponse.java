package org.acme.conversation;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class ConversationDetailResponse {

    public UUID conversationId;
    public List<String> participants;

    @JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC")
    public Instant createdAt;

    public ConversationDetailResponse() {}

    public ConversationDetailResponse(UUID conversationId, List<String> participants, Instant createdAt) {
        this.conversationId = conversationId;
        this.participants = participants;
        this.createdAt = createdAt;
    }
}

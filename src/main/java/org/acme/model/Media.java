package org.acme.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "media_attachments",
    indexes = {
        @Index(name = "idx_media_uploader", columnList = "uploader_id"),
        @Index(name = "idx_media_created", columnList = "created_at")
    }
)
public class Media extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    public UUID id;

    @Column(name = "uploader_id", nullable = false)
    public UUID uploaderId;

    /** Stores the base64-encoded encrypted image/media payload */
    @Lob
    @Column(name = "data", nullable = false, columnDefinition = "LONGTEXT")
    public String data;

    @Column(name = "content_type")
    public String contentType;

    @Column(name = "file_name")
    public String fileName;

    @Column(name = "size_bytes")
    public Long sizeBytes;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}

package datasource.evidence;

import java.net.URI;
import java.time.Instant;

/** An immutable, addressable version of a source document. */
public record SourceDocument(
        String kind,
        String sourceId,
        String title,
        String url,
        String revision,
        ContentDigest contentDigest,
        String retrievedAt) {
    public SourceDocument {
        kind = required(kind, "Document source kind is required");
        sourceId = text(sourceId);
        title = text(title);
        url = text(url);
        revision = text(revision);
        if (sourceId.isBlank() && url.isBlank()) {
            throw new IllegalArgumentException("Document sourceId or URL is required");
        }
        if (!url.isBlank()) URI.create(url);
        if (revision.isBlank() && contentDigest == null) {
            throw new IllegalArgumentException("Document revision or content digest is required");
        }
        retrievedAt = required(retrievedAt, "Retrieval time is required");
        Instant.parse(retrievedAt);
    }

    public String documentId() {
        return kind + ':' + (!sourceId.isBlank() ? sourceId : url);
    }

    public String versionId() {
        String digest = contentDigest == null ? "" : contentDigest.encoded();
        if (!revision.isBlank() && !digest.isBlank()) {
            return "revision:" + revision + ";content:" + digest;
        }
        return !revision.isBlank() ? "revision:" + revision : "content:" + digest;
    }

    public boolean sameVersion(SourceDocument other) {
        return other != null && documentId().equals(other.documentId())
                && versionId().equals(other.versionId());
    }

    public Instant retrievedInstant() { return Instant.parse(retrievedAt); }

    private static String required(String value, String message) {
        String normalized = text(value);
        if (normalized.isBlank()) throw new IllegalArgumentException(message);
        return normalized;
    }

    private static String text(String value) { return value == null ? "" : value.trim(); }
}

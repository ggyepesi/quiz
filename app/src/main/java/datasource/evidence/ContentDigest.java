package datasource.evidence;

/** Algorithm-qualified document digest. */
public record ContentDigest(String algorithm, String value) {
    public ContentDigest {
        algorithm = required(algorithm, "Digest algorithm is required").toLowerCase();
        value = required(value, "Digest value is required");
        if (!algorithm.matches("[a-z0-9][a-z0-9._-]*")) {
            throw new IllegalArgumentException("Invalid digest algorithm: " + algorithm);
        }
    }

    public static ContentDigest parse(String encoded) {
        String value = required(encoded, "Digest is required");
        int split = value.indexOf(':');
        return split > 0
                ? new ContentDigest(value.substring(0, split), value.substring(split + 1))
                : new ContentDigest("sha256", value);
    }

    public String encoded() { return algorithm + ':' + value; }

    private static String required(String value, String message) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) throw new IllegalArgumentException(message);
        return normalized;
    }
}

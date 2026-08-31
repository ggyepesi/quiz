package wikidata.explore.model;

import java.util.UUID;
import java.nio.charset.StandardCharsets;

/** Stable semantic identities for class and selection declarations. */
public final class DeclarationIds {
    private static final String PREFIX = "decl:";

    private DeclarationIds() {}

    public static String create() {
        return PREFIX + UUID.randomUUID();
    }

    /** Deterministic one-time identity for declarations loaded from pre-id models. */
    public static String legacy(String projectName, String kind, String name) {
        String seed = clean(projectName) + "\u0000" + clean(kind) + "\u0000" + clean(name);
        return PREFIX + UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    public static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}

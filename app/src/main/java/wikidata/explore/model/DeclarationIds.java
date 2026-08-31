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

    /** Stable identity minted when a declaration first enters a shared module. */
    public static String module(String moduleId, String kind, String initialName) {
        String owner = clean(moduleId);
        String declarationKind = clean(kind);
        String name = clean(initialName);
        if (owner.isBlank() || declarationKind.isBlank() || name.isBlank()) {
            throw new IllegalArgumentException(
                    "Module id, declaration kind and initial name are required");
        }
        return "module:" + owner + ":" + declarationKind + ":" + name;
    }

    public static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}

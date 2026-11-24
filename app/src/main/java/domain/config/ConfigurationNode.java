package domain.config;

import domain.datasource.api.SourceBindingTarget;

import java.util.List;

/** One UI-neutral node in the stored-configuration projection. */
public record ConfigurationNode(
        Id id,
        Kind kind,
        String caption,
        String detailKey,
        ConfigurationReferenceInventory.Site referenceTarget,
        ConfigurationReferenceInventory.BindingSite bindingSite,
        SourceBindingTarget sourceContext,
        List<ConfigurationNode> children) {

    public enum Kind {
        STORED_CONFIGURATION,
        AVAILABLE_SLOT,
        REFERENCE,
        INVENTORY_SUMMARY
    }

    public record Id(String declarationId, String path, String key) {
        public Id {
            declarationId = clean(declarationId);
            path = clean(path);
            key = required(key, "Configuration node key");
        }
    }

    public ConfigurationNode {
        if (id == null) throw new IllegalArgumentException("Configuration node id is required");
        if (kind == null) throw new IllegalArgumentException("Configuration node kind is required");
        caption = required(caption, "Configuration node caption");
        detailKey = required(detailKey, "Configuration detail key");
        children = children == null ? List.of() : List.copyOf(children);
    }

    public ConfigurationNode(Id id, Kind kind, String caption, String detailKey,
            List<ConfigurationNode> children) {
        this(id, kind, caption, detailKey, null, null, null, children);
    }

    public ConfigurationNode(Id id, Kind kind, String caption, String detailKey,
            ConfigurationReferenceInventory.Site referenceTarget,
            List<ConfigurationNode> children) {
        this(id, kind, caption, detailKey, referenceTarget, null, null, children);
    }

    public ConfigurationNode(Id id, Kind kind, String caption, String detailKey,
            ConfigurationReferenceInventory.Site referenceTarget,
            ConfigurationReferenceInventory.BindingSite bindingSite,
            List<ConfigurationNode> children) {
        this(id, kind, caption, detailKey, referenceTarget, bindingSite,
                bindingSite == null ? null : bindingSite.binding().target(), children);
    }

    private static String required(String value, String name) {
        String clean = clean(value);
        if (clean.isBlank()) throw new IllegalArgumentException(name + " is required");
        return clean;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}

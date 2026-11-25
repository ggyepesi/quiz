package domain.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/** Projects the reference inventory into the same Needs and Used by branches for each editor. */
final class ConfigurationReferenceProjection {
    static final String DETAIL = "reference";

    private ConfigurationReferenceProjection() { }

    static Optional<ConfigurationNode> needs(ConfigurationReferenceInventory.Site owner,
            List<ConfigurationReferenceInventory.Reference> references) {
        return group(owner, "needs", "Needs", references, true);
    }

    static Optional<ConfigurationNode> usedBy(ConfigurationReferenceInventory.Site owner,
            List<ConfigurationReferenceInventory.Reference> references) {
        return group(owner, "used-by", "Used by", references, false);
    }

    private static Optional<ConfigurationNode> group(ConfigurationReferenceInventory.Site owner,
            String key, String caption, List<ConfigurationReferenceInventory.Reference> references,
            boolean outgoing) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<ConfigurationNode> children = new ArrayList<>();
        for (var reference : references) {
            if (reference.relation() == ConfigurationReferenceInventory.Relation.SOURCE_BINDING_TARGET)
                continue;
            var endpoint = outgoing ? reference.target() : reference.source();
            String unique = reference.relation() + "|" + endpoint + "|" + reference.role();
            if (!seen.add(unique)) continue;
            children.add(new ConfigurationNode(id(owner, key + ":" + unique),
                    ConfigurationNode.Kind.REFERENCE,
                    reference.role() + ": " + endpoint.displayName(), DETAIL, endpoint, List.of()));
        }
        return children.isEmpty() ? Optional.empty() : Optional.of(new ConfigurationNode(
                id(owner, key), ConfigurationNode.Kind.REFERENCE, caption, DETAIL, children));
    }

    private static ConfigurationNode.Id id(ConfigurationReferenceInventory.Site owner, String key) {
        return new ConfigurationNode.Id(owner.declarationId(), owner.fieldPath(), key);
    }
}

package quiz.curation;

import objectview.Viewable;
import quiz.source.ExternalSource;
import quiz.source.WikidataSource;

import java.util.Collection;
import java.util.List;

/** Materializes durable identity links as the ordinary @Provenance source field. */
public final class IdentitySources {
    private IdentitySources() { }

    public static int apply(Collection<? extends Viewable> instances,
                            Collection<IdentityLink> links) {
        if (instances == null || links == null) return 0;
        int count = 0;
        for (IdentityLink link : links) {
            Viewable target = instances.stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(instance -> java.util.Objects.equals(
                            instance.typeName(), link.type()))
                    .filter(instance -> java.util.Objects.equals(
                            instance.getIdentifier(), link.targetId()))
                    .findFirst().orElse(null);
            if (target != null) {
                apply(target, link);
                count++;
            }
        }
        return count;
    }

    public static void apply(Viewable target, IdentityLink link) {
        if (target == null || link == null) return;
        target.source("Wikidata".equalsIgnoreCase(link.sourceKind())
                ? new WikidataSource(
                        link.sourceId(), link.recordUrl(), link.canonicalName())
                : new ExternalSource(
                        link.sourceKind(), link.sourceId(), link.recordUrl(),
                        link.canonicalName()));
    }

    public static void refresh(Viewable target, ManualCuration curation) {
        if (target == null) return;
        IdentityLink link = curation == null ? null : curation.identityLinks().stream()
                .filter(candidate -> java.util.Objects.equals(
                        candidate.type(), target.typeName()))
                .filter(candidate -> java.util.Objects.equals(
                        candidate.targetId(), target.getIdentifier()))
                .findFirst().orElse(null);
        if (link != null) {
            apply(target, link);
        } else if (target.getIdentifier() == null
                || !target.getIdentifier().matches("Q\\d+")) {
            target.source(null);
        }
    }
}

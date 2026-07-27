package quiz.enrichment;

import objectview.field.FieldAccess;
import quiz.Quizable;
import quiz.curation.IdentityLink;
import quiz.curation.ManualCuration;
import quiz.source.Source;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Collects embedded and manually approved sources without exposing their storage. */
public final class EnrichmentSources {

    private EnrichmentSources() { }

    public static List<EnrichmentProposal.SourceRef> collect(
            Quizable member, String type, ManualCuration curation) {
        Map<String, EnrichmentProposal.SourceRef> result = new LinkedHashMap<>();
        Object value = FieldAccess.getPath(member, "source");
        if (value instanceof Source source) {
            add(result, source.kind(), source.sourceId(), source.url());
        } else if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item instanceof Source source) {
                    add(result, source.kind(), source.sourceId(), source.url());
                }
            }
        }

        if (curation != null) {
            String targetId = member.getIdentifier();
            for (IdentityLink link : curation.identityLinks()) {
                if (java.util.Objects.equals(type, link.type())
                        && java.util.Objects.equals(targetId, link.targetId())) {
                    add(result, link.sourceKind(), link.sourceId(), link.recordUrl());
                }
            }
        }
        return new ArrayList<>(result.values());
    }

    private static void add(Map<String, EnrichmentProposal.SourceRef> result,
                            String kind, String sourceId, String url) {
        EnrichmentProposal.SourceRef source =
                new EnrichmentProposal.SourceRef(kind, sourceId, url);
        result.put(String.valueOf(kind) + '\u0000' + sourceId + '\u0000' + url, source);
    }
}

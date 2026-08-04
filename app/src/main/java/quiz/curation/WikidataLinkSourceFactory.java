package quiz.curation;

import objectview.Viewable;
import quiz.source.SourceFactory;
import quiz.source.WikidataSource;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * The manual-link {@link SourceFactory} strategy: identifies an instance from an
 * approved curation {@link IdentityLink}, producing the exact {@link WikidataSource}
 * the link names — or nothing when no Wikidata link matches.
 *
 * <p>This is the "identify" half of the datasource construct for curated
 * identities: {@link IdentitySources} orchestrates it (identify → resolve → set
 * the anchor). A non-Wikidata cross-reference produces no candidate, so it is
 * simply not identified rather than mis-anchored.</p>
 */
public final class WikidataLinkSourceFactory implements SourceFactory<WikidataSource> {

    private final Collection<IdentityLink> links;

    public WikidataLinkSourceFactory(Collection<IdentityLink> links) {
        this.links = links == null ? List.of() : links;
    }

    @Override public List<WikidataSource> identify(Viewable instance) {
        if (instance == null) {
            return List.of();
        }
        return links.stream()
                .filter(link -> "Wikidata".equalsIgnoreCase(link.sourceKind()))
                .filter(link -> Objects.equals(link.type(), instance.typeName()))
                .filter(link -> Objects.equals(link.targetId(), instance.getIdentifier()))
                .map(link -> new WikidataSource(link.sourceId(), link.canonicalName()))
                .toList();
    }
}

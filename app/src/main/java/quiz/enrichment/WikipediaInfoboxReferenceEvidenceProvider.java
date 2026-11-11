package quiz.enrichment;

import datasource.EntityRef;
import datasource.SourceRef;
import datasource.enrichment.EnrichmentProposal;
import datasource.evidence.EvidenceFragment;
import datasource.evidence.ExtractedClaim;
import datasource.evidence.InfoboxParameters;
import datasource.evidence.WikipediaFragment;
import objectview.Viewable;
import objectview.field.FieldKind;
import work.Query;
import work.QueryContext;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Interprets links already retained in one rendered infobox row as reference candidates.
 * The configured target class supplies the closed candidate universe: links which do not
 * resolve to one of those loaded instances are reported by the query summary and ignored.
 */
public final class WikipediaInfoboxReferenceEvidenceProvider
        implements EnrichmentProvider {
    static final String RECIPE_VERSION = "rendered-infobox-links-v1";

    private final InfoboxParameters.Key key;
    private final WikipediaLinkedReferenceResolver candidates;
    private final WikimediaEntityLookup lookup;

    public WikipediaInfoboxReferenceEvidenceProvider(String configuredKey,
            Collection<? extends Viewable> loadedCandidates) {
        this(configuredKey, loadedCandidates, WikimediaEntityLookup.defaultFetcher());
    }

    WikipediaInfoboxReferenceEvidenceProvider(String configuredKey,
            Collection<? extends Viewable> loadedCandidates,
            WikimediaEntityLookup.JsonFetcher fetcher) {
        key = InfoboxParameters.Key.parse(configuredKey);
        candidates = new WikipediaLinkedReferenceResolver(loadedCandidates);
        lookup = new WikimediaEntityLookup(fetcher);
    }

    @Override public String name() { return "Wikipedia infobox link evidence"; }

    @Override public boolean supports(EnrichmentRequest request) {
        return request != null && request.subject() != null
                && request.targetField() != null && !request.targetField().isBlank()
                && request.targetSchema() != null
                && request.targetSchema().collection()
                && request.targetSchema().valueKind() == FieldKind.REFERENCE
                && key != null && key.rendered()
                && fragment(request.infoboxParameters()) != null;
    }

    @Override public Query<EnrichmentProposal> discover(EnrichmentRequest request) {
        if (!supports(request)) {
            throw new IllegalArgumentException("Rendered infobox link evidence requires a "
                    + "retained rendered row and a reference-list target field");
        }
        return new Query<>() {
            private int linked;
            private int resolvedLinks;
            private int resolvedTargets;

            @Override public String purpose() {
                return "Resolve retained infobox links to loaded instances";
            }

            @Override public String skeleton() {
                return "Read links retained in " + key
                        + "; admit only loaded instances of the configured target class";
            }

            @Override public String queryType() { return "Retained Wikipedia evidence"; }

            @Override public String description() {
                return "Propose typed references for explicit review; create no entities";
            }

            @Override public Map<String, String> parameters() {
                return Map.of("field", request.targetField(), "infoboxRow", key.toString());
            }

            @Override public EnrichmentProposal execute(QueryContext context) throws Exception {
                WikipediaFragment row = fragment(request.infoboxParameters());
                linked = row == null ? 0 : row.links().size();
                WikipediaLinkedReferenceResolver.Resolution resolution =
                        candidates.resolveDetailed(
                                WikipediaLinkedReferenceResolver.fragmentLinks(row.links()),
                                lookup, context);
                List<WikipediaLinkedReferenceResolver.Match> matches =
                        resolution.matches();
                Set<String> current = currentIds(request.currentValue());
                List<EnrichmentProposal.FieldCandidate> fields = matches.stream()
                        .filter(match -> !current.contains(match.candidate().getIdentifier()))
                        .map(match -> candidate(request, row, match))
                        .toList();
                resolvedLinks = resolution.resolvedLinks();
                resolvedTargets = matches.size();
                return new EnrichmentProposal(request.subject(), List.of(), fields, List.of());
            }

            @Override public int rowCount(EnrichmentProposal result) {
                return result == null ? 0 : result.fields().size();
            }

            @Override public String summary(EnrichmentProposal result) {
                int proposed = rowCount(result);
                return linked + " retained link(s), " + resolvedLinks
                        + " resolved to " + resolvedTargets + " loaded target(s), "
                        + proposed + " proposed, "
                        + Math.max(0, linked - resolvedLinks) + " unresolved";
            }
        };
    }

    private EnrichmentProposal.FieldCandidate candidate(EnrichmentRequest request,
            WikipediaFragment row, WikipediaLinkedReferenceResolver.Match match) {
        Viewable value = match.candidate();
        String semantic = "field:" + request.targetField();
        InfoboxParameters parameters = request.infoboxParameters();
        String excerpt = row.text().isBlank()
                ? row.links().stream().map(link -> link.text().isBlank()
                        ? link.targetTitle() : link.text()).filter(text -> !text.isBlank())
                        .reduce((left, right) -> left + "; " + right).orElse(key.parameter())
                : row.text();
        SourceRef source = new SourceRef(parameters.document().kind(),
                parameters.document().sourceId(), parameters.document().url(), semantic,
                key.toString(), "linked target");
        ExtractedClaim claim = new ExtractedClaim(subjectRef(request), semantic, null,
                EntityRef.wikidata(value.getIdentifier()),
                List.of(EvidenceFragment.excerpt(parameters.document(),
                        "Infobox — " + key.parameter(), excerpt)),
                "linked target in rendered infobox row", RECIPE_VERSION, "", 0.92,
                List.of("The link identifies the entity; review decides whether the "
                        + "infobox row expresses this field role"));
        return new EnrichmentProposal.FieldCandidate(
                "infobox-link:" + request.subject().targetId() + ':'
                        + request.targetField() + ':' + value.getIdentifier(),
                "", request.targetField(), request.currentValue(), value, source,
                EnrichmentProposal.ReviewAction.ADD_TO_COLLECTION,
                null, true, List.of(claim));
    }

    private WikipediaFragment fragment(InfoboxParameters parameters) {
        if (parameters == null || key == null) return null;
        return parameters.structuredValues().entrySet().stream()
                .filter(entry -> entry.getKey().toString().equalsIgnoreCase(key.toString()))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    private static Set<String> currentIds(Object currentValue) {
        Collection<?> values = currentValue instanceof Collection<?> collection
                ? collection : currentValue == null ? List.of() : List.of(currentValue);
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (Object value : values) {
            if (value instanceof Viewable viewable && viewable.getIdentifier() != null) {
                ids.add(viewable.getIdentifier());
            } else if (value instanceof String id) {
                ids.add(id);
            }
        }
        return Set.copyOf(ids);
    }

    private static EntityRef subjectRef(EnrichmentRequest request) {
        String id = request.subject().targetId() == null
                ? "" : request.subject().targetId().trim();
        return wikidata.WikidataIds.isQid(id) ? EntityRef.wikidata(id)
                : new EntityRef("domain:"
                        + request.subject().type().toLowerCase(Locale.ROOT), id);
    }
}

package domain.enrichment;

import domain.config.TextSource;
import domain.datasource.EntityRef;
import domain.datasource.SourceRef;
import domain.datasource.evidence.EvidenceFragment;
import domain.datasource.evidence.ExtractedClaim;
import domain.datasource.evidence.TextPassages;
import domain.datasource.evidence.WikipediaArticle;
import domain.datasource.wikipedia.WikipediaDatasourceProvider;
import objectview.Viewable;
import objectview.field.FieldKind;
import wikidata.WikidataIds;
import wikipedia.WikipediaArticleClient;
import work.Query;
import work.QueryContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Proposes one target-class instance per passage of a source instance's text sources: the
 * recipe's text sources whose work is that instance.
 *
 * <p>Assembly only, over two separate constructs: {@link TextPassages} divides each page at
 * its authored divisions, and {@link LoadedNameMatches} proposes the loaded instances a
 * passage names uniquely. Every proposed instance is its own review decision and is never
 * bulk-accepted; a name several loaded instances share is a review warning, never a
 * reference (directive 9).</p>
 */
public final class TextPassageInstanceEvidenceProvider implements EnrichmentProvider {
    static final String RECIPE_VERSION = "text-passage-instances-v1";

    private final WikipediaDatasourceProvider.TextPassageInstanceSpec spec;
    private final List<TextSource> texts;
    private final List<String> missingTextSourceIds;
    private final LoadedNameMatches names;
    private final String namedType;
    private final WikipediaArticleClient pages;
    private final domain.DomainModel domain;

    /** {@code texts} are the text sources declared in or imported by the project. */
    public TextPassageInstanceEvidenceProvider(WikipediaDatasourceProvider.TextPassageInstanceSpec spec,
            List<TextSource> texts, Collection<? extends Viewable> namedCandidates, String namedType,
            domain.DomainModel domain) {
        this(spec, texts, namedCandidates, namedType, domain, new WikipediaArticleClient());
    }

    TextPassageInstanceEvidenceProvider(WikipediaDatasourceProvider.TextPassageInstanceSpec spec,
            List<TextSource> texts, Collection<? extends Viewable> namedCandidates, String namedType,
            domain.DomainModel domain, WikipediaArticleClient pages) {
        this.spec = java.util.Objects.requireNonNull(spec);
        this.texts = texts == null ? List.of() : texts.stream()
                .filter(text -> text != null && spec.textSources().contains(text.declarationId()))
                .toList();
        Set<String> missing = new LinkedHashSet<>(spec.textSources());
        this.texts.forEach(text -> missing.remove(text.declarationId()));
        this.missingTextSourceIds = List.copyOf(missing);
        this.names = LoadedNameMatches.of(namedCandidates, LoadedNameMatches::labelAndAliases);
        this.namedType = namedType == null ? "" : namedType.trim();
        this.domain = java.util.Objects.requireNonNull(domain);
        this.pages = java.util.Objects.requireNonNull(pages);
    }

    @Override public String name() { return "Text passage instances"; }

    @Override public boolean supports(EnrichmentRequest request) {
        return request != null && request.subject() != null
                && WikidataIds.isQid(request.subject().id())
                && request.targetSchema() != null && request.targetSchema().collection()
                && request.targetSchema().valueKind() == FieldKind.REFERENCE
                && spec.configured();
    }

    @Override public Query<EnrichmentProposal> discover(EnrichmentRequest request) {
        if (!supports(request)) {
            throw new IllegalArgumentException("Text passage instances require configured text "
                    + "sources and a reference-list target field");
        }
        List<TextSource> sources = texts.stream()
                .filter(text -> text.workQid().equalsIgnoreCase(request.subject().id())).toList();
        List<String> titles = sources.stream().flatMap(text -> text.pages().stream()).toList();
        return new Query<>() {
            private int passages;
            private int named;
            private int ambiguousNames;

            @Override public String purpose() { return "Propose one instance per text passage"; }
            @Override public String skeleton() {
                return "Read " + titles.size() + " page(s) of " + sources.size()
                        + " text source(s); divide each at its authored divisions; propose an "
                        + "instance per passage and the loaded instances it names";
            }
            @Override public String queryType() { return "Retained source text"; }
            @Override public String description() {
                return "Text passages of " + request.subject().displayName();
            }
            @Override public Map<String, String> parameters() {
                return Map.of("field", request.targetField(), "pages", String.join(" | ", titles),
                        "text sources", String.join(" | ",
                                sources.stream().map(TextSource::name).toList()));
            }

            @Override public EnrichmentProposal execute(QueryContext context) throws Exception {
                if (!missingTextSourceIds.isEmpty()) {
                    throw new IllegalStateException("Text sources named by "
                            + request.subject().type() + "." + request.targetField()
                            + " are not declared or imported by this project: "
                            + String.join(", ", missingTextSourceIds));
                }
                String targetType = request.targetSchema().targetType() == null
                        ? "" : request.targetSchema().targetType().trim();
                Set<String> current = FieldValueCompatibility.compatibleReferenceIds(
                        domain, request.targetSchema(), request.currentValue());
                List<EnrichmentProposal.InstanceCandidate> instances = new ArrayList<>();
                List<EnrichmentProposal.FieldCandidate> fields = new ArrayList<>();
                for (TextSource text : sources) for (String title : text.pages()) {
                    context.cancellation().throwIfCancelled();
                    var page = pages.structuredByTitle(text.site(), title).execute(context);
                    WikipediaArticle article = page == null ? null : page.article().structuredArticle();
                    if (article == null) continue;
                    for (TextPassages.Passage passage : TextPassages.divide(article, text.division())) {
                        passages++;
                        String instanceId = instanceId(request, text.site(), title, passage);
                        if (current.contains(instanceId)) continue;
                        String label = label(title, passage);
                        SourceRef source = new SourceRef(article.document().kind(),
                                article.document().sourceId(), article.document().url(),
                                "field:" + request.targetField(), title + " — " + passage.label(),
                                "text passage");
                        EvidenceFragment fragment = EvidenceFragment.positioned(article.document(),
                                passage.label(), passage.start(), passage.text());
                        var matches = names.find(passage.text());
                        List<String> warnings = new ArrayList<>();
                        warnings.add("The page's own division bounds this passage; review decides "
                                + "whether it is one " + targetType);
                        for (var ambiguity : matches.ambiguous()) {
                            ambiguousNames++;
                            warnings.add("\"" + ambiguity.phrase() + "\" names "
                                    + ambiguity.candidates().size() + " loaded instances ("
                                    + String.join(", ", ambiguity.candidates().stream()
                                            .map(candidate -> candidate.getDisplayName()
                                                    + " " + candidate.getIdentifier()).toList())
                                    + "); none is proposed for it");
                        }
                        ExtractedClaim content = new ExtractedClaim(subjectRef(request),
                                "field:" + request.targetField(), passage.text(), null,
                                List.of(fragment), "authored text passage proposed as an instance",
                                RECIPE_VERSION, "", 0.5, warnings);
                        List<EnrichmentProposal.InstanceField> values = new ArrayList<>();
                        values.add(EnrichmentProposal.InstanceField.literal(spec.textField(), passage.text()));
                        values.add(EnrichmentProposal.InstanceField.references(spec.sourceField(),
                                List.of(new EnrichmentProposal.TypedReference(
                                        request.subject().type(), request.subject().targetId()))));
                        if (!spec.titleField().isBlank()) {
                            values.add(EnrichmentProposal.InstanceField.literal(spec.titleField(), label));
                        }
                        if (!spec.partField().isBlank()) {
                            values.add(EnrichmentProposal.InstanceField.literal(
                                    spec.partField(), part(title)));
                        }
                        if (!spec.referencesField().isBlank() && !namedType.isBlank()
                                && !matches.hits().isEmpty()) {
                            values.add(EnrichmentProposal.InstanceField.references(spec.referencesField(),
                                    matches.hits().stream().map(hit -> new EnrichmentProposal.TypedReference(
                                            namedType, hit.candidate().getIdentifier())).toList()));
                            named += matches.hits().size();
                        }
                        instances.add(new EnrichmentProposal.InstanceCandidate(
                                "text-passage-instance:" + instanceId, targetType, instanceId, label,
                                values, source, List.of(content), false));
                        var proposed = new domain.transform.DynamicViewable(instanceId, label);
                        proposed.type(targetType);
                        fields.add(new EnrichmentProposal.FieldCandidate(
                                "text-passage-reference:" + instanceId, "", request.targetField(),
                                request.currentValue(), proposed, source,
                                EnrichmentProposal.ReviewAction.ADD_TO_COLLECTION, null, true,
                                List.of(new ExtractedClaim(subjectRef(request),
                                        "field:" + request.targetField(), null,
                                        new EntityRef("text-passage", instanceId), List.of(fragment),
                                        "reviewed text passage instance", RECIPE_VERSION, "", 0.5,
                                        List.of("Accepting the passage adds it to "
                                                + request.targetField())))));
                    }
                }
                return new EnrichmentProposal(request.subject(), List.of(), fields, List.of(), instances);
            }

            @Override public int rowCount(EnrichmentProposal result) {
                return result == null ? 0 : result.logicalChangeCount();
            }

            @Override public String summary(EnrichmentProposal result) {
                return passages + " passage(s) in " + titles.size() + " page(s); "
                        + rowCount(result) + " proposed; " + named + " named instance(s) attached"
                        + (ambiguousNames == 0 ? "" : "; " + ambiguousNames + " ambiguous name(s) left to review");
            }
        };
    }

    /** A passage is its owner, field, site, page and authored division label, so an edit to its
     *  wording keeps the accepted instance instead of proposing a second one. */
    private static String instanceId(EnrichmentRequest request, WikipediaArticleClient.Site site,
            String page, TextPassages.Passage passage) {
        String material = request.subject().type() + '\0' + request.subject().targetId() + '\0'
                + request.targetField() + '\0' + site.host() + '\0' + page + '\0' + passage.label();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return "text-passage:" + HexFormat.of().formatHex(digest).substring(0, 24);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** The part of the work a page holds: its last title segment ("…/Gylfaginning"). */
    static String part(String page) {
        return page.contains("/") ? page.substring(page.lastIndexOf('/') + 1) : page;
    }

    /** The page's part, the division label and the passage's first words. */
    static String label(String page, TextPassages.Passage passage) {
        String part = part(page);
        String text = passage.text().replaceFirst("^" + java.util.regex.Pattern.quote(passage.label())
                + "\\.\\s*", "").replace('\n', ' ').trim();
        int sentence = text.indexOf(". ");
        if (sentence > 0) text = text.substring(0, sentence + 1);
        if (text.length() > 90) text = text.substring(0, 87).trim() + "…";
        return part + " " + passage.label() + (text.isBlank() ? "" : " — " + text);
    }

    private static EntityRef subjectRef(EnrichmentRequest request) {
        String id = request.subject().targetId() == null ? "" : request.subject().targetId().trim();
        return WikidataIds.isQid(id) ? EntityRef.wikidata(id)
                : new EntityRef("domain:" + request.subject().type().toLowerCase(Locale.ROOT), id);
    }
}

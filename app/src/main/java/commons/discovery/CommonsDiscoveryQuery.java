package commons.discovery;

import batch.BatchCheckpointStore;
import batch.BatchExecutor;
import batch.BatchPolicy;
import batch.BatchProgress;
import batch.WorkDescriptor;
import batch.WorkUnit;
import quiz.enrichment.WikimediaEntityLookup;
import quiz.source.WikidataSource;
import wikidata.WikidataBinding;
import wikidata.WikidataIds;
import wikidata.explore.CommonsMedia;
import wikidata.explore.extract.WikidataDynamicObject;
import work.Query;
import work.QueryContext;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds structured Commons files that explicitly depict the selected Wikidata entities.
 * Each distinct selected QID gets its own capped subquery, combined into one request while
 * that is viable and adaptively split by the shared batch executor when it is not.
 */
public final class CommonsDiscoveryQuery
        implements Query<CommonsDiscoveryQuery.Result> {
    public static final int MAX_SUBJECTS = 20;
    public static final int DEFAULT_MEDIA_PER_SUBJECT = 20;

    private static final String PREFIXES = """
            PREFIX wdt: <http://www.wikidata.org/prop/direct/>
            PREFIX wd: <http://www.wikidata.org/entity/>
            PREFIX schema: <http://schema.org/>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            """;

    @FunctionalInterface
    interface SparqlSource {
        List<WikidataBinding> query(String sparql) throws Exception;
    }

    @FunctionalInterface
    interface LabelSource {
        Map<String, String> labels(QueryContext context, Collection<String> qids)
                throws Exception;
    }

    private final List<WikidataDynamicObject> subjects;
    private final int mediaPerSubject;
    private final SparqlSource commons;
    private final LabelSource labels;

    public CommonsDiscoveryQuery(
            List<WikidataDynamicObject> subjects,
            CommonsStructuredDataClient commons) {
        this(subjects, DEFAULT_MEDIA_PER_SUBJECT, commons::query,
                CommonsDiscoveryQuery::loadLabels);
    }

    CommonsDiscoveryQuery(
            List<WikidataDynamicObject> subjects,
            int mediaPerSubject,
            SparqlSource commons,
            LabelSource labels) {
        this.subjects = selected(subjects);
        if (mediaPerSubject < 1 || mediaPerSubject > 100) {
            throw new IllegalArgumentException(
                    "Commons media per selected entity must be between 1 and 100");
        }
        this.mediaPerSubject = mediaPerSubject;
        this.commons = java.util.Objects.requireNonNull(commons);
        this.labels = java.util.Objects.requireNonNull(labels);
    }

    @Override public String purpose() {
        return "Discover Commons depictions for selected entities";
    }

    @Override public String skeleton() {
        return "exact selected QIDs -> capped Commons depicts (P180) subqueries"
                + " -> structured media facts";
    }

    @Override public String queryType() { return "Commons structured data"; }

    @Override public String description() {
        return "Read only Commons files whose depicts (P180) statement names an exact "
                + "selected QID; do not crawl or modify the project";
    }

    @Override public Map<String, String> parameters() {
        int qids = sourceQids(subjects).size();
        return Map.of("selectedInstances", Integer.toString(subjects.size()),
                "selectedQids", Integer.toString(qids),
                "maximumFilesPerQid", Integer.toString(mediaPerSubject),
                "maximumFilesBeforeDeduplication",
                Integer.toString(qids * mediaPerSubject));
    }

    @Override public Result execute(QueryContext context) throws Exception {
        if (subjects.isEmpty()) return new Result(List.of(), List.of(), 0,
                mediaPerSubject, 0);

        List<WikidataBinding> rows = new ArrayList<>();
        // QID is the source-request identity. Several distinct modeled instances may
        // correspond to it, but Commons needs only one capped subquery for that source.
        List<String> qids = sourceQids(subjects);
        WorkUnit<List<WikidataBinding>> root = unit(context, qids);
        BatchPolicy policy = new BatchPolicy(1, 5, 0L, false, 1);
        new BatchExecutor<List<WikidataBinding>>(
                policy, BatchProgress.NOOP, batch.FailureClassifier.standard(),
                context.cancellation(), BatchCheckpointStore.NONE, 1)
                .run(List.of(root), (descriptor, values) -> rows.addAll(values));

        Set<String> metadataQids = new LinkedHashSet<>();
        for (WikidataBinding row : rows) {
            addQid(metadataQids, row.value("creator"));
            addQid(metadataQids, row.value("medium"));
            addQid(metadataQids, row.value("collection"));
            addQid(metadataQids, row.value("location"));
        }
        Map<String, String> metadataLabels = labels.labels(context, metadataQids);
        Map<String, List<WikidataDynamicObject>> byQid = new LinkedHashMap<>();
        subjects.forEach(subject -> byQid.computeIfAbsent(subject.qid(),
                ignored -> new ArrayList<>()).add(subject));

        LinkedHashMap<String, MutableMedia> found = new LinkedHashMap<>();
        Set<String> nonImages = new LinkedHashSet<>();
        for (WikidataBinding row : rows) {
            String mediaId = entityId(row.value("file"), "M");
            String depictedQid = entityId(row.value("depicted"), "Q");
            String imageUrl = text(row.value("image"));
            if (mediaId.isBlank() || imageUrl.isBlank()
                    || !byQid.containsKey(depictedQid)) continue;
            // A depiction is shown as an image; audio, video and documents also carry
            // depicts statements but would render as a broken image.
            if (!CommonsMedia.isImageFilename(CommonsMedia.fileName(imageUrl))) {
                nonImages.add(mediaId);
                continue;
            }
            MutableMedia media = found.computeIfAbsent(mediaId,
                    ignored -> new MutableMedia(mediaId, imageUrl));
            media.depicted.addAll(byQid.get(depictedQid));
            media.captions.add(text(row.value("caption")));
            media.dates.add(displayDate(row.value("creationDate")));
            media.creators.add(entityId(row.value("creator"), "Q"));
            media.media.add(entityId(row.value("medium"), "Q"));
            media.collections.add(entityId(row.value("collection"), "Q"));
            media.locations.add(entityId(row.value("location"), "Q"));
        }

        List<MediaRepresentation> representations = found.values().stream()
                .map(value -> value.freeze(metadataLabels)).toList();
        Set<WikidataDynamicObject> represented = new LinkedHashSet<>();
        representations.forEach(media -> represented.addAll(media.depictedEntities()));
        List<WikidataDynamicObject> withoutMedia = subjects.stream()
                .filter(value -> !represented.contains(value)).toList();
        return new Result(representations, withoutMedia, subjects.size(), mediaPerSubject,
                nonImages.size());
    }

    @Override public int rowCount(Result result) {
        return result == null ? 0 : result.media().size();
    }

    @Override public String summary(Result result) {
        if (result == null) return "No Commons result.";
        return "Found " + result.media().size() + " distinct Commons file(s) for "
                + (result.selectedSubjects() - result.withoutMedia().size()) + " of "
                + result.selectedSubjects() + " selected entit"
                + (result.selectedSubjects() == 1 ? "y" : "ies") + ", capped at "
                + result.mediaPerSubject() + " files per QID"
                + (result.nonImageFiles() == 0 ? "." : "; " + result.nonImageFiles()
                        + " non-image file(s) within that cap were not shown.");
    }

    String sparqlForTest(List<String> qids) { return sparql(qids); }

    private WorkUnit<List<WikidataBinding>> unit(
            QueryContext context, List<String> qids) {
        List<String> exact = List.copyOf(qids);
        String sparql = sparql(exact);
        return new WorkUnit<>() {
            @Override public WorkDescriptor descriptor() {
                String ids = String.join(",", exact);
                return new WorkDescriptor("commons-depicts", "commons-depicts:" + ids,
                        "Commons depicts for " + exact.size() + " selected QID(s)",
                        Map.of("qids", ids, "limitPerQid",
                                Integer.toString(mediaPerSubject)));
            }

            @Override public String request() { return sparql; }

            @Override public List<WikidataBinding> execute() throws Exception {
                return context.step(title(), queryType(), skeleton(),
                        Map.of("selectedQids", Integer.toString(exact.size()),
                                "maximumFilesPerQid", Integer.toString(mediaPerSubject)),
                        step -> {
                            step.request(sparql);
                            List<WikidataBinding> values = commons.query(sparql);
                            step.summary(values.size() + " structured row(s)");
                            return values;
                        });
            }

            @Override public List<? extends WorkUnit<List<WikidataBinding>>> split() {
                if (exact.size() < 2) return List.of();
                int middle = exact.size() / 2;
                return List.of(unit(context, exact.subList(0, middle)),
                        unit(context, exact.subList(middle, exact.size())));
            }
        };
    }

    private String sparql(List<String> qids) {
        StringBuilder unions = new StringBuilder();
        for (String qid : qids) {
            if (!unions.isEmpty()) unions.append("\n  UNION\n");
            unions.append("  { SELECT (wd:").append(qid)
                    .append(" AS ?depicted) ?file ?image WHERE {\n")
                    .append("      ?file wdt:P180 wd:").append(qid)
                    .append(" ; schema:url ?image .\n")
                    .append("    } ORDER BY ?file LIMIT ").append(mediaPerSubject)
                    .append(" }");
        }
        return PREFIXES + """
                SELECT ?depicted ?file ?image ?caption ?creator ?creationDate
                       ?medium ?collection ?location WHERE {
                """ + unions + """

                  OPTIONAL { ?file rdfs:label ?caption . %s }
                  OPTIONAL { ?file wdt:P170 ?creator }
                  OPTIONAL { ?file wdt:P571 ?creationDate }
                  OPTIONAL { ?file wdt:P186 ?medium }
                  OPTIONAL { ?file wdt:P195 ?collection }
                  OPTIONAL { ?file wdt:P276 ?location }
                }
                ORDER BY ?depicted ?file
                """.formatted(wikidata.query.LabelService.labelFilter("caption", null));
    }

    private static List<WikidataDynamicObject> selected(
            List<WikidataDynamicObject> values) {
        List<WikidataDynamicObject> clean = values == null ? List.of()
                : values.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (clean.size() > MAX_SUBJECTS) {
            throw new IllegalArgumentException("Commons discovery accepts at most "
                    + MAX_SUBJECTS + " selected entities; " + clean.size()
                    + " were selected.");
        }
        for (WikidataDynamicObject value : clean) {
            if (!WikidataIds.isQid(value.qid())) {
                throw new IllegalArgumentException("Commons discovery requires an exact "
                        + "Wikidata QID for every selected entity: "
                        + value.getDisplayName());
            }
        }
        return clean;
    }

    static List<String> sourceQids(
            Collection<? extends WikidataDynamicObject> subjects) {
        return subjects == null ? List.of() : subjects.stream()
                .filter(java.util.Objects::nonNull)
                .map(WikidataDynamicObject::qid)
                .filter(WikidataIds::isQid)
                .distinct().toList();
    }

    static Map<String, String> loadLabels(
            QueryContext context, Collection<String> qids) throws Exception {
        List<String> values = qids == null ? List.of() : qids.stream()
                .filter(WikidataIds::isQid).distinct().toList();
        Map<String, String> result = new LinkedHashMap<>();
        WikimediaEntityLookup lookup = new WikimediaEntityLookup();
        for (int start = 0; start < values.size(); start += 50) {
            List<String> batch = values.subList(start, Math.min(start + 50, values.size()));
            result.putAll(lookup.labels(batch).execute(context));
        }
        return result;
    }

    private static void addQid(Set<String> result, String uri) {
        String qid = entityId(uri, "Q");
        if (!qid.isBlank()) result.add(qid);
    }

    private static String entityId(String uri, String prefix) {
        String value = text(uri);
        int slash = value.lastIndexOf('/');
        String id = slash < 0 ? value : value.substring(slash + 1);
        return id.matches(java.util.regex.Pattern.quote(prefix) + "[1-9][0-9]*")
                ? id : "";
    }

    private static String displayDate(String raw) {
        String value = text(raw);
        return value.matches("[+-]?[0-9]{4,}-[0-9]{2}-[0-9]{2}T00:00:00Z")
                ? value.substring(value.startsWith("+") ? 1 : 0, value.indexOf('T'))
                : value;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static String filePage(String filename) {
        return "https://commons.wikimedia.org/wiki/File:"
                + URLEncoder.encode(filename.replace(' ', '_'), StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private static final class MutableMedia {
        private final String mediaId;
        private final String imageUrl;
        private final Set<WikidataDynamicObject> depicted = new LinkedHashSet<>();
        private final Set<String> captions = new LinkedHashSet<>();
        private final Set<String> creators = new LinkedHashSet<>();
        private final Set<String> dates = new LinkedHashSet<>();
        private final Set<String> media = new LinkedHashSet<>();
        private final Set<String> collections = new LinkedHashSet<>();
        private final Set<String> locations = new LinkedHashSet<>();

        private MutableMedia(String mediaId, String imageUrl) {
            this.mediaId = mediaId;
            this.imageUrl = imageUrl;
        }

        private MediaRepresentation freeze(Map<String, String> labels) {
            String filename = CommonsMedia.fileName(imageUrl);
            String caption = captions.stream().filter(value -> !value.isBlank())
                    .findFirst().orElse(filename);
            return new MediaRepresentation(mediaId, caption, imageUrl,
                    List.copyOf(depicted), filename + "|" + filePage(filename),
                    sources(creators, labels), nonBlank(dates), sources(media, labels),
                    sources(collections, labels), sources(locations, labels));
        }
    }

    private static List<WikidataSource> sources(
            Collection<String> qids, Map<String, String> labels) {
        return qids.stream().filter(WikidataIds::isQid)
                .map(qid -> new WikidataSource(qid,
                        labels.getOrDefault(qid, qid))).toList();
    }

    private static List<String> nonBlank(Collection<String> values) {
        return values.stream().filter(value -> value != null && !value.isBlank()).toList();
    }

    public record Result(
            List<MediaRepresentation> media,
            List<WikidataDynamicObject> withoutMedia,
            int selectedSubjects,
            int mediaPerSubject,
            int nonImageFiles) {
        public Result {
            media = media == null ? List.of() : List.copyOf(media);
            withoutMedia = withoutMedia == null ? List.of() : List.copyOf(withoutMedia);
        }
    }
}

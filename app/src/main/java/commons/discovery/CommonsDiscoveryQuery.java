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
    private static final int SCENE_FILES_PER_BATCH = 25;
    private static final String COMMONS_ENTITY =
            "https://commons.wikimedia.org/entity/";
    /** Depicts qualifiers documented by Commons' depiction-modeling guidance. A bound
     * predicate list is essential here: a variable-predicate scan stalls QLever even
     * for one file. */
    private static final List<String> DEPICTION_QUALIFIER_PIDS = List.of(
            "P1354", // shown with features
            "P462",  // color
            "P3828", // wears
            "P1114", // quantity
            "P1684", // inscription
            "P4878", // symbolizes
            "P2677", // relative position within image
            "P1419", // shape
            "P186",  // made from material
            "P518",  // applies to part
            "P1884", // hair color
            "P2283", // uses
            "P6022", // expression, gesture or body pose
            "P459",  // determination method or standard
            "P5961"  // depicted part
    );

    private static final String PREFIXES = """
            PREFIX wdt: <http://www.wikidata.org/prop/direct/>
            PREFIX wd: <http://www.wikidata.org/entity/>
            PREFIX schema: <http://schema.org/>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            PREFIX p: <http://www.wikidata.org/prop/>
            PREFIX ps: <http://www.wikidata.org/prop/statement/>
            PREFIX pq: <http://www.wikidata.org/prop/qualifier/>
            PREFIX wikibase: <http://wikiba.se/ontology#>
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
                + " -> bounded files -> complete depiction statements and qualifiers";
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
                "sceneFilesPerBatch", Integer.toString(SCENE_FILES_PER_BATCH),
                "maximumFilesBeforeDeduplication",
                Integer.toString(qids * mediaPerSubject));
    }

    @Override public Result execute(QueryContext context) throws Exception {
        if (subjects.isEmpty()) return new Result(List.of(), List.of(), 0,
                mediaPerSubject, 0);

        List<WikidataBinding> matches = new ArrayList<>();
        // QID is the source-request identity. Several distinct modeled instances may
        // correspond to it, but Commons needs only one capped subquery for that source.
        List<String> qids = sourceQids(subjects);
        run(context, List.of(discoveryUnit(context, qids)), 1, matches);

        Map<String, List<WikidataDynamicObject>> byQid = new LinkedHashMap<>();
        subjects.forEach(subject -> byQid.computeIfAbsent(subject.qid(),
                ignored -> new ArrayList<>()).add(subject));

        LinkedHashMap<String, MutableMedia> found = new LinkedHashMap<>();
        Set<String> nonImages = new LinkedHashSet<>();
        for (WikidataBinding row : matches) {
            String mediaId = entityId(row.value("file"), "M");
            String matchedQid = entityId(row.value("matched"), "Q");
            String imageUrl = text(row.value("image"));
            if (mediaId.isBlank() || imageUrl.isBlank()
                    || !byQid.containsKey(matchedQid)) continue;
            // A depiction is shown as an image; audio, video and documents also carry
            // depicts statements but would render as a broken image.
            if (!CommonsMedia.isImageFilename(CommonsMedia.fileName(imageUrl))) {
                nonImages.add(mediaId);
                continue;
            }
            MutableMedia media = found.computeIfAbsent(mediaId,
                    ignored -> new MutableMedia(mediaId, imageUrl));
            media.selected.addAll(byQid.get(matchedQid));
        }

        List<WikidataBinding> details = new ArrayList<>();
        List<WorkUnit<List<WikidataBinding>>> sceneUnits = new ArrayList<>();
        List<String> mediaIds = new ArrayList<>(found.keySet());
        for (int start = 0; start < mediaIds.size(); start += SCENE_FILES_PER_BATCH) {
            sceneUnits.add(sceneUnit(context, mediaIds.subList(start,
                    Math.min(start + SCENE_FILES_PER_BATCH, mediaIds.size()))));
        }
        run(context, sceneUnits, 2, details);

        Set<String> labelIds = new LinkedHashSet<>();
        for (WikidataBinding row : details) {
            addId(labelIds, row.value("creator"));
            addId(labelIds, row.value("medium"));
            addId(labelIds, row.value("collection"));
            addId(labelIds, row.value("location"));
            addId(labelIds, row.value("sceneEntity"));
            addId(labelIds, row.value("qualifierProperty"));
            addId(labelIds, row.value("qualifierValue"));
            addId(labelIds, row.value("digitalRepresentation"));
            addId(labelIds, row.value("mainSubject"));
        }
        Map<String, String> metadataLabels = labels.labels(context, labelIds);
        for (WikidataBinding row : details) {
            String mediaId = entityId(row.value("file"), "M");
            MutableMedia media = found.get(mediaId);
            if (media == null) continue;
            media.captions.add(text(row.value("caption")));
            media.dates.add(displayDate(row.value("creationDate")));
            media.creators.add(entityId(row.value("creator"), "Q"));
            media.media.add(entityId(row.value("medium"), "Q"));
            media.collections.add(entityId(row.value("collection"), "Q"));
            media.locations.add(entityId(row.value("location"), "Q"));
            media.digitalRepresentations.add(
                    entityId(row.value("digitalRepresentation"), "Q"));
            media.mainSubjects.add(entityId(row.value("mainSubject"), "Q"));
            media.addDepiction(row);
        }

        List<MediaRepresentation> representations = found.values().stream()
                .map(value -> value.freeze(metadataLabels)).toList();
        Set<WikidataDynamicObject> represented = new LinkedHashSet<>();
        representations.forEach(media -> represented.addAll(media.selectedEntities()));
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
        long depictions = result.media().stream()
                .mapToLong(value -> value.depictions().size()).sum();
        long qualifiers = result.media().stream().flatMap(value -> value.depictions().stream())
                .mapToLong(value -> value.qualifiers().size()).sum();
        return "Found " + result.media().size() + " distinct Commons file(s) for "
                + (result.selectedSubjects() - result.withoutMedia().size()) + " of "
                + result.selectedSubjects() + " selected entit"
                + (result.selectedSubjects() == 1 ? "y" : "ies") + ", capped at "
                + result.mediaPerSubject() + " files per QID; loaded " + depictions
                + " depiction statement(s) and " + qualifiers + " qualifier value(s)"
                + (result.nonImageFiles() == 0 ? "." : "; " + result.nonImageFiles()
                        + " non-image file(s) within that cap were not shown.");
    }

    String sparqlForTest(List<String> qids) { return discoverySparql(qids); }

    String sceneSparqlForTest(List<String> mediaIds) { return sceneSparql(mediaIds); }

    String metadataSparqlForTest(List<String> mediaIds) { return metadataSparql(mediaIds); }

    private void run(QueryContext context,
            List<? extends WorkUnit<List<WikidataBinding>>> units,
            int parallelism,
            List<WikidataBinding> into) throws Exception {
        if (units.isEmpty()) return;
        BatchPolicy policy = new BatchPolicy(1, 5, 0L, false, 1);
        new BatchExecutor<List<WikidataBinding>>(
                policy, BatchProgress.NOOP, batch.FailureClassifier.standard(),
                context.cancellation(), BatchCheckpointStore.NONE, parallelism)
                .run(units, (descriptor, values) -> into.addAll(values));
    }

    private WorkUnit<List<WikidataBinding>> discoveryUnit(
            QueryContext context, List<String> qids) {
        List<String> exact = List.copyOf(qids);
        String sparql = discoverySparql(exact);
        return new WorkUnit<>() {
            @Override public WorkDescriptor descriptor() {
                String ids = String.join(",", exact);
                return new WorkDescriptor("commons-files", "commons-files:" + ids,
                        "Find Commons files for " + exact.size() + " selected QID(s)",
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
                            step.summary(values.size() + " matched file row(s)");
                            return values;
                        });
            }

            @Override public List<? extends WorkUnit<List<WikidataBinding>>> split() {
                if (exact.size() < 2) return List.of();
                int middle = exact.size() / 2;
                return List.of(discoveryUnit(context, exact.subList(0, middle)),
                        discoveryUnit(context, exact.subList(middle, exact.size())));
            }
        };
    }

    private String discoverySparql(List<String> qids) {
        StringBuilder unions = new StringBuilder();
        for (String qid : qids) {
            if (!unions.isEmpty()) unions.append("\n  UNION\n");
            unions.append("  { SELECT (wd:").append(qid)
                    .append(" AS ?matched) ?file ?image WHERE {\n")
                    .append("      ?file wdt:P180 wd:").append(qid)
                    .append(" ; schema:url ?image .\n")
                    .append("    } ORDER BY ?file LIMIT ").append(mediaPerSubject)
                    .append(" }");
        }
        return PREFIXES + """
                SELECT ?matched ?file ?image WHERE {
                """ + unions + """
                }
                ORDER BY ?matched ?file
                """;
    }

    private WorkUnit<List<WikidataBinding>> sceneUnit(
            QueryContext context, List<String> mediaIds) {
        List<String> exact = List.copyOf(mediaIds);
        String sparql = sceneSparql(exact);
        String metadata = metadataSparql(exact);
        return new WorkUnit<>() {
            @Override public WorkDescriptor descriptor() {
                String ids = String.join(",", exact);
                return new WorkDescriptor("commons-scenes", "commons-scenes:" + ids,
                        "Load depiction scenes for " + exact.size() + " Commons file(s)",
                        Map.of("mediaIds", ids));
            }

            @Override public String request() { return sparql; }

            /** Metadata and scene are separate requests: in one query every metadata
             *  value multiplied every depiction/qualifier row of the same file. */
            @Override public List<WikidataBinding> execute() throws Exception {
                List<WikidataBinding> values = new ArrayList<>(context.step(
                        "Read file metadata", queryType(),
                        "exact Commons files -> caption, creator, date, medium, collection, "
                                + "location, digital representation, main subject",
                        Map.of("files", Integer.toString(exact.size())), step -> {
                            step.request(metadata);
                            List<WikidataBinding> rows = commons.query(metadata);
                            step.summary(rows.size() + " file-metadata row(s)");
                            return rows;
                        }));
                values.addAll(context.step(title(), queryType(),
                        "exact Commons files -> all non-deprecated depicts statements, "
                                + "ranks and supported qualifiers",
                        Map.of("files", Integer.toString(exact.size())), step -> {
                            step.request(sparql);
                            List<WikidataBinding> rows = commons.query(sparql);
                            step.summary(rows.size() + " depiction-scene row(s)");
                            return rows;
                        }));
                return values;
            }

            @Override public List<? extends WorkUnit<List<WikidataBinding>>> split() {
                if (exact.size() < 2) return List.of();
                int middle = exact.size() / 2;
                return List.of(sceneUnit(context, exact.subList(0, middle)),
                        sceneUnit(context, exact.subList(middle, exact.size())));
            }
        };
    }

    private static String fileValues(List<String> mediaIds) {
        return mediaIds.stream().map(id -> "<" + COMMONS_ENTITY + id + ">")
                .collect(java.util.stream.Collectors.joining(" "));
    }

    private String metadataSparql(List<String> mediaIds) {
        return PREFIXES + """
                SELECT ?file ?caption ?creator ?creationDate ?medium ?collection ?location
                       ?digitalRepresentation ?mainSubject WHERE {
                  VALUES ?file { %s }
                  OPTIONAL { ?file rdfs:label ?caption . %s }
                  OPTIONAL { ?file wdt:P170 ?creator }
                  OPTIONAL { ?file wdt:P571 ?creationDate }
                  OPTIONAL { ?file wdt:P186 ?medium }
                  OPTIONAL { ?file wdt:P195 ?collection }
                  OPTIONAL { ?file wdt:P276 ?location }
                  OPTIONAL { ?file wdt:P6243 ?digitalRepresentation }
                  OPTIONAL { ?file wdt:P921 ?mainSubject }
                }
                ORDER BY ?file
                """.formatted(fileValues(mediaIds),
                wikidata.query.LabelService.labelFilter("caption", null));
    }

    private String sceneSparql(List<String> mediaIds) {
        String qualifierPatterns = DEPICTION_QUALIFIER_PIDS.stream()
                .map(pid -> "{ ?depictionStatement pq:" + pid
                        + " ?qualifierValue . BIND(wd:" + pid
                        + " AS ?qualifierProperty) }")
                .collect(java.util.stream.Collectors.joining("\n                    UNION "));
        return PREFIXES + """
                SELECT ?file ?depictionStatement ?sceneEntity ?depictionRank
                       ?qualifierProperty ?qualifierValue WHERE {
                  VALUES ?file { %s }
                  ?file p:P180 ?depictionStatement .
                  ?depictionStatement ps:P180 ?sceneEntity ;
                                      wikibase:rank ?depictionRank .
                  FILTER(?depictionRank != wikibase:DeprecatedRank)
                  OPTIONAL {
                    %s
                  }
                }
                ORDER BY ?file ?depictionStatement ?qualifierProperty ?qualifierValue
                """.formatted(fileValues(mediaIds), qualifierPatterns);
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
            QueryContext context, Collection<String> ids) throws Exception {
        List<String> values = ids == null ? List.of() : ids.stream()
                .filter(WikidataIds::isId).distinct().toList();
        Map<String, String> result = new LinkedHashMap<>();
        WikimediaEntityLookup lookup = new WikimediaEntityLookup();
        for (int start = 0; start < values.size(); start += 50) {
            List<String> batch = values.subList(start, Math.min(start + 50, values.size()));
            result.putAll(lookup.labels(batch).execute(context));
        }
        return result;
    }

    private static void addId(Set<String> result, String uri) {
        String value = text(uri);
        int slash = value.lastIndexOf('/');
        String id = slash < 0 ? value : value.substring(slash + 1);
        if (WikidataIds.isId(id)) result.add(id);
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
        private final Set<WikidataDynamicObject> selected = new LinkedHashSet<>();
        private final Set<String> captions = new LinkedHashSet<>();
        private final Set<String> creators = new LinkedHashSet<>();
        private final Set<String> dates = new LinkedHashSet<>();
        private final Set<String> media = new LinkedHashSet<>();
        private final Set<String> collections = new LinkedHashSet<>();
        private final Set<String> locations = new LinkedHashSet<>();
        private final Set<String> digitalRepresentations = new LinkedHashSet<>();
        private final Set<String> mainSubjects = new LinkedHashSet<>();
        private final Map<String, MutableDepiction> depictions = new LinkedHashMap<>();

        private MutableMedia(String mediaId, String imageUrl) {
            this.mediaId = mediaId;
            this.imageUrl = imageUrl;
        }

        private void addDepiction(WikidataBinding row) {
            String statementId = text(row.value("depictionStatement"));
            String qid = entityId(row.value("sceneEntity"), "Q");
            if (statementId.isBlank() || qid.isBlank()) return;
            MutableDepiction depiction = depictions.computeIfAbsent(statementId,
                    ignored -> new MutableDepiction(statementId, qid));
            depiction.prominent |= text(row.value("depictionRank"))
                    .endsWith("PreferredRank");
            String pid = entityId(row.value("qualifierProperty"), "P");
            String value = text(row.value("qualifierValue"));
            if (!pid.isBlank() && !value.isBlank()) {
                depiction.qualifiers.putIfAbsent(pid + "\u001f" + value,
                        new MutableQualifier(pid, value));
            }
        }

        private MediaRepresentation freeze(Map<String, String> labels) {
            String filename = CommonsMedia.fileName(imageUrl);
            String caption = captions.stream().filter(value -> !value.isBlank())
                    .findFirst().orElse(filename);
            return new MediaRepresentation(mediaId, caption, imageUrl,
                    List.copyOf(selected), depictions.values().stream()
                    .map(value -> value.freeze(labels)).toList(),
                    filename + "|" + filePage(filename),
                    sources(creators, labels), nonBlank(dates), sources(media, labels),
                    sources(collections, labels), sources(locations, labels),
                    sources(digitalRepresentations, labels), sources(mainSubjects, labels));
        }
    }

    private static final class MutableDepiction {
        private final String statementId;
        private final String qid;
        private boolean prominent;
        private final Map<String, MutableQualifier> qualifiers = new LinkedHashMap<>();

        private MutableDepiction(String statementId, String qid) {
            this.statementId = statementId;
            this.qid = qid;
        }

        private DepictionEvidence freeze(Map<String, String> labels) {
            return new DepictionEvidence(statementId, qid,
                    labels.getOrDefault(qid, qid), prominent,
                    qualifiers.values().stream()
                            .map(value -> value.freeze(statementId, labels)).toList());
        }
    }

    private record MutableQualifier(String pid, String value) {
        private DepictionQualifier freeze(String statementId, Map<String, String> labels) {
            String valueQid = entityId(value, "Q");
            String shownValue = valueQid.isBlank() ? displayDate(value)
                    : labels.getOrDefault(valueQid, valueQid);
            return new DepictionQualifier(statementId, pid,
                    labels.getOrDefault(pid, pid), value, valueQid, shownValue);
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

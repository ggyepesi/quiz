package quiz.curation;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Manual curation persisted to a sidecar JSON beside the snapshot
 * ({@code <name>.curation.json}), so user-entered values re-apply automatically
 * whenever the base data is regenerated. Keyed by {@code qid} + field; a second
 * value for the same field replaces the first.
 */
public final class ManualCuration implements CorrectionSource {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final File file;
    private final List<Correction> entries = new ArrayList<>();
    private final List<Merge> merges = new ArrayList<>();
    private final List<IdentityLink> identityLinks = new ArrayList<>();

    public ManualCuration(File file) {
        this.file = file;
    }

    /** The curation sidecar for a snapshot file: {@code <name>.curation.json} beside
     *  it, loaded (empty when the file doesn't exist yet). */
    public static ManualCuration forSnapshot(File snapshot) {
        String base = snapshot.getName()
                .replaceFirst("\\.snapshot\\.json$", "")
                .replaceFirst("\\.json$", "");
        ManualCuration c = new ManualCuration(new File(snapshot.getParentFile(), base + ".curation.json"));
        c.load();
        return c;
    }

    public ManualCuration load() {
        entries.clear();
        merges.clear();
        identityLinks.clear();
        if (file != null && file.isFile()) {
            try {
                Doc doc = MAPPER.readValue(file, Doc.class);
                if (doc.corrections != null) {
                    for (Entry e : doc.corrections) {
                        entries.add(new Correction(
                                e.type, e.qid, e.field, e.value, e.origin, e.valueKind));
                    }
                }
                if (doc.merges != null) {
                    for (MergeEntry e : doc.merges) {
                        merges.add(new Merge(e.type, e.primary, e.duplicate,
                                e.fieldSource == null ? java.util.Map.of() : e.fieldSource,
                                e.origin));
                    }
                }
                if (doc.identityLinks != null) {
                    for (IdentityEntry e : doc.identityLinks) {
                        identityLinks.add(new IdentityLink(
                                e.type, e.targetId, e.sourceKind, e.sourceId,
                                e.recordUrl, e.canonicalName, e.origin));
                    }
                }
            } catch (IOException ignored) {
                // A missing/unreadable curation file is simply no curation.
            }
        }
        return this;
    }

    public void save() throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        Doc doc = new Doc();
        for (Correction c : entries) {
            doc.corrections.add(new Entry(c));
        }
        for (Merge m : merges) {
            doc.merges.add(new MergeEntry(m));
        }
        for (IdentityLink link : identityLinks) {
            doc.identityLinks.add(new IdentityEntry(link));
        }
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(file, doc);
    }

    /** Set (or replace) a manually curated value for one field of one instance. */
    public void put(String qid, String field, Object value) {
        put(qid, field, value, Correction.MANUAL);
    }

    /** As {@link #put(String, String, Object)} but with an explicit provenance — e.g.
     *  {@code "dbpedia"} for an accepted enrichment (fill-only precedence, so it never
     *  overrides real base data), vs {@link Correction#MANUAL} which overrides. */
    public void put(String qid, String field, Object value, String origin) {
        remove(qid, field);
        entries.add(new Correction(qid, field, value, origin));
    }

    /** Store a correction qualified by its owning domain type and declared value shape. */
    public void put(String type, String qid, String field, Object value,
                    String origin, String valueKind) {
        entries.removeIf(c -> (c.type() == null || java.util.Objects.equals(c.type(), type))
                && c.qid().equals(qid) && c.field().equals(field));
        entries.add(new Correction(type, qid, field, value, origin, valueKind));
    }

    public void remove(String qid, String field) {
        entries.removeIf(c -> c.qid().equals(qid) && c.field().equals(field));
    }

    public void remove(String type, String qid, String field) {
        entries.removeIf(c -> java.util.Objects.equals(c.type(), type)
                && c.qid().equals(qid) && c.field().equals(field));
    }

    /** Restore an exact entry, used to roll back a failed sidecar save. */
    public void restore(Correction correction) {
        remove(correction.type(), correction.qid(), correction.field());
        entries.add(correction);
    }

    /** Record (or replace) a manual merge of {@code duplicate} into {@code primary} with
     *  the approved per-field resolution; one merge per duplicate. */
    public void putMerge(String type, String primary, String duplicate,
                         java.util.Map<String, String> fieldSource) {
        merges.removeIf(m -> java.util.Objects.equals(m.type(), type)
                && m.duplicate().equals(duplicate));
        merges.add(new Merge(type, primary, duplicate, fieldSource, Merge.MANUAL));
    }

    public void removeMerge(String type, String duplicate) {
        merges.removeIf(m -> java.util.Objects.equals(m.type(), type)
                && m.duplicate().equals(duplicate));
    }

    public List<Merge> merges() {
        return List.copyOf(merges);
    }

    /** Record the approved identity for one source, replacing an earlier choice. */
    public void putIdentityLink(IdentityLink link) {
        removeIdentityLink(link.type(), link.targetId(), link.sourceKind());
        identityLinks.add(link);
    }

    public void removeIdentityLink(String type, String targetId, String sourceKind) {
        identityLinks.removeIf(link -> java.util.Objects.equals(link.type(), type)
                && link.targetId().equals(targetId)
                && java.util.Objects.equals(link.sourceKind(), sourceKind));
    }

    public List<IdentityLink> identityLinks() {
        return List.copyOf(identityLinks);
    }

    @Override
    public List<Correction> corrections() {
        return List.copyOf(entries);
    }

    public File file() {
        return file;
    }

    /** The on-disk shape — a plain POJO so JSON round-tripping doesn't depend on
     *  record-serialization support in the Jackson version. */
    static final class Doc {
        public List<Entry> corrections = new ArrayList<>();
        public List<MergeEntry> merges = new ArrayList<>();
        public List<IdentityEntry> identityLinks = new ArrayList<>();
    }

    static final class Entry {
        public String type;
        public String qid;
        public String field;
        public Object value;
        public String origin;
        public String valueKind;

        Entry() {}

        Entry(Correction c) {
            this.type = c.type();
            this.qid = c.qid();
            this.field = c.field();
            this.value = c.value();
            this.origin = c.origin();
            this.valueKind = c.valueKind();
        }
    }

    static final class MergeEntry {
        public String type;
        public String primary;
        public String duplicate;
        public java.util.Map<String, String> fieldSource;
        public String origin;

        MergeEntry() {}

        MergeEntry(Merge m) {
            this.type = m.type();
            this.primary = m.primary();
            this.duplicate = m.duplicate();
            this.fieldSource = m.fieldSource();
            this.origin = m.origin();
        }
    }

    static final class IdentityEntry {
        public String type;
        public String targetId;
        public String sourceKind;
        public String sourceId;
        public String recordUrl;
        public String canonicalName;
        public String origin;

        IdentityEntry() { }

        IdentityEntry(IdentityLink link) {
            this.type = link.type();
            this.targetId = link.targetId();
            this.sourceKind = link.sourceKind();
            this.sourceId = link.sourceId();
            this.recordUrl = link.recordUrl();
            this.canonicalName = link.canonicalName();
            this.origin = link.origin();
        }
    }
}

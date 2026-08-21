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
    private final List<FieldDeclaration> fieldDeclarations = new ArrayList<>();
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
        fieldDeclarations.clear();
        merges.clear();
        identityLinks.clear();
        if (file != null && file.isFile()) {
            try {
                Doc doc = MAPPER.readValue(file, Doc.class);
                if (doc.corrections != null) {
                    for (Entry e : doc.corrections) {
                        entries.add(new Correction(
                                e.type, e.qid, e.field, e.value, e.origin, e.valueKind,
                                e.policy, e.source));
                    }
                }
                if (doc.fieldDefinitions != null) {
                    for (FieldEntry e : doc.fieldDefinitions) {
                        fieldDeclarations.add(new FieldDeclaration(
                                e.type, e.name, e.kind, e.valueKind, e.typeLabel,
                                e.reference, e.collection, e.targetType, e.structural,
                                e.minor, e.inline, e.annotatedReference));
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
                                e.recordUrl, e.canonicalName, e.origin, e.evidence));
                    }
                }
            } catch (IOException unreadable) {
                // Missing is handled by the isFile guard. A present but unreadable
                // sidecar must not fail silently and masquerade as "no curation".
                System.err.println("Could not read curation sidecar " + file + ": "
                        + unreadable.getMessage());
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
        for (FieldDeclaration field : fieldDeclarations) {
            doc.fieldDefinitions.add(new FieldEntry(field));
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
        put(type, qid, field, value, origin, valueKind, null, null);
    }

    /** Store a typed field directive with independent replay policy and provenance. */
    public void put(String type, String qid, String field, Object value,
                    String origin, String valueKind, CorrectionPolicy policy,
                    ValueSource source) {
        entries.removeIf(c -> (c.type() == null || java.util.Objects.equals(c.type(), type))
                && c.qid().equals(qid) && c.field().equals(field));
        entries.add(new Correction(type, qid, field, value, origin, valueKind,
                policy, source));
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
        inheritIdentityOnMerge(type, primary, duplicate);
    }

    /** A merge carries identity, because identity lives here (in the curation), not on
     *  the instance: the survivor keeps the primary's Wikidata identity, or inherits the
     *  secondary's when the primary had none; the secondary's link is always dropped, its
     *  instance being gone. So the resolved/unresolved lists follow the merge. */
    private void inheritIdentityOnMerge(String survivorType, String survivorId, String loserId) {
        IdentityLink survivorLink = wikidataLinkFor(survivorId);
        IdentityLink loserLink = wikidataLinkFor(loserId);
        if (survivorLink == null && loserLink != null) {
            putIdentityLink(new IdentityLink(
                    survivorType, survivorId, loserLink.sourceKind(), loserLink.sourceId(),
                    loserLink.recordUrl(), loserLink.canonicalName(), loserLink.origin(),
                    loserLink.evidence()));
        }
        if (loserLink != null) {
            removeIdentityLink(loserLink.type(), loserLink.targetId(), loserLink.sourceKind());
        }
    }

    /** The approved Wikidata identity link whose target is {@code id}, or null. Matched
     *  by target id (the instance key) so a type alias never hides it. */
    private IdentityLink wikidataLinkFor(String id) {
        return identityLinks.stream()
                .filter(link -> "Wikidata".equalsIgnoreCase(link.sourceKind()))
                .filter(link -> java.util.Objects.equals(link.targetId(), id))
                .findFirst().orElse(null);
    }

    public void removeMerge(String type, String duplicate) {
        merges.removeIf(m -> java.util.Objects.equals(m.type(), type)
                && m.duplicate().equals(duplicate));
    }

    public List<Merge> merges() {
        return List.copyOf(merges);
    }

    public void putFieldDeclaration(String type, objectview.field.FieldRef field) {
        fieldDeclarations.removeIf(existing ->
                java.util.Objects.equals(existing.type(), type)
                        && existing.name().equals(field.name()));
        fieldDeclarations.add(FieldDeclaration.from(type, field));
    }

    public void removeFieldDeclaration(String type, String name) {
        fieldDeclarations.removeIf(existing ->
                java.util.Objects.equals(existing.type(), type)
                        && existing.name().equals(name));
    }

    public List<FieldDeclaration> fieldDeclarations() {
        return List.copyOf(fieldDeclarations);
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
        public List<FieldEntry> fieldDefinitions = new ArrayList<>();
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
        public CorrectionPolicy policy;
        public ValueSource source;

        Entry() {}

        Entry(Correction c) {
            this.type = c.type();
            this.qid = c.qid();
            this.field = c.field();
            this.value = c.value();
            this.origin = c.origin();
            this.valueKind = c.valueKind();
            this.policy = c.policy();
            this.source = c.source();
        }
    }

    static final class FieldEntry {
        public String type;
        public String name;
        public objectview.field.FieldKind kind;
        public objectview.field.FieldKind valueKind;
        public String typeLabel;
        public boolean reference;
        public boolean collection;
        public String targetType;
        public boolean structural;
        public boolean minor;
        public boolean inline;
        public boolean annotatedReference;

        FieldEntry() { }

        FieldEntry(FieldDeclaration field) {
            type = field.type();
            name = field.name();
            kind = field.kind();
            valueKind = field.valueKind();
            typeLabel = field.typeLabel();
            reference = field.reference();
            collection = field.collection();
            targetType = field.targetType();
            structural = field.structural();
            minor = field.minor();
            inline = field.inline();
            annotatedReference = field.annotatedReference();
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
        public java.util.List<datasource.evidence.ExtractedClaim> evidence;

        IdentityEntry() { }

        IdentityEntry(IdentityLink link) {
            this.type = link.type();
            this.targetId = link.targetId();
            this.sourceKind = link.sourceKind();
            this.sourceId = link.sourceId();
            this.recordUrl = link.recordUrl();
            this.canonicalName = link.canonicalName();
            this.origin = link.origin();
            this.evidence = link.evidence();
        }
    }
}

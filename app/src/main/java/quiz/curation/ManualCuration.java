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
        if (file != null && file.isFile()) {
            try {
                Doc doc = MAPPER.readValue(file, Doc.class);
                if (doc.corrections != null) {
                    for (Entry e : doc.corrections) {
                        entries.add(new Correction(e.qid, e.field, e.value, e.origin));
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
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(file, doc);
    }

    /** Set (or replace) a manually curated value for one field of one instance. */
    public void put(String qid, String field, Object value) {
        remove(qid, field);
        entries.add(new Correction(qid, field, value, Correction.MANUAL));
    }

    public void remove(String qid, String field) {
        entries.removeIf(c -> c.qid().equals(qid) && c.field().equals(field));
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
    }

    static final class Entry {
        public String qid;
        public String field;
        public Object value;
        public String origin;

        Entry() {}

        Entry(Correction c) {
            this.qid = c.qid();
            this.field = c.field();
            this.value = c.value();
            this.origin = c.origin();
        }
    }
}

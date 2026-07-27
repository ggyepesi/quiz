package quiz.curation;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads/writes a list of {@link Correction}s as a JSON sidecar beside a snapshot,
 * in the same shape as the manual-curation file. Used for <em>generated</em>
 * overlays (e.g. an automatic fallback fix cached to {@code <name>.autofix.json})
 * so they apply on load without re-querying, alongside {@link ManualCuration}.
 */
public final class CorrectionsSidecar {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CorrectionsSidecar() { }

    /** The sidecar file {@code <snapshot-base><suffix>} (e.g. {@code .autofix.json}). */
    public static File beside(File snapshot, String suffix) {
        String base = snapshot.getName()
                .replaceFirst("\\.snapshot\\.json$", "")
                .replaceFirst("\\.json$", "");
        return new File(snapshot.getParentFile(), base + suffix);
    }

    public static List<Correction> load(File file) {
        List<Correction> out = new ArrayList<>();
        if (file != null && file.isFile()) {
            try {
                Doc doc = MAPPER.readValue(file, Doc.class);
                if (doc.corrections != null) {
                    for (Entry e : doc.corrections) {
                        out.add(new Correction(
                                e.type, e.qid, e.field, e.value, e.origin, e.valueKind));
                    }
                }
            } catch (IOException ignored) {
                // A missing/unreadable sidecar is simply no overlay.
            }
        }
        return out;
    }

    public static void save(File file, List<Correction> corrections) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        Doc doc = new Doc();
        for (Correction c : corrections) {
            doc.corrections.add(new Entry(c));
        }
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(file, doc);
    }

    /** A {@link CorrectionSource} view of a sidecar file. */
    public static CorrectionSource source(File file) {
        List<Correction> loaded = load(file);
        return () -> loaded;
    }

    static final class Doc {
        public List<Entry> corrections = new ArrayList<>();
    }

    static final class Entry {
        public String type;
        public String qid;
        public String field;
        public Object value;
        public String origin;
        public String valueKind;

        Entry() { }

        Entry(Correction c) {
            this.type = c.type();
            this.qid = c.qid();
            this.field = c.field();
            this.value = c.value();
            this.origin = c.origin();
            this.valueKind = c.valueKind();
        }
    }
}

package quiz;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Registry of generated datasets — each a model + rule-tree + snapshot saved
 * <b>together</b> as a consistent triple. Lets domains (constellations,
 * greekmyth, …) coexist without clobbering, lets the web serve all of them, and
 * ensures a snapshot is never paired with a mismatched model. Lives at
 * {@code data/wikidata/datasets.json}; snapshots themselves are not in git, so
 * this registry + the local files are the source of truth.
 */
public final class DatasetRegistry {

    /** One saved dataset: a model/rule-tree/snapshot triple + its served types. */
    public static final class Dataset {
        private String name = "";
        private String key = "";
        private String rootClass = "";
        private String modelPath = "";
        private String ruletreePath = "";
        private String snapshotPath = "";
        private List<String> types = new ArrayList<>();
        private int instanceCount = 0;
        private String savedAt = "";
        // Signature (rule-tree hash) of the model the saved snapshot was
        // generated from — so a later load can detect the model drifted past
        // the instances. Blank for pre-signature datasets.
        private String modelSignature = "";

        public Dataset() {}

        public String name() { return name; }
        public void name(String v) { name = v == null ? "" : v; }
        public String key() { return key; }
        public void key(String v) { key = v == null ? "" : v; }
        public String rootClass() { return rootClass; }
        public void rootClass(String v) { rootClass = v == null ? "" : v; }
        public String modelPath() { return modelPath; }
        public void modelPath(String v) { modelPath = v == null ? "" : v; }
        public String ruletreePath() { return ruletreePath; }
        public void ruletreePath(String v) { ruletreePath = v == null ? "" : v; }
        public String snapshotPath() { return snapshotPath; }
        public void snapshotPath(String v) { snapshotPath = v == null ? "" : v; }
        public List<String> types() { return types; }
        public void types(List<String> v) { types = v == null ? new ArrayList<>() : v; }
        public int instanceCount() { return instanceCount; }
        public void instanceCount(int v) { instanceCount = v; }
        public String savedAt() { return savedAt; }
        public void savedAt(String v) { savedAt = v == null ? "" : v; }
        public String modelSignature() { return modelSignature; }
        public void modelSignature(String v) { modelSignature = v == null ? "" : v; }
    }

    private final List<Dataset> datasets = new ArrayList<>();

    public List<Dataset> datasets() { return datasets; }

    /** Insert or replace the dataset with the same key. */
    public void upsert(Dataset d) {
        if (d == null || d.key() == null || d.key().isBlank()) {
            return;
        }
        datasets.removeIf(x -> d.key().equals(x.key()));
        datasets.add(d);
    }

    // ------------------------------------------------------------------
    // Store
    // ------------------------------------------------------------------

    private static ObjectMapper mapper() {
        ObjectMapper m = new ObjectMapper();
        m.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE);
        m.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        m.enable(SerializationFeature.INDENT_OUTPUT);
        return m;
    }

    public static File defaultFile() {
        return new File(aux.Constants.wikidataDataDirectory + "datasets.json");
    }

    public static DatasetRegistry load() {
        return load(defaultFile());
    }

    public static DatasetRegistry load(File file) {
        if (file == null || !file.isFile()) {
            return new DatasetRegistry();
        }
        try {
            return mapper().readValue(file, DatasetRegistry.class);
        } catch (Exception e) {
            System.err.println("Could not read dataset registry "
                    + file + ": " + e.getMessage());
            return new DatasetRegistry();
        }
    }

    public void save() throws IOException {
        save(defaultFile());
    }

    public void save(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        mapper().writeValue(file, this);
    }
}

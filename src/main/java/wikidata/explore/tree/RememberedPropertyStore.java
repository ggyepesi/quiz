package wikidata.explore.tree;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

public class RememberedPropertyStore {
    private final File file;
    private final ObjectMapper mapper =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private Snapshot snapshot = new Snapshot();

    public RememberedPropertyStore(File file) {
        this.file = file;
        load();
    }

    public static RememberedPropertyStore defaultStore() {
        File dir = new File("data/wikidata");
        if (!dir.exists()) dir.mkdirs();
        return new RememberedPropertyStore(new File(dir, "remembered-properties.json"));
    }

    public synchronized void remember(PropertyValidationResult r, String source) {
        if (r == null || !r.valid()) return;
        RememberedProperty existing = snapshot.properties.get(r.pid());
        if (existing == null) {
            snapshot.properties.put(r.pid(), new RememberedProperty(r, source));
        } else {
            existing.label = r.label();
            existing.description = r.description();
            existing.wikibaseType = r.wikibaseType();
            existing.recommendedFieldType = r.recommendedFieldType();
            existing.lastSeenMillis = System.currentTimeMillis();
            existing.timesUsed++;
        }
        save();
    }

    public synchronized Map<String, RememberedProperty> properties() {
        return new LinkedHashMap<>(snapshot.properties);
    }

    private void load() {
        if (file == null || !file.exists()) {
            snapshot = new Snapshot();
            return;
        }
        try {
            snapshot = mapper.readValue(file, Snapshot.class);
            if (snapshot.properties == null) snapshot.properties = new LinkedHashMap<>();
        } catch (Exception ex) {
            snapshot = new Snapshot();
        }
    }

    private void save() {
        if (file == null) return;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            mapper.writeValue(file, snapshot);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public static class Snapshot {
        public Map<String, RememberedProperty> properties = new LinkedHashMap<>();
    }
}

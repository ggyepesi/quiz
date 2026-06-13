package wikidata.explore.extract;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Saves/loads extracted WikidataDynamicObject trees.
 *
 * Bridge:
 *   RuleTreeExtractor output -> JSON cache -> GeneratedKnowledgeSet -> QuizFactory
 */
public class WikidataDynamicObjectJsonStore {

    private final ObjectMapper mapper;

    public WikidataDynamicObjectJsonStore() {
        mapper = new ObjectMapper();

        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE);
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);

        mapper.activateDefaultTypingAsProperty(
                BasicPolymorphicTypeValidator.builder()
                        .allowIfSubType("wikidata.explore")
                        .allowIfSubType("java.util")
                        .allowIfSubType("java.lang")
                        .build(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                "@class");

        mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public void save(List<WikidataDynamicObject> objects, File file)
            throws IOException {

        if (objects == null) objects = List.of();

        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        mapper.writeValue(file, new Snapshot(objects));
    }

    public List<WikidataDynamicObject> load(File file) throws IOException {
        Snapshot snapshot = mapper.readValue(file, Snapshot.class);
        return snapshot == null || snapshot.objects == null
                ? new ArrayList<>()
                : new ArrayList<>(snapshot.objects);
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public static class Snapshot {
        private int version = 1;
        private List<WikidataDynamicObject> objects = new ArrayList<>();

        public Snapshot() {
        }

        public Snapshot(List<WikidataDynamicObject> objects) {
            this.objects = objects == null ? new ArrayList<>() : objects;
        }

        public int version() {
            return version;
        }

        public void version(int version) {
            this.version = version;
        }

        public List<WikidataDynamicObject> objects() {
            return objects;
        }

        public void objects(List<WikidataDynamicObject> objects) {
            this.objects = objects == null ? new ArrayList<>() : objects;
        }
    }
}

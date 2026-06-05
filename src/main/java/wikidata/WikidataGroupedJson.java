package wikidata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.util.*;

public final class WikidataGroupedJson {
    private WikidataGroupedJson() {}

    public record Store(List<Entry> entries) {}

    public record Entry(
            Entity root,
            Map<String, Set<Entity>> groups
    ) {}

    public record Entity(String name, String qid) {
        public static Entity from(WikidataEntity e) {
            return new Entity(e.getName(), e.getQid());
        }

        public WikidataEntity toWikidataEntity() {
            return WikidataEntity.canonical(name, qid);
        }
    }

    public static void write(
            Map<WikidataEntity, WikidataGroupedDownloader.Downloaded> data,
            File file
                            ) throws Exception {

        List<Entry> entries = new ArrayList<>();

        for (WikidataGroupedDownloader.Downloaded d : data.values()) {
            entries.add(toEntry(d));
        }

        ObjectMapper mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);

        mapper.writeValue(file, new Store(entries));
    }

    private static Entry toEntry(WikidataGroupedDownloader.Downloaded d) {
        Map<String, Set<Entity>> groups = new TreeMap<>();

        for (Map.Entry<String, Set<WikidataEntity>> e : d.groups().entrySet()) {
            groups.put(e.getKey(), entities(e.getValue()));
        }

        return new Entry(
                Entity.from(d.root()),
                groups);
    }

    private static Set<Entity> entities(Set<WikidataEntity> entities) {
        Set<Entity> out = new TreeSet<>(
                Comparator.comparing(
                        Entity::name,
                        Comparator.nullsLast(String::compareTo)));

        if (entities == null) {
            return out;
        }

        for (WikidataEntity e : entities) {
            out.add(Entity.from(e));
        }

        return out;
    }
}
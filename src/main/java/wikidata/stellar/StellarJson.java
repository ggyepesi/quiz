package wikidata.stellar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import wikidata.WikidataEntity;

import java.io.File;
import java.util.*;

public final class StellarJson {
    private StellarJson() {}

    public record Store(List<ObjectData> objects) {}

    public record ObjectData(
            Entity object,
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
            Map<WikidataEntity, StellarDownloader.StellarObjectData> data,
            File file
                            ) throws Exception {

        List<ObjectData> objects = new ArrayList<>();

        for (StellarDownloader.StellarObjectData d : data.values()) {
            objects.add(toDto(d));
        }

        ObjectMapper mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);

        mapper.writeValue(file, new Store(objects));
    }

    private static ObjectData toDto(
            StellarDownloader.StellarObjectData d
                                   ) {
        Map<String, Set<Entity>> groups = new TreeMap<>();

        for (Map.Entry<String, Set<WikidataEntity>> e : d.groups().entrySet()) {
            groups.put(e.getKey(), entities(e.getValue()));
        }

        return new ObjectData(
                Entity.from(d.object()),
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
package wikidata.countries;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import wikidata.WikidataEntity;

import java.io.File;
import java.util.*;

public final class CountryGeoJson {
    private CountryGeoJson() {}

    public record Store(List<Country> countries) {}

    public record Country(
            Entity country,
            Set<Entity> neighbours,
            Set<Entity> rivers,
            Set<TypedEntity> bodiesOfWater
    ) {}

    public record TypedEntity(String type, Entity entity) {}

    public record Entity(String name, String qid) {
        public static Entity from(WikidataEntity e) {
            return new Entity(e.getName(), e.getQid());
        }

        public WikidataEntity toWikidataEntity() {
            return WikidataEntity.canonical(name, qid);
        }
    }

    public static void write(
            Map<WikidataEntity, CountryGeoDownloader.CountryGeo> geo,
            File file
                            ) throws Exception {

        List<Country> countries = new ArrayList<>();

        for (CountryGeoDownloader.CountryGeo g : geo.values()) {
            countries.add(toDto(g));
        }

        Store store = new Store(countries);

        ObjectMapper mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);

        mapper.writeValue(file, store);
    }

    private static Country toDto(CountryGeoDownloader.CountryGeo g) {
        return new Country(
                Entity.from(g.country()),
                entities(g.group("neighbours")),
                entities(g.group("rivers")),
                bodiesOfWater(g.groups())
        );
    }

    private static Set<Entity> entities(Set<WikidataEntity> entities) {
        Set<Entity> out = new TreeSet<>(
                Comparator.comparing(Entity::name,
                                     Comparator.nullsLast(String::compareTo)));

        if (entities == null) {
            return out;
        }

        for (WikidataEntity e : entities) {
            out.add(Entity.from(e));
        }

        return out;
    }

    private static Set<TypedEntity> bodiesOfWater(
            Map<String, Set<WikidataEntity>> groups
                                                 ) {
        Set<TypedEntity> out = new TreeSet<>(
                Comparator
                        .comparing(TypedEntity::type)
                        .thenComparing(t -> t.entity().name(),
                                       Comparator.nullsLast(String::compareTo)));

        addTyped(out, "lake", groups.get("lakes"));
        addTyped(out, "sea", groups.get("seas"));
        addTyped(out, "gulf", groups.get("gulfs"));
        addTyped(out, "ocean", groups.get("oceans"));

        return out;
    }

    private static void addTyped(Set<TypedEntity> out,
                                 String type,
                                 Set<WikidataEntity> entities) {
        if (entities == null) {
            return;
        }

        for (WikidataEntity e : entities) {
            out.add(new TypedEntity(type, Entity.from(e)));
        }
    }
}
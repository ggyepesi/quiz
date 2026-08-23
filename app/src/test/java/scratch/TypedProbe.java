package scratch;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.*;
import wikidata.explore.model.*;
import java.io.File; import java.util.*;
class TypedProbe {
    @Test void probe() throws Exception {
        File dir = new File("../data/wikidata/oscarnominations");
        GeneratedProjectModel model = new GeneratedProjectModelStore()
                .load(new File(dir, "oscarnominations.model.json"));
        Set<String> declared = new TreeSet<>();
        model.classes().forEach(c -> declared.add(c.className()));
        if (model.rootClass() != null) declared.add(model.rootClass().className());
        System.out.println("PROBE classes declared in the configuration: " + declared);

        List<WikidataDynamicObject> pool = new WikidataDynamicObjectJsonStore()
                .loadAll(new File(dir, "oscarnominations.snapshot.json"));
        Map<String,Integer> stamped = new TreeMap<>();
        int unstamped = 0, unstampedWithQid = 0;
        for (WikidataDynamicObject o : pool) {
            if (o.hasTypeStamp()) stamped.merge(o.typeName(), 1, Integer::sum);
            else { unstamped++; if (wikidata.WikidataIds.isQid(o.qid())) unstampedWithQid++; }
        }
        System.out.println("PROBE stamped types: " + stamped);
        System.out.println("PROBE stamped types NOT declared as classes: "
                + stamped.keySet().stream().filter(t -> !declared.contains(t)).toList());
        System.out.println("PROBE unstamped=" + unstamped
                + " of which real entities (QID)=" + unstampedWithQid);
        pool.stream().filter(o -> !o.hasTypeStamp() && wikidata.WikidataIds.isQid(o.qid()))
            .limit(6).forEach(o -> System.out.println("PROBE   untyped: "
                + o.getIdentifier() + "  \"" + o.getDisplayName() + "\""
                + "  fields=" + o.dynamicFields().keySet()));
    }
}

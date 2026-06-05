package wikidata.explore.tree;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public class WikidataObjectRegistry {
    private final Map<String, WikidataDynamicObject> byQid = new LinkedHashMap<>();

    public WikidataDynamicObject getOrCreate(String qid, String label) {
        qid = cleanQid(qid);
        if (qid.isBlank()) return new WikidataDynamicObject("", label);

        String key = qid;
        return byQid.compute(key, (k, existing) -> {
            if (existing != null) {
                if ((existing.getName() == null || existing.getName().isBlank())
                        && label != null && !label.isBlank()) {
                    existing.name(label);
                }
                return existing;
            }
            return new WikidataDynamicObject(key, label);
        });
    }

    public WikidataDynamicObject get(String qid) {
        return byQid.get(cleanQid(qid));
    }

    public Collection<WikidataDynamicObject> values() {
        return byQid.values();
    }

    private static String cleanQid(String qid) {
        if (qid == null) return "";
        qid = qid.trim();
        if (qid.startsWith("wd:")) qid = qid.substring(3);
        int slash = qid.lastIndexOf('/');
        if (slash >= 0) qid = qid.substring(slash + 1);
        return qid.trim();
    }
}

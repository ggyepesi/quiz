package wikidata.explore.extract;

import quiz.Link;
import quiz.QuizableAdapter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dynamic Wikidata-backed object.
 *
 * Core semantic rule:
 *   one QID -> one WikidataDynamicObject
 *
 * Repeated field values are merged:
 *   0 values  -> absent
 *   1 value   -> scalar
 *   2+ values -> List
 */
public class WikidataDynamicObject extends QuizableAdapter {
    private String qid;
    private String name;

    @Link
    private String wikidataUrl;

    private final Map<String, Object> dynamicFields =
            new LinkedHashMap<>();

    public WikidataDynamicObject() {
        this("", "");
    }

    public WikidataDynamicObject(String qid, String name) {
        this.qid = qid == null ? "" : qid;
        this.name = name == null || name.isBlank() ? this.qid : name;
        this.wikidataUrl = this.qid.isBlank()
                ? ""
                : "https://www.wikidata.org/wiki/" + this.qid;
    }

    @Override
    public String getIdentifier() { return qid; }

    @Override
    public String getDisplayName() { return name == null || name.isBlank() ? qid : name; }

    public String displayLabel() { return getDisplayName(); }

    public String qid() {
        return qid;
    }

    public void qid(String qid) {
        this.qid = normalizeQid(qid);
        this.wikidataUrl = this.qid.isBlank()
                ? ""
                : "https://www.wikidata.org/wiki/" + this.qid;
    }

    public void name(String name) {
        this.name = name == null ? "" : name;
    }

    public String wikidataUrl() {
        return wikidataUrl;
    }

    public Map<String, Object> dynamicFields() {
        return dynamicFields;
    }

    public Object get(String fieldName) {
        return dynamicFields.get(fieldName);
    }

    public void put(String fieldName, Object value) {
        if (fieldName == null || fieldName.isBlank() || value == null) {
            return;
        }
        dynamicFields.put(fieldName, value);
    }

    public void merge(String fieldName, Object value) {
        if (fieldName == null || fieldName.isBlank() || value == null) {
            return;
        }

        Object existing = dynamicFields.get(fieldName);

        if (existing == null) {
            dynamicFields.put(fieldName, value);
            return;
        }

        if (sameValue(existing, value)) return;

        if (existing instanceof List<?> existingList) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) existingList;
            addIfMissing(list, value);
            return;
        }

        List<Object> list = new ArrayList<>();
        list.add(existing);
        addIfMissing(list, value);
        dynamicFields.put(fieldName, list);
    }

    private static String normalizeQid(String qid) {
        return qid == null ? null : qid.strip().trim();
    }

    private static void addIfMissing(List<Object> list, Object value) {
        for (Object item : list) {
            if (sameValue(item, value)) return;
        }
        list.add(value);
    }

    private static boolean sameValue(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;

        if (a instanceof WikidataDynamicObject wa
                && b instanceof WikidataDynamicObject wb) {
            return safe(wa.qid()).equals(safe(wb.qid()));
        }

        if (a instanceof WikidataMediaValue ma
                && b instanceof WikidataMediaValue mb) {
            return safe(ma.url()).equals(safe(mb.url()))
                    && safe(ma.label()).equals(safe(mb.label()));
        }

        return a.equals(b);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    @Override
    public String toString() {
        return name + (qid == null || qid.isBlank() ? "" : " (" + qid + ")");
    }

}

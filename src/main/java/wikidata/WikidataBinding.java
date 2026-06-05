package wikidata;

import java.util.Map;

public class WikidataBinding {
    private final Map<String, String> values;

    public WikidataBinding(Map<String, String> values) {
        this.values = values;
    }

    public String value(String var) {
        return values.get(strip(var));
    }

    public String label(String var) {
        return values.get(strip(var) + "Label");
    }

    public String qid(String var) {
        String v = value(var);
        if (v == null) return null;

        int i = v.lastIndexOf('/');
        return i >= 0 ? v.substring(i + 1) : v;
    }

    public WikidataEntity entity(String var) {
        String qid = qid(var);
        if (qid == null || qid.isBlank()) return null;
        return WikidataEntity.canonical(label(var), qid);
    }

    private static String strip(String var) {
        return var.startsWith("?") ? var.substring(1) : var;
    }
}
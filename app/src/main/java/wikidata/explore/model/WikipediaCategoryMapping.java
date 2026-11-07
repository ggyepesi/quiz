package wikidata.explore.model;

import wikidata.WikidataIds;

import java.util.Locale;
import java.util.Objects;

/**
 * One exact, authored interpretation of an English Wikipedia category.
 *
 * <p>The category says which maintained Wikipedia group supplies the evidence;
 * the QID says which value that group means for this field.  Keeping the value
 * explicit lets several differently named groups (for example “Cat gods” and
 * “Cat goddesses”) produce the same value without guessing from their titles.
 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public final class WikipediaCategoryMapping {
    private String category = "";
    private String valueQid = "";

    public WikipediaCategoryMapping() { }

    public WikipediaCategoryMapping(String category, String valueQid) {
        category(category);
        valueQid(valueQid);
    }

    public String category() { return cleanCategory(category); }

    public void category(String value) { category = cleanCategory(value); }

    public String valueQid() { return cleanQid(valueQid); }

    public void valueQid(String value) { valueQid = cleanQid(value); }

    public boolean configured() {
        return !category().isBlank() && WikidataIds.isQid(valueQid());
    }

    public boolean matches(String value) {
        return configured() && category().equalsIgnoreCase(cleanCategory(value));
    }

    public WikipediaCategoryMapping copy() {
        return new WikipediaCategoryMapping(category(), valueQid());
    }

    @Override public boolean equals(Object other) {
        return other instanceof WikipediaCategoryMapping that
                && category().equals(that.category())
                && valueQid().equals(that.valueQid());
    }

    @Override public int hashCode() {
        return Objects.hash(category(), valueQid());
    }

    @Override public String toString() {
        return category() + " → " + valueQid();
    }

    static String cleanCategory(String value) {
        String clean = value == null ? "" : value.trim();
        return clean.regionMatches(true, 0, "Category:", 0, 9)
                ? clean.substring(9).trim() : clean;
    }

    private static String cleanQid(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}

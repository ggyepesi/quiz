package commons.discovery;

import canonical.StableKey;
import objectview.ViewableAdapter;
import objectview.annotations.DisplayField;
import objectview.annotations.Hidden;
import objectview.annotations.Label;
import objectview.annotations.Link;
import quiz.source.WikidataSource;
import wikidata.ui.WikidataLinks;

import java.util.List;

/** One qualifier value on one Commons depicts (P180) statement. */
public final class DepictionQualifier extends ViewableAdapter {
    @Hidden private final String identifier;
    @Hidden private final String facetKey;
    @Hidden private final String facetLabel;
    @DisplayField private final String name;
    @Link @Label("Property") private final String property;
    @Label("Entity value") private final WikidataSource entityValue;
    /** Plain text (a colour name, a count, a date), never a link target. */
    @Label("Literal value") private final String literalValue;

    DepictionQualifier(String statementId, String pid, String propertyLabel,
            String value, String valueQid, String valueLabel) {
        String cleanPid = text(pid);
        String shownProperty = text(propertyLabel).isBlank() ? cleanPid : text(propertyLabel);
        String shownValue = text(valueLabel).isBlank() ? text(value) : text(valueLabel);
        this.identifier = StableKey.encode(List.of(text(statementId), cleanPid, text(value)));
        this.name = shownProperty + (shownValue.isBlank() ? "" : ": " + shownValue);
        this.facetKey = cleanPid + "\u001f" + text(value);
        this.facetLabel = name + " (" + cleanPid
                + (wikidata.WikidataIds.isQid(valueQid) ? ", " + valueQid : "") + ")";
        String propertyUrl = WikidataLinks.url(cleanPid);
        this.property = propertyUrl == null ? shownProperty
                : shownProperty + " (" + cleanPid + ")|" + propertyUrl;
        this.entityValue = wikidata.WikidataIds.isQid(valueQid)
                ? new WikidataSource(valueQid, shownValue) : null;
        this.literalValue = entityValue == null ? shownValue : "";
    }

    @Override public String getIdentifier() { return identifier; }
    @Override public String getDisplayName() { return name; }

    public String property() { return property; }
    public WikidataSource entityValue() { return entityValue; }
    public String literalValue() { return literalValue; }
    String facetKey() { return facetKey; }
    String facetLabel() { return facetLabel; }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}

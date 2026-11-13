package commons.discovery;

import objectview.ViewableAdapter;
import objectview.annotations.DisplayField;
import objectview.annotations.Hidden;
import objectview.annotations.Inline;
import objectview.annotations.Label;
import quiz.source.WikidataSource;

import java.util.List;

/** One structured depicts (P180) statement on a Commons file. */
public final class DepictionEvidence extends ViewableAdapter {
    @Hidden private final String statementId;
    @DisplayField private final String name;
    @Label("Depicted entity") private final WikidataSource depictedEntity;
    private final boolean prominent;
    @Inline private final List<DepictionQualifier> qualifiers;

    DepictionEvidence(String statementId, String qid, String label, boolean prominent,
            List<DepictionQualifier> qualifiers) {
        this.statementId = text(statementId);
        this.depictedEntity = new WikidataSource(qid, label);
        this.name = depictedEntity.getDisplayName() + " (" + qid + ")";
        this.prominent = prominent;
        this.qualifiers = qualifiers == null ? List.of() : List.copyOf(qualifiers);
    }

    @Override public String getIdentifier() { return statementId; }
    @Override public String getDisplayName() { return name; }

    public WikidataSource depictedEntity() { return depictedEntity; }
    public boolean prominent() { return prominent; }
    public List<DepictionQualifier> qualifiers() { return qualifiers; }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}

package wikidata.ui;

import javax.swing.JComponent;
import javax.swing.JLabel;
import java.awt.Color;
import java.awt.Font;

/**
 * Renders a resolved identity as a small card-header chip: the Wikidata QID as a clickable
 * link, or a faint "unidentified" marker when there is none. Rendering only — resolution
 * stays with the caller (native id, curation history, or staged link), so identity
 * presentation is uniform across the workbench cards, the curation panel and the model
 * builder, while the card renderer itself stays source-agnostic (it just places whatever
 * component it is handed). Lives next to {@link WikidataLinks} so any view can reuse it.
 */
public final class IdentityChip {

    private IdentityChip() {}

    /** A chip for {@code qid}: a clickable QID link when present, else an "unidentified" mark. */
    public static JComponent of(String qid) {
        if (qid != null && !qid.isBlank()) {
            JLabel link = new JLabel(qid);
            WikidataLinks.linkify(link, () -> qid);
            link.setToolTipText("Wikidata identity");
            return link;
        }
        JLabel none = new JLabel("unidentified");
        none.setForeground(Color.GRAY);
        none.setFont(none.getFont().deriveFont(
                Font.ITALIC, none.getFont().getSize2D() - 1f));
        none.setToolTipText("No Wikidata identity yet");
        return none;
    }

    /** A reified Wikidata statement is already anchored, but is not an entity whose
     * label can be identity-resolved. Keep it visibly distinct from "unidentified". */
    public static JComponent statement() {
        return statement(null);
    }

    /** A statement chip that links to the entity which CONTAINS the claim. */
    public static JComponent statement(String statementId) {
        String subject = wikidata.WikidataIds.statementSubject(statementId);
        JLabel statement = new JLabel(subject == null ? "statement" : "statement on " + subject);
        statement.setForeground(Color.GRAY);
        statement.setFont(statement.getFont().deriveFont(
                Font.ITALIC, statement.getFont().getSize2D() - 1f));
        if (subject != null) {
            WikidataLinks.linkify(statement, () -> subject);
            statement.setToolTipText("Wikidata statement stored on " + subject
                    + " — open the containing entity");
        } else {
            statement.setToolTipText(
                    "Wikidata statement — already anchored; no entity identity to resolve");
        }
        return statement;
    }

    /**
     * The card decorator for instances identified by their NATIVE id — the model
     * builder's case, where there is no curation sidecar to consult. A non-Wikidata
     * id stays undecorated (null) rather than showing "unidentified", since such an
     * instance has no Wikidata identity to resolve in the first place.
     *
     * <p>THE decorator for that case: every view that renders natively-identified
     * instances passes this, so the QID link appears the same way everywhere.
     */
    public static JComponent ofInstance(objectview.Viewable instance) {
        String id = instance == null ? null : instance.getIdentifier();
        if (wikidata.WikidataIds.isStatementId(id)) return statement(id);
        return quiz.source.WikidataSource.isQid(id) ? of(id) : null;
    }
}

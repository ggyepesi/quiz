package wikidata.explore.workbench;

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
}

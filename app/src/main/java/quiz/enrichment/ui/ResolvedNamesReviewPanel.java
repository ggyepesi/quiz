package quiz.enrichment.ui;

import javax.swing.JCheckBox;
import javax.swing.JDialog;
import java.awt.Component;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import quiz.enrichment.ResolveNamesProcess.Name;

/**
 * The outcome of a reference-name repair, as the same review surface every other batch
 * uses: a Resolved section of ⟨QID → name⟩ rows, pre-accepted, and an Unresolved section
 * kept visible so the entities that still have no name do not simply vanish.
 *
 * <p>A batch that reports only "1930 of 1933" tells the reader a number and hides the
 * three, which are the interesting ones — and gives them nothing to accept or reject.
 * Reviewing before staging is how every other bulk fill in this app works, and a name is
 * no less of a decision than a value.
 */
public final class ResolvedNamesReviewPanel {


    private ResolvedNamesReviewPanel() { }

    /** Modeless, like every other batch review: the process waits on the answer while
     *  the reader can still scroll the domain behind it. */
    public static JDialog showModeless(
            Component owner,
            quiz.enrichment.ResolveNamesProcess.ReviewRequest request,
            Consumer<List<Name>> onAccepted) {

        return CategorizedReviewPanel.showModeless(
                owner,
                request.title(),
                prompt(request.field(), request.requested(), request.resolved(),
                        request.failedBatches()),
                sections(request.requested(), request.resolved()),
                new Dimension(640, 520),
                onAccepted);
    }

    private static String prompt(
            String field, Set<String> requested, Map<String, String> resolved,
            int failedBatches) {
        StringBuilder text = new StringBuilder("<html>")
                .append("<b>").append(field).append("</b> — ")
                .append(resolved.size()).append(" of ").append(requested.size())
                .append(" referenced entities now have a name.");
        if (failedBatches > 0) {
            text.append("<br>").append(failedBatches)
                .append(" request(s) were refused; those entities keep their QID and can ")
                .append("be retried.");
        }
        text.append("<br><br>Accepted names are recorded against the ENTITY, so one name ")
            .append("fixes every instance referring to it. They appear once the domain is ")
            .append("reloaded — the compiled value is a copy of the name, not a pointer.");
        return text.append("</html>").toString();
    }

    private static List<CategorizedReviewPanel.Section<Name>> sections(
            Set<String> requested, Map<String, String> resolved) {

        List<CategorizedReviewPanel.Row<Name>> found = new ArrayList<>();
        List<CategorizedReviewPanel.Row<Name>> missing = new ArrayList<>();
        for (String qid : requested) {
            String label = resolved.get(qid);
            if (label != null && !label.isBlank() && !label.equals(qid)) {
                found.add(row(new Name(qid, label), qid + "  →  " + label, true));
            } else {
                missing.add(row(new Name(qid, null), qid + "  —  no name returned", false));
            }
        }
        return List.of(
                new CategorizedReviewPanel.Section<>(
                        "Resolved",
                        "Names found — accepted; uncheck any you don't want", found),
                new CategorizedReviewPanel.Section<>(
                        "Unresolved",
                        "No name returned — the entity may be deleted, unlabelled in "
                                + "English, or its request was refused", missing));
    }

    private static CategorizedReviewPanel.Row<Name> row(
            Name name, String label, boolean selectable) {
        JCheckBox accept = new JCheckBox(
                CategorizedReviewPanel.truncate(label, 90), selectable);
        accept.setEnabled(selectable);
        accept.setToolTipText(label);
        return new CategorizedReviewPanel.Row<>(name, accept);
    }
}

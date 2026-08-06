package quiz.enrichment.ui;

import quiz.enrichment.ResolveIdentitiesDecision;
import quiz.enrichment.ResolveIdentitiesReviewRequest.IdentityMatch;
import quiz.enrichment.ResolveIdentitiesReviewRequest.InstanceIdentity;
import wikidata.explore.workbench.WikidataLinks;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * One review over identity candidates, grouped by match confidence so attention goes where
 * it's needed: CONFIDENT (a candidate's label matches the instance name), AMBIGUOUS
 * (candidates found but none is an exact-name match — pick one), and NO MATCH (search
 * returned nothing). A thin adapter over {@link CategorizedReviewPanel}: it builds the rows
 * (name + candidate combo + QID link + description) and maps the accepted rows back to
 * identity assignments; the tabs / layout / select-all / apply are the shared surface, the
 * same one curation's Find Data review uses.
 */
public final class ResolveIdentitiesReviewPanel {

    private ResolveIdentitiesReviewPanel() { }

    /** A payload row: the instance and its candidate combo (null when there are no
     *  candidates), read at apply time to build the chosen assignment. */
    private record IdRow(InstanceIdentity instance, JComboBox<IdentityMatch> combo) { }

    public static void showDialog(
            Component owner, String title, String prompt,
            List<InstanceIdentity> instances, Consumer<ResolveIdentitiesDecision> onDone) {
        CategorizedReviewPanel.showDialog(owner, title, promptWithApplied(prompt, List.of()),
                sections(instances, List.of()), new Dimension(840, 660),
                accepted -> onDone.accept(decision(accepted)));
    }

    public static JDialog showModeless(
            Component owner, String title, String prompt,
            List<InstanceIdentity> instances, Consumer<ResolveIdentitiesDecision> onDone) {
        return showModeless(owner, title, prompt, instances, List.of(), onDone);
    }

    /** Re-open a retained result and mark assignments already applied in memory. */
    public static JDialog showModeless(
            Component owner, String title, String prompt,
            List<InstanceIdentity> instances,
            List<ResolveIdentitiesDecision.Resolved> applied,
            Consumer<ResolveIdentitiesDecision> onDone) {
        return CategorizedReviewPanel.showModeless(
                owner, title, promptWithApplied(prompt, applied),
                sections(instances, applied), new Dimension(840, 660),
                accepted -> onDone.accept(decision(accepted)));
    }

    private static String promptWithApplied(
            String prompt, List<ResolveIdentitiesDecision.Resolved> applied) {
        int n = applied == null ? 0 : applied.size();
        return n == 0 ? prompt
                : prompt + " — " + n + " assignment(s) already applied in memory (not yet saved).";
    }

    private static List<CategorizedReviewPanel.Section<IdRow>> sections(
            List<InstanceIdentity> instances, List<ResolveIdentitiesDecision.Resolved> applied) {
        List<InstanceIdentity> confident = new ArrayList<>();
        List<InstanceIdentity> ambiguous = new ArrayList<>();
        List<InstanceIdentity> noMatch = new ArrayList<>();
        for (InstanceIdentity instance : instances == null ? List.<InstanceIdentity>of() : instances) {
            if (instance.candidates().isEmpty()) {
                noMatch.add(instance);
            } else if (exactMatch(instance) != null) {
                confident.add(instance);
            } else {
                ambiguous.add(instance);
            }
        }

        Map<String, ResolveIdentitiesDecision.Resolved> appliedByInstance = new LinkedHashMap<>();
        for (ResolveIdentitiesDecision.Resolved resolved
                : applied == null ? List.<ResolveIdentitiesDecision.Resolved>of() : applied) {
            appliedByInstance.put(key(resolved.type(), resolved.targetId()), resolved);
        }

        return List.of(
                new CategorizedReviewPanel.Section<>("Confident",
                        "Top exact name match — accepted; uncheck any wrong entity type",
                        rows(confident, true, true, appliedByInstance)),
                new CategorizedReviewPanel.Section<>("Ambiguous",
                        "Choose the right entity",
                        rows(ambiguous, true, false, appliedByInstance)),
                new CategorizedReviewPanel.Section<>("No match",
                        "No match found",
                        rows(noMatch, false, false, appliedByInstance)));
    }

    private static List<CategorizedReviewPanel.Row<IdRow>> rows(
            List<InstanceIdentity> items, boolean selectable, boolean checkedByDefault,
            Map<String, ResolveIdentitiesDecision.Resolved> applied) {
        List<CategorizedReviewPanel.Row<IdRow>> out = new ArrayList<>();
        for (InstanceIdentity instance : items) {
            out.add(row(instance, selectable, checkedByDefault,
                    applied.get(key(instance.type(), instance.targetId()))));
        }
        return out;
    }

    private static CategorizedReviewPanel.Row<IdRow> row(
            InstanceIdentity instance, boolean selectable, boolean checked,
            ResolveIdentitiesDecision.Resolved applied) {
        boolean alreadyApplied = applied != null;
        JCheckBox accept = new JCheckBox(alreadyApplied ? "Applied" : "",
                alreadyApplied || selectable && checked && !instance.candidates().isEmpty());
        accept.setEnabled(!alreadyApplied && selectable && !instance.candidates().isEmpty());
        accept.setToolTipText(alreadyApplied ? "Already applied in memory" : null);
        if (alreadyApplied) {
            accept.setForeground(new Color(35, 125, 55));
            accept.setFont(accept.getFont().deriveFont(Font.BOLD));
        }

        String typedName = instance.name() + "  [" + instance.type() + "]";
        JLabel name = new JLabel(CategorizedReviewPanel.truncate(typedName, 34));
        name.setToolTipText(typedName);
        name.setPreferredSize(new Dimension(210, name.getPreferredSize().height));

        if (instance.candidates().isEmpty()) {
            JLabel none = new JLabel("no candidates — resolve individually later");
            none.setForeground(Color.GRAY);
            return new CategorizedReviewPanel.Row<>(new IdRow(instance, null), accept,
                    CategorizedReviewPanel.Cell.of(name),
                    CategorizedReviewPanel.Cell.stretch(none));
        }

        List<IdentityMatch> candidates = new ArrayList<>(instance.candidates());
        if (alreadyApplied && candidates.stream()
                .noneMatch(candidate -> applied.qid().equals(candidate.qid()))) {
            candidates.add(0, new IdentityMatch(applied.qid(), applied.label(), "Applied in memory"));
        }
        JComboBox<IdentityMatch> combo = new JComboBox<>(candidates.toArray(new IdentityMatch[0]));
        combo.setRenderer(new MatchRenderer());
        combo.setPreferredSize(new Dimension(190, combo.getPreferredSize().height));
        IdentityMatch exact = exactMatch(instance);
        IdentityMatch appliedMatch = alreadyApplied
                ? candidates.stream()
                        .filter(candidate -> applied.qid().equals(candidate.qid()))
                        .findFirst().orElse(null)
                : null;
        combo.setSelectedItem(appliedMatch != null ? appliedMatch
                : exact != null ? exact : instance.candidates().get(0));
        combo.setEnabled(!alreadyApplied);

        JLabel qid = new JLabel();
        WikidataLinks.linkify(qid, () -> combo.getSelectedItem() instanceof IdentityMatch m
                ? m.qid() : null);
        JLabel description = new JLabel();
        description.setForeground(Color.GRAY);
        Runnable showCandidate = () -> {
            IdentityMatch match = combo.getSelectedItem() instanceof IdentityMatch candidate
                    ? candidate : null;
            String selectedQid = match == null || match.qid() == null ? "" : match.qid();
            qid.setText(selectedQid.isBlank() ? ""
                    : "<html><u>" + CategorizedReviewPanel.html(selectedQid) + "</u></html>");
            qid.setToolTipText(selectedQid.isBlank() ? null : "Open " + selectedQid + " on Wikidata");
            String text = match == null ? "" : match.description();
            description.setText(CategorizedReviewPanel.truncate(text, 46));
            description.setToolTipText(text == null || text.isBlank() ? null : text);
        };
        combo.addActionListener(e -> showCandidate.run());
        showCandidate.run();

        return new CategorizedReviewPanel.Row<>(new IdRow(instance, combo), accept,
                CategorizedReviewPanel.Cell.of(name),
                CategorizedReviewPanel.Cell.of(combo),
                CategorizedReviewPanel.Cell.of(qid),
                CategorizedReviewPanel.Cell.stretch(description));
    }

    private static ResolveIdentitiesDecision decision(List<IdRow> accepted) {
        List<ResolveIdentitiesDecision.Resolved> resolved = new ArrayList<>();
        for (IdRow row : accepted) {
            if (row.combo() != null
                    && row.combo().getSelectedItem() instanceof IdentityMatch match) {
                resolved.add(new ResolveIdentitiesDecision.Resolved(
                        row.instance().type(), row.instance().targetId(),
                        match.qid(), match.label()));
            }
        }
        return new ResolveIdentitiesDecision(resolved);
    }

    private static String key(String type, String targetId) {
        return String.valueOf(type) + ' ' + targetId;
    }

    /** The confident pick: the FIRST exact-label match. Candidates come back in Wikidata
     *  relevance order, so the top exact hit is the canonical entity ("India" → Q668).
     *  Homonyms sharing the label no longer demote it to ambiguous — ranking decides. */
    static IdentityMatch exactMatch(InstanceIdentity instance) {
        for (IdentityMatch match : instance.candidates()) {
            if (match.label() != null && instance.name() != null
                    && match.label().equalsIgnoreCase(instance.name())) {
                return match;
            }
        }
        return null;
    }

    private static final class MatchRenderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean selected, boolean focus) {
            super.getListCellRendererComponent(list, value, index, selected, focus);
            if (value instanceof IdentityMatch match) {
                // Dropdown items (index >= 0) also carry the description, to tell same-labelled
                // candidates apart; the collapsed selected value (index -1) stays compact.
                String desc = index >= 0 && match.description() != null
                        && !match.description().isBlank()
                        ? "  — " + match.description() : "";
                setText(match.label() + "  (" + match.qid() + ")" + desc);
            } else {
                setText("(no match)");
            }
            return this;
        }
    }
}

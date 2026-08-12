package quiz.enrichment.ui;

import quiz.enrichment.ResolveIdentitiesDecision;
import quiz.enrichment.ResolveIdentitiesReviewRequest.IdentityMatch;
import quiz.enrichment.ResolveIdentitiesReviewRequest.InstanceIdentity;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JComponent;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * One review over identity candidates, grouped by match confidence so attention goes where
 * it's needed: CONFIDENT (a candidate's label matches the instance name), AMBIGUOUS
 * (candidates found but none is an exact-name match — pick one), and NO MATCH (search
 * returned nothing). Instances are rendered through the standard Viewable card pipeline;
 * candidate controls are card decorations whose state survives virtualization.
 */
public final class ResolveIdentitiesReviewPanel {

    private ResolveIdentitiesReviewPanel() { }


    public static void showDialog(
            Component owner, String title, String prompt,
            List<InstanceIdentity> instances, Consumer<ResolveIdentitiesDecision> onDone) {
        showCardReview(owner, title, promptWithApplied(prompt, List.of()),
                instances, List.of(), java.awt.Dialog.ModalityType.APPLICATION_MODAL, onDone);
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
        return showCardReview(owner, title, promptWithApplied(prompt, applied),
                instances, applied, java.awt.Dialog.ModalityType.MODELESS, onDone);
    }

    /** Card-based, virtualized result review. A 20k-result run materializes only visible
     *  cards; candidate controls live in the card decorator and survive virtualization
     *  because their state is held in ReviewState, not Swing components. */
    private static JDialog showCardReview(
            Component owner, String title, String prompt,
            List<InstanceIdentity> instances,
            List<ResolveIdentitiesDecision.Resolved> applied,
            java.awt.Dialog.ModalityType modality,
            Consumer<ResolveIdentitiesDecision> onDone) {
        java.util.Map<String, ResolveIdentitiesDecision.Resolved> appliedByKey =
                new java.util.HashMap<>();
        for (ResolveIdentitiesDecision.Resolved value
                : applied == null ? List.<ResolveIdentitiesDecision.Resolved>of() : applied) {
            appliedByKey.put(key(value.type(), value.targetId()), value);
        }
        List<ReviewState> states = new ArrayList<>();
        List<objectview.Viewable> cards = new ArrayList<>();
        java.util.Map<String, ReviewState> byCard = new java.util.HashMap<>();
        for (InstanceIdentity instance
                : instances == null ? List.<InstanceIdentity>of() : instances) {
            ResolveIdentitiesDecision.Resolved wasApplied =
                    appliedByKey.get(key(instance.type(), instance.targetId()));
            ReviewState state = new ReviewState(instance, wasApplied);
            states.add(state);
            quiz.transform.DynamicViewable card = new quiz.transform.DynamicViewable(
                    key(instance.type(), instance.targetId()), instance.name());
            card.type(instance.candidates().isEmpty() ? "No match"
                    : exactMatch(instance) != null ? "Confident" : "Ambiguous");
            card.put("Outcome", card.typeName());
            card.put("Candidates", instance.candidates().size());
            cards.add(card);
            byCard.put(card.getIdentifier(), state);
        }

        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 10, 10, 10));
        panel.add(new JLabel("<html>" + CategorizedReviewPanel.html(prompt)
                + "<br><br>Select a card, choose its candidate, then stage the selected card."
                + " Nothing is persisted until Apply changes.</html>"), BorderLayout.NORTH);
        JButton close = new JButton("Close without staging");
        JButton stage = new JButton("Stage selected card");
        stage.setEnabled(false);
        java.util.concurrent.atomic.AtomicReference<ReviewState> selectedCard =
                new java.util.concurrent.atomic.AtomicReference<>();
        if (cards.isEmpty()) {
            panel.add(new JLabel("No unresolved identities required searching."), BorderLayout.CENTER);
        } else {
            panel.add(objectview.view.SearchableView.builder(cards)
                    .sample(cards.get(0))
                    .mode(objectview.render.RenderingMode.CARD)
                    .collapsible(false)
                    .columns(2)
                    .cardDecorator(card -> controls(byCard.get(card.getIdentifier())))
                    .selectionListener(card -> {
                        ReviewState selected = card instanceof objectview.Viewable viewable
                                ? byCard.get(viewable.getIdentifier()) : null;
                        selectedCard.set(selected);
                        stage.setEnabled(selected != null && selected.applied == null
                                && selected.selected != null);
                    })
                    .build(), BorderLayout.CENTER);
        }
        JPanel footer = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT));
        footer.add(close); footer.add(stage); panel.add(footer, BorderLayout.SOUTH);
        JDialog dialog = new JDialog(javax.swing.SwingUtilities.getWindowAncestor(owner),
                title, modality);
        dialog.setContentPane(panel); dialog.setSize(new Dimension(900, 700));
        dialog.setLocationRelativeTo(owner);
        close.addActionListener(e -> { dialog.dispose(); onDone.accept(new ResolveIdentitiesDecision(List.of())); });
        stage.addActionListener(e -> {
            ReviewState selected = selectedCard.get();
            List<ResolveIdentitiesDecision.Resolved> chosen = selected == null
                    || selected.applied != null || selected.selected == null ? List.of()
                    : List.of(new ResolveIdentitiesDecision.Resolved(
                            selected.instance.type(), selected.instance.targetId(),
                            selected.selected.qid(), selected.selected.label()));
            dialog.dispose(); onDone.accept(new ResolveIdentitiesDecision(chosen));
        });
        dialog.setVisible(true);
        return dialog;
    }

    private static JComponent controls(ReviewState state) {
        JPanel line = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
        if (state == null) return line;
        JComboBox<IdentityMatch> choices = new JComboBox<>(
                state.instance.candidates().toArray(new IdentityMatch[0]));
        choices.setRenderer(new MatchRenderer());
        choices.setPreferredSize(new Dimension(360, choices.getPreferredSize().height));
        if (state.selected != null) choices.setSelectedItem(state.selected);
        choices.setEnabled(state.applied == null && choices.getItemCount() > 0);
        choices.addActionListener(e -> {
            state.selected = choices.getSelectedItem() instanceof IdentityMatch m ? m : null;
        });
        if (state.applied != null) line.add(new JLabel("Already staged"));
        if (choices.getItemCount() > 0) line.add(choices);
        else line.add(new JLabel("No candidate returned"));
        return line;
    }

    private static final class ReviewState {
        final InstanceIdentity instance;
        final ResolveIdentitiesDecision.Resolved applied;
        IdentityMatch selected;
        ReviewState(InstanceIdentity instance, ResolveIdentitiesDecision.Resolved applied) {
            this.instance = instance; this.applied = applied;
            this.selected = applied == null ? exactMatch(instance) : new IdentityMatch(
                    applied.qid(), applied.label(), "Already staged");
            if (this.selected == null && !instance.candidates().isEmpty()) {
                this.selected = instance.candidates().get(0);
            }
        }
    }

    private static String promptWithApplied(
            String prompt, List<ResolveIdentitiesDecision.Resolved> applied) {
        int n = applied == null ? 0 : applied.size();
        return n == 0 ? prompt
                : prompt + " — " + n + " assignment(s) already applied in memory (not yet saved).";
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

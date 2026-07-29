package quiz.enrichment.ui;

import objectview.media.ImagePane;
import quiz.enrichment.EnrichmentDecision;
import quiz.enrichment.EnrichmentProposal;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Reusable four-stage review UI: choose an identity, review proposed information,
 * choose media, and approve the resulting decision. The panel performs no discovery,
 * persistence, or mutation; callers supply a proposal and handle the final decision.
 */
public final class EnrichmentReviewPanel extends JPanel {

    private static final String[] STEP_NAMES =
            {"1. Identity", "2. Information", "3. Media", "4. Preview"};

    private final EnrichmentProposal proposal;
    private final Consumer<EnrichmentDecision> onApprove;
    private final CardLayout cards = new CardLayout();
    private final JPanel cardHolder = new JPanel(cards);
    private final JLabel stepLabel = new JLabel();
    private final JButton back = new JButton("Back");
    private final JButton next = new JButton("Next");
    private final JButton approve = new JButton("Apply approved changes");
    private final JButton cancel = new JButton("Cancel");
    private final ButtonGroup identities = new ButtonGroup();
    private ButtonGroup media = new ButtonGroup();
    private final Map<EnrichmentProposal.IdentityCandidate, JRadioButton> identityButtons =
            new LinkedHashMap<>();
    private final Map<EnrichmentProposal.FieldCandidate,
            JComboBox<EnrichmentProposal.ReviewAction>> fieldActions = new LinkedHashMap<>();
    private final Map<EnrichmentProposal.MediaCandidate, JRadioButton> mediaButtons =
            new LinkedHashMap<>();
    private JRadioButton noMedia;
    private final JPanel informationRows = verticalPanel();
    private final JPanel mediaRows = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
    private final JTextArea preview = new JTextArea();

    private int step;

    public EnrichmentReviewPanel(
            EnrichmentProposal proposal,
            Consumer<EnrichmentDecision> onApprove) {
        super(new BorderLayout(8, 8));
        this.proposal = java.util.Objects.requireNonNull(proposal, "proposal");
        this.onApprove = onApprove == null ? ignored -> { } : onApprove;

        add(header(), BorderLayout.NORTH);
        cardHolder.add(identityPage(), "0");
        cardHolder.add(scroll(informationRows), "1");
        cardHolder.add(scroll(mediaRows), "2");
        cardHolder.add(previewPage(), "3");
        add(cardHolder, BorderLayout.CENTER);
        add(buttons(), BorderLayout.SOUTH);

        selectFirstIdentity();
        rebuildDependentPages();
        showStep(0);
    }

    /** Show the reusable panel in a modal dialog; returns immediately after it closes. */
    public static void showDialog(
            Component owner,
            String title,
            EnrichmentProposal proposal,
            Consumer<EnrichmentDecision> onApprove) {
        createDialog(owner, title, proposal, onApprove,
                Dialog.ModalityType.APPLICATION_MODAL).setVisible(true);
    }

    public static JDialog showModeless(
            Component owner, String title, EnrichmentProposal proposal,
            Consumer<EnrichmentDecision> onApprove) {
        JDialog dialog = createDialog(owner, title, proposal, onApprove,
                Dialog.ModalityType.MODELESS);
        dialog.setVisible(true);
        return dialog;
    }

    private static JDialog createDialog(
            Component owner, String title, EnrichmentProposal proposal,
            Consumer<EnrichmentDecision> onApprove, Dialog.ModalityType modality) {
        Window window = SwingUtilities.getWindowAncestor(owner);
        JDialog dialog = new JDialog(window, title, modality);
        Consumer<EnrichmentDecision> handler =
                onApprove == null ? ignored -> { } : onApprove;
        java.util.concurrent.atomic.AtomicBoolean completed =
                new java.util.concurrent.atomic.AtomicBoolean();
        Consumer<EnrichmentDecision> finish = decision -> {
            if (completed.compareAndSet(false, true)) {
                handler.accept(decision);
                dialog.dispose();
            }
        };
        EnrichmentReviewPanel panel = new EnrichmentReviewPanel(proposal, finish);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                finish.accept(null);
            }
        });
        dialog.add(panel);
        dialog.setSize(960, 650);
        dialog.setLocationRelativeTo(owner);
        return dialog;
    }

    private JComponent header() {
        JPanel panel = new JPanel(new BorderLayout(8, 4));
        JLabel subject = new JLabel("<html><b>"
                + html(proposal.subject().displayName()) + "</b> &nbsp; "
                + html(proposal.subject().type()) + " · "
                + html(proposal.subject().id()) + "</html>");
        panel.add(subject, BorderLayout.WEST);
        stepLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        panel.add(stepLabel, BorderLayout.EAST);
        return panel;
    }

    private JComponent identityPage() {
        JPanel rows = verticalPanel();
        if (proposal.identities().isEmpty()) {
            rows.add(message("No identity candidates were discovered."));
        }
        for (EnrichmentProposal.IdentityCandidate candidate : proposal.identities()) {
            JRadioButton choose = new JRadioButton(identityText(candidate));
            choose.setVerticalAlignment(SwingConstants.TOP);
            choose.addActionListener(e -> rebuildDependentPages());
            identities.add(choose);
            identityButtons.put(candidate, choose);

            JPanel row = new JPanel(new BorderLayout(8, 4));
            row.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createEtchedBorder(),
                    BorderFactory.createEmptyBorder(8, 8, 8, 8)));
            row.add(choose, BorderLayout.NORTH);
            JTextArea evidence = textArea(candidateEvidence(candidate));
            evidence.setRows(Math.max(2, candidate.evidence().size() + 1));
            row.add(evidence, BorderLayout.CENTER);
            rows.add(row);
        }
        return scroll(rows);
    }

    private JComponent previewPage() {
        preview.setEditable(false);
        preview.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        preview.setMargin(new Insets(8, 8, 8, 8));
        return new JScrollPane(preview);
    }

    private JComponent buttons() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        back.addActionListener(e -> showStep(step - 1));
        next.addActionListener(e -> showStep(step + 1));
        approve.addActionListener(e -> onApprove.accept(decision()));
        cancel.addActionListener(e -> {
            Window window = SwingUtilities.getWindowAncestor(this);
            if (window != null) window.dispose();
        });
        panel.add(cancel);
        panel.add(back);
        panel.add(next);
        panel.add(approve);
        return panel;
    }

    private void showStep(int requested) {
        step = Math.max(0, Math.min(STEP_NAMES.length - 1, requested));
        if (step == STEP_NAMES.length - 1) {
            preview.setText(previewText());
            preview.setCaretPosition(0);
        }
        cards.show(cardHolder, Integer.toString(step));
        stepLabel.setText(STEP_NAMES[step]);
        back.setEnabled(step > 0);
        next.setVisible(step < STEP_NAMES.length - 1);
        approve.setVisible(step == STEP_NAMES.length - 1);
        approve.setEnabled(selectedIdentity() != null);
    }

    private void selectFirstIdentity() {
        identityButtons.values().stream().findFirst().ifPresent(button -> button.setSelected(true));
    }

    private void rebuildDependentPages() {
        EnrichmentProposal.IdentityCandidate identity = selectedIdentity();
        String identityId = identity == null ? null : identity.candidateId();

        informationRows.removeAll();
        fieldActions.clear();
        List<EnrichmentProposal.FieldCandidate> applicableFields = proposal.fields().stream()
                .filter(f -> belongsTo(f.identityCandidateId(), identityId))
                .toList();
        if (applicableFields.isEmpty()) {
            informationRows.add(message("No additional field values were discovered."));
        }
        for (EnrichmentProposal.FieldCandidate candidate : applicableFields) {
            informationRows.add(fieldRow(candidate));
        }

        mediaRows.removeAll();
        mediaButtons.clear();
        media = new ButtonGroup();
        noMedia = new JRadioButton("Do not apply a media candidate", true);
        media.add(noMedia);
        mediaRows.add(noMedia);
        List<EnrichmentProposal.MediaCandidate> applicableMedia = proposal.media().stream()
                .filter(m -> belongsTo(m.identityCandidateId(), identityId))
                .toList();
        if (applicableMedia.isEmpty()) {
            mediaRows.add(message("No media candidates were discovered."));
        }
        for (EnrichmentProposal.MediaCandidate candidate : applicableMedia) {
            mediaRows.add(mediaCell(candidate));
        }
        informationRows.revalidate();
        informationRows.repaint();
        mediaRows.revalidate();
        mediaRows.repaint();
    }

    private JComponent fieldRow(EnrichmentProposal.FieldCandidate candidate) {
        JPanel row = new JPanel(new GridLayout(1, 4, 8, 4));
        row.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        row.add(new JLabel(candidate.field()));
        row.add(new JLabel(display(candidate.currentValue())));
        row.add(new JLabel(display(candidate.proposedValue())));
        JComboBox<EnrichmentProposal.ReviewAction> action =
                new JComboBox<>(EnrichmentProposal.ReviewAction.values());
        action.setSelectedItem(candidate.suggestedAction() == null
                ? EnrichmentProposal.ReviewAction.IGNORE : candidate.suggestedAction());
        fieldActions.put(candidate, action);
        row.add(action);
        return row;
    }

    private JComponent mediaCell(EnrichmentProposal.MediaCandidate candidate) {
        JPanel cell = new JPanel(new BorderLayout(4, 4));
        cell.setBorder(BorderFactory.createEtchedBorder());
        try {
            String previewUrl = blank(candidate.previewUrl())
                    ? candidate.imageUrl() : candidate.previewUrl();
            ImagePane image = new ImagePane(fileName(candidate.imageUrl()), previewUrl,
                    null, true, isSvg(candidate.imageUrl()));
            image.setPreferredSize(new Dimension(210, 210));
            cell.add(image, BorderLayout.CENTER);
        } catch (Exception ex) {
            cell.add(message(candidate.imageUrl()), BorderLayout.CENTER);
        }

        JRadioButton choose = new JRadioButton("<html><b>"
                + html(candidate.source().kind()) + "</b><br>"
                + html(candidate.discoveryMethod()) + "<br>"
                + Math.round(candidate.confidence() * 100) + "% confidence</html>");
        media.add(choose);
        mediaButtons.put(candidate, choose);
        cell.add(choose, BorderLayout.SOUTH);
        return cell;
    }

    public EnrichmentDecision decision() {
        List<EnrichmentDecision.FieldDecision> fields = new ArrayList<>();
        fieldActions.forEach((candidate, combo) -> {
            EnrichmentProposal.ReviewAction action =
                    (EnrichmentProposal.ReviewAction) combo.getSelectedItem();
            if (action != null && action != EnrichmentProposal.ReviewAction.IGNORE) {
                fields.add(new EnrichmentDecision.FieldDecision(candidate, action));
            }
        });
        return new EnrichmentDecision(
                proposal.subject(), selectedIdentity(), fields, selectedMedia());
    }

    private String previewText() {
        EnrichmentDecision decision = decision();
        StringBuilder out = new StringBuilder();
        out.append("Target\n  ").append(proposal.subject().type()).append(" ")
                .append(proposal.subject().id()).append(" — ")
                .append(proposal.subject().displayName()).append("\n\n");
        if (decision.identity() != null) {
            out.append("Identity\n  ").append(decision.identity().canonicalName())
                    .append("\n  ").append(decision.identity().source().kind())
                    .append(": ").append(decision.identity().source().recordUrl())
                    .append("\n\n");
        }
        out.append("Approved information\n");
        if (decision.fields().isEmpty()) {
            out.append("  (none)\n");
        } else {
            for (EnrichmentDecision.FieldDecision field : decision.fields()) {
                out.append("  ").append(field.action()).append(" ")
                        .append(field.candidate().field()).append(" = ")
                        .append(display(field.candidate().proposedValue())).append("\n");
            }
        }
        out.append("\nApproved media\n");
        if (decision.media() == null) {
            out.append("  (none)\n");
        } else {
            out.append("  ").append(decision.media().field()).append(" = ")
                    .append(decision.media().imageUrl()).append("\n  source: ")
                    .append(decision.media().source().kind()).append(" — ")
                    .append(decision.media().source().recordUrl()).append("\n");
        }
        return out.toString();
    }

    private EnrichmentProposal.IdentityCandidate selectedIdentity() {
        return identityButtons.entrySet().stream()
                .filter(e -> e.getValue().isSelected())
                .map(Map.Entry::getKey)
                .findFirst().orElse(null);
    }

    private EnrichmentProposal.MediaCandidate selectedMedia() {
        return mediaButtons.entrySet().stream()
                .filter(e -> e.getValue().isSelected())
                .map(Map.Entry::getKey)
                .findFirst().orElse(null);
    }

    private static boolean belongsTo(String candidateIdentity, String selectedIdentity) {
        return candidateIdentity == null || java.util.Objects.equals(
                candidateIdentity, selectedIdentity);
    }

    private static String identityText(EnrichmentProposal.IdentityCandidate candidate) {
        return "<html><b>" + html(candidate.canonicalName()) + "</b> &nbsp; "
                + Math.round(candidate.confidence() * 100) + "%<br>"
                + html(candidate.source().kind()) + " · "
                + html(candidate.source().sourceId()) + "<br>"
                + html(candidate.description()) + "</html>";
    }

    private static String candidateEvidence(EnrichmentProposal.IdentityCandidate candidate) {
        StringBuilder out = new StringBuilder();
        if (!candidate.aliases().isEmpty()) {
            out.append("Aliases: ").append(String.join(", ", candidate.aliases())).append('\n');
        }
        for (String evidence : candidate.evidence()) {
            out.append("✓ ").append(evidence).append('\n');
        }
        if (!blank(candidate.source().recordUrl())) {
            out.append("Source: ").append(candidate.source().recordUrl());
        }
        return out.toString().strip();
    }

    private static JPanel verticalPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        return panel;
    }

    private static JScrollPane scroll(Component component) {
        JScrollPane scroll = new JScrollPane(component);
        scroll.getVerticalScrollBar().setUnitIncrement(20);
        return scroll;
    }

    private static JTextArea textArea(String value) {
        JTextArea area = new JTextArea(value);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        return area;
    }

    private static JLabel message(String value) {
        JLabel label = new JLabel("<html><body style='width:500px'>"
                + html(value) + "</body></html>");
        label.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        return label;
    }

    private static String display(Object value) {
        return value == null ? "—" : String.valueOf(value);
    }

    private static String fileName(String url) {
        int slash = url.lastIndexOf('/');
        String result = slash >= 0 ? url.substring(slash + 1) : url;
        int query = result.indexOf('?');
        return query >= 0 ? result.substring(0, query) : result;
    }

    private static boolean isSvg(String url) {
        int end = url.length();
        int query = url.indexOf('?');
        int fragment = url.indexOf('#');
        if (query >= 0) end = Math.min(end, query);
        if (fragment >= 0) end = Math.min(end, fragment);
        return url.substring(0, end).toLowerCase(java.util.Locale.ROOT).endsWith(".svg");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String html(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

package domain.enrichment.ui;

import domain.datasource.SourceRef;
import domain.datasource.evidence.ExtractedClaim;
import domain.enrichment.BatchReviewDecision;
import domain.enrichment.EnrichmentDecision;
import domain.enrichment.EnrichmentProposal;
import domain.provenance.WikipediaCorrespondence;
import domain.provenance.WikipediaSource;
import domain.runtime.WikidataDynamicObject;
import domain.transform.DynamicViewable;

import objectview.Viewable;
import objectview.field.LinkedTextSequence;
import objectview.render.RenderingMode;
import objectview.view.SearchableView;

import process.swing.Dialogs;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/** Virtualized card review over every proposal produced by Load values. */
public final class FindDataBatchReviewPanel {

    private FindDataBatchReviewPanel() { }

    public static void showDialog(
            Component owner, String title, String prompt,
            List<EnrichmentProposal> proposals, Predicate<BatchReviewDecision> onDone) {
        showReview(owner, title, prompt, proposals, List.of(), onDone);
    }

    /**
     * @param sourceReport what each configured source yielded, as cards. Rendered
     *        alongside the proposals rather than in a separate window: "DBpedia was
     *        asked and had nothing" is the answer to why a member shows no value, and
     *        it belongs where the reader is already asking that.
     */
    public static JDialog showReview(
            Component owner, String title, String prompt,
            List<EnrichmentProposal> proposals, List<Viewable> sourceReport,
            Predicate<BatchReviewDecision> onDone) {
        return show(owner, title, prompt, proposals, sourceReport, onDone);
    }

    private static JDialog show(
            Component owner, String title, String prompt,
            List<EnrichmentProposal> proposals, List<Viewable> sourceReport,
            Predicate<BatchReviewDecision> onDone) {
        JDialog dialog = Dialogs.reviewDialog(owner, title);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setContentPane(reviewPanel(prompt, proposals, sourceReport, onDone,
                dialog::dispose));
        dialog.setSize(reviewSize(owner));
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        return dialog;
    }

    /** Proposal cards carry whole passages, so the review takes most of the usable area
     * of the screen it opens on rather than a fixed size that shows two rows. */
    static Dimension reviewSize(Component owner) {
        java.awt.GraphicsConfiguration screen = owner == null ? null
                : owner.getGraphicsConfiguration();
        java.awt.Rectangle usable;
        if (screen != null) {
            usable = screen.getBounds();
            java.awt.Insets insets = java.awt.Toolkit.getDefaultToolkit()
                    .getScreenInsets(screen);
            usable = new java.awt.Rectangle(usable.x + insets.left, usable.y + insets.top,
                    usable.width - insets.left - insets.right,
                    usable.height - insets.top - insets.bottom);
        } else if (!java.awt.GraphicsEnvironment.isHeadless()) {
            usable = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getMaximumWindowBounds();
        } else {
            return new Dimension(900, 680);
        }
        return reviewSize(usable.getSize());
    }

    static Dimension reviewSize(Dimension usable) {
        return new Dimension(Math.min(usable.width, Math.max(900, usable.width * 9 / 10)),
                Math.min(usable.height, Math.max(680, usable.height * 9 / 10)));
    }

    /** The review content without its dialog, so the staging controls can be exercised
     * headlessly. {@code close} runs after a successful stage and on Close. */
    static JPanel reviewPanel(
            String prompt, List<EnrichmentProposal> proposals, List<Viewable> sourceReport,
            Predicate<BatchReviewDecision> onDone, Runnable close) {
        Map<String, EnrichmentDecision> applicable = new LinkedHashMap<>();
        List<EnrichmentDecision> safeDefaults = new ArrayList<>();
        List<Viewable> cards = new ArrayList<>();
        int ordinal = 0;
        List<EnrichmentProposal> reviewProposals = (proposals == null
                ? List.<EnrichmentProposal>of() : proposals).stream()
                .flatMap(proposal -> splitInstanceCandidates(proposal).stream()).toList();
        for (EnrichmentProposal proposal : reviewProposals) {
            String id = cardId(proposal, ordinal++);
            EnrichmentDecision decision = EnrichmentDecision.acceptDefault(proposal);
            boolean overwrite = overwrites(proposal);
            DynamicViewable card = new DynamicViewable(id, proposal.subject().displayName());
            card.type(EnrichmentDecision.requiresIdentityChoice(proposal)
                    ? "Needs identity choice"
                    : corroborationOnly(decision) ? "Corroborated"
                    : sourceUpdateOnly(decision) ? "Source update"
                    : decision == null ? "Not found" : overwrite ? "Overwrite" : "Found");
            card.put("Outcome", card.typeName());
            card.put("Summary", detail(proposal));
            LinkedTextSequence proposalSource = proposalSource(proposal);
            if (proposalSource != null) card.put("Proposal source", proposalSource);
            List<Viewable> changes = changeCards(proposal);
            if (!changes.isEmpty()) card.put("Proposed changes", changes);
            cards.add(card);
            if (decision != null) {
                applicable.put(id, decision);
                // A new instance is never accepted by DEFAULT unless its provider marks the
                // candidate bulk-safe (a configured structural table row). The reader may
                // still stage any explicit selection of cards, including all of them.
                if (bulkSafeDefault(decision, overwrite)) {
                    safeDefaults.add(decision);
                }
            }
        }

        // Last, so the proposals a reader came to act on stay at the top; present even
        // when nothing was found, which is when the question they answer is asked.
        cards.addAll(sourceReport == null ? List.<Viewable>of() : sourceReport);

        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 10, 10, 10));
        // The explanation is read once; the cards are what the reader works through, so
        // it is a short scrollable strip rather than a block that pushes them down.
        javax.swing.JEditorPane explanation = new javax.swing.JEditorPane("text/html",
                "<html>" + CategorizedReviewPanel.html(prompt)
                + "<br><br>Select cards (Cmd/Ctrl-click, Shift-click or Select all) and "
                + "Stage selected applies and saves them, then refreshes the existing "
                + "instances/group view. Stage all safe results takes only fills and "
                + "instances produced by an exact configured structure, such as table "
                + "rows; overwrites and other new instances are staged only when you "
                + "select them.</html>");
        explanation.setEditable(false);
        explanation.setOpaque(false);
        explanation.putClientProperty(javax.swing.JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
        explanation.setFont(new JLabel().getFont());
        javax.swing.JScrollPane explanationScroll = new javax.swing.JScrollPane(explanation,
                javax.swing.JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                javax.swing.JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        explanationScroll.setBorder(javax.swing.BorderFactory.createEmptyBorder());
        int lines = explanation.getFontMetrics(explanation.getFont()).getHeight();
        explanationScroll.setPreferredSize(new Dimension(0, 5 * lines));
        panel.add(explanationScroll, BorderLayout.NORTH);
        JButton closeButton = new JButton("Close without staging");
        JButton selectAll = new JButton("Select all");
        JButton selectNone = new JButton("Select none");
        JButton selected = new JButton(stageSelectedLabel(0));
        selected.setName("review.stageSelected");
        selectAll.setName("review.selectAll");
        selected.setEnabled(false);
        JButton all = new JButton("Stage all safe results (" + safeDefaults.size() + ")");
        all.setEnabled(!safeDefaults.isEmpty());
        AtomicReference<List<EnrichmentDecision>> selectedDecisions =
                new AtomicReference<>(List.of());
        JPanel footer = new JPanel(new objectview.utils.swing.WrapLayout(
                FlowLayout.RIGHT, 8, 4));
        JLabel outcome = new JLabel(" ");
        if (!cards.isEmpty()) {
            SearchableView review = SearchableView.builder(cards)
                    .sample(cards.get(0)).mode(RenderingMode.CARD)
                    .collapsible(false).columns(2)
                    .valueLinker(wikidata.ui.WikidataLinks.valueLinker())
                    .selectionSetListener(selection -> {
                        List<EnrichmentDecision> decisions = stageable(selection, applicable);
                        selectedDecisions.set(decisions);
                        selected.setText(stageSelectedLabel(decisions.size()));
                        selected.setEnabled(!decisions.isEmpty());
                    }).build();
            panel.add(review, BorderLayout.CENTER);
            selectAll.addActionListener(e -> review.renderContext().selectAll());
            selectNone.addActionListener(e -> review.renderContext().clearSelection());
            footer.add(selectAll);
            footer.add(selectNone);
            // A one-result review should be actionable immediately. More generally,
            // the first proposal is what the reader is initially looking at; source
            // report cards follow it and are never staged.
            cards.stream()
                    .filter(card -> applicable.containsKey(card.getIdentifier()))
                    .findFirst().ifPresent(review.renderContext()::select);
        }
        footer.add(outcome);
        footer.add(closeButton); footer.add(selected); footer.add(all);
        panel.add(footer, BorderLayout.SOUTH);
        closeButton.addActionListener(e -> close.run());
        selected.addActionListener(e -> {
            if (accept(onDone, selectedDecisions.get(), outcome, selected, all)) close.run();
        });
        all.addActionListener(e -> {
            if (accept(onDone, safeDefaults, outcome, selected, all)) close.run();
        });
        return panel;
    }

    static String stageSelectedLabel(int count) {
        return count == 0 ? "Stage selected" : "Stage selected (" + count + ")";
    }

    /** The decisions behind the selected cards, in the order the reader selected them.
     * Source-report cards and cards with nothing to stage are skipped. */
    static List<EnrichmentDecision> stageable(
            List<Object> selection, Map<String, EnrichmentDecision> applicable) {
        List<EnrichmentDecision> result = new ArrayList<>();
        for (Object value : selection == null ? List.<Object>of() : selection) {
            if (value instanceof Viewable card) {
                EnrichmentDecision decision = applicable.get(card.getIdentifier());
                if (decision != null) result.add(decision);
            }
        }
        return List.copyOf(result);
    }

    static boolean bulkSafeDefault(EnrichmentDecision decision, boolean overwrite) {
        return decision != null && !overwrite && decision.instances().stream()
                .allMatch(EnrichmentProposal.InstanceCandidate::bulkSafe);
    }

    static boolean accept(Predicate<BatchReviewDecision> callback,
                          List<EnrichmentDecision> decisions, JLabel outcome,
                          JButton selected, JButton all) {
        if (decisions.isEmpty()) return false;
        if (!callback.test(new BatchReviewDecision(List.copyOf(decisions)))) {
            outcome.setText("Nothing staged; correct the reported problem and retry.");
            return false;
        }
        outcome.setText("Staged " + decisions.size() + " result"
                + (decisions.size() == 1 ? "" : "s")
                + ".");
        selected.setEnabled(false);
        all.setEnabled(false);
        return true;
    }

    private static String cardId(EnrichmentProposal proposal, int ordinal) {
        return proposal.subject().type() + '\u0000' + proposal.subject().targetId()
                + '\u0000' + ordinal;
    }

    /** One interpretive proposed instance is one review decision. Exact structural rows
     * are different: their configured table is one bounded field result and the provider
     * explicitly marks every row bulk-safe, so keep that result together. */
    static List<EnrichmentProposal> splitInstanceCandidates(EnrichmentProposal proposal) {
        if (proposal == null || proposal.instances().size() <= 1) {
            return proposal == null ? List.of() : List.of(proposal);
        }
        if (bulkSafeStructuralBatch(proposal)) return List.of(proposal);
        List<EnrichmentProposal> result = new ArrayList<>();
        for (EnrichmentProposal.InstanceCandidate instance : proposal.instances()) {
            List<EnrichmentProposal.FieldCandidate> fields = proposal.fields().stream()
                    .filter(field -> EnrichmentProposal.proposesInstance(field, instance))
                    .toList();
            result.add(new EnrichmentProposal(proposal.subject(), proposal.identities(),
                    fields, List.of(), List.of(instance)));
        }
        return List.copyOf(result);
    }

    private static boolean bulkSafeStructuralBatch(EnrichmentProposal proposal) {
        return proposal != null && !proposal.instances().isEmpty()
                && proposal.identities().isEmpty() && proposal.media().isEmpty()
                && proposal.instances().stream()
                        .allMatch(EnrichmentProposal.InstanceCandidate::bulkSafe);
    }

    private static boolean overwrites(EnrichmentProposal proposal) {
        EnrichmentDecision decision = EnrichmentDecision.acceptDefault(proposal);
        return decision != null && decision.fields().stream().anyMatch(
                field -> field.action() == EnrichmentProposal.ReviewAction.REPLACE
                        && !field.candidate().sourceUpdate());
    }

    private static boolean sourceUpdateOnly(EnrichmentDecision decision) {
        return decision != null && decision.media() == null && decision.instances().isEmpty()
                && !decision.fields().isEmpty()
                && decision.fields().stream().allMatch(field -> field.candidate().sourceUpdate());
    }

    private static boolean corroborationOnly(EnrichmentDecision decision) {
        return decision != null && decision.media() == null && !decision.fields().isEmpty()
                && decision.fields().stream().allMatch(field ->
                        field.action() == EnrichmentProposal.ReviewAction.CORROBORATE);
    }

    static String detail(EnrichmentProposal proposal) {
        if (EnrichmentDecision.requiresIdentityChoice(proposal)) {
            return "Multiple records from the same source are referenced; choose an identity "
                    + "before applying.";
        }
        EnrichmentDecision accepted = EnrichmentDecision.acceptDefault(proposal);
        if (accepted != null && accepted.instances().size() > 1
                && bulkSafeStructuralBatch(proposal)) {
            EnrichmentProposal.InstanceCandidate first = accepted.instances().getFirst();
            if (accepted.fields().isEmpty()) {
                return "Create " + accepted.instances().size() + " new " + first.type()
                        + " instances" + sourceSuffix(first.source());
            }
            String field = accepted.fields().getFirst().candidate().field();
            return field + ": add " + accepted.fields().size() + " member"
                    + (accepted.fields().size() == 1 ? "" : "s")
                    + " (create " + accepted.instances().size() + " new "
                    + first.type() + " instance"
                    + (accepted.instances().size() == 1 ? "" : "s") + ")"
                    + sourceSuffix(first.source());
        }
        if (accepted != null && !accepted.instances().isEmpty()) {
            EnrichmentProposal.InstanceCandidate instance = accepted.instances().getFirst();
            return "Create " + instance.type() + " “" + instance.displayName() + "”"
                    + sourceSuffix(instance.source());
        }
        if (accepted != null && !accepted.fields().isEmpty()) {
            EnrichmentProposal.FieldCandidate field = accepted.fields().get(0).candidate();
            if (field.sourceUpdate()) {
                return "Update the source for " + field.field() + " and keep "
                        + proposedValueCaption(field.proposedValue()) + sourceSuffix(field.source());
            }
            return (field.suggestedAction() == EnrichmentProposal.ReviewAction.CORROBORATE
                    ? field.field() + " already contains "
                            + proposedValueCaption(field.proposedValue())
                    : field.field() + " = "
                            + proposedValueCaption(field.proposedValue()))
                    + sourceSuffix(field.source());
        }
        if (accepted != null && accepted.media() != null) {
            EnrichmentProposal.MediaCandidate media = accepted.media();
            return media.field() + ": image" + sourceSuffix(media.source());
        }
        if (!proposal.fields().isEmpty()) {
            EnrichmentProposal.FieldCandidate field = proposal.fields().get(0);
            return field.field() + " rejected: " + field.compatibilityError()
                    + sourceSuffix(field.source());
        }
        return "No value found";
    }

    private static String sourceSuffix(SourceRef source) {
        if (source == null || source.kind() == null || source.kind().isBlank()) return "";
        String property = source.propertyId() == null || source.propertyId().isBlank()
                ? "" : ", " + source.propertyId();
        return " [" + source.kind() + property + "]";
    }

    static {
        // The fields of the review cards, stated once (#363).
        for (String outcome : List.of("Needs identity choice", "Corroborated", "Source update", "Not found",
                "Overwrite", "Found")) {
            objectview.field.RecordTypes.declare(outcome,
                    objectview.field.RecordTypes.text("Outcome"),
                    objectview.field.RecordTypes.text("Summary"),
                    objectview.field.RecordTypes.text("Proposal source"),
                    objectview.field.RecordTypes.records("Proposed changes", "Proposed value"));
        }
        objectview.field.RecordTypes.declare("Proposed value",
                objectview.field.RecordTypes.display("Proposal"),
                objectview.field.RecordTypes.text("Target field"),
                objectview.field.RecordTypes.value("Value"),
                objectview.field.RecordTypes.text("Action"),
                objectview.field.RecordTypes.text("Source"),
                objectview.field.RecordTypes.text("Semantic property"),
                objectview.field.RecordTypes.text("Member article"),
                objectview.field.RecordTypes.records("Evidence", "Evidence"),
                objectview.field.RecordTypes.text("Compatibility"));
        objectview.field.RecordTypes.declare("Proposed instance",
                objectview.field.RecordTypes.text("Class"),
                objectview.field.RecordTypes.text("Identifier"),
                objectview.field.RecordTypes.text("Display name"),
                objectview.field.RecordTypes.records("Fields", "Proposed value"),
                objectview.field.RecordTypes.text("Source"),
                objectview.field.RecordTypes.value("Source URL"),
                objectview.field.RecordTypes.records("Evidence", "Evidence"));
        objectview.field.RecordTypes.declare("Evidence",
                objectview.field.RecordTypes.text("Supporting text"),
                objectview.field.RecordTypes.text("Document"),
                objectview.field.RecordTypes.value("Source URL"),
                objectview.field.RecordTypes.text("Document version"),
                objectview.field.RecordTypes.value("Retrieved"),
                objectview.field.RecordTypes.text("Extraction"),
                objectview.field.RecordTypes.text("Confidence"),
                objectview.field.RecordTypes.text("Warnings"),
                objectview.field.RecordTypes.text("Claim ID"));
    }

    /** Every value that Apply will stage gets its own nested card and evidence list. */
    static List<Viewable> changeCards(EnrichmentProposal proposal) {
        List<Viewable> result = new ArrayList<>();
        String prefix = proposal.subject().type() + '-' + proposal.subject().targetId() + '-';
        int ordinal = 0;
        EnrichmentDecision accepted = EnrichmentDecision.acceptDefault(proposal);
        if (accepted != null && bulkSafeStructuralBatch(proposal)
                && !accepted.fields().isEmpty()) {
            return structuralMemberChangeCards(prefix, accepted);
        }
        List<EnrichmentProposal.FieldCandidate> fields = accepted == null
                ? proposal.fields() : accepted.fields().stream()
                .map(EnrichmentDecision.FieldDecision::candidate).toList();
        for (EnrichmentProposal.FieldCandidate field : fields) {
            DynamicViewable change = changeCard(prefix + "field-" + ordinal++, field.field(),
                    field.proposedValue(), field.source(),
                    field.sourceUpdate() ? "Update source" : field.suggestedAction().name(),
                    field.evidence());
            if (!field.compatible()) change.put("Compatibility", field.compatibilityError());
            result.add(change);
        }
        List<EnrichmentProposal.MediaCandidate> mediaCandidates = accepted == null
                ? proposal.media() : accepted.media() == null
                ? List.of() : List.of(accepted.media());
        for (EnrichmentProposal.MediaCandidate media : mediaCandidates) {
            result.add(changeCard(prefix + "media-" + ordinal++, media.field(), media.imageUrl(),
                    media.source(), "MEDIA", media.evidence()));
        }
        List<EnrichmentProposal.IdentityCandidate> identities = accepted == null
                ? proposal.identities() : accepted.identities();
        for (EnrichmentProposal.IdentityCandidate identity : identities) {
            if (identity.evidence().isEmpty()) continue;
            result.add(changeCard(prefix + "identity-" + ordinal++, "Identity",
                    identity.canonicalName(), identity.source(), "LINK",
                    identity.evidence()));
        }
        List<EnrichmentProposal.InstanceCandidate> instances = accepted == null
                ? proposal.instances() : accepted.instances();
        for (EnrichmentProposal.InstanceCandidate instance : instances) {
            DynamicViewable card = new DynamicViewable(
                    prefix + "instance-" + ordinal++, instance.displayName());
            card.type("Proposed instance");
            card.put("Class", instance.type());
            card.put("Identifier", instance.id());
            card.put("Display name", instance.displayName());
            List<Viewable> instanceFields = new ArrayList<>();
            int fieldOrdinal = 0;
            for (EnrichmentProposal.InstanceField field : instance.fields()) {
                Object value = field.references().isEmpty()
                        ? field.value() : field.references().stream()
                                .map(reference -> reference.type() + ":" + reference.id())
                                .toList();
                instanceFields.add(changeCard(
                        prefix + "instance-field-" + fieldOrdinal++, field.field(), value,
                        instance.source(), "SET", List.of()));
            }
            if (!instanceFields.isEmpty()) card.put("Fields", instanceFields);
            card.put("Source", instance.source().kind() + " — "
                    + instance.source().sourceId());
            if (!instance.source().recordUrl().isBlank()) {
                card.put("Source URL", instance.source().recordUrl());
            }
            List<Viewable> evidence = evidenceCards(card.getIdentifier(), instance.evidence());
            if (!evidence.isEmpty()) card.put("Evidence", evidence);
            result.add(card);
        }
        return List.copyOf(result);
    }

    /** One configured structural table proposes its visible members. Creating a missing
     * typed member is an implementation consequence of that member addition, not a
     * second user-visible change beside it. */
    private static List<Viewable> structuralMemberChangeCards(
            String prefix, EnrichmentDecision accepted) {
        List<EnrichmentProposal.FieldCandidate> fields = accepted.fields().stream()
                .map(EnrichmentDecision.FieldDecision::candidate).toList();
        List<Viewable> result = new ArrayList<>();
        int ordinal = 0;
        for (EnrichmentProposal.FieldCandidate field : fields) {
            result.add(changeCard(prefix + "structural-member-" + ordinal++,
                    field.field(), field.proposedValue(), field.source(),
                    field.suggestedAction().name(), field.evidence()));
        }
        return List.copyOf(result);
    }

    private static DynamicViewable changeCard(
            String id, String field, Object value, SourceRef source, String action,
            List<ExtractedClaim> claims) {
        DynamicViewable card = new DynamicViewable(
                id, field + " → " + proposedValueCaption(value));
        card.type("Proposed value");
        card.put("Proposal", proposalCaption(field, value, claims));
        card.put("Target field", field);
        card.put("Value", value);
        card.put("Action", action);
        card.put("Source", source.kind() + (source.sourceId().isBlank()
                ? "" : " — " + source.sourceId()));
        if (!source.propertyId().isBlank()) card.put("Semantic property", source.propertyId());
        LinkedTextSequence article = memberArticle(value, claims);
        if (article != null) card.put("Member article", article);
        List<Viewable> evidence = evidenceCards(id, claims);
        if (!evidence.isEmpty()) card.put("Evidence", evidence);
        return card;
    }

    /**
     * The member's own article: a loaded entity's English Wikipedia correspondence, or,
     * for a member the source itself defines (a table row), the link the page authored
     * on that member's name in the retained evidence. Null when the page shows none.
     */
    private static LinkedTextSequence memberArticle(
            Object value, List<ExtractedClaim> claims) {
        Object member = value;
        if (member instanceof Collection<?> values && values.size() == 1) {
            member = values.iterator().next();
        }
        if (member instanceof WikidataDynamicObject entity) {
            String title = WikipediaCorrespondence.title(entity);
            if (!title.isBlank()) {
                String url = new WikipediaSource(title).url();
                return link(url, url);
            }
        }
        if (claims == null) return null;
        List<domain.datasource.evidence.EvidenceFragment.Link> links = claims.stream()
                .flatMap(claim -> claim.evidence().stream())
                .flatMap(fragment -> fragment.links().stream()).toList();
        // Only a link the source structure placed on the member itself (the configured
        // display cell) is the member's article; another cell's link in the same row is not.
        for (domain.datasource.evidence.EvidenceFragment.Link authored : links) {
            if (authored.identifying()) return link(authored.url(), authored.url());
        }
        return null;
    }

    /** The supporting text with each authored link made clickable where it occurs. */
    static Object linkedExcerpt(domain.datasource.evidence.EvidenceFragment fragment) {
        String text = fragment.excerpt();
        if (fragment.links().isEmpty()) return text;
        List<LinkedTextSequence.Part> parts = new ArrayList<>();
        int at = 0;
        for (domain.datasource.evidence.EvidenceFragment.Link authored : fragment.links()) {
            int found = text.indexOf(authored.text(), at);
            if (found < 0) continue;
            if (found > at) parts.add(LinkedTextSequence.text(text.substring(at, found)));
            parts.add(LinkedTextSequence.link(authored.text(), authored.url()));
            at = found + authored.text().length();
        }
        if (parts.isEmpty()) return text;
        if (at < text.length()) parts.add(LinkedTextSequence.text(text.substring(at)));
        return LinkedTextSequence.of(parts.toArray(LinkedTextSequence.Part[]::new));
    }

    /** One result may contain many member contributions from one configured document.
     * Show each distinct proposal document once on the owning result card. */
    static LinkedTextSequence proposalSource(EnrichmentProposal proposal) {
        if (proposal == null) return null;
        java.util.LinkedHashSet<String> urls = new java.util.LinkedHashSet<>();
        for (var field : proposal.fields()) {
            var source = field.source();
            var documents = field.evidence().stream().flatMap(claim -> claim.evidence().stream())
                    .map(fragment -> fragment.document().url()).filter(url -> !url.isBlank()).toList();
            if (source.sourceId().isBlank() && !documents.isEmpty()) urls.addAll(documents);
            else if (!source.recordUrl().isBlank()) urls.add(source.recordUrl());
        }
        proposal.instances().stream().map(EnrichmentProposal.InstanceCandidate::source)
                .map(SourceRef::recordUrl).filter(url -> !url.isBlank()).forEach(urls::add);
        proposal.identities().stream().map(EnrichmentProposal.IdentityCandidate::source)
                .map(SourceRef::recordUrl).filter(url -> !url.isBlank()).forEach(urls::add);
        proposal.media().stream().map(EnrichmentProposal.MediaCandidate::source)
                .map(SourceRef::recordUrl).filter(url -> !url.isBlank()).forEach(urls::add);
        if (urls.isEmpty()) return null;
        List<LinkedTextSequence.Part> parts = new ArrayList<>();
        for (String url : urls) {
            if (!parts.isEmpty()) parts.add(LinkedTextSequence.text(" · "));
            parts.add(LinkedTextSequence.link(url, url));
        }
        return LinkedTextSequence.of(parts.toArray(LinkedTextSequence.Part[]::new));
    }

    /** A visible member row links only to that member's own article, when one is known.
     * The common proposal source belongs to the owning result card. */
    private static LinkedTextSequence proposalCaption(
            String field, Object value, List<ExtractedClaim> claims) {
        String member = proposedValueCaption(value);
        LinkedTextSequence article = memberArticle(value, claims);
        if (article == null || article.urls().isEmpty()) {
            return LinkedTextSequence.of(
                    LinkedTextSequence.text(field + " → " + member));
        }
        // The member's name is the link, as on the page; hovering it shows the URL.
        return LinkedTextSequence.of(
                LinkedTextSequence.text(field + " → "),
                LinkedTextSequence.link(member, article.urls().getFirst()));
    }

    private static LinkedTextSequence link(String label, String url) {
        return LinkedTextSequence.of(LinkedTextSequence.link(label, url));
    }

    /** A collapsed Proposed changes collection must still say what each entry proposes.
     *  Keep the original object in {@code Value} for ObjectView navigation; this is only
     *  the nested change card's concise caption. */
    private static String proposedValueCaption(Object value) {
        if (value instanceof Viewable viewable) {
            String display = viewable.getDisplayName();
            return display == null || display.isBlank()
                    ? viewable.getIdentifier() : display;
        }
        if (value instanceof java.util.Collection<?> values) {
            if (values.isEmpty()) return "(empty collection)";
            String joined = values.stream().limit(3)
                    .map(FindDataBatchReviewPanel::proposedValueCaption)
                    .collect(java.util.stream.Collectors.joining(", "));
            return joined + (values.size() > 3 ? ", …" : "");
        }
        return value == null ? "(empty)" : String.valueOf(value);
    }

    private static List<Viewable> evidenceCards(
            String parentId, List<domain.datasource.evidence.ExtractedClaim> claims) {
        List<Viewable> cards = new ArrayList<>();
        int ordinal = 0;
        for (domain.datasource.evidence.ExtractedClaim claim : claims) {
            for (domain.datasource.evidence.EvidenceFragment fragment : claim.evidence()) {
                DynamicViewable evidence = new DynamicViewable(
                        parentId + "-evidence-" + ordinal++, fragment.section().isBlank()
                        ? fragment.document().title() : fragment.section());
                evidence.type("Evidence");
                evidence.put("Supporting text", linkedExcerpt(fragment));
                evidence.put("Document", fragment.document().title().isBlank()
                        ? fragment.document().documentId() : fragment.document().title());
                if (!fragment.document().url().isBlank()) {
                    evidence.put("Source URL", link(fragment.document().url(), fragment.document().url()));
                }
                evidence.put("Document version", fragment.document().versionId());
                evidence.put("Retrieved", fragment.document().retrievedAt());
                evidence.put("Extraction", claim.extractionMethod()
                        + " / " + claim.recipeVersion());
                evidence.put("Confidence", Math.round(claim.confidence() * 100) + "%");
                if (!claim.warnings().isEmpty()) {
                    evidence.put("Warnings", String.join("; ", claim.warnings()));
                }
                evidence.put("Claim ID", claim.claimId());
                cards.add(evidence);
            }
        }
        return List.copyOf(cards);
    }
}

package wikidata.explore.workbench;

import objectview.render.Card;
import objectview.render.RenderContext;
import objectview.utils.swing.GridBagUtils;
import objectview.viewconfig.ViewConfig;
import quiz.Quizable;
import wikidata.api.WikidataApiClient;
import wikidata.explore.extract.SourceContentResolver;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.Source;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Browse a {@link Source}'s CONTENT (slice 2 of the Source construct). A Source is
 * never a served product, but its content is inspectable: pick a Source, resolve
 * its members (labelled), and render them as the same cards used everywhere else —
 * so making the Oscar categories a vocabulary hides nothing, it just stops them
 * being a class. Reuses the shared Card rendering rather than a bespoke widget.
 */
public class SourceViewerPanel extends JPanel {

    private final GeneratedProjectModel project;
    private final WikidataApiClient api;

    private final JComboBox<String> sourceBox = new JComboBox<>();
    private final JButton showButton = new JButton("Show content");
    private final JLabel status = new JLabel(" ");
    private final JPanel cards = new JPanel(new GridBagLayout());
    private final JScrollPane cardsScroll = new JScrollPane(cards);

    private final JTextField newNameField = new JTextField(14);
    private final JTextField newQidsField = new JTextField(24);
    private final JButton addButton = new JButton("Add & show");

    public SourceViewerPanel(GeneratedProjectModel project, WikidataApiClient api) {
        super(new BorderLayout(6, 6));
        this.project = project;
        this.api = api;
        cardsScroll.getVerticalScrollBar().setUnitIncrement(18);
        cardsScroll.setPreferredSize(new Dimension(460, 520));

        JPanel viewRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        viewRow.add(new JLabel("Source:"));
        viewRow.add(sourceBox);
        viewRow.add(showButton);

        // Inline "declare a vocabulary": a name + QIDs, so the construct is usable
        // without a separate editor — paste the Oscar categories, see them at once.
        JPanel newRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        newRow.add(new JLabel("New vocabulary:"));
        newRow.add(newNameField);
        newRow.add(new JLabel("QIDs:"));
        newRow.add(newQidsField);
        newRow.add(addButton);
        newQidsField.setToolTipText("Comma- or space-separated QIDs, e.g. Q102427 Q106301");

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(viewRow);
        top.add(newRow);

        add(top, BorderLayout.NORTH);
        add(cardsScroll, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);

        showButton.addActionListener(e -> showSelected());
        addButton.addActionListener(e -> addVocabulary());
    }

    private void addVocabulary() {
        String name = newNameField.getText().trim();
        if (name.isBlank()) {
            status.setText("Give the vocabulary a name.");
            return;
        }
        java.util.List<String> qids = new java.util.ArrayList<>();
        for (String tok : newQidsField.getText().split("[,\\s]+")) {
            if (tok != null && tok.trim().matches("(?i)Q\\d+")) {
                qids.add(tok.trim());
            }
        }
        if (qids.isEmpty()) {
            status.setText("Enter at least one QID (e.g. Q102427).");
            return;
        }
        Source s = new Source(name, Source.Kind.VOCABULARY);
        s.valueQids(qids);
        project.addSource(s);
        newNameField.setText("");
        newQidsField.setText("");
        refreshSources();
        sourceBox.setSelectedItem(name + "  [" + Source.Kind.VOCABULARY + "]");
        showSelected();
    }

    /** Re-read the project's sources into the selector — call when the window opens,
     *  since sources can be added/edited while it's closed. */
    public void refreshSources() {
        String previous = (String) sourceBox.getSelectedItem();
        sourceBox.removeAllItems();
        for (Source s : project.sources()) {
            sourceBox.addItem(s.name() + "  [" + s.kind() + "]");
        }
        if (previous != null) {
            sourceBox.setSelectedItem(previous);
        }
        boolean any = sourceBox.getItemCount() > 0;
        showButton.setEnabled(any);
        if (!any) {
            cards.removeAll();
            cards.revalidate();
            cards.repaint();
            status.setText("No sources declared in this domain.");
        }
    }

    private void showSelected() {
        int i = sourceBox.getSelectedIndex();
        if (i < 0 || i >= project.sources().size()) {
            return;
        }
        Source source = project.sources().get(i);
        status.setText("Resolving \"" + source.name() + "\" …");
        showButton.setEnabled(false);
        new SwingWorker<List<WikidataDynamicObject>, Void>() {
            @Override
            protected List<WikidataDynamicObject> doInBackground() {
                return new SourceContentResolver().resolve(source, api, null);
            }

            @Override
            protected void done() {
                List<WikidataDynamicObject> content;
                try {
                    content = get();
                } catch (Exception ex) {
                    content = new ArrayList<>();
                }
                render(source, content);
                showButton.setEnabled(true);
            }
        }.execute();
    }

    private void render(Source source, List<WikidataDynamicObject> content) {
        cards.removeAll();

        List<Quizable> quizables = new ArrayList<>(content);
        RenderContext context = new RenderContext(quizables);

        ViewConfig config = ViewConfig.of(WikidataDynamicObject.class);
        config.setAllFields(false);
        config.setThumb(false);
        config.setAddListener(false);
        config.addField("name", ViewConfig.leaf());
        context.putClassConfig(WikidataDynamicObject.class, config);

        int row = 0;
        for (WikidataDynamicObject o : content) {
            Card card = new Card(o, config.copy(), context, false);
            context.registerTopLevel(o, card);
            card.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createEmptyBorder(2, 2, 2, 2),
                    BorderFactory.createLineBorder(Color.LIGHT_GRAY)));
            GridBagUtils.stackedCard(cards, row++, card);
        }
        GridBagUtils.verticalGlue(cards, row);

        cards.revalidate();
        cards.repaint();
        status.setText(source.name() + " [" + source.kind() + "]: "
                + content.size() + " member(s)");
    }
}

package wikidata.explore;

import wikidata.WikidataTripleSample;
import wikidata.rule.WikidataRuleSpec;
import wikidata.rule.WikidataRuleSpecStore;

import javax.swing.*;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class WorkbenchState {

    private final File specFile;
    private WikidataTripleSample selectedValue;

    public WikidataTripleSample selectedValue() {
        return selectedValue;
    }

    public void selectedValue(WikidataTripleSample selectedValue) {
        this.selectedValue = selectedValue;
    }

    public final JTextField rootNameField = new JTextField("Taurus");
    public final JTextField rootQidField = new JTextField("Q10570");
    public final JTextField ruleNameField = new JTextField("stars");
    public final JTextField itemVarField = new JTextField("item");
    public final JTextField limitField = new JTextField("200");
    public final JTextField manualTypeQidField = new JTextField("Q523");
    public final JTextField manualTypeLabelField = new JTextField("star");

    public final JCheckBox requireLabelBox =
            new JCheckBox("Require English label", true);

    public final JTextField minLengthKmField =
            new JTextField("", 8);

    public final JTextField minAreaKm2Field =
            new JTextField("", 8);

    public final JComboBox<String> directionBox =
            new JComboBox<>(new String[] {
                    "ITEM_TO_ROOT",
                    "ROOT_TO_ITEM"
            });

    public final JCheckBox subclassClosureBox =
            new JCheckBox("Use subclass closure", false);

    public final DefaultListModel<WikidataTripleSample> typesModel =
            new DefaultListModel<>();

    public final JList<WikidataTripleSample> typesList =
            new JList<>(typesModel);

    public final DefaultListModel<WikidataRuleSpec> specsModel =
            new DefaultListModel<>();

    public final JList<WikidataRuleSpec> specsList =
            new JList<>(specsModel);

    public final List<WikidataRuleSpec> specs = new ArrayList<>();
    public final Deque<RootRef> history = new ArrayDeque<>();

    private WikidataTripleSample selectedRelation;

    public record RootRef(String name, String qid) {}

    public WorkbenchState(File specFile) throws Exception {
        this.specFile = specFile;

        specs.addAll(WikidataRuleSpecStore.read(specFile));
        for (WikidataRuleSpec s : specs) {
            specsModel.addElement(s);
        }

        typesList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        specsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    }

    public File specFile() {
        return specFile;
    }

    public String rootName() {
        return rootNameField.getText().trim();
    }

    public String rootQid() {
        String qid = rootQidField.getText().trim();

        if (qid.startsWith("wd:")) {
            qid = qid.substring(3);
        }

        if (!qid.matches("Q\\d+")) {
            throw new IllegalArgumentException(
                    "Root QID must look like Q10570, not: " + qid);
        }

        return qid;
    }

    public int queryLimit() {
        try {
            return Math.max(1, Integer.parseInt(limitField.getText().trim()));
        } catch (Exception e) {
            return 200;
        }
    }

    public WikidataTripleSample selectedRelation() {
        return selectedRelation;
    }

    public void selectedRelation(WikidataTripleSample selectedRelation) {
        this.selectedRelation = selectedRelation;

        if (selectedRelation != null) {
            directionBox.setSelectedItem(selectedRelation.direction());
        }
    }

    public Double minLengthKm() {
        return parseOptionalDouble(minLengthKmField.getText());
    }

    public Double minAreaKm2() {
        return parseOptionalDouble(minAreaKm2Field.getText());
    }

    private static Double parseOptionalDouble(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }

        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
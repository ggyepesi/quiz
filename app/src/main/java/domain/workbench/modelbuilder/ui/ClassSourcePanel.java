package domain.workbench.modelbuilder.ui;

import wikidata.WikidataIds;

import objectview.utils.swing.GridBagUtils;
import domain.workbench.ui.SimpleDocumentListener;

import domain.compile.rule.RuleTreeCompiler;
import domain.compile.rule.RuleNode;
import domain.config.RuleDirection;
import domain.config.CanonicalSpec;
import domain.config.ClassConfigurationProjection;
import domain.config.ClassKind;
import domain.config.ClassSourceBindings;
import domain.config.EntityBound;
import domain.config.FieldSourceMapping;
import domain.datasource.schema.FieldType;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedFieldModel;
import domain.config.GeneratedProjectModel;
import domain.config.EntityRepresentationRule;
import domain.config.EntityKindRule;
import domain.config.MembershipPattern;
import domain.config.StatementClassSource;
import domain.datasource.api.SourceBindingSlot;
import wikidata.explore.query.logical.ClassSearchQuery;
import wikidata.explore.query.result.TableQueryResult;
import domain.workbench.execution.ui.SwingQueryRunner;
import wikidata.api.WikidataApiClient;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import wikidata.ui.WikidataLinks;

public class ClassSourcePanel extends JPanel {
    private final ClassConfigurationEditor configuration =
            new ClassConfigurationEditor(ClassKind.SOURCE);

    private final JComboBox<ClassSearchQuery.Mode> searchModeBox =
            new JComboBox<>(ClassSearchQuery.Mode.values());

    private GeneratedClassModel clazz;
    private GeneratedProjectModel projectModel;
    private SwingQueryRunner queryRunner;

    private Consumer<String> log = s -> {};
    private Consumer<Void> afterChange = v -> {};

    private final JLabel titleLabel = new JLabel("Class");

    private final ClassHeaderEditor header =
            new ClassHeaderEditor(() -> projectModel);
    // Subclass discriminator: a (property, value) pair narrowing the inherited
    // membership to instances that also have ?value wdt:<pid> wd:<qid> (Person =
    // nominee membership AND P31=human). Property defaults to P31 but can be any.
    private final JTextField discriminatorPidField = new JTextField(MembershipPattern.INSTANCE_OF, 5);
    private final JTextField discriminatorQidField = new JTextField(8);
    private final JLabel discriminatorEquals = new JLabel("=");
    private final JCheckBox discriminatorIncludesDescendants =
            new JCheckBox("Include subclasses");
    private static final String CONDITION_PROPERTY = "Property contains QID";
    private static final String CONDITION_IN_POPULATION = "QID is in population";
    private static final String CONDITION_OUTSIDE_POPULATION = "QID is outside population";
    private final JComboBox<String> subclassConditionMode = new JComboBox<>(new String[] {
            CONDITION_PROPERTY, CONDITION_IN_POPULATION, CONDITION_OUTSIDE_POPULATION});
    private final JComboBox<String> subclassPopulation = new JComboBox<>();
    private final JLabel inheritedPopulationFilterLabel =
            new JLabel("Inherited population filter:");
    private final JPanel inheritedPopulationFilter =
            new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    // Ordered: a role's alternatives are tried in order, so position IS the meaning.
    private final OrderedChoiceList<String> representations = new OrderedChoiceList<>(true);
    // STATEMENT reification: instances of this class are the statements of the
    // relation property on each member of the named class (e.g. Nomination = the
    // P1411 statements of Oscarnominations). Its fields draw from the value (ps:)
    // and qualifiers (pq:, set per-field). Blank = a normal class.

    // Subject, property and objects in one component, because that is one triple. The
    // property and the QIDs were three controls over one bound, and the QID row was
    // labelled by asking whether the property was P31.
    private final TripleEditor triple = new TripleEditor();
    // One answer to "where do this class's instances come from": the Wikidata
    // population triple, or one other registered class-population operation. The
    // chooser lists both; only the chosen source's controls are shown, so the triple
    // and a category can never both look configured.
    private static final String WIKIDATA_POPULATION = "Wikidata — population triple";
    private final domain.workbench.ui.DatasourceOperationForm alternativePopulationSource =
            new domain.workbench.ui.DatasourceOperationForm(
                    domain.datasource.Datasources.standard(),
                    domain.datasource.api.BindingScope.CLASS_POPULATION,
                    recipe -> !domain.datasource.wikidata.WikidataDatasourceProvider.ID.equals(
                            recipe.providerId()));
    private final JComboBox<Object> populationSource = new JComboBox<>();
    private final JLabel populationReplaces = new JLabel();
    private final JLabel excludeTypesLabel = new JLabel("Exclude instances of:");
    private final JLabel ownPopulationLimitLabel = new JLabel("Own population limit:");
    private final JLabel rankByLabel = new JLabel("Rank by:");
    private final JPanel rankRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private JComponent excludeTypesRow;
    private final JButton findRelationButton = new JButton("Find…");
    // Lazily-created; the property/item name search uses the Wikidata API.
    private WikidataApiClient api;
    private final JTextField excludeTypesField = new JTextField(14);
    // Discover the membership targets from a parent's "has part(s)" relation —
    // e.g. Academy Awards (Q19020) wdt:P527 → its award categories — so the
    // multi-QID membership is data-driven instead of a hand-pasted QID list.
    private final JButton fromPartsButton = new JButton("Find related objects…");
    private final JCheckBox notableOnlyBox =
            new JCheckBox("Notable only (require Wikipedia article)");

    // Class-level ranking: keep the top `limit` instances by this measure.
    private static final String NO_RANK = "(none — by name)";
    private static final String RANK_NOTABILITY = "Notability (sitelinks)";
    private final JComboBox<String> rankByBox = new JComboBox<>();
    private final JCheckBox rankDescBox = new JCheckBox("highest first", true);

    // Explicit instance QIDs (curated set, e.g. seeded from a WikiProject /
    // category). With no type above, these alone are the class's instances.
    private final JTextArea seedQidsArea = new JTextArea(3, 18);
    private final JComponent seedQidsEditor = WikidataIdLines.qidList(seedQidsArea);

    private final JTextField searchTextField = new JTextField(18);
    private final JButton searchTypeButton = new JButton("Search");

    private final SearchTableModel searchModel = new SearchTableModel();
    private final JTable searchTable = new JTable(searchModel);
    private final JButton useSelectedButton = new JButton("Use selected");

    private final JSpinner limitSpinner =
            new JSpinner(new SpinnerNumberModel(200, 1, 1_000_000, 10));

    private final JCheckBox requireLabelBox =
            new JCheckBox("Require label", true);

    private final JTextField langField =
            new JTextField(wikidata.WikidataLanguageDefaults.CODE, 4);

    // --- Identity & label (canonicalization) ---
    private static final String KIND_ENTITY = "Source entity (id + label)";
    private static final String KIND_DERIVED = "Derived (key + field/template)";
    /** States the regime rather than offering it: it is a consequence, not a choice. */
    private final javax.swing.JLabel canonicalKindLabel = new javax.swing.JLabel();
    private final javax.swing.JLabel canonicalSourcesLabel = new javax.swing.JLabel();
    private final JCheckBox aliasesBox = new JCheckBox("Add aliases (Also known as)");
    private final JCheckBox wikipediaArticleBox =
            new JCheckBox("Add English Wikipedia source link");
    private static final String NOT_A_GROUP = "(not a group)";
    private final JComboBox<String> groupMembersField = new JComboBox<>();
    private boolean shownAliases;
    private boolean shownWikipediaArticle;
    private boolean configurationDirty;
    // Mode, field and template, asked the way every kind now asks them.
    private final DisplayNameEditor displayNameEditor = new DisplayNameEditor();
    // The same editor every other construct uses. This was a space-separated text
    // field, which could not show that a SOURCE class keys on its source identity, and
    // could not keep an order — both of which identity depends on.
    private final ClassIdentityEditor identityEditor = new ClassIdentityEditor();


    private final JLabel summaryLabel =
            new JLabel(" ");

    public ClassSourcePanel() {
        super(new BorderLayout(4, 4));
        buildUi();
        alternativePopulationSource.setName("class.alternativePopulationSource");
        alternativePopulationSource.operationChooserVisible(false);
        alternativePopulationSource.onChange(() -> configurationDirty = true);
        populationSource.setName("class.populationSource");
        seedQidsEditor.setName("class.seedQids");
        populationSource.addItem(WIKIDATA_POPULATION);
        alternativePopulationSource.choices().forEach(populationSource::addItem);
        populationSource.addActionListener(event -> {
            showChosenPopulationSource();
            configurationDirty = true;
        });
        showChosenPopulationSource();
        watchConfiguration(header);
        watchConfiguration(triple);
        watchConfiguration(representations);
        watchConfiguration(excludeTypesField);
        watchConfiguration(limitSpinner);
        watchConfiguration(requireLabelBox);
        watchConfiguration(notableOnlyBox);
        watchConfiguration(langField);
        watchConfiguration(seedQidsArea);
        watchConfiguration(rankByBox);
        watchConfiguration(rankDescBox);
        watchConfiguration(aliasesBox);
        watchConfiguration(wikipediaArticleBox);
        watchConfiguration(groupMembersField);
        watchConfiguration(displayNameEditor);
        watchConfiguration(identityEditor);
    }

    public void log(Consumer<String> log) {
        this.log = log == null ? s -> {} : log;
    }

    public void setProjectModel(GeneratedProjectModel projectModel) {
        this.projectModel = projectModel;
    }

    /** Fill the class population relation from the explicit workbench selection. */
    public void usePopulationProperty(String pid, String label) {
        if (!WikidataIds.isPid(pid)
                || !configuration.tree().selectDetail(ClassConfigurationProjection.PRODUCTION)) {
            return;
        }
        triple.propertyPid(pid, label == null || label.isBlank() ? pid : label);
    }

    /**
     * One-shot: the search button's action listener binds to the first
     * runner's workflow, so later calls are ignored.
     */
    public void setQueryRunner(SwingQueryRunner queryRunner) {
        if (this.queryRunner != null || queryRunner == null) {
            return;
        }

        this.queryRunner = queryRunner;
        wireQuery();
        updateSearchButtonState();
    }

    public void afterChange(Consumer<Void> afterChange) {
        this.afterChange = afterChange == null ? v -> {} : afterChange;
    }

    public void edit(GeneratedClassModel clazz) {
        this.clazz = clazz;

        if (clazz == null) {
            clear();
            refreshConfiguration();
            return;
        }

        FieldSourceMapping m = clazz.instanceMapping();

        titleLabel.setText("Class: " + clazz.className());

        header.show(clazz);
        searchTextField.setText(clazz.className());

        populateClassDetails();
        populateGroupMembersField();
        populateRepresentations();

        // The property's label is set AFTER the PID, whose document listener blanks it
        // — a saved relation must still show its name on load.
        triple.show(clazz, projectModel);
        showPopulationSource(ClassSourceBindings.alternativePopulationSource(clazz));
        excludeTypesField.setText(String.join(" ", m.excludedTypeQids()));

        limitSpinner.setValue(Math.max(1, m.limit()));
        requireLabelBox.setSelected(m.requireLabel());
        notableOnlyBox.setSelected(m.requireSitelink());
        langField.setText(m.labelLanguage());
        seedQidsArea.setText(String.join(" ", clazz.seedQids()));
        populateRankBy(m);
        loadCanonical();

        updateSummary();
        updateSearchButtonState();
        configurationDirty = false;
        refreshConfiguration();
    }

    // Fill the extends combo with the other classes (excluding self), selecting
    // this class's current base.
    private void populateClassDetails() {
        domain.config.SubclassCondition condition = clazz == null
                ? domain.config.SubclassCondition.none()
                : clazz.subclassCondition();
        discriminatorPidField.setText(condition.kind()
                == domain.config.SubclassCondition.Kind.PROPERTY_VALUE
                ? condition.propertyPid() : MembershipPattern.INSTANCE_OF);
        discriminatorQidField.setText(condition.kind()
                == domain.config.SubclassCondition.Kind.PROPERTY_VALUE
                ? condition.qid() : "");
        discriminatorIncludesDescendants.setSelected(condition.kind()
                == domain.config.SubclassCondition.Kind.PROPERTY_VALUE
                && condition.includeDescendants());
        subclassPopulation.removeAllItems();
        if (projectModel != null) projectModel.selections().stream()
                .filter(domain.config.PopulationSelection.class::isInstance)
                .map(domain.config.Selection::name)
                .forEach(subclassPopulation::addItem);
        if (condition.populationBased()) {
            subclassConditionMode.setSelectedItem(condition.kind()
                    == domain.config.SubclassCondition.Kind.IN_POPULATION
                    ? CONDITION_IN_POPULATION : CONDITION_OUTSIDE_POPULATION);
            subclassPopulation.setSelectedItem(condition.selectionName());
        } else {
            subclassConditionMode.setSelectedItem(CONDITION_PROPERTY);
        }
        updateSubclassConditionControls();
        showInheritedPopulationFilter(clazz != null && !clazz.baseClassName().isBlank());
    }

    /** Selects {@code recipe}'s operation in the one chooser, or the Wikidata triple. */
    private void showPopulationSource(domain.datasource.api.SourceRecipe recipe) {
        Object chosen = WIKIDATA_POPULATION;
        for (domain.workbench.ui.DatasourceOperationForm.Choice choice
                : alternativePopulationSource.choices()) {
            if (recipe != null && choice.provider().id().equals(recipe.providerId())
                    && choice.operation().id().equals(recipe.operationId())) {
                chosen = choice;
            }
        }
        java.awt.event.ActionListener[] listeners = populationSource.getActionListeners();
        for (java.awt.event.ActionListener listener : listeners) {
            populationSource.removeActionListener(listener);
        }
        populationSource.setSelectedItem(chosen);
        for (java.awt.event.ActionListener listener : listeners) {
            populationSource.addActionListener(listener);
        }
        alternativePopulationSource.show(chosen == WIKIDATA_POPULATION ? null : recipe);
        showChosenPopulationSource();
    }

    /** Shows only the controls of the chosen population source. The Wikidata-only
     * query knobs do not apply to another datasource's exact identities, so they are
     * not shown as if they did. */
    private void showChosenPopulationSource() {
        Object chosen = populationSource.getSelectedItem();
        boolean wikidata = chosen == null || chosen == WIKIDATA_POPULATION;
        if (!wikidata && chosen instanceof domain.workbench.ui.DatasourceOperationForm.Choice choice) {
            domain.datasource.api.SourceRecipe current = alternativePopulationSource.recipe();
            boolean same = current != null
                    && current.providerId().equals(choice.provider().id())
                    && current.operationId().equals(choice.operation().id());
            if (!same) {
                alternativePopulationSource.show(new domain.datasource.api.SourceRecipe(
                        choice.provider().id(), choice.operation().id(),
                        java.util.Map.of()));
            }
        } else if (wikidata) {
            alternativePopulationSource.show(null);
        }
        String replaces = wikidata ? "" : replacedWikidataPopulation(
                triple.hasMembershipDraft(), seedQidsDraft().size());
        populationReplaces.setText(replaces);
        populationReplaces.setVisible(!replaces.isEmpty());
        alternativePopulationSource.setVisible(!wikidata);
        triple.setVisible(wikidata);
        excludeTypesLabel.setVisible(wikidata);
        if (excludeTypesRow != null) excludeTypesRow.setVisible(wikidata);
        ownPopulationLimitLabel.setVisible(wikidata);
        limitSpinner.setVisible(wikidata);
        requireLabelBox.setVisible(wikidata);
        notableOnlyBox.setVisible(wikidata);
        rankByLabel.setVisible(wikidata);
        rankRow.setVisible(wikidata);
        revalidate();
        repaint();
    }

    /** What Apply discards when another datasource replaces the Wikidata population,
     * or "" when the visible draft has none. Said before Apply, not only in a tooltip. */
    private static String replacedWikidataPopulation(
            boolean membership, int seeds) {
        if (!membership && seeds == 0) return "";
        String what = membership && seeds > 0
                ? "its Wikidata membership and " + seeds + " seed QID(s)"
                : membership ? "its Wikidata membership" : seeds + " seed QID(s)";
        return "Apply replaces " + what + "; choosing the Wikidata triple again later "
                + "starts empty.";
    }

    /** The valid seed identities currently visible in the editor. Apply and its
     * replacement warning read this same draft. */
    private java.util.List<String> seedQidsDraft() {
        java.util.List<String> result = new java.util.ArrayList<>();
        for (String token : seedQidsArea.getText().trim().split("[,;\\s]+")) {
            String qid = RuleNode.cleanQid(token);
            if (WikidataIds.isQid(qid) && !result.contains(qid)) result.add(qid);
        }
        return result;
    }

    private void populateGroupMembersField() {
        groupMembersField.removeAllItems();
        groupMembersField.addItem(NOT_A_GROUP);
        if (clazz == null) return;
        for (GeneratedFieldModel field : clazz.effectiveFields(projectModel)) {
            if (field != null
                    && field.type() == FieldType.ENTITY
                    && field.cardinality() == domain.config.FieldCardinality.COLLECTION) {
                groupMembersField.addItem(field.name());
            }
        }
        if (clazz.groupSpec() != null) {
            String configured = clazz.groupSpec().membersField();
            boolean offered = false;
            for (int i = 0; i < groupMembersField.getItemCount(); i++) {
                if (configured.equals(groupMembersField.getItemAt(i))) {
                    offered = true;
                    break;
                }
            }
            if (!offered) groupMembersField.addItem(configured);
            groupMembersField.setSelectedItem(configured);
        } else {
            groupMembersField.setSelectedItem(NOT_A_GROUP);
        }
    }

    private void updateSubclassConditionControls() {
        boolean property = CONDITION_PROPERTY.equals(
                subclassConditionMode.getSelectedItem());
        discriminatorPidField.setVisible(property);
        discriminatorEquals.setVisible(property);
        discriminatorQidField.setVisible(property);
        discriminatorIncludesDescendants.setVisible(property);
        subclassPopulation.setVisible(!property);
        inheritedPopulationFilter.revalidate();
        inheritedPopulationFilter.repaint();
    }

    private void showInheritedPopulationFilter(boolean visible) {
        inheritedPopulationFilterLabel.setVisible(visible);
        inheritedPopulationFilter.setVisible(visible);
        revalidate();
        repaint();
    }

    // Rank-by options: none, notability, and the class's sortable (number/date)
    // fields by name.
    private void populateRankBy(FieldSourceMapping m) {
        rankByBox.removeAllItems();
        rankByBox.addItem(NO_RANK);
        rankByBox.addItem(RANK_NOTABILITY);
        if (clazz != null) {
            for (GeneratedFieldModel f : clazz.fields()) {
                if (f != null && !f.isNameField()
                        && (f.type() == FieldType.NUMBER || f.type() == FieldType.DATE)) {
                    rankByBox.addItem(f.name());
                }
            }
        }
        String rb = m.rankBy();
        if (FieldSourceMapping.RANK_BY_SITELINKS.equals(rb)) {
            rankByBox.setSelectedItem(RANK_NOTABILITY);
        } else if (rb != null && !rb.isBlank()) {
            rankByBox.setSelectedItem(rb);
        } else {
            rankByBox.setSelectedItem(NO_RANK);
        }
        rankDescBox.setSelected(m.rankDescending());
    }

    public void useSourceQid(String qid, String label) {
        if (clazz == null) {
            return;
        }

        // The leading target, keeping the rest — the editor shows the first as
        // "Wikidata type" and the others as "Also include types", which is what this
        // replaced when the first one was a field of its own.
        java.util.List<String> targets = new java.util.ArrayList<>();
        if (qid != null && !qid.isBlank()) targets.add(qid);
        clazz.membership().qids().stream().skip(1)
                .filter(existing -> !existing.equals(qid)).forEach(targets::add);
        String pid = clazz.membership().relationPid();
        clazz.membership(targets.isEmpty()
                ? EntityBound.unbounded()
                : EntityBound.qualifiedRelation(pid.isBlank()
                        ? domain.config.MembershipPattern.DEFAULT_PROPERTY : pid,
                        targets, clazz.membership().includeDescendants(),
                        clazz.membership().qualifierCondition()));
        clazz.instanceMapping().sourceLabel(label);

        triple.objectQids(targets);

        updateSummary();
        triple.refreshConfigurationStatus(clazz, projectModel);
        refreshConfiguration();
        afterChange.accept(null);
    }

    public RuleNode toRuleNode() {
        return clazz == null ? null : RuleTreeCompiler.compileClass(clazz);
    }

    /** Instances to try the identity configuration against, before applying it. */

    public void applyEdits() {
        if (!configurationDirty) return;
        apply();
        configurationDirty = false;
    }

    /** Navigation must not rewrite a declaration merely because its editor was shown. */
    private void watchConfiguration(Component component) {
        if (component instanceof TextBackedEditor backed) {
            // Its configuration is the text it writes; a draft being typed is not.
            watchConfiguration(backed.storage());
            return;
        } else if (component instanceof javax.swing.text.JTextComponent text
                && EditableComponents.isInformational(text)) {
            // Text nobody can type into — a class name shown, an id's links re-rendered
            // as its label arrives — changes nothing that a flush should write.
        } else if (component instanceof javax.swing.text.JTextComponent text) {
            text.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                @Override public void insertUpdate(javax.swing.event.DocumentEvent e) {
                    configurationDirty = true;
                }
                @Override public void removeUpdate(javax.swing.event.DocumentEvent e) {
                    configurationDirty = true;
                }
                @Override public void changedUpdate(javax.swing.event.DocumentEvent e) {
                    configurationDirty = true;
                }
            });
        } else if (component instanceof javax.swing.JToggleButton toggle) {
            toggle.addItemListener(e -> configurationDirty = true);
        } else if (component instanceof javax.swing.JComboBox<?> combo) {
            combo.addItemListener(e -> configurationDirty = true);
        } else if (component instanceof javax.swing.JSpinner spinner) {
            spinner.addChangeListener(e -> configurationDirty = true);
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) watchConfiguration(child);
        }
    }

    private void buildUi() {
        groupMembersField.setName("class.groupMembersField");
        JPanel form = configuration.form(ClassConfigurationProjection.DEFINITION);

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        int y = 0;

        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 16f));
        GridBagUtils.wideRow(form, y++, titleLabel);
        GridBagUtils.wideRow(form, y, header);

        form = configuration.form(ClassConfigurationProjection.PRODUCTION);
        y = 0;
        JLabel question =
                new JLabel("How do we find instances of this class?");
        question.setFont(question.getFont().deriveFont(Font.ITALIC));
        GridBagUtils.wideRow(form, y++, question);

        populationSource.setToolTipText("<html>Where this class's instances come from: "
                + "the Wikidata population triple, or another registered domain.datasource.<br>"
                + "Choosing another datasource and pressing Apply clears the Wikidata "
                + "triple and explicit seed QIDs, which it replaces.</html>");
        GridBagUtils.labeledRow(form, c, y++, "Population source:", populationSource);
        populationReplaces.setName("class.populationSource.replaces");
        GridBagUtils.wideRow(form, y++, populationReplaces);
        GridBagUtils.wideRow(form, y++, alternativePopulationSource);
        GridBagUtils.wideRow(form, y++, triple);
        GridBagUtils.wideRow(form, y, summaryLabel);
        configuration.detail(ClassConfigurationProjection.TRANSFORMATION,
                new JLabel("Edit this class's population transformation in TransformApp."));
        configuration.detail(ClassConfigurationProjection.DISPLAY_NAME, displayNameEditor);

        form = configuration.form(ClassConfigurationProjection.GROUP);
        y = 0;
        groupMembersField.setToolTipText("Declare each class instance as a group whose "
                + "members come from the selected list field. This changes presentation, "
                + "not how the class population is generated.");
        GridBagUtils.labeledRow(form, c, y, "Group members field:", groupMembersField);

        form = configuration.form(ClassConfigurationProjection.REPRESENTATION);
        y = 0;
        discriminatorPidField.setToolTipText("Property used to further restrict the "
                + "instances inherited from the base class; P31 means instance of.");
        discriminatorQidField.setToolTipText("<html>Required value of that property "
                + "(for example Q5 human). This does not allow objects into the "
                + "population triple; it filters the instances inherited through "
                + "Extends.</html>");
        discriminatorIncludesDescendants.setToolTipText(
                "Also accept values whose class is below this QID through P279.");
        inheritedPopulationFilter.add(subclassConditionMode);
        inheritedPopulationFilter.add(WikidataIdLines.pid(discriminatorPidField));
        inheritedPopulationFilter.add(discriminatorEquals);
        inheritedPopulationFilter.add(WikidataIdLines.qid(discriminatorQidField));
        inheritedPopulationFilter.add(discriminatorIncludesDescendants);
        inheritedPopulationFilter.add(subclassPopulation);
        subclassConditionMode.addActionListener(event -> updateSubclassConditionControls());
        GridBagUtils.labeledRow(form, c, y++, inheritedPopulationFilterLabel,
                inheritedPopulationFilter);
        header.onBaseSelectionChanged(this::showInheritedPopulationFilter);


        representations.describe(this::representationDescription);
        representations.setToolTipText("Explicit alternatives for this role. A target is used "
                + "only when its class admission rule matches; this class is the fallback.");
        GridBagUtils.labeledRow(form, c, y, "Represent matching entities as:", representations);

        form = configuration.form(ClassConfigurationProjection.REFINE);
        y = 0;
        excludeTypesField.setToolTipText("<html>Type QIDs (space-separated) to "
                + "EXCLUDE: drop any item that is instance-of (P31) one of these, "
                + "even if it matched membership.<br>e.g. add Q11688446 (Roman "
                + "deity) to keep a Greek-character class free of Roman ones. "
                + "Emitted as FILTER NOT EXISTS.</html>");
        excludeTypesRow = WikidataIdLines.qidList(excludeTypesField);
        GridBagUtils.labeledRow(form, c, y++, excludeTypesLabel, excludeTypesRow);


        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        ownPopulationLimitLabel.setToolTipText("Applies only to instances discovered "
                + "from this class's own population source, not to instances represented "
                + "from another role class.");
        limitSpinner.setToolTipText(ownPopulationLimitLabel.getToolTipText());
        options.add(ownPopulationLimitLabel);
        options.add(limitSpinner);
        options.add(requireLabelBox);
        options.add(new JLabel("lang:"));
        options.add(langField);
        GridBagUtils.wideRow(form, y++, options);


        notableOnlyBox.setToolTipText("<html>Require an English Wikipedia article "
                + "(a sitelink). A selective entry that bounds a huge class "
                + "(e.g. star Q523, ~3M) to its ~2886 NOTABLE members, so the "
                + "query completes and returns famous entities instead of timing "
                + "out. Combine with a magnitude filter + sort for the brightest "
                + "famous ones.</html>");
        GridBagUtils.wideRow(form, y++, notableOnlyBox);


        rankByBox.setToolTipText("<html>Keep the top <b>Limit</b> instances by "
                + "this measure (importance):<br><b>Notability (sitelinks)</b> — "
                + "Wikipedia language count, a generic importance proxy.<br>Or a "
                + "<b>number/date field</b> of this class (e.g. brightness, area, "
                + "population). <b>(none)</b> = arbitrary, ordered by name.</html>");
        rankRow.add(rankByBox);
        rankRow.add(rankDescBox);
        GridBagUtils.labeledRow(form, c, y, rankByLabel, rankRow);


        seedQidsArea.setLineWrap(true);
        seedQidsArea.setWrapStyleWord(true);
        seedQidsArea.setToolTipText("<html>Explicit instance QIDs (space/comma "
                + "separated). These entities ARE the class's instances — a "
                + "curated set. Leave the Wikidata type empty to use ONLY these "
                + "(e.g. the 12 Olympians); or set a type too, to restrict it. "
                + "The WikiProject tab's \"Add selected\" fills this.</html>");
        triple.sourceSubjectControl("Restrict to these subject QIDs:",
                seedQidsEditor);


        fromPartsButton.setToolTipText("<html>Read the values of a property on an entity "
                + "and choose which values to add as object constraints; for example "
                + "Academy Awards (Q19020) <b>P527</b> (has part).</html>");
        findRelationButton.setToolTipText(
                "Search Wikidata properties by name (e.g. \"nominated\" → P1411)");
        findRelationButton.addActionListener(e -> pickProperty());
        triple.actions(List.of(findRelationButton),
                List.of(fromPartsButton));

        form = configuration.form(ClassConfigurationProjection.MEMBERSHIP_SEARCH);
        y = 0;
        JPanel searchRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        searchRow.add(new JLabel("Search:"));
        searchRow.add(searchTextField);
        searchRow.add(new JLabel("Method:"));
        searchRow.add(searchModeBox);
        searchRow.add(searchTypeButton);
        GridBagUtils.wideRow(form, y++, searchRow);

        installSearchTableBehavior();

        JScrollPane tableScroll = new JScrollPane(searchTable);
        tableScroll.setPreferredSize(new Dimension(700, 180));
        GridBagUtils.wideRow(form, y++, tableScroll);

        JPanel useRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        useSelectedButton.setEnabled(false);
        useRow.add(useSelectedButton);
        GridBagUtils.wideRow(form, y, useRow);

        form = configuration.form(ClassConfigurationProjection.IDENTITY);
        y = 0;
        GridBagUtils.wideRow(form, y++, identityEditor);

        // No "Kind:" control. Which identity regime a class is in follows from how it
        // is built, so offering it separately only created a second answer that could
        // disagree with the first. The label below states the regime instead.
        canonicalKindLabel.setToolTipText("<html>Identity follows the class's "
                + "construction.<br><b>Source</b>: the datasource's id, and its label as "
                + "the display name.<br><b>Statement</b> and <b>Owned</b>: a key over "
                + "fields, or the owner and the site that produced it — with a display "
                + "name from a field or a template.</html>");
        GridBagUtils.labeledRow(form, c, y++, "Identity:", canonicalKindLabel);
        canonicalSourcesLabel.setToolTipText("Datasource operations supplying the source "
                + "class's identity, display label and alternate names.");
        GridBagUtils.labeledRow(form, c, y++, "Source bindings:", canonicalSourcesLabel);

        aliasesBox.setToolTipText("Retain Wikidata aliases as the minor "
                + "alternateNames field. They can then be configured for view, search "
                + "and sort like other fields.");
        GridBagUtils.labeledRow(form, c, y, "Additional names:", aliasesBox);

        form = configuration.form(ClassConfigurationProjection.SOURCE_LINKS);
        y = 0;
        wikipediaArticleBox.setToolTipText("Retain the entity's enwiki sitelink and "
                + "render it as the Wikipedia source. This is independent of its fields.");
        GridBagUtils.labeledRow(form, c, y, "Source links:", wikipediaArticleBox);

        add(configuration, BorderLayout.CENTER);

        searchTextField.addActionListener(e -> searchTypeButton.doClick());
        useSelectedButton.addActionListener(e -> useSelectedSearchRow());

        searchTable.getSelectionModel().addListSelectionListener(e ->
                                                                         useSelectedButton.setEnabled(searchTable.getSelectedRow() >= 0));

        updateSearchButtonState();
    }

    ClassConfigurationEditor configurationEditor() { return configuration; }

    private void refreshConfiguration() { configuration.showClass(projectModel, clazz); }

    private void wireQuery() {
        queryRunner.wireButton(
                searchTypeButton,
                this::acceptSearchResult,
                this::buildSearchQuery,
                ex -> JOptionPane.showMessageDialog(
                        this,
                        "Search failed:\n" + ex.getMessage(),
                        "Search failed",
                        JOptionPane.ERROR_MESSAGE));

        queryRunner.wireButton(
                fromPartsButton,
                this::acceptPartsResult,
                this::buildPartsQuery,
                ex -> JOptionPane.showMessageDialog(
                        this,
                        "Discover from parts failed:\n" + ex.getMessage(),
                        "Discover failed",
                        JOptionPane.ERROR_MESSAGE));
    }

    // The parent + parts pid the user last entered, for the result-dialog title.
    private String partsParentQid = "";
    private String partsParentLabel = "";

    private wikidata.explore.query.logical.RelationMembersQuery buildPartsQuery() {
        if (clazz == null) {
            return null;
        }
        JTextField parentField = new JTextField(triple.firstObjectQid(), 12);
        JTextField pidField = new JTextField("P527", 6);
        JPanel form = new JPanel(new java.awt.GridLayout(0, 2, 4, 4));
        form.add(new JLabel("Source entity QID:"));
        form.add(WikidataIdLines.qid(parentField));
        // Not "(P527 = has part)": the field takes any property and the query uses
        // what it is given, so a label naming one asserts a constraint that is not
        // there. The example belongs in the tooltip, which is where it is.
        pidField.setToolTipText("The property whose values become the members — "
                + "P527 (has part) is the usual one, but any property works.");
        form.add(new JLabel("Relation property:"));
        form.add(WikidataIdLines.pid(pidField));
        int ok = JOptionPane.showConfirmDialog(this, form,
                "Find related objects",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) {
            return null;
        }
        String parent = RuleNode.cleanQid(parentField.getText());
        String pid = RuleNode.cleanPid(pidField.getText());
        if (!WikidataIds.isQid(parent) || !WikidataIds.isPid(pid)) {
            JOptionPane.showMessageDialog(this,
                    "Enter a parent QID (e.g. Q19020) and a parts property "
                            + "(e.g. P527).",
                    "Discover from parts", JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        partsParentQid = parent;
        partsParentLabel = parent;
        log.accept("Discover membership: " + parent + " " + pid + " → members\n");
        return new wikidata.explore.query.logical.RelationMembersQuery(
                parent, pid, false, 1000);
    }

    private void acceptPartsResult(TableQueryResult result) {
        List<List<Object>> rows = result == null ? List.of() : result.rows();
        SwingUtilities.invokeLater(() -> showPartsDialog(rows));
    }

    // RelationMembersQuery rows are [QID, Label]; "Add all" / "Add selected"
    // append to the multi-QID membership field — the data-driven category list.
    private void showPartsDialog(List<List<Object>> rows) {
        if (rows.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "No members found for " + partsParentQid + ".",
                    "Discover from parts", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String[] cols = {"Label", "QID"};
        Object[][] data = new Object[rows.size()][2];
        for (int i = 0; i < rows.size(); i++) {
            List<Object> r = rows.get(i);
            data[i][0] = r.size() > 1 ? r.get(1) : "";
            data[i][1] = r.size() > 0 ? r.get(0) : "";
        }
        DefaultTableModel model = new DefaultTableModel(data, cols) {
            @Override public boolean isCellEditable(int r, int col) { return false; }
        };
        JTable table = new JTable(model);
        table.setRowHeight(22);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.getColumnModel().getColumn(0).setPreferredWidth(320);
        table.getColumnModel().getColumn(1).setPreferredWidth(90);
        WikidataLinks.installOnColumn(table, 1);

        JScrollPane sp = new JScrollPane(table);
        sp.setPreferredSize(new Dimension(560, 320));

        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this),
                "Parts of " + partsParentQid + " (" + rows.size() + ")",
                Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setLayout(new BorderLayout(0, 6));
        dialog.add(new JLabel("  Add these as membership targets "
                + "(\"Also include types\")."), BorderLayout.NORTH);
        dialog.add(sp, BorderLayout.CENTER);

        JButton addSelected = new JButton("Add selected");
        addSelected.setEnabled(false);
        table.getSelectionModel().addListSelectionListener(e ->
                addSelected.setEnabled(table.getSelectedRow() >= 0));
        JButton addAll = new JButton("Add all " + rows.size());
        JButton close = new JButton("Close");
        addSelected.addActionListener(ev -> {
            for (int viewRow : table.getSelectedRows()) {
                addAdditionalType(String.valueOf(table.getValueAt(viewRow, 1)));
            }
        });
        addAll.addActionListener(ev -> {
            for (int i = 0; i < table.getRowCount(); i++) {
                addAdditionalType(String.valueOf(table.getValueAt(i, 1)));
            }
            dialog.dispose();
        });
        close.addActionListener(ev -> dialog.dispose());

        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        south.add(addSelected);
        south.add(addAll);
        south.add(close);
        dialog.add(south, BorderLayout.SOUTH);

        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private void addAdditionalType(String rawQid) {
        String qid = RuleNode.cleanQid(rawQid);
        if (!WikidataIds.isQid(qid)) {
            return;
        }
        java.util.List<String> targets = triple.objectQids();
        if (!targets.contains(qid)) targets.add(qid);
        triple.objectQids(targets);
        apply();
        log.accept("Added membership type " + qid + "\n");
    }

    private ClassSearchQuery buildSearchQuery() {
        if (clazz == null) {
            return null;
        }

        String text = searchText();

        if (text.isBlank()) {
            return null;
        }

        ClassSearchQuery.Mode mode =
                (ClassSearchQuery.Mode) searchModeBox.getSelectedItem();

        log.accept("Class/type search: "
                           + text
                           + " using "
                           + mode
                           + "\n");

        searchModel.setRows(List.of());
        useSelectedButton.setEnabled(false);

        return new ClassSearchQuery(
                text,
                mode,
                25);
    }

    private String searchText() {
        String text =
                searchTextField.getText() == null
                        ? ""
                        : searchTextField.getText().trim();

        if (text.isBlank()) {
            text =
                    clazz == null ? "" : clazz.className();
        }

        return text;
    }

    private void acceptSearchResult(TableQueryResult result) {
        List<SearchResult> rows = new ArrayList<>();

        if (result != null) {
            for (List<Object> row : result.rows()) {
                rows.add(new SearchResult(
                        value(row, 0),
                        value(row, 1),
                        value(row, 2)));
            }
        }

        SwingUtilities.invokeLater(() -> {
            searchModel.setRows(rows);
            useSelectedButton.setEnabled(false);
        });
    }

    private static String value(List<Object> row, int index) {
        if (row == null || index >= row.size()) {
            return "";
        }

        Object v = row.get(index);
        return v == null ? "" : String.valueOf(v);
    }

    private void updateSearchButtonState() {
        boolean ready = clazz != null && queryRunner != null;
        searchTypeButton.setEnabled(ready);
        fromPartsButton.setEnabled(ready);
    }

    private void useSelectedSearchRow() {
        int row = searchTable.getSelectedRow();

        if (row < 0) {
            return;
        }

        SearchResult r =
                searchModel.row(searchTable.convertRowIndexToModel(row));

        useSourceQid(r.qid(), r.label());
        configuration.tree().selectDetail(ClassConfigurationProjection.PRODUCTION);
    }

    private void installSearchTableBehavior() {
        searchTable.setRowHeight(24);
        searchTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);

        searchTable.getColumnModel().getColumn(0)
                   .setPreferredWidth(80);
        searchTable.getColumnModel().getColumn(1)
                   .setPreferredWidth(180);
        searchTable.getColumnModel().getColumn(2)
                   .setPreferredWidth(420);

        WikidataLinks.installOnColumn(searchTable, 0); // QID column → clickable link
    }

    private void apply() {
        if (clazz == null) {
            return;
        }

        boolean wasStatementClass = clazz.reifiesStatements();

        header.applyEdits();
        if (!header.hasSelectedBase()) {
            clazz.subclassCondition(domain.config.SubclassCondition.none());
        } else if (CONDITION_PROPERTY.equals(subclassConditionMode.getSelectedItem())) {
            clazz.subclassCondition(domain.config.SubclassCondition.propertyValue(
                    RuleNode.cleanPid(discriminatorPidField.getText()),
                    RuleNode.cleanQid(discriminatorQidField.getText()),
                    discriminatorIncludesDescendants.isSelected()));
        } else {
            String selectionName = subclassPopulation.getSelectedItem() == null ? ""
                    : subclassPopulation.getSelectedItem().toString();
            domain.config.Selection selection = projectModel == null ? null
                    : projectModel.findSelection(selectionName);
            String selectionId = selection == null ? "" : selection.declarationId();
            clazz.subclassCondition(CONDITION_IN_POPULATION.equals(
                    subclassConditionMode.getSelectedItem())
                    ? domain.config.SubclassCondition.inPopulation(
                            selectionName, selectionId)
                    : domain.config.SubclassCondition.outsidePopulation(
                            selectionName, selectionId));
        }
        FieldSourceMapping m = clazz.instanceMapping();
        m.excludedTypeQids().clear();
        for (String tok : excludeTypesField.getText().trim().split("[,;\\s]+")) {
            String qid = RuleNode.cleanQid(tok);
            if (WikidataIds.isQid(qid)) m.excludedTypeQids().add(qid);
        }
        // The triple writes the elements it owns — for this kind, the property and the
        // objects. This editor keeps only the knobs beside it.
        triple.applyEdits(clazz);
        // No "Reifies statements of" here. It was a free-text field that turned a class
        // into a statement class by keystroke, on the editor for the kind it turned it
        // away from, and it was a second editor for the same value the triple's subject
        // population holds. A class's kind is not changed from its configuration.
        m.direction(RuleDirection.ITEM_TO_ROOT);
        // Commit a value typed into the spinner editor but not yet entered, so
        // Apply reads what's on screen (an out-of-range value otherwise reverts).
        try {
            limitSpinner.commitEdit();
        } catch (java.text.ParseException ignore) {
        }
        m.limit(((Number) limitSpinner.getValue()).intValue());
        m.requireLabel(requireLabelBox.isSelected());
        m.requireSitelink(notableOnlyBox.isSelected());
        m.labelLanguage(langField.getText());

        Object rank = rankByBox.getSelectedItem();
        if (rank == null || NO_RANK.equals(rank)) {
            m.rankBy("");
        } else if (RANK_NOTABILITY.equals(rank)) {
            m.rankBy(FieldSourceMapping.RANK_BY_SITELINKS);
        } else {
            m.rankBy(rank.toString());
        }
        m.rankDescending(rankDescBox.isSelected());

        clazz.seedQids().clear();
        clazz.seedQids().addAll(seedQidsDraft());

        domain.datasource.api.SourceRecipe alternative =
                populationSource.getSelectedItem() == WIKIDATA_POPULATION
                        || !populationSource.isEnabled()
                        ? null : alternativePopulationSource.recipe();
        ClassSourceBindings.alternativePopulationSource(clazz, alternative);
        if (alternative != null) {
            // The chosen producer is the population. Keep the controls honest after
            // Apply: a blank Wikidata triple and seed list, not stale values which no
            // longer participate in generation.
            triple.show(clazz, projectModel);
            seedQidsArea.setText("");
        }

        if (projectModel != null) {
            projectModel.representationClasses(clazz, representations.chosen());
        }

        Object selectedGroupField = groupMembersField.getSelectedItem();
        String groupField = selectedGroupField == null ? ""
                : selectedGroupField.toString().trim();
        clazz.groupSpec(groupField.isBlank() || NOT_A_GROUP.equals(groupField)
                ? null : new domain.config.GroupClassSpec(groupField));

        titleLabel.setText("Class: " + clazz.className());
        triple.objectQids(clazz.membership().qids());

        applyCanonical();

        // This older class-source entry point can also create a statement class.
        // applyCanonical() above rebuilds CanonicalSpec from this panel's controls,
        // so defaults MUST be materialized afterwards; doing it before that call
        // merely wrote values which were immediately discarded. Preserve anything
        // explicitly entered in the same apply: fill only a missing key and the
        // entity-only LABEL display, while always making the identity kind agree
        // with the newly assigned statement source.
        if (!wasStatementClass && clazz.reifiesStatements()) {
            CanonicalSpec canonical = clazz.canonical();

            if (canonical.displayNameMode() == CanonicalSpec.DisplayNameMode.LABEL) {
                domain.config.StatementDisplayDefaults
                        .replaceDisplayWithSuggestion(clazz);
            }
        }

        updateSummary();
        triple.refreshConfigurationStatus(clazz, projectModel);
        refreshConfiguration();
        afterChange.accept(null);
    }

    // --- Identity & label (canonicalization) ---

    /** Loads the class's canonical spec into the section. */
    private void loadCanonical() {
        shownAliases = clazz != null && ClassSourceBindings.binding(clazz,
                SourceBindingSlot.CLASS_ALIASES) != null;
        aliasesBox.setSelected(shownAliases);
        shownWikipediaArticle = clazz != null
                && ClassSourceBindings.articleCorrespondence(clazz) != null;
        wikipediaArticleBox.setSelected(shownWikipediaArticle);
        displayNameEditor.show(clazz);
        identityEditor.show(clazz);

        updateCanonicalEnablement();
    }

    private void updateCanonicalEnablement() {
        boolean hasClass = clazz != null;
        boolean keyed = hasClass
                && CanonicalEditorPolicy.editsCanonicalKey(clazz.classKind());
        canonicalKindLabel.setText(clazz == null ? ""
                : switch (clazz.classKind()) {
                    case SOURCE -> KIND_ENTITY;
                    case STATEMENT -> KIND_DERIVED;
                    case OWNED -> "Owned (owner + production site)";
                    case AGGREGATE -> "Aggregate (grouping key)";
                    case GRAPH -> "Graph (classified candidate)";
                });
        canonicalSourcesLabel.setText(describeClassSources());

        // Not told whether the key is editable: the editor asks the compiled plan,
        // which knows a structural key is supplied by production. Deciding it here as
        // well would be the same fact derived two ways — and the two would agree until
        // a construct's identity regime changed on one side only.
        aliasesBox.setEnabled(hasClass && clazz.classKind() == ClassKind.SOURCE);
        // Only a source class has an independent population to choose a source for.
        populationSource.setEnabled(hasClass && clazz.classKind() == ClassKind.SOURCE);
        wikipediaArticleBox.setEnabled(
                hasClass && clazz.classKind() == ClassKind.SOURCE);
    }

    private String describeClassSources() {
        domain.datasource.api.DatasourceRegistry registry = domain.datasource.Datasources.standard();
        java.util.List<String> labels = new java.util.ArrayList<>();
        for (domain.datasource.api.SourceBinding binding
                : ClassSourceBindings.effectiveNameBindings(clazz)) {
            labels.add(binding.resolve(registry).displayName());
        }
        // Only a kind that has no name sources at all says so; a Source class always
        // has them, whether or not it has stored them yet.
        return labels.isEmpty() ? "—" : String.join(" · ", labels);
    }

    private void applyCanonical() {
        if (clazz == null) {
            return;
        }
        // How a class is BUILT decides its identity regime, so the editor no longer
        // offers it as a separate choice that could disagree with the class's kind.
        displayNameEditor.applyEdits();
        // Which language the label is acquired in is this panel's control, beside the
        // other source options, and it applies to the mode that acquires a label.
        if (displayNameEditor.mode() == CanonicalSpec.DisplayNameMode.LABEL) {
            clazz.canonical().labelLanguage(langField.getText());
        }
        // Merely opening and leaving an editor is navigation, not a configuration
        // change. Materialize explicit name bindings only when the user actually
        // changes the optional alias choice; otherwise legacy/default semantics stay
        // represented without rewriting the model behind the inspection.
        if (aliasesBox.isSelected() != shownAliases) {
            ClassSourceBindings.declareRequiredNameSources(clazz);
            ClassSourceBindings.aliases(clazz, aliasesBox.isSelected());
            shownAliases = aliasesBox.isSelected();
        }
        if (wikipediaArticleBox.isSelected() != shownWikipediaArticle) {
            ClassSourceBindings.wikipediaArticle(
                    clazz, wikipediaArticleBox.isSelected());
            shownWikipediaArticle = wikipediaArticleBox.isSelected();
        }

        String warning = displayNameEditor.warning();
        if (!warning.isBlank()) {
            log.accept("Identity & label: " + warning + "\n");
        }
    }

    private void updateSummary() {
        if (clazz == null) {
            summaryLabel.setText(" ");
            return;
        }

        domain.datasource.api.SourceRecipe alternative =
                ClassSourceBindings.alternativePopulationSource(clazz);
        if (alternative != null) {
            try {
                domain.datasource.api.DatasourceOperation operation = alternative.resolve(
                        domain.datasource.Datasources.standard(),
                        domain.datasource.api.BindingScope.CLASS_POPULATION);
                summaryLabel.setText("Current source: "
                        + operation.prepare(new domain.datasource.api.SourceBinding(
                                domain.datasource.api.SourceBindingTarget.classPopulation(
                                        clazz.className()), alternative)).description());
            } catch (IllegalArgumentException invalid) {
                summaryLabel.setText("Current source is incomplete: "
                        + invalid.getMessage());
            }
            return;
        }
        summaryLabel.setText(" ");
    }

    private void clear() {
        titleLabel.setText("Class");
        header.show(null);
        searchTextField.setText("");
        triple.show(null, projectModel);
        showPopulationSource(null);
        representations.show(List.of(), List.of());
        summaryLabel.setText(" ");
        searchModel.setRows(List.of());
        updateSearchButtonState();
    }

    private void populateRepresentations() {
        if (clazz == null || projectModel == null) {
            representations.show(List.of(), List.of());
            return;
        }
        List<String> chosen = new java.util.ArrayList<>();
        for (EntityRepresentationRule rule : projectModel.entityRepresentationRules()) {
            if (rule != null && projectModel.resolveClass(
                    rule.roleClassId(), rule.roleClassName()) == clazz) {
                chosen.add(rule.representationClassName());
            }
        }
        List<String> addable = new java.util.ArrayList<>();
        for (GeneratedClassModel candidate : projectModel.classes()) {
            if (candidate == null || candidate == clazz
                    || MembershipPattern.kindRule(candidate, projectModel) == null) continue;
            addable.add(candidate.className());
        }
        representations.show(chosen, addable);
    }

    private String representationDescription(String className) {
        if (projectModel == null) return className;
        GeneratedClassModel target = projectModel.findClass(className);
        EntityKindRule rule = MembershipPattern.kindRule(target, projectModel);
        return rule == null ? className : className + " — "
                + rule.propertyPid() + " = " + String.join(", ", rule.evidenceQids())
                + (target != null && !className.equals(rule.className())
                        ? " (inherited from " + rule.className() + ")" : "");
    }

    private record SearchResult(
            String qid,
            String label,
            String description) {
    }

    private static final class SearchTableModel extends AbstractTableModel {

        private final String[] columns =
                {"QID", "Label", "Description"};

        private List<SearchResult> rows = new ArrayList<>();

        public void setRows(List<SearchResult> rows) {
            this.rows = rows == null ? new ArrayList<>() : rows;
            fireTableDataChanged();
        }

        public SearchResult row(int i) {
            return rows.get(i);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            SearchResult r = rows.get(rowIndex);

            return switch (columnIndex) {
                case 0 -> r.qid();
                case 1 -> r.label();
                case 2 -> r.description();
                default -> "";
            };
        }
    }

    private WikidataApiClient api() {
        if (api == null) {
            api = new WikidataApiClient("quiz-modelbuilder/1.0 (ggyepesi@gmail.com)");
            // Surface every API request (URL + timing) on stdout AND in the query
            // log, the same way SPARQL is logged.
            api.log(s -> {
                System.out.print(s);
                log.accept(s);
            });
        }
        return api;
    }

    // Modal "find a property by name" dialog: type a word, see matching Wikidata
    // properties (PID label — description), pick one to fill the relation PID.
    // A real JDialog (not JOptionPane) so Enter = search, not "OK & close".
    private void pickProperty() {
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this),
                "Find relation property", Dialog.ModalityType.APPLICATION_MODAL);

        // Seed the search from the PID already in the relation field — a PID is an
        // exact match (just that property), which is clearer than seeding the
        // label (a fuzzy text match that also pulls in related properties). Fall
        // back to the resolved label only when there's no PID yet.
        String seed = triple.propertyPid();
        if (seed.isEmpty()) {
            seed = triple.propertyLabelText();
        }
        JTextField input = new JTextField(seed, 20);
        JButton searchBtn = new JButton("Search");
        JButton useBtn = new JButton("Use");
        JButton cancelBtn = new JButton("Cancel");
        JLabel status = new JLabel(" ");
        DefaultListModel<WikidataApiClient.SearchResult> model = new DefaultListModel<>();
        JList<WikidataApiClient.SearchResult> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(
                    JList<?> l, Object v, int i, boolean sel, boolean foc) {
                super.getListCellRendererComponent(l, v, i, sel, foc);
                if (v instanceof WikidataApiClient.SearchResult r) {
                    setText("<html><b>" + r.qid() + "</b> " + esc(r.label())
                            + (r.description() == null || r.description().isBlank()
                                ? "" : " — <i>" + esc(r.description()) + "</i>") + "</html>");
                }
                return this;
            }
        });

        // Latest-wins: only the most recent search updates the list, so a slow/
        // timed-out earlier search (e.g. the auto-search on open) can't overwrite
        // or contradict a later successful one.
        int[] gen = {0};
        Runnable doSearch = () -> {
            String q = input.getText().trim();
            if (q.isEmpty()) return;
            final int myGen = ++gen[0];
            searchBtn.setEnabled(false);
            status.setText("Searching…");
            model.clear();
            new SwingWorker<List<WikidataApiClient.SearchResult>, Void>() {
                @Override protected List<WikidataApiClient.SearchResult> doInBackground()
                        throws Exception {
                    try {
                        return api().searchEntities(q, 20, "property");
                    } catch (java.io.IOException firstTry) {
                        // The first HTTPS call after start can hit a cold-connection
                        // timeout — retry once before giving up.
                        return api().searchEntities(q, 20, "property");
                    }
                }
                @Override protected void done() {
                    if (myGen != gen[0]) {
                        return; // a newer search superseded this one
                    }
                    searchBtn.setEnabled(true);
                    try {
                        for (WikidataApiClient.SearchResult r : get()) model.addElement(r);
                        status.setText(model.isEmpty()
                                ? "No properties match \"" + q + "\"."
                                : model.size() + " match(es) — pick one.");
                        if (!model.isEmpty()) list.setSelectedIndex(0);
                    } catch (Exception ex) {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        status.setText("Search failed: " + cause.getMessage());
                    }
                }
            }.execute();
        };
        Runnable use = () -> {
            WikidataApiClient.SearchResult picked = list.getSelectedValue();
            if (picked != null) {
                // The label after the PID: setting the PID blanks it.
                triple.propertyPid(picked.qid(), picked.label());
                dialog.dispose();
            }
        };

        searchBtn.addActionListener(e -> doSearch.run());
        input.addActionListener(e -> doSearch.run());
        useBtn.addActionListener(e -> use.run());
        cancelBtn.addActionListener(e -> dialog.dispose());
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) use.run();
            }
        });

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        top.add(new JLabel("Search:"));
        top.add(input);
        top.add(searchBtn);
        JPanel south = new JPanel(new BorderLayout());
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        btns.add(useBtn);
        btns.add(cancelBtn);
        south.add(status, BorderLayout.WEST);
        south.add(btns, BorderLayout.EAST);

        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.add(top, BorderLayout.NORTH);
        JScrollPane sc = new JScrollPane(list);
        sc.setPreferredSize(new Dimension(460, 240));
        panel.add(sc, BorderLayout.CENTER);
        panel.add(south, BorderLayout.SOUTH);

        dialog.setContentPane(panel);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        if (!seed.isEmpty()) {
            doSearch.run();
        }
        SwingUtilities.invokeLater(input::requestFocusInWindow);
        dialog.setVisible(true);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

}

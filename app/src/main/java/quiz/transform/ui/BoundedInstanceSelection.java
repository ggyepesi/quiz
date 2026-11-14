package quiz.transform.ui;

import objectview.Viewable;
import objectview.view.SearchableView;
import wikidata.explore.extract.WikidataDynamicObject;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Predicate;

/**
 * The explicit, bounded instance selection source discovery starts from: a class, a
 * random starting sample, and ObjectView panels to add and remove exact instances. The
 * Discovery workbench holds one, and every source tab registers as a {@link Host} and
 * reads that same selection; selecting never starts a request.
 *
 * <p>This was written out once per workbench. Two copies already disagreed on the
 * identity of a selected instance and on whether another view could hand a selection
 * over, which is why it is one control now.</p>
 */
public final class BoundedInstanceSelection {
    /** What the owning workbench does when the selection or class changes. */
    public interface Host {
        /** The previous results no longer describe the selection. */
        void selectionChanged(String reason);
        /** Describe the current selection state to the user. Shown once, by whoever
         *  shows the selection; a source tab describes only its own work. */
        default void status(String text) { }
        /** Re-evaluate the owner's actions; selection controls update themselves. */
        void updateActions();
    }

    private final TransformController domain;
    /** Every source that runs over this selection; each keeps its own results. */
    private final List<Host> hosts = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Host host = new Host() {
        @Override public void selectionChanged(String reason) {
            hosts.forEach(value -> value.selectionChanged(reason));
        }
        @Override public void status(String text) {
            hosts.forEach(value -> value.status(text));
        }
        @Override public void updateActions() {
            hosts.forEach(Host::updateActions);
        }
    };
    private final int maximum;
    private final String selectedNoun;
    private final Predicate<WikidataDynamicObject> eligible;
    private final String eligibility;

    private final JComboBox<String> type = new JComboBox<>();
    private final JSpinner sampleSize;
    private final JButton randomSample = new JButton("Start random sample");
    private final JButton addSelection = new JButton("Add selection");
    private final JButton removeSelection = new JButton("Remove selection");
    private final JPanel instancesHolder = new JPanel(new BorderLayout());
    private final JPanel selectedHolder = new JPanel(new BorderLayout());
    private final JTabbedPane tabs = new JTabbedPane();
    private final LinkedHashMap<WikidataDynamicObject, WikidataDynamicObject> selected =
            new LinkedHashMap<>();
    private List<WikidataDynamicObject> classMembers = List.of();
    private List<WikidataDynamicObject> selectedInClass = List.of();
    private List<WikidataDynamicObject> selectedInSample = List.of();
    private SearchableView instancesView;
    private SearchableView selectedView;
    /** The sources whose work over the selection is running now. */
    private final java.util.Set<Object> running = new java.util.HashSet<>();

    /**
     * @param selectedNoun what one run reads per selected instance, plural ("articles")
     * @param eligibility  the instances {@code eligible} admits, as a predicate phrase
     *                     ("have Wikidata QIDs")
     */
    public BoundedInstanceSelection(TransformController domain, Host host, int maximum,
            int defaultSample, String selectedNoun,
            Predicate<WikidataDynamicObject> eligible, String eligibility) {
        this.domain = java.util.Objects.requireNonNull(domain);
        this.hosts.add(java.util.Objects.requireNonNull(host));
        this.maximum = maximum;
        this.selectedNoun = selectedNoun;
        this.eligible = java.util.Objects.requireNonNull(eligible);
        this.eligibility = eligibility;
        this.sampleSize = new JSpinner(new SpinnerNumberModel(
                Math.min(defaultSample, maximum), 1, maximum, 1));
        build();
        type.addActionListener(event -> showType(className()));
        randomSample.addActionListener(event -> startRandomSample());
        addSelection.addActionListener(event -> addSelectedInstances());
        removeSelection.addActionListener(event -> removeSelectedInstances());
    }

    private void build() {
        JPanel instances = new JPanel(new BorderLayout(4, 4));
        JPanel instanceActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        instanceActions.add(addSelection);
        instances.add(instanceActions, BorderLayout.NORTH);
        instances.add(instancesHolder, BorderLayout.CENTER);
        tabs.addTab("Class instances", instances);

        JPanel sample = new JPanel(new BorderLayout(4, 4));
        JPanel sampleActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        sampleActions.add(removeSelection);
        sample.add(sampleActions, BorderLayout.NORTH);
        sample.add(selectedHolder, BorderLayout.CENTER);
        tabs.addTab(selectedTitle(), sample);
    }

    /** Adds the class and sample controls, in order, to the owner's control row. */
    public void addControls(JComponent controls) {
        controls.add(new JLabel("Class:"));
        controls.add(type);
        controls.add(new JLabel("Random starting sample:"));
        controls.add(sampleSize);
        controls.add(randomSample);
    }

    /** The class-instances and selected-instances tabs. */
    public JTabbedPane tabs() { return tabs; }

    public String className() { return (String) type.getSelectedItem(); }

    public List<WikidataDynamicObject> selected() { return new ArrayList<>(selected.values()); }

    public int size() { return selected.size(); }

    public boolean hasClasses() { return type.getItemCount() > 0; }

    /**
     * Offers every class with an eligible instance and shows the requested one, or the
     * first. Returns false when no class qualifies; the owner explains that.
     */
    public boolean populate(String requestedClass) {
        for (String candidate : domain.types()) {
            if (TransformController.ALL_ENTITIES.equals(candidate)) continue;
            if (!eligibleMembers(candidate).isEmpty()) type.addItem(candidate);
        }
        if (type.getItemCount() == 0) {
            updateControls();
            return false;
        }
        boolean offered = requestedClass != null
                && java.util.stream.IntStream.range(0, type.getItemCount())
                .mapToObj(type::getItemAt).anyMatch(requestedClass::equals);
        if (offered) type.setSelectedItem(requestedClass);
        else type.setSelectedIndex(0);
        // setSelectedItem fires showType only when the item changes.
        if (classMembers.isEmpty()) showType(className());
        return true;
    }

    /**
     * Selects exactly the given instances of the shown class, as another view handed
     * them over. Returns how many were not eligible members and were left out. An
     * oversized hand-over is kept whole so the user reduces it explicitly.
     */
    public int seed(List<WikidataDynamicObject> instances, String reason) {
        java.util.Map<WikidataDynamicObject, WikidataDynamicObject> available =
                new LinkedHashMap<>();
        classMembers.forEach(value -> available.putIfAbsent(value, value));
        selected.clear();
        int omitted = 0;
        for (WikidataDynamicObject requested : instances) {
            WikidataDynamicObject member = available.get(requested);
            if (member == null) omitted++;
            else selected.putIfAbsent(member, member);
        }
        selectedInSample = List.of();
        rebuildSelectedView();
        tabs.setSelectedIndex(1);
        host.selectionChanged(reason);
        updateControls();
        host.updateActions();
        return omitted;
    }

    /** Another source that runs over this same selection. */
    public void addHost(Host another) {
        hosts.add(java.util.Objects.requireNonNull(another));
    }

    /** Disables selection edits while any source runs work over the selection; one
     *  source finishing does not re-enable them under another that is still running. */
    public void setRunning(Object source, boolean running) {
        if (running) this.running.add(source);
        else this.running.remove(source);
        updateControls();
    }

    public int maximum() { return maximum; }

    /** An ObjectView of instances of {@code className}, rendered with its schema. */
    public SearchableView instanceView(List<? extends Viewable> values, String className,
            java.util.function.Consumer<List<Viewable>> selection) {
        return SearchableView.builder(values)
                .sample(className == null ? null : domain.configSample(className))
                .hiddenFields(className == null ? java.util.Set.of()
                        : domain.structuralFields(className))
                .fieldTypes(className == null ? null : domain.fieldTypes(className))
                .fieldSchemas(value -> domain.renderedFieldSchema(value, className))
                .typeSchemas(domain::fieldSchema)
                .valueLinker(wikidata.ui.WikidataLinks.valueLinker())
                .selectionSetListener(chosen -> {
                    selection.accept(chosen.stream()
                            .filter(Viewable.class::isInstance)
                            .map(Viewable.class::cast).toList());
                    updateControls();
                    host.updateActions();
                })
                .collapsible(true)
                .build();
    }

    private void showType(String className) {
        classMembers = className == null ? List.of() : eligibleMembers(className);
        selected.clear();
        selectedInClass = List.of();
        selectedInSample = List.of();
        instancesView = instanceView(classMembers, className,
                values -> selectedInClass = dynamic(values));
        replace(instancesHolder, instancesView);
        rebuildSelectedView();
        host.selectionChanged("The class changed. Select instances before fetching.");
        host.status(classMembers.size() + " " + className + " instance(s) " + eligibility
                + ". Nothing is requested until you fetch.");
        updateControls();
        host.updateActions();
    }

    private List<WikidataDynamicObject> eligibleMembers(String className) {
        return domain.domain().instancesOf(className).stream()
                .filter(WikidataDynamicObject.class::isInstance)
                .map(WikidataDynamicObject.class::cast)
                .filter(eligible)
                .distinct().toList();
    }

    private void startRandomSample() {
        List<WikidataDynamicObject> shuffled = new ArrayList<>(classMembers);
        java.util.Collections.shuffle(shuffled);
        int count = Math.min((Integer) sampleSize.getValue(), shuffled.size());
        selected.clear();
        shuffled.stream().limit(count).forEach(value -> selected.putIfAbsent(value, value));
        selectedInSample = List.of();
        rebuildSelectedView();
        tabs.setSelectedIndex(1);
        host.selectionChanged("The selected " + selectedNoun
                + " changed. Fetch to produce new results.");
        host.status("Selected " + count + " random " + className()
                + " instance(s). Add or remove instances before fetching; nothing has "
                + "been requested.");
        updateControls();
        host.updateActions();
    }

    private void addSelectedInstances() {
        int available = Math.max(0, maximum - selected.size());
        List<WikidataDynamicObject> candidates = selectedInClass.stream()
                .filter(value -> !selected.containsKey(value)).toList();
        List<WikidataDynamicObject> added = candidates.stream().limit(available).toList();
        boolean firstAddition = selected.isEmpty() && !added.isEmpty();
        added.forEach(value -> selected.putIfAbsent(value, value));
        if (selectedView == null || firstAddition) {
            // An empty SearchableView deliberately has no cards container. The first
            // addition creates that container; later additions stay local to it.
            rebuildSelectedView();
        } else {
            selectedView.appendViewables(added);
            tabs.setTitleAt(1, selectedTitle());
        }
        if (!added.isEmpty()) {
            host.selectionChanged("The selected " + selectedNoun
                    + " changed. Fetch to produce new results.");
        }
        host.status("Added " + added.size() + " instance(s); " + selected.size()
                + " are now selected."
                + (added.size() == candidates.size() ? "" : " The remaining "
                + (candidates.size() - added.size()) + " instance(s) were not added "
                + "because one run is bounded to " + maximum + " " + selectedNoun + "."));
        updateControls();
        host.updateActions();
    }

    private void removeSelectedInstances() {
        List<WikidataDynamicObject> removed = List.copyOf(selectedInSample);
        removed.forEach(selected::remove);
        if (selectedView != null) selectedView.removeViewables(removed);
        selectedInSample = List.of();
        tabs.setTitleAt(1, selectedTitle());
        if (!removed.isEmpty()) {
            host.selectionChanged("The selected " + selectedNoun
                    + " changed. Fetch to produce new results.");
        }
        host.status("Removed " + removed.size() + " instance(s); " + selected.size()
                + " remain selected.");
        updateControls();
        host.updateActions();
    }

    private void rebuildSelectedView() {
        selectedView = instanceView(new ArrayList<>(selected.values()), className(),
                values -> selectedInSample = dynamic(values));
        replace(selectedHolder, selectedView);
        tabs.setTitleAt(1, selectedTitle());
    }

    private String selectedTitle() {
        return "Selected " + selectedNoun + " — " + selected.size();
    }

    private void updateControls() {
        boolean hasType = className() != null && !classMembers.isEmpty();
        boolean idle = running.isEmpty();
        type.setEnabled(idle && type.getItemCount() > 0);
        sampleSize.setEnabled(idle && hasType);
        randomSample.setEnabled(idle && hasType);
        addSelection.setEnabled(idle && !selectedInClass.isEmpty()
                && selected.size() < maximum);
        removeSelection.setEnabled(idle && !selectedInSample.isEmpty());
    }

    private static void replace(JPanel holder, JComponent view) {
        holder.removeAll();
        holder.add(view, BorderLayout.CENTER);
        holder.revalidate();
        holder.repaint();
    }

    private static List<WikidataDynamicObject> dynamic(List<Viewable> values) {
        return values.stream().filter(WikidataDynamicObject.class::isInstance)
                .map(WikidataDynamicObject.class::cast).toList();
    }
}

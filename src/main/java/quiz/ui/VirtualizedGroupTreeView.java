package quiz.ui;

import quiz.Quizable;
import quiz.QuizableAdapter;
import quiz.QuizableGroup;
import quiz.ui.viewconfig.QuizablePanelConfig;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.*;
import java.util.function.Function;

/**
 * A single virtualized outline for an entire QuizableGroup hierarchy.
 *
 * Visible group headers and member cards are flattened into one row list and
 * rendered by one VirtualizedCardList. Expanding/collapsing a group changes only
 * the flattened row list; member QuizablePanels remain lazily materialized.
 */
public final class VirtualizedGroupTreeView extends JPanel
        implements ConfigurableVirtualizedQuizableContainer {

    private static final int INDENT = 18;

    private final QuizableGroup root;
    private final VirtualizedCardList rows;
    private final JScrollPane rowsScroll;

    private final QuizableRenderContext renderContext =
            new QuizableRenderContext();

    /**
     * Expansion is structural, so identity is appropriate.
     */
    private final Set<QuizableGroup> expandedGroups =
            Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * Search operates on real members, not outline-row wrappers.
     */
    private final List<Quizable> members = new ArrayList<>();

    /**
     * One real member can occur in several buckets. Keep all corresponding rows.
     */
    private final Map<Quizable, List<MemberRow>> rowsByMember =
            new IdentityHashMap<>();

    private final List<Quizable> visibleRows = new ArrayList<>();

    private QuizablePanelConfig cardConfig;
    private Comparator<Quizable> memberComparator;
    private Quizable lastNavigated;

    public VirtualizedGroupTreeView(
            QuizableGroup root,
            QuizablePanelConfig cardConfig) {

        this.root = Objects.requireNonNull(root, "root");
        this.cardConfig = cardConfig == null ? null : cardConfig.copy();

        setLayout(new BorderLayout());

        collectUniqueMembers(root, members);

        for (Quizable member : members) {
            renderContext.addTopLevel(member);
        }

        rows = new VirtualizedCardList(this::createRowComponent);
        rowsScroll = new JScrollPane();
        rows.install(rowsScroll);

        add(rowsScroll, BorderLayout.CENTER);

        /*
         * The root itself is conceptual; show its children initially.
         */
        expandedGroups.add(root);

        rebuildVisibleRows();
    }

    /**
     * Use this when GroupTreeBrowser already owns the outer scrolling.
     */
    public VirtualizedGroupTreeView(
            QuizableGroup root,
            QuizablePanelConfig cardConfig,
            boolean exposeInnerScroll) {

        this(root, cardConfig);

        if (!exposeInnerScroll) {
            remove(rowsScroll);
            add(rows, BorderLayout.CENTER);
        }
    }

    @Override
    public List<Quizable> items() {
        return Collections.unmodifiableList(members);
    }

    @Override
    public Quizable topVisibleItem() {
        Quizable row = rows.topVisibleItem();

        if (row instanceof MemberRow memberRow) {
            return memberRow.member;
        }

        return lastNavigated;
    }

    @Override
    public JComponent navigateToTop(Quizable member) {
        if (member == null) {
            return null;
        }

        List<MemberRow> locations = rowsByMember.get(member);

        /*
         * The member may currently be hidden below collapsed ancestors.
         */
        if (locations == null || locations.isEmpty()) {
            expandPathTo(member);
            rebuildVisibleRows();
            locations = rowsByMember.get(member);
        }

        if (locations == null || locations.isEmpty()) {
            return null;
        }

        MemberRow row = locations.getFirst();
        JComponent component = rows.navigateToTop(row);

        lastNavigated = member;

        /*
         * QuizableSearchPanel expects the actual QuizablePanel so it can expand
         * nested fields and apply exact highlighting.
         */
        return findQuizablePanel(component);
    }

    /**
     * Applies a global member order while retaining the group hierarchy.
     */
    @Override
    public void setItems(List<Quizable> orderedMembers) {
        Map<Quizable, Integer> rank = new IdentityHashMap<>();

        if (orderedMembers != null) {
            for (int i = 0; i < orderedMembers.size(); i++) {
                rank.put(orderedMembers.get(i), i);
            }

            members.clear();
            members.addAll(orderedMembers);
        }

        memberComparator = Comparator.comparingInt(
                q -> rank.getOrDefault(q, Integer.MAX_VALUE)
                                                  );

        rebuildVisibleRows();
    }

    @Override
    public void setCardConfig(QuizablePanelConfig config) {
        cardConfig = config == null ? null : config.copy();

        /*
         * The factory is unchanged conceptually, but replacing it discards built
         * rows and stale height measurements. Rows rebuild lazily.
         */
        rows.setCardFactory(this::createRowComponent);
    }

    public JScrollPane scrollPane() {
        return rowsScroll;
    }

    public VirtualizedCardList virtualList() {
        return rows;
    }

    private void rebuildVisibleRows() {
        visibleRows.clear();
        rowsByMember.clear();

        appendGroupContents(root, 0);

        rows.setItems(new ArrayList<>(visibleRows));
    }

    /**
     * Appends the currently visible outline below a group.
     */
    private void appendGroupContents(QuizableGroup group, int depth) {
        for (QuizableGroup child : new ArrayList<>(group.getChildren())) {
            if (child == null) {
                continue;
            }

            GroupRow groupRow =
                    new GroupRow(child, depth, expandedGroups.contains(child));
            visibleRows.add(groupRow);

            if (!groupRow.expanded) {
                continue;
            }

            if (!child.getChildren().isEmpty()) {
                appendGroupContents(child, depth + 1);
            }

            // Members of this bucket not represented by a deeper child bucket (at a
            // leaf that's every member; higher up it's the ones lacking the next
            // grouping value).
            addMemberRows(directMembersOf(child), depth + 1);
        }

        // The root's own direct members: the fully-flat case (no groups at all) AND
        // root-level orphans — members with no value for any top dimension, so in
        // NO bucket. Rendered here so they're never dropped.
        if (group == root) {
            addMemberRows(directMembersOf(root), depth);
        }
    }

    private void addMemberRows(List<Quizable> directMembers, int depth) {
        directMembers.sort(effectiveMemberComparator());

        for (Quizable member : directMembers) {
            MemberRow row = new MemberRow(member, depth);
            visibleRows.add(row);
            rowsByMember
                    .computeIfAbsent(member, ignored -> new ArrayList<>())
                    .add(row);
        }
    }

    private Comparator<Quizable> effectiveMemberComparator() {
        if (memberComparator != null) {
            return memberComparator;
        }

        return Comparator.comparing(
                q -> Objects.toString(q.getDisplayName(), ""),
                String.CASE_INSENSITIVE_ORDER
                                   );
    }

    /**
     * Members belonging to this bucket but not to any visible child bucket.
     */
    private List<Quizable> directMembersOf(QuizableGroup group) {
        if (group.getChildren().isEmpty()) {
            return identityDistinct(group.getMembers());
        }

        Set<Quizable> childMembers =
                Collections.newSetFromMap(new IdentityHashMap<>());

        for (QuizableGroup child : group.getChildren()) {
            collectUniqueMembers(child, childMembers);
        }

        List<Quizable> direct = new ArrayList<>();

        for (Quizable member : group.getMembers()) {
            if (member != null && !childMembers.contains(member)) {
                direct.add(member);
            }
        }

        return identityDistinct(direct);
    }

    private JComponent createRowComponent(Quizable rowObject) {
        if (rowObject instanceof GroupRow groupRow) {
            return createGroupHeader(groupRow);
        }

        if (rowObject instanceof MemberRow memberRow) {
            return createMemberCard(memberRow);
        }

        throw new IllegalArgumentException(
                "Unknown outline row: " + rowObject
        );
    }

    private JComponent createGroupHeader(GroupRow row) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(
                3,
                row.depth * INDENT,
                3,
                4
                                                       ));

        JButton button = new JButton(
                (row.expanded ? "▾ " : "▸ ")
                        + groupLabel(row.group)
        );

        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);

        button.addActionListener(e -> toggle(row.group));

        panel.add(button, BorderLayout.CENTER);
        return panel;
    }

    private String groupLabel(QuizableGroup group) {
        int memberCount =
                identityDistinct(group.getMembers()).size();

        int childCount =
                nonEmptyChildCount(group);

        if (childCount > 0) {
            return group.getDisplayName()
                    + " (" + childCount + " groups · "
                    + memberCount + " members)";
        }

        return group.getDisplayName()
                + " (" + memberCount + " members)";
    }

    private JComponent createMemberCard(MemberRow row) {
        JPanel indented = new JPanel(new BorderLayout());
        indented.setOpaque(false);
        indented.setBorder(BorderFactory.createEmptyBorder(
                2,
                row.depth * INDENT,
                2,
                4
                                                          ));

        QuizablePanelConfig config = configFor(row.member);

        QuizablePanel card = new QuizablePanel(
                row.member,
                config,
                renderContext,
                false
        );

        renderContext.registerTopLevel(row.member, card);

        indented.add(card, BorderLayout.CENTER);

        /*
         * QuizableSearchPanel needs the real card. It finds it recursively through
         * findQuizablePanel(...) after navigateToTop(...).
         */
        return indented;
    }

    private QuizablePanelConfig configFor(Quizable member) {
        if (cardConfig == null) {
            return QuizablePanelConfig.all(member.getClass());
        }

        QuizablePanelConfig copy = cardConfig.copy();

        if (copy.getCls() == null) {
            @SuppressWarnings("unchecked")
            Class<? extends Quizable> cls =
                    (Class<? extends Quizable>) member.getClass();

            copy.setCls(cls);
        }

        return copy;
    }

    private void toggle(QuizableGroup group) {
        if (!expandedGroups.remove(group)) {
            expandedGroups.add(group);
        }

        rebuildVisibleRows();
    }

    /**
     * Expands the first group path containing the requested member.
     */
    private void expandPathTo(Quizable member) {
        List<QuizableGroup> path =
                findContainingPath(root, member);

        if (path == null) {
            return;
        }

        expandedGroups.add(root);
        expandedGroups.addAll(path);
    }

    private List<QuizableGroup> findContainingPath(
            QuizableGroup group,
            Quizable target) {

        for (QuizableGroup child : group.getChildren()) {
            if (!containsIdentity(child.getMembers(), target)) {
                continue;
            }

            List<QuizableGroup> deeper =
                    findContainingPath(child, target);

            List<QuizableGroup> result = new ArrayList<>();
            result.add(child);

            if (deeper != null) {
                result.addAll(deeper);
            }

            return result;
        }

        return containsIdentity(group.getMembers(), target)
                ? List.of()
                : null;
    }

    private static QuizablePanel findQuizablePanel(Component root) {
        if (root instanceof QuizablePanel panel) {
            return panel;
        }

        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                QuizablePanel found =
                        findQuizablePanel(child);

                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }

    private static int nonEmptyChildCount(
            QuizableGroup group) {

        int count = 0;

        for (QuizableGroup child : group.getChildren()) {
            if (child != null
                    && (!child.getChildren().isEmpty()
                    || !child.getMembers().isEmpty())) {
                count++;
            }
        }

        return count;
    }

    private static void collectUniqueMembers(
            QuizableGroup group,
            Collection<Quizable> out) {

        if (group == null) {
            return;
        }

        for (Quizable member : group.getMembers()) {
            if (member != null && !containsIdentity(out, member)) {
                out.add(member);
            }
        }

        for (QuizableGroup child : group.getChildren()) {
            collectUniqueMembers(child, out);
        }
    }

    private static List<Quizable> identityDistinct(
            Collection<? extends Quizable> input) {

        Set<Quizable> seen =
                Collections.newSetFromMap(
                        new IdentityHashMap<>());

        List<Quizable> result = new ArrayList<>();

        if (input != null) {
            for (Quizable value : input) {
                if (value != null && seen.add(value)) {
                    result.add(value);
                }
            }
        }

        return result;
    }

    private static boolean containsIdentity(
            Collection<? extends Quizable> values,
            Quizable candidate) {

        for (Quizable value : values) {
            if (value == candidate) {
                return true;
            }
        }

        return false;
    }

    /**
     * Internal rows are Quizable only so the existing VirtualizedCardList can
     * virtualize them without becoming generic yet.
     */
    private sealed interface OutlineRow
            permits GroupRow, MemberRow {
    }

    private static final class GroupRow
            extends QuizableAdapter
            implements OutlineRow {

        private final QuizableGroup group;
        private final int depth;
        private final boolean expanded;

        private GroupRow(QuizableGroup group, int depth, boolean expanded) {
            this.group = group;
            this.depth = depth;
            this.expanded = expanded;
        }

        @Override
        public String getIdentifier() {
            return "group:" + System.identityHashCode(group) + ":" + depth;
        }

        @Override
        public String getDisplayName() {
            return group.getDisplayName();
        }
    }

    private static final class MemberRow
            extends QuizableAdapter
            implements OutlineRow {

        private final Quizable member;
        private final int depth;

        private MemberRow(Quizable member, int depth) {
            this.member = member;
            this.depth = depth;
        }

        @Override
        public String getIdentifier() {
            return "member:" + System.identityHashCode(this);
        }

        @Override
        public String getDisplayName() {
            return member.getDisplayName();
        }
    }
}
package wikidata.explore.workbench;

import objectview.Viewable;
import objectview.render.RenderContext;
import objectview.render.RenderingMode;
import objectview.view.SearchableView;

import javax.swing.BorderFactory;
import javax.swing.JSplitPane;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/** Searchable collision records beside the ordinary instance browser they select. */
final class NameCollisionInspectionPanel extends JPanel implements AutoCloseable {
    private static final Comparator<NameCollision> DISPLAY_ORDER =
            Comparator.comparing(NameCollision::name, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(NameCollision::className,
                            String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(NameCollision::name)
                    .thenComparing(NameCollision::className);

    private final List<NameCollision> collisions;
    private final RenderContext memberContext;
    private final JPanel membersHost = new JPanel(new BorderLayout());
    private final SearchableView collisionView;
    private SearchableView membersView;
    private NameCollision selectedCollision;

    NameCollisionInspectionPanel(Collection<NameCollision> collisions,
            RenderContext memberContext) {
        super(new BorderLayout());
        this.memberContext = memberContext == null ? new RenderContext() : memberContext;
        List<NameCollision> ordered = new ArrayList<>();
        if (collisions != null) ordered.addAll(collisions);
        ordered.sort(DISPLAY_ORDER);
        this.collisions = List.copyOf(ordered);

        collisionView = SearchableView.builder(this.collisions)
                .type(NameCollision.class)
                .sample(this.collisions.isEmpty() ? null : this.collisions.getFirst())
                .mode(RenderingMode.TABLE)
                .collapsible(false)
                .selectionListener(value -> {
                    if (value instanceof NameCollision collision) selectCollision(collision);
                })
                .emptyMessage("No name collisions.")
                .build();

        JPanel collisionHost = new JPanel(new BorderLayout());
        collisionHost.setBorder(BorderFactory.createTitledBorder(
                "Shared names (" + this.collisions.size() + ")"));
        collisionHost.add(collisionView, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                collisionHost, membersHost);
        split.setResizeWeight(0.34);
        split.setOneTouchExpandable(true);
        add(split, BorderLayout.CENTER);

        if (!this.collisions.isEmpty()) {
            collisionView.renderContext().select(this.collisions.getFirst());
        } else {
            showMembers(null);
        }
    }

    private void selectCollision(NameCollision collision) {
        if (collision == null || selectedCollision == collision) return;
        selectedCollision = collision;
        showMembers(collision);
    }

    private void showMembers(NameCollision collision) {
        if (membersView != null) membersView.dispose();
        membersHost.removeAll();
        List<Viewable> members = collision == null ? List.of() : collision.entities();
        membersView = SearchableView.builder(members)
                .sample(members.isEmpty() ? null : members.getFirst())
                .renderContext(memberContext)
                .collapsible(true)
                .columns(1)
                .emptyMessage("Select a shared name.")
                .build();
        String title = collision == null ? "Distinct instances"
                : collision.name() + " — " + collision.className()
                        + " (" + collision.instances() + ")";
        membersHost.setBorder(BorderFactory.createTitledBorder(title));
        membersHost.add(membersView, BorderLayout.CENTER);
        membersHost.revalidate();
        membersHost.repaint();
    }

    List<NameCollision> collisions() { return collisions; }
    NameCollision selectedCollision() { return selectedCollision; }
    SearchableView collisionView() { return collisionView; }
    SearchableView membersView() { return membersView; }

    /** Package-visible interaction seam for the UI contract test. */
    void selectForInspection(NameCollision collision) {
        collisionView.renderContext().select(collision);
    }

    @Override public void close() {
        collisionView.dispose();
        if (membersView != null) membersView.dispose();
    }
}

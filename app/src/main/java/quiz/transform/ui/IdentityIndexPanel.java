package quiz.transform.ui;

import objectview.Viewable;
import wikidata.explore.workbench.EntityResultPanel;

import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Compact, searchable identity index displayed alongside an existing instance-card view.
 * Identity is presentation metadata supplied by a resolver (native identity or curation
 * history); it is never projected back into the instance's ordinary fields.
 *
 * <p>The current identity provider is Wikidata, so the shared entity table renders its QID
 * column as links. Keeping resolution outside this component leaves room for another
 * source-specific identity presentation without changing Viewable or the cards.
 */
public final class IdentityIndexPanel extends JPanel {

    private final EntityResultPanel table = new EntityResultPanel(
            List.of("Instance", "Class", "Wikidata identity"), 2, false);
    private Consumer<Viewable> selectionListener = value -> { };

    public IdentityIndexPanel(
            List<? extends Viewable> members,
            Function<Viewable, String> type,
            Function<Viewable, String> qid) {
        super(new BorderLayout());
        Function<Viewable, String> typeResolver = type == null
                ? Viewable::typeName : type;
        Function<Viewable, String> identityResolver = qid == null
                ? value -> null : qid;
        table.setColumnWidths(260, 120, 120);
        table.setRows((members == null ? List.<Viewable>of() : members).stream()
                .filter(Objects::nonNull)
                .map(member -> List.<Object>of(
                        new Entry(member),
                        Objects.requireNonNullElse(typeResolver.apply(member), ""),
                        Objects.requireNonNullElse(identityResolver.apply(member), "")))
                .toList());
        table.onSelectionChanged(() -> selectionListener.accept(selectedMember()));
        add(table, BorderLayout.CENTER);
    }

    public void onSelectionChanged(Consumer<Viewable> listener) {
        selectionListener = listener == null ? value -> { } : listener;
    }

    private Viewable selectedMember() {
        List<List<Object>> rows = table.selectedRows();
        Object value = rows.isEmpty() || rows.get(0).isEmpty()
                ? null : rows.get(0).get(0);
        return value instanceof Entry entry ? entry.member() : null;
    }

    private record Entry(Viewable member) {
        @Override public String toString() {
            return member == null ? "" : member.getDisplayName();
        }
    }
}

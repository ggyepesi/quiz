package quiz.ui;

import objectview.Viewable;
import objectview.render.Card;
import objectview.render.RenderContext;
import objectview.search.SearchPanel;
import objectview.view.SearchControlsDisclosure;
import objectview.viewconfig.ViewConfig;
import objectview.virtual.VirtualizedCardList;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A virtualized quiz choice list using ObjectView's one search/sort/view path.
 * The rendered content is the searchable item; the quiz source and state stay
 * outside ObjectView and are restored whenever virtualization rebuilds a card.
 */
public final class SearchableChoiceBoard extends JPanel {
    public record Choice(int index, Viewable item, SelectableCard card) {}

    private final List<ChoiceBoard.CardItem> items;
    private final List<CardSelectionState> states;
    private final Map<Viewable, Integer> indexByContent = new IdentityHashMap<>();
    private final QuizCardFactory cardFactory;
    private final ChoiceBoardPolicy policy;
    private final Collection<? extends Viewable> localRenderContext;
    private final VirtualizedCardList cards;
    private final SearchPanel search;
    private final SearchControlsDisclosure controls;
    private Function<Viewable, ViewConfig> activeViewConfig;
    private Consumer<Choice> onChoice = ignored -> {};

    public SearchableChoiceBoard(
            List<ChoiceBoard.CardItem> items,
            QuizCardFactory cardFactory,
            ChoiceBoardPolicy policy,
            Collection<? extends Viewable> localRenderContext,
            ViewConfig answerConfig) {
        super(new BorderLayout(4, 4));
        if (cardFactory == null || policy == null) {
            throw new IllegalArgumentException(
                    "SearchableChoiceBoard needs a card factory and policy");
        }
        this.items = items == null ? List.of() : items.stream()
                .filter(item -> item != null
                        && item.source() != null
                        && item.content() != null)
                .toList();
        this.cardFactory = cardFactory;
        this.policy = policy;
        this.localRenderContext = localRenderContext == null
                ? List.of() : localRenderContext;
        this.states = new ArrayList<>(this.items.size());

        List<Viewable> contents = new ArrayList<>(this.items.size());
        for (int index = 0; index < this.items.size(); index++) {
            ChoiceBoard.CardItem item = this.items.get(index);
            indexByContent.put(item.content(), index);
            contents.add(item.content());
            states.add(CardSelectionState.IDLE);
        }

        Viewable sample = contents.isEmpty() ? null : contents.getFirst();
        @SuppressWarnings("unchecked")
        Class<? extends Viewable> type = sample == null ? null
                : (Class<? extends Viewable>) sample.getClass();
        ViewConfig initial = answerConfig == null ? new ViewConfig() : answerConfig;
        search = type == null ? null : new SearchPanel(
                type, sample,
                new SearchPanel.ConfigState(initial, initial, initial));
        controls = search == null ? null
                : new SearchControlsDisclosure(search, false);

        activeViewConfig = viewable -> {
            Integer index = indexByContent.get(viewable);
            return index == null ? initial : this.items.get(index).viewConfig();
        };
        cards = new VirtualizedCardList(this::buildCard);
        cards.setCardConfigConsumer(resolver -> activeViewConfig = resolver);
        JScrollPane scroll = new JScrollPane();
        cards.install(scroll);
        cards.setItems(contents);

        if (search != null) {
            search.setRenderContext(new RenderContext(contents));
            search.setTargetAndApplyViewConfig(cards, cards, scroll);
            add(controls, BorderLayout.NORTH);
        }
        add(scroll, BorderLayout.CENTER);
    }

    public SearchPanel search() {
        return search;
    }

    public boolean controlsExpanded() {
        return controls != null && controls.isExpanded();
    }

    public void setControlsExpanded(boolean expanded) {
        if (controls != null) controls.setExpanded(expanded);
    }

    public void onChoice(Consumer<Choice> callback) {
        onChoice = callback == null ? ignored -> {} : callback;
    }

    public void setState(int index, CardSelectionState state) {
        if (index < 0 || index >= states.size()) return;
        CardSelectionState next = state == null ? CardSelectionState.IDLE : state;
        states.set(index, next);
        Viewable content = items.get(index).content();
        if (cards.builtCard(content) instanceof SelectableCard card) {
            card.setState(next);
        }
    }

    CardSelectionState stateAt(int index) {
        return states.get(index);
    }

    private javax.swing.JComponent buildCard(Viewable content) {
        Integer index = indexByContent.get(content);
        if (index == null) return new JPanel();
        ChoiceBoard.CardItem item = items.get(index);
        ViewConfig config = activeViewConfig == null
                ? item.viewConfig() : activeViewConfig.apply(content);
        Card rendered = cardFactory.create(
                content, config, policy.cardRole(), localRenderContext,
                item.reveal());
        SelectableCard selectable =
                new SelectableCard(item.source(), rendered, policy.framedIdle());
        selectable.setState(states.get(index));
        selectable.onSelected(ignored -> onChoice.accept(
                new Choice(index, item.source(), selectable)));
        return selectable;
    }
}

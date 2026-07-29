package quiz.ordering;

import objectview.viewconfig.ViewConfig;
import objectview.Viewable;
import quiz.web.ViewableJson;
import quiz.web.ViewableView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Converts Viewables into an ordered deck. It contains no round/session state.
 * Missing order values and entity-local data errors are reported separately so
 * one malformed entity does not prevent the remaining deck from being used.
 */
public final class OrderingQuizGenerator {
    private OrderingQuizGenerator() {}

    public static GenerationResult generate(
            Collection<? extends Viewable> source,
            OrderingQuizConfig config) {
        List<OrderedItem> ordered = new ArrayList<>();
        List<InvalidItem> invalidItems = new ArrayList<>();
        int missing = 0;
        for (Viewable q : source) {
            OrderValue value;
            try {
                value = config.orderKey().extract(q);
            } catch (IllegalArgumentException e) {
                invalidItems.add(new InvalidItem(
                        q == null ? null : q.getIdentifier(),
                        q == null ? null : q.getDisplayName(),
                        messageOf(e)));
                continue;
            }
            if (value != null) {
                ordered.add(new OrderedItem(q, value));
            } else {
                missing++;
            }
        }
        Comparator<OrderedItem> comparator = (a, b) ->
                config.direction().apply(a.value().compareTo(b.value()));
        if (config.equalValuePolicy() == EqualValuePolicy.STABLE_BY_ID) {
            comparator = comparator.thenComparing(OrderedItem::id,
                    Comparator.nullsLast(String::compareTo));
        }
        ordered.sort(comparator);

        List<Item> out = new ArrayList<>(ordered.size());
        for (OrderedItem item : ordered) {
            out.add(new Item(
                    item.id(),
                    render(item.viewable(), config.promptView()),
                    render(item.viewable(), config.answerView()),
                    item.value().label()));
        }
        return new GenerationResult(out, missing, invalidItems);
    }

    private static List<ViewableView.Field> render(Viewable q, ViewConfig config) {
        List<ViewableView.Field> fields = new ArrayList<>();
        for (String name : config.getFields().keySet()) {
            ViewableView.Field field = ViewableJson.fieldOf(q, name);
            if (field != null) {
                fields.add(field);
            }
        }
        return List.copyOf(fields);
    }

    private static String messageOf(IllegalArgumentException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }

    /**
     * Diagnostic details remain a server-side generation concern. The public
     * stateless view exposes only the invalid count, except when no usable item
     * exists and the server includes one example in its error response.
     */
    public record InvalidItem(String id, String name, String message) {}

    public record GenerationResult(
            List<Item> items,
            int missing,
            List<InvalidItem> invalidItems) {

        public GenerationResult {
            items = List.copyOf(items);
            invalidItems = List.copyOf(invalidItems);
            if (missing < 0) {
                throw new IllegalArgumentException("Missing count cannot be negative");
            }
        }

        public int matched() {
            return items.size();
        }

        public int invalid() {
            return invalidItems.size();
        }
    }

    /**
     * {@code id} is transport identity, while all visible identity and detail
     * content comes from the prompt/answer ViewConfigs. {@code orderLabel} is
     * intentionally present in this single-player contract; an authoritative
     * multiplayer session view must withhold it until reveal.
     */
    public record Item(
            String id,
            List<ViewableView.Field> prompt,
            List<ViewableView.Field> answer,
            String orderLabel) {}
}

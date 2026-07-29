package quiz.ordering;

import aux.FlexibleDate;
import objectview.viewconfig.ViewConfig;
import org.junit.jupiter.api.Test;
import objectview.ViewableAdapter;
import quiz.web.ViewableJson;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderingQuizGeneratorTest {
    static final class Event extends ViewableAdapter {
        private final String id;
        private final String title;
        private final FlexibleDate date;

        Event(String id, String title, FlexibleDate date) {
            this.id = id;
            this.title = title;
            this.date = date;
        }

        @Override public String getIdentifier() { return id; }
        @Override public String getDisplayName() { return title; }
    }

    static final class RawEvent extends ViewableAdapter {
        private final String id;
        private final String title;
        private final Object date;

        RawEvent(String id, String title, Object date) {
            this.id = id;
            this.title = title;
            this.date = date;
        }

        @Override public String getIdentifier() { return id; }
        @Override public String getDisplayName() { return title; }
    }

    @Test
    void ordersItemsAndUsesIndependentPromptAndAnswerViews() {
        ViewConfig prompt = ViewConfig.of(Event.class);
        prompt.setAllFields(false);
        prompt.addField("title", ViewConfig.leaf());
        ViewConfig answer = ViewConfig.of(Event.class);
        answer.setAllFields(false);
        answer.addField("date", ViewConfig.leaf());

        OrderingQuizConfig config = new OrderingQuizConfig(
                prompt, answer, new OrderKey("date", OrderValueType.DATE),
                SortDirection.ASCENDING, EqualValuePolicy.EQUIVALENT);

        OrderingQuizGenerator.GenerationResult result =
                OrderingQuizGenerator.generate(List.of(
                new Event("later", "Later", new FlexibleDate(2000)),
                new Event("earlier", "Earlier", new FlexibleDate(1900))), config);

        assertEquals(List.of("earlier", "later"),
                result.items().stream().map(OrderingQuizGenerator.Item::id).toList());
        assertEquals("title", result.items().getFirst().prompt().getFirst().name());
        assertEquals("date", result.items().getFirst().answer().getFirst().name());
        assertEquals("1900", result.items().getFirst().orderLabel());
        assertEquals(2, result.matched());
        assertEquals(0, result.missing());
        assertEquals(0, result.invalid());

        Map<?, ?> json = ViewableJson.mapper().convertValue(
                result.items().getFirst(), Map.class);
        assertFalse(json.containsKey("name"));
        assertTrue(json.containsKey("orderLabel"));
    }

    @Test
    void skipsAndCountsMissingMalformedAndMultiValuedEntities() {
        ViewConfig prompt = ViewConfig.of(RawEvent.class);
        prompt.setAllFields(false);
        prompt.addField("title", ViewConfig.leaf());
        ViewConfig answer = ViewConfig.of(RawEvent.class);
        answer.setAllFields(false);
        answer.addField("date", ViewConfig.leaf());
        OrderingQuizConfig config = new OrderingQuizConfig(
                prompt, answer, new OrderKey("date", OrderValueType.DATE),
                SortDirection.ASCENDING, EqualValuePolicy.EQUIVALENT);

        OrderingQuizGenerator.GenerationResult result =
                OrderingQuizGenerator.generate(List.of(
                        new RawEvent("valid", "Valid", "1900"),
                        new RawEvent("missing", "Missing", null),
                        new RawEvent("malformed", "Malformed", "unknown"),
                        new RawEvent("multiple", "Multiple",
                                List.of("1800", "1801"))), config);

        assertEquals(List.of("valid"),
                result.items().stream().map(OrderingQuizGenerator.Item::id).toList());
        assertEquals(1, result.matched());
        assertEquals(1, result.missing());
        assertEquals(2, result.invalid());
        assertEquals(List.of("malformed", "multiple"),
                result.invalidItems().stream()
                        .map(OrderingQuizGenerator.InvalidItem::id).toList());
        assertTrue(result.invalidItems().getFirst().message().contains("Not a date"));
        assertTrue(result.invalidItems().getLast().message()
                .contains("must resolve to one value"));
    }

    @Test
    void stableByIdBreaksGenerationTiesDeterministically() {
        ViewConfig empty = ViewConfig.of(RawEvent.class);
        empty.setAllFields(false);
        OrderingQuizConfig config = new OrderingQuizConfig(
                empty, empty, new OrderKey("date", OrderValueType.DATE),
                SortDirection.ASCENDING, EqualValuePolicy.STABLE_BY_ID);

        OrderingQuizGenerator.GenerationResult result =
                OrderingQuizGenerator.generate(List.of(
                        new RawEvent("b", "B", "1900"),
                        new RawEvent("a", "A", "1900")), config);

        assertEquals(List.of("a", "b"),
                result.items().stream().map(OrderingQuizGenerator.Item::id).toList());
    }
}

package quiz.web.ordering;

import com.fasterxml.jackson.annotation.JsonInclude;
import quiz.ordering.EqualValuePolicy;
import quiz.ordering.OrderValueType;
import quiz.ordering.OrderingQuizGenerator;
import quiz.ordering.SortDirection;

import java.util.List;

/**
 * Stateless first-slice JSON contract. Like the current ABCD contract, it
 * includes answers for casual client-side play. Multiplayer will use a
 * separate server-authoritative session view that withholds orderLabel.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderingQuizView(
        String quizType,
        String presentation,
        String type,
        String orderField,
        OrderValueType valueType,
        SortDirection direction,
        EqualValuePolicy equalValuePolicy,
        // Pool provenance: matched + missing + invalid == total. "items" is the
        // shuffled, capped subset actually dealt, so items.size() may be smaller
        // than matched. Entity-local invalid values do not abort a mixed deck.
        int total,
        int matched,
        int missing,
        int invalid,
        List<OrderingQuizGenerator.Item> items) {}

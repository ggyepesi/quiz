package nobel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MotivationParserTest {

    @Test
    void blankMotivationDoesNotCreateAnInvisibleTopic() {
        MotivationParser.Motivation motivation = MotivationParser.parse("");

        assertTrue(motivation.topics.isEmpty());
        assertTrue(motivation.keywords.isEmpty());
    }
}

package nobel;

import java.util.*;
import java.util.regex.*;

import objectview.ViewableAdapter;

public class MotivationParser {

    public static class Motivation extends ViewableAdapter implements quiz.ValueObject {
        public String action;
        public List<String> topics = new ArrayList<>();
        public String method;
        public String application;
        public List<String> keywords = new ArrayList<>();

        @Override
        public String toString() {
            String s = "";
            if (action != null) s += " Action: " + action;
            if (!topics.isEmpty()) s += " Topics: " + topics;
            if (method != null) s += " Method: " + method;
            if (application != null) s += " Application: " + application;
            if (!keywords.isEmpty()) s += " Keywords: " + keywords;
            return s;
        }

        // A VALUE object ({@link quiz.ValueObject}) — inlined in its
        // LaureatesWithMotivation, no identity invented (a constant id had merged every
        // motivation into one entity, cross-contaminating topics). The topics are its
        // LABEL, not an identifier.
        @Override
        public String getIdentifier() { return getDisplayName(); }

        @Override
        public String getDisplayName() {
            return topics.isEmpty() ? "Motivation" : String.join(", ", topics);
        }
    }

    private static final List<String> ACTIONS = List.of(
            "discovery","discoveries",
            "research",
            "development",
            "invention",
            "analysis",
            "work",
            "contribution","contributions",
            "efforts",
            "studies",
            "advances"
    );

    private static final Pattern METHOD_PATTERN =
            Pattern.compile("\\b(by|through|using|via|with|based on)\\b (.+)",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern APPLICATION_PATTERN =
            Pattern.compile("\\b(for|in|leading to|resulting in|which led to)\\b (.+)",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern TOPIC_PATTERN =
            Pattern.compile("\\b(of|concerning|into|on|about)\\b (.+)",
                    Pattern.CASE_INSENSITIVE);

    public static Motivation parse(String text) {

        Motivation m = new Motivation();

        text = normalize(text);

        m.action = detectAction(text);

        text = removeAction(text, m.action);

        text = removeLeadingArticles(text);

        text = extractMethod(text, m);

        text = extractApplication(text, m);

        text = extractTopics(text, m);

        extractKeywords(m);

        return m;
    }

    private static String normalize(String text) {

        text = text.trim();

        if (text.toLowerCase().startsWith("for "))
            text = text.substring(4);

        text = text.replaceAll("[\"“”]", "");

        return text;
    }

    private static String detectAction(String text) {

        String lower = text.toLowerCase();

        for (String a : ACTIONS)
            if (lower.startsWith(a))
                return a;

        for (String a : ACTIONS)
            if (lower.contains(a))
                return a;

        return "unknown";
    }

    private static String removeAction(String text, String action) {

        if (action == null)
            return text;

        String lower = text.toLowerCase();

        if (lower.startsWith(action))
            return text.substring(action.length()).trim();

        return text;
    }

    private static String removeLeadingArticles(String text) {

        return text.replaceFirst("^(the|their|his|her)\\s+", "");
    }

    private static String extractMethod(String text, Motivation m) {

        Matcher matcher = METHOD_PATTERN.matcher(text);

        if (matcher.find()) {

            m.method = matcher.group(2).trim();

            text = text.substring(0, matcher.start()).trim();
        }

        return text;
    }

    private static String extractApplication(String text, Motivation m) {

        Matcher matcher = APPLICATION_PATTERN.matcher(text);

        if (matcher.find()) {

            m.application = matcher.group(2).trim();

            text = text.substring(0, matcher.start()).trim();
        }

        return text;
    }

    private static String extractTopics(String text, Motivation m) {

        Matcher matcher = TOPIC_PATTERN.matcher(text);

        if (matcher.find()) {

            String topicsText = matcher.group(2);

            String[] split = topicsText.split("\\band\\b|,");

            for (String t : split) {

                String topic = t.trim();

                if (!topic.isEmpty())
                    m.topics.add(topic);
            }

        } else {

            String topic = text.trim();
            if (!topic.isEmpty())
                m.topics.add(topic);
        }

        return text;
    }

    private static void extractKeywords(Motivation m) {

        Set<String> stopwords = Set.of(
                "of","the","and","in","to","for","its",
                "their","his","her","about","into"
        );

        for (String topic : m.topics) {

            String[] words = topic.toLowerCase().split("\\W+");

            for (String w : words) {

                if (w.length() > 3 && !stopwords.contains(w))
                    m.keywords.add(w);
            }
        }
    }

    public static void main(String[] args) {

        List<String> motivations = List.of(
                "for the discovery of microRNA and its role in post-transcriptional gene regulation",
                "for their discoveries concerning organization and elicitation of individual and social behaviour patterns",
                "for the development of cryo-electron microscopy for the high-resolution structure determination of biomolecules in solution",
                "for efforts to build and disseminate greater knowledge about man-made climate change"
        );

        for (String s : motivations) {
            Motivation m = parse(s);

            System.out.println("Original: " + s);
            System.out.println(m);
            System.out.println("-----------------------------------");
        }
    }
}

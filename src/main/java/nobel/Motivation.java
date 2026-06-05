package nobel;

import quiz.QuizableAdapter;

import java.util.ArrayList;
import java.util.List;

public class Motivation extends QuizableAdapter {
    private String originalText;

    private String awardReason;     // short natural-language summary
    private String action;          // discovery, development, invention, efforts...
    private String field;           // theoretical physics, molecular biology...
    private final List<String> topics = new ArrayList<>();
    private final List<String> methods = new ArrayList<>();
    private final List<String> applications = new ArrayList<>();
    private final List<String> objects = new ArrayList<>();   // atoms, microRNA, biomolecules...
    private final List<String> keywords = new ArrayList<>();

    public String getOriginalText() { return originalText; }
    public String getAwardReason() { return awardReason; }
    public String getAction() { return action; }
    public String getField() { return field; }
    public List<String> getTopics() { return topics; }
    public List<String> getMethods() { return methods; }
    public List<String> getApplications() { return applications; }
    public List<String> getObjects() { return objects; }
    public List<String> getKeywords() { return keywords; }

    @Override
    public String getName() {
        if (awardReason != null && !awardReason.isBlank()) return awardReason;
        if (!topics.isEmpty()) return String.join(", ", topics);
        return originalText == null ? "Motivation" : originalText;
    }

    @Override
    public String toString() {
        return getName();
    }

    @Override
    public QuizableAdapter createNew() {
        return new Motivation();
    }
}
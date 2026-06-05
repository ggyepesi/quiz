package wikidata.explore.qid;

import quiz.QuizableAdapter;

public class TopQidStatement extends QuizableAdapter {

    private final String pid;
    private final String name;
    private final String propertyDescription;
    private final String valueQid;
    private final String valueLabel;
    private final String rawValue;

    public TopQidStatement(
            String pid,
            String propertyLabel,
            String propertyDescription,
            String valueQid,
            String valueLabel,
            String rawValue) {

        this.pid = pid == null ? "" : pid;
        this.name = propertyLabel == null || propertyLabel.isBlank()
                ? this.pid
                : propertyLabel;
        this.propertyDescription =
                propertyDescription == null ? "" : propertyDescription;
        this.valueQid = valueQid == null ? "" : valueQid;
        this.valueLabel = valueLabel == null ? "" : valueLabel;
        this.rawValue = rawValue == null ? "" : rawValue;
    }

    @Override
    public String getName() {
        return name;
    }

    public String pid() {
        return pid;
    }

    public String propertyDescription() {
        return propertyDescription;
    }

    public String valueQid() {
        return valueQid;
    }

    public String valueLabel() {
        return valueLabel;
    }

    public String rawValue() {
        return rawValue;
    }

    public String valueDisplay() {
        String value = !valueLabel.isBlank() ? valueLabel : rawValue;

        if (!valueQid.isBlank()) {
            value += " (" + valueQid + ")";
        }

        return value;
    }

    public boolean hasValueQid() {
        return valueQid != null && valueQid.matches("Q\\d+");
    }

    public TopQidResult asQidResult() {
        if (!hasValueQid()) {
            return null;
        }

        String label = !valueLabel.isBlank() ? valueLabel : valueQid;
        return new TopQidResult(valueQid, label, "");
    }

    @Override
    public String toString() {
        return name + " (" + pid + ") -> " + valueDisplay();
    }

    @Override
    public QuizableAdapter createNew() {
        return null;
    }
}

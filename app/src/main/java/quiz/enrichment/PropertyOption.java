package quiz.enrichment;

/** One selectable property in the field-property picker: its id, human label, and an
 *  example value drawn from the sample entity — so the choice is made from real data. */
public record PropertyOption(String pid, String label, String example) {

    public PropertyOption {
        pid = pid == null ? "" : pid.trim();
        label = label == null ? "" : label.trim();
        example = example == null ? "" : example.trim();
    }
}

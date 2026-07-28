package quiz.enrichment;

/** The Wikidata property a user chose to source a field from — picked from the sample
 *  entity's real claims, not a hardcoded name→property map. */
public record ChosenProperty(String pid, String label) {

    public ChosenProperty {
        pid = pid == null ? "" : pid.trim();
        label = label == null ? "" : label.trim();
    }

    public boolean isPresent() {
        return pid.matches("P\\d+");
    }
}

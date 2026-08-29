package wikidata.explore.demo.closure;

/** A reached value together with the population member that witnesses the connection. */
public record SharedPopulationEdge(
        String sourceQid,
        String sourceLabel,
        String memberQid,
        String memberLabel,
        String targetQid,
        String targetLabel) {

    public SharedPopulationEdge {
        sourceLabel = labelOrId(sourceLabel, sourceQid);
        memberLabel = labelOrId(memberLabel, memberQid);
        targetLabel = labelOrId(targetLabel, targetQid);
    }

    private static String labelOrId(String label, String id) {
        return label == null || label.isBlank() ? id : label;
    }
}

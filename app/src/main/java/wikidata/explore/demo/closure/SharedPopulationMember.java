package wikidata.explore.demo.closure;

/** One member of the population selected by a frontier value. */
public record SharedPopulationMember(
        String sourceQid,
        String sourceLabel,
        String memberQid,
        String memberLabel) {

    public SharedPopulationMember {
        sourceLabel = labelOrId(sourceLabel, sourceQid);
        memberLabel = labelOrId(memberLabel, memberQid);
    }

    private static String labelOrId(String label, String id) {
        return label == null || label.isBlank() ? id : label;
    }
}

package wikidata.rule;

import wikidata.query.WikidataQueryBuilder;

import java.util.List;

public record WikidataRuleSpec(
        String name,
        String rootExampleName,
        String rootExampleQid,
        String itemVar,
        String relationPropertyPid,
        String relationDirection,
        List<String> targetTypeQids,
        boolean useSubclassClosure
) {

    public String toSparql(String rootQid) {
        String typePath = useSubclassClosure
                ? "wdt:P31/wdt:P279*"
                : "wdt:P31";

        WikidataQueryBuilder b = new WikidataQueryBuilder()
                .select(itemVar, itemVar + "Label")
                .label(itemVar);

        if (targetTypeQids != null && !targetTypeQids.isEmpty()) {
            b.values("type", targetTypeQids.toArray(String[]::new));
            b.where("?" + itemVar + " " + typePath + " ?type .");
        }

        if ("ITEM_TO_ROOT".equals(relationDirection)) {
            b.where("?" + itemVar
                            + " wdt:" + relationPropertyPid
                            + " wd:" + rootQid + " .");
        } else {
            b.where("wd:" + rootQid
                            + " wdt:" + relationPropertyPid
                            + " ?" + itemVar + " .");
        }

        return b.build();
    }

    public String meaning(String currentRootName, String currentRootQid) {
        StringBuilder sb = new StringBuilder();

        sb.append("Rule: ").append(name).append("\n");

        sb.append("Root: ")
          .append(currentRootName)
          .append(" (")
          .append(currentRootQid)
          .append(")\n");

        sb.append("Relation property: ")
          .append(relationPropertyPid)
          .append("\n");

        sb.append("Direction: ");

        if ("ITEM_TO_ROOT".equals(relationDirection)) {
            sb.append("?")
              .append(itemVar)
              .append(" --")
              .append(relationPropertyPid)
              .append("--> root\n");
        } else {
            sb.append("root --")
              .append(relationPropertyPid)
              .append("--> ?")
              .append(itemVar)
              .append("\n");
        }

        sb.append("Target type QIDs: ")
          .append(targetTypeQids)
          .append("\n");

        sb.append("Subclass closure: ")
          .append(useSubclassClosure)
          .append("\n");

        return sb.toString();
    }

    public String commentedQuery(
            String currentRootName,
            String currentRootQid
                                ) {
        return """
                # %s
                # Root: %s (%s)
                # Relation property: %s
                # Direction: %s
                # Target types: %s
                # Subclass closure: %s

                %s
                """.formatted(
                name,
                currentRootName,
                currentRootQid,
                relationPropertyPid,
                relationDirection,
                targetTypeQids,
                useSubclassClosure,
                toSparql(currentRootQid));
    }

    @Override
    public String toString() {
        return name
                + " [" + relationPropertyPid + "] "
                + targetTypeQids;
    }
}
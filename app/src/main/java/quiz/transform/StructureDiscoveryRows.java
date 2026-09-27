package quiz.transform;

import objectview.Viewable;

import java.util.ArrayList;
import java.util.List;

/**
 * ObjectView rows for every inspectable intermediate structure.
 *
 * <p>The words are the bridge's own: shared ENTITIES, whatever the configured shared
 * field points at. Calling them holders would name History's first bridge inside a
 * construct meant to serve any two-field bridge — a country or a composer would then be
 * reported as a holder.
 */
public final class StructureDiscoveryRows {
    public static final String FAMILY = "StructureFamily";
    public static final String SHARED_LINK = "SharedNeighbourLink";

    private StructureDiscoveryRows() { }

    public static List<Viewable> families(StructureDiscovery.Result result) {
        if (result == null) return List.of();
        List<Viewable> rows = new ArrayList<>();
        for (StructureDiscovery.Family family : result.families()) {
            DynamicViewable row = new DynamicViewable("family-" + family.index(),
                    family.representative().getDisplayName());
            row.type(FAMILY);
            row.put("representative", family.representative());
            row.put("representativeSharedCount", family.representativeSharedCount());
            row.put("familySharedCount", family.sharedEntities().size());
            row.put("familySize", family.members().size());
            row.put("members", family.members());
            row.put("sharedEntities", family.sharedEntities());
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    public static List<Viewable> links(StructureDiscovery.Result result) {
        if (result == null) return List.of();
        List<Viewable> rows = new ArrayList<>();
        int index = 0;
        for (StructureDiscovery.SharedLink link : result.links()) {
            String left = link.left().representative().getDisplayName();
            String right = link.right().representative().getDisplayName();
            DynamicViewable row = new DynamicViewable("shared-link-" + index++,
                    left + " — " + right);
            row.type(SHARED_LINK);
            row.put("left", link.left().representative());
            row.put("right", link.right().representative());
            row.put("sharedCount", link.sharedEntities().size());
            row.put("sharedEntities", link.sharedEntities());
            rows.add(row);
        }
        return List.copyOf(rows);
    }
}

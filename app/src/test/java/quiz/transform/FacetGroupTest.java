package quiz.transform;

import objectview.Viewable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class FacetGroupTest {

    private static DynamicViewable city(String name, String region) {
        DynamicViewable c = new DynamicViewable(name, name);
        c.type("City");
        c.put("region", region);
        return c;
    }

    @Test void partitionsByFieldAndRefreshesOnReproduce() {
        List<Viewable> members = new ArrayList<>(List.of(
                city("Paris", "Europe"),
                city("Berlin", "Europe"),
                city("Tokyo", "Asia")));

        FacetGroup fg = new FacetGroup("By region", "City", "region");
        fg.reproduce(members);

        assertEquals("City", fg.memberType());
        assertEquals("region", fg.field());
        assertEquals(3, fg.getMembers().size());
        // Structure the group-tree renderer expects: FacetGroup -> "region" -> buckets.
        objectview.group.ViewableGroup<?> dim = fg.getChild("region");
        assertNotNull(dim);
        assertNotNull(dim.getChild("Europe"));
        assertNotNull(dim.getChild("Asia"));
        assertEquals(2, dim.getChild("Europe").getMembers().size());
        assertNull(dim.getChild("Africa"));

        // instance set changes online -> reproduce refreshes the buckets from the rule
        members.add(city("Cairo", "Africa"));
        fg.reproduce(members);
        assertEquals(4, fg.getMembers().size());
        objectview.group.ViewableGroup<?> dim2 = fg.getChild("region");
        assertNotNull(dim2.getChild("Africa"));
        assertEquals(1, dim2.getChild("Africa").getMembers().size());
    }
}

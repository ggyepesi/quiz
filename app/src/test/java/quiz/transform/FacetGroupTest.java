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
        assertNotNull(fg.getChild("Europe"));
        assertNotNull(fg.getChild("Asia"));
        assertEquals(2, fg.getChild("Europe").getMembers().size());
        assertNull(fg.getChild("Africa"));

        // instance set changes online -> reproduce refreshes the buckets from the rule
        members.add(city("Cairo", "Africa"));
        fg.reproduce(members);
        assertEquals(4, fg.getMembers().size());
        assertNotNull(fg.getChild("Africa"));
        assertEquals(1, fg.getChild("Africa").getMembers().size());
    }
}

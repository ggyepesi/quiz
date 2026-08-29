package wikidata.explore.workbench;

import objectview.group.DefaultViewableGroup;
import objectview.group.ViewableGroup;
import wikidata.explore.PropertyStructuralHints;
import wikidata.explore.WikidataProperty;

import java.util.List;
import java.util.Map;

/** Builds the overlapping ViewableGroup structure derived from property metadata. */
final class PropertyStructureGroups {
    private PropertyStructureGroups() { }

    static PropertyGroup build(
            List<WikidataProperty> properties,
            Map<String, WikidataPropertyViewable> viewsByPid) {
        PropertyGroup root = new PropertyGroup("All properties");
        for (WikidataProperty property : properties) {
            WikidataPropertyViewable view = viewsByPid.get(property.pid());
            if (view == null) continue;
            root.addMember(view, false);
            List<String> hints = PropertyStructuralHints.of(property);
            if (hints.isEmpty()) {
                root.getOrCreateChild("Other values").role(ViewableGroup.Role.BUCKET)
                        .addMember(view, false);
            } else {
                for (String hint : hints) {
                    root.getOrCreateChild(hint).role(ViewableGroup.Role.BUCKET)
                            .addMember(view, false);
                }
            }
        }
        return root;
    }

    static final class PropertyGroup
            extends DefaultViewableGroup<WikidataPropertyViewable, PropertyGroup> {
        private PropertyGroup(String name) { super(name); }
        @Override protected PropertyGroup newChild(String name) {
            return new PropertyGroup(name);
        }
    }
}

package wikidata.explore.extract;

import wikidata.explore.extract.WikidataDynamicObjectJsonStore;

import wikidata.explore.extract.WikidataDynamicObject;

import objectview.viewconfig.DomainViews;
import objectview.Viewable;
import quiz.group.ViewableGroup;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GeneratedKnowledgeSet implements DomainViews {

    private final String name;
    private final File file;

    private final Map<String, WikidataDynamicObject> viewables =
            new LinkedHashMap<>();

    private ViewableGroup rootGroup;

    public GeneratedKnowledgeSet(String name, File file) {
        this.name = name == null ? "Generated Wikidata Set" : name;
        this.file = file;
    }

    @Override
    public void buildViews() throws Exception {
        List<WikidataDynamicObject> objects =
                new WikidataDynamicObjectJsonStore().load(file);

        viewables.clear();

        for (WikidataDynamicObject object : objects) {
            String key =
                    object.qid() == null || object.qid().isBlank()
                            ? object.getName()
                            : object.qid();

            viewables.put(key, object);
        }

        rootGroup = new ViewableGroup(name);

        for (WikidataDynamicObject object : viewables.values()) {
            rootGroup.addMember(object);
        }
    }

    @Override
    public java.util.List<objectview.viewconfig.DomainGroupRoot> getGroupRootBindings() {
        if (rootGroup == null || viewables.isEmpty()) return java.util.List.of();
        String memberType = viewables.values().iterator().next().typeName();
        return java.util.List.of(new objectview.viewconfig.DomainGroupRoot(
                memberType, rootGroup));
    }

    @Override
    public Map<String, ? extends Viewable> getViewables() {
        return viewables;
    }

    @Override
    public String toString() {
        return name + " (" + viewables.size() + ")";
    }
}

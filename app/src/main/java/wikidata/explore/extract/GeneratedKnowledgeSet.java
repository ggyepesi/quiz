package wikidata.explore.extract;

import objectview.viewconfig.DomainViews;
import objectview.Viewable;
import quiz.ViewableGroup;

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
    public java.util.List<? extends objectview.group.ViewableGroup<?>> getRootGroups() {
        return rootGroup == null ? java.util.List.of() : java.util.List.of(rootGroup);
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

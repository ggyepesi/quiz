package wikidata.explore.extract;

import objectview.viewconfig.DomainViews;
import objectview.Viewable;
import quiz.ViewableGroup;
import objectview.render.GroupView;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GeneratedKnowledgeSet implements DomainViews {

    private final String name;
    private final File file;

    private final Map<String, WikidataDynamicObject> viewables =
            new LinkedHashMap<>();

    private GroupView groupView;

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

        ViewableGroup root = new ViewableGroup(name);

        for (WikidataDynamicObject object : viewables.values()) {
            root.addMember(object);
        }

        groupView =
                new GroupView(root);
    }

    @Override
    public GroupView getGroupView() {
        return groupView;
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
package wikidata.explore.extract;

import objectview.viewconfig.DomainViews;
import quiz.Quizable;
import quiz.QuizableGroup;
import objectview.render.GroupView;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GeneratedKnowledgeSet implements DomainViews {

    private final String name;
    private final File file;

    private final Map<String, WikidataDynamicObject> quizables =
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

        quizables.clear();

        for (WikidataDynamicObject object : objects) {
            String key =
                    object.qid() == null || object.qid().isBlank()
                            ? object.getName()
                            : object.qid();

            quizables.put(key, object);
        }

        QuizableGroup root = new QuizableGroup(name);

        for (WikidataDynamicObject object : quizables.values()) {
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
    public Map<String, ? extends Quizable> getViewables() {
        return quizables;
    }

    @Override
    public String toString() {
        return name + " (" + quizables.size() + ")";
    }
}
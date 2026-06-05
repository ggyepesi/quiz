package wikidata.explore.tree;

import quiz.Quizable;
import quiz.QuizableGroup;
import quiz.ui.QuizableGroupView;
import quiz.ui.QuizableViews;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GeneratedKnowledgeSet implements QuizableViews {

    private final String name;
    private final File file;

    private final Map<String, WikidataDynamicObject> quizables =
            new LinkedHashMap<>();

    private QuizableGroupView groupView;

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
                new QuizableGroupView(root);
    }

    @Override
    public QuizableGroupView getGroupView() {
        return groupView;
    }

    @Override
    public Map<String, ? extends Quizable> getQuizables() {
        return quizables;
    }

    @Override
    public String toString() {
        return name + " (" + quizables.size() + ")";
    }
}
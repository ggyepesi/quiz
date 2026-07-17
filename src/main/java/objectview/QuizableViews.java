package objectview;


import objectview.Viewable;

import java.util.Map;

public interface QuizableViews {
    public void buildViews() throws Exception;
    public QuizableGroupView getGroupView();
    public Map<String, ? extends Viewable> getQuizables();
}
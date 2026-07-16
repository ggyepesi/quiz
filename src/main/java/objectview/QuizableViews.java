package objectview;


import quiz.Quizable;

import java.util.Map;

public interface QuizableViews {
    public void buildViews() throws Exception;
    public QuizableGroupView getGroupView();
    public Map<String, ? extends Quizable> getQuizables();
}
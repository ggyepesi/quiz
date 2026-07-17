package objectview;


import java.util.Map;

public interface ViewableViews {
    public void buildViews() throws Exception;
    public ViewableGroupView getGroupView();
    public Map<String, ? extends Viewable> getQuizables();
}
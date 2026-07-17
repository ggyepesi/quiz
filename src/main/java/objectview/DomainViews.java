package objectview;


import java.util.Map;

public interface DomainViews {
    public void buildViews() throws Exception;
    public GroupView getGroupView();
    public Map<String, ? extends Viewable> getViewables();
}
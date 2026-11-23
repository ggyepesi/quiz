package domain.datasource.discovery.ui;

import javax.swing.JTabbedPane;

/**
 * UI handoff from one datasource operation to the shared action/result navigator.
 * The source panel still owns and renders every result tab; the navigator mirrors only
 * their labels, order and active tab under the action that produced them.
 */
public interface DiscoveryResultListener {
    DiscoveryResultListener NONE = new DiscoveryResultListener() {
        @Override public void published(
                String actionId, JTabbedPane tabs, int primaryTab) { }
        @Override public void invalidated(String... actionIds) { }
    };

    void published(String actionId, JTabbedPane tabs, int primaryTab);

    void invalidated(String... actionIds);
}

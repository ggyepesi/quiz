package domain.workbench.unified.app;

import domain.transform.app.DomainCatalog;
import domain.transform.ui.DomainNavigator;
import domain.workbench.execution.ui.SwingQuerySession;
import domain.workbench.unified.ui.UnifiedWorkbenchPanel;
import wikidata.WikidataSparqlClient;
import wikidata.api.WikidataApiClient;
import wikidata.query.core.QueryFactory;

import javax.swing.JFrame;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

public final class UnifiedWorkbenchApp {
    private static final String USER_AGENT = "QuizProject/1.0 (ggyepesi@gmail.com)";
    private UnifiedWorkbenchApp() { }

    public static void main(String[] args) {
        DomainNavigator.show(DomainCatalog::modelBacked, "Unified Workbench — saved projects",
                "Open in Unified Workbench", (domain, name) -> {
                    var client = new WikidataSparqlClient(USER_AGENT, 2);
                    var factory = new QueryFactory(client, new WikidataApiClient(USER_AGENT), USER_AGENT);
                    var panel = new UnifiedWorkbenchPanel(domain, new SwingQuerySession(factory.newContext()));
                    var frame = new JFrame("Unified Workbench — " + name);
                    frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
                    frame.add(panel);
                    frame.addWindowListener(new WindowAdapter() {
                        @Override public void windowClosing(WindowEvent event) {
                            panel.requestClose(frame::dispose);
                        }

                        @Override public void windowClosed(WindowEvent event) {
                            panel.close();
                            factory.close();
                            client.close();
                        }
                    });
                    frame.setSize(1260, 870);
                    frame.setLocationRelativeTo(null);
                    frame.setVisible(true);
                    return frame;
                });
    }
}

package graphview;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import netscape.javascript.JSObject;
import objectview.utils.BrowserLauncher;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

/** Swing host for a local Cytoscape.js graph. No provider or domain knowledge lives here. */
public final class InteractiveGraphView extends JPanel implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CYTOSCAPE = "/META-INF/resources/webjars/cytoscape/3.33.1/dist/cytoscape.min.js";
    private static final String HTML_LABEL = "/META-INF/resources/webjars/cytoscape-node-html-label/1.2.2/dist/cytoscape-node-html-label.min.js";

    /** The classpath assets this renderer needs, so a version bump fails at build. */
    static java.util.List<String> assetPaths() {
        return java.util.List.of(CYTOSCAPE, HTML_LABEL);
    }
    private final JPanel host = new JPanel(new BorderLayout());
    private final JLabel status = new JLabel("Run discovery to draw the graph.");
    // Written on the EDT, read on the FX thread — by the load handler that sends the
    // first graph, and by the bridge resolving a clicked node's link. A stale read here
    // would look like a node on screen having no link, intermittently.
    private volatile GraphViewModel model = new GraphViewModel(List.of(), List.of());
    private Consumer<Set<String>> selectionListener = ignored -> { };
    private Consumer<String> activationListener = ignored -> { };
    private volatile WebEngine engine;
    private boolean initialized;

    public InteractiveGraphView() {
        super(new BorderLayout());
        JToolBar tools = new JToolBar();
        tools.setFloatable(false);
        tools.add(button("Fit", "fitGraph()"));
        tools.add(button("Zoom in", "zoomGraph(1.25)"));
        tools.add(button("Zoom out", "zoomGraph(0.8)"));
        tools.add(button("Collapse selected", "collapseSelected()"));
        tools.add(button("Expand selected", "expandSelected()"));
        tools.add(button("Open selected link", "openSelected()"));
        JLabel help = new JLabel("  ⓘ  ");
        help.setToolTipText("<html><b>Graph controls</b><br>Click a node to select it<br>Cmd/Ctrl-click to extend selection<br>Drag nodes or the canvas<br>Scroll or use Zoom to change scale<br>Right-click a node to fold/unfold descendants<br>Click the QID link to open the source</html>");
        tools.add(help);
        tools.addSeparator();
        tools.add(status);
        add(tools, BorderLayout.NORTH);
        add(host, BorderLayout.CENTER);
    }

    @Override public void addNotify() {
        super.addNotify();
        if (!initialized) {
            initialized = true;
            JFXPanel fxHost = new JFXPanel();
            host.add(fxHost, BorderLayout.CENTER);
            host.revalidate();
            Platform.runLater(() -> initializeFx(fxHost));
        }
    }

    public void model(GraphViewModel value) {
        model = value == null ? new GraphViewModel(List.of(), List.of()) : value;
        runScript("setGraph(" + modelJson(model) + ")");
    }

    public void onSelectionChanged(Consumer<Set<String>> listener) {
        selectionListener = listener == null ? ignored -> { } : listener;
    }

    public void onActivated(Consumer<String> listener) {
        activationListener = listener == null ? ignored -> { } : listener;
    }

    @Override public void close() {
        WebEngine current = engine;
        engine = null;
        if (current != null) Platform.runLater(() -> current.load(null));
    }

    private void initializeFx(JFXPanel fxHost) {
        WebView web = new WebView();
        engine = web.getEngine();
        engine.getLoadWorker().stateProperty().addListener((ignored, oldState, state) -> {
            if (state == javafx.concurrent.Worker.State.SUCCEEDED) {
                JSObject window = (JSObject) engine.executeScript("window");
                window.setMember("graphBridge", new Bridge());
                engine.executeScript("setGraph(" + modelJson(model) + ")");
            }
        });
        engine.loadContent(page());
        fxHost.setScene(new Scene(web));
    }

    private JButton button(String label, String script) {
        JButton button = new JButton(label);
        button.addActionListener(event -> runScript(script));
        return button;
    }

    private void runScript(String script) {
        WebEngine current = engine;
        if (current != null) Platform.runLater(() -> {
            try { current.executeScript(script); }
            catch (RuntimeException failure) { message("Graph action failed: " + failure.getMessage()); }
        });
    }

    private void message(String value) {
        SwingUtilities.invokeLater(() -> status.setText(value == null ? "" : value));
    }

    /** Public because JavaScript calls this object through the JavaFX bridge. */
    public final class Bridge {
        public void selection(String json) {
            try {
                @SuppressWarnings("unchecked") List<String> ids = JSON.readValue(json, List.class);
                Set<String> selected = Set.copyOf(ids);
                SwingUtilities.invokeLater(() -> selectionListener.accept(selected));
            } catch (IOException failure) { message("Could not read graph selection."); }
        }
        public void open(String id) {
            model.nodes().stream().filter(node -> node.id().equals(id)).findFirst().ifPresent(node -> {
                if (node.link() != null) BrowserLauncher.open(node.link().toString());
                SwingUtilities.invokeLater(() -> activationListener.accept(id));
            });
        }
        public void message(String value) { InteractiveGraphView.this.message(value); }
    }

    static String modelJson(GraphViewModel model) {
        List<Map<String, Object>> nodes = model.nodes().stream().map(node -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", node.id()); data.put("label", node.label());
            data.put("link", node.link() == null ? "" : node.link().toString());
            data.put("level", node.level()); data.put("state", node.state().name().toLowerCase());
            data.put("details", node.details());
            return Map.<String, Object>of("data", data, "classes", data.get("state"));
        }).toList();
        List<Map<String, Object>> edges = model.edges().stream().map(edge -> Map.<String, Object>of("data",
                Map.of("id", edge.id(), "source", edge.sourceId(), "target", edge.targetId(),
                        "label", edge.label(), "directed", edge.directed()))).toList();
        try { return JSON.writeValueAsString(Map.of("nodes", nodes, "edges", edges)); }
        catch (JsonProcessingException impossible) { throw new IllegalArgumentException(impossible); }
    }

    static String page() {
        return PAGE.replace("__CYTOSCAPE__", resource(CYTOSCAPE)).replace("__HTML_LABEL__", resource(HTML_LABEL));
    }

    private static String resource(String path) {
        try (InputStream input = InteractiveGraphView.class.getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing graph resource " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("</script>", "<\\/script>");
        } catch (IOException failure) { throw new IllegalStateException("Cannot load graph resource " + path, failure); }
    }

    private static final String PAGE = """
        <!doctype html><html><head><meta charset="UTF-8"><style>
        html,body,#cy{width:100%;height:100%;margin:0;overflow:hidden;font:13px sans-serif;background:#f7f8fa}
        .node-card{box-sizing:border-box;min-width:145px;max-width:220px;padding:8px 10px;border:2px solid #8795aa;border-radius:10px;background:#e8eef8;text-align:center;box-shadow:0 1px 3px #0002;pointer-events:auto}
        .node-card.frontier{background:#fff2cc;border-color:#c59a28}.node-card.expanded{background:#dcefe2;border-color:#4e9567}.node-card.unavailable{background:#f7dddd;border-color:#b65e5e}
        .node-label{font-weight:600;overflow-wrap:anywhere}.node-link{display:inline-block;margin-top:3px;color:#1d5fa7;text-decoration:underline}.node-details{margin-top:3px;color:#596579;font-size:11px}
        </style><script>__CYTOSCAPE__</script><script>__HTML_LABEL__</script></head><body><div id="cy"></div><script>
        let cy=cytoscape({container:document.getElementById('cy'),elements:[],selectionType:'single',boxSelectionEnabled:true,wheelSensitivity:0.25,style:[
          {selector:'node',style:{'width':165,'height':62,'background-opacity':0,'border-width':0,'label':''}},
          {selector:'node:selected',style:{'overlay-color':'#3478c8','overlay-opacity':0.18,'overlay-padding':8}},
          {selector:'edge',style:{'curve-style':'bezier','width':2,'line-color':'#718097','target-arrow-color':'#718097','target-arrow-shape':'triangle','label':'data(label)','font-size':11,'text-background-color':'#f7f8fa','text-background-opacity':1,'text-background-padding':2}},
          {selector:'.undirected',style:{'target-arrow-shape':'none'}}]});
        const esc=s=>String(s??'').replace(/[&<>\"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',"'":'&#39;'}[c]));
        const details=d=>Object.entries(d.details||{}).map(([k,v])=>`<span><b>${esc(k)}:</b> ${esc(v)}</span>`).join(' · ');
        cy.nodeHtmlLabel([{query:'node',tpl:d=>`<div class="node-card ${esc(d.state)}"><div class="node-label">${esc(d.label)}</div>${d.link?`<a class="node-link" data-node="${esc(d.id)}" href="#" onclick="event.stopPropagation();graphBridge.open(this.dataset.node);return false">${esc(d.id)}</a>`:`<div>${esc(d.id)}</div>`}<div class="node-details">${details(d)}</div></div>`}],{enablePointerEvents:true});
        function layout(){cy.layout({name:'breadthfirst',directed:true,spacingFactor:1.35,padding:35,animate:false}).run()}function fitGraph(){cy.fit(cy.elements(':visible'),35)}function zoomGraph(f){cy.zoom({level:cy.zoom()*f,renderedPosition:{x:cy.width()/2,y:cy.height()/2}})}
        function setGraph(model){cy.elements().remove();cy.add(model.nodes);cy.add(model.edges.map(e=>{if(!e.data.directed)e.classes='undirected';return e}));layout();fitGraph();graphBridge.message(`${model.nodes.length} node(s), ${model.edges.length} relation(s)`)}
        function selectedIds(){return cy.nodes(':selected').map(n=>n.id())}function descendants(root){let level=Number(root.data('level')),found=cy.collection(),pending=root.neighborhood('node'),seen=new Set([root.id()]);while(pending.length){let next=cy.collection();pending.forEach(n=>{if(seen.has(n.id()))return;seen.add(n.id());if(Number(n.data('level'))>level){found=found.union(n);next=next.union(n.neighborhood('node'))}});pending=next}return found}
        function collapseSelected(){let roots=cy.nodes(':selected');if(!roots.length){graphBridge.message('Select one or more nodes to collapse.');return}let hidden=cy.collection();roots.forEach(n=>hidden=hidden.union(descendants(n)));hidden.hide();graphBridge.message(`Collapsed ${hidden.length} deeper node(s).`);fitGraph()}
        function expandSelected(){let roots=cy.nodes(':selected');if(!roots.length){graphBridge.message('Select one or more nodes to expand.');return}let shown=cy.collection();roots.forEach(n=>shown=shown.union(descendants(n)));shown.show();graphBridge.message(`Expanded ${shown.length} deeper node(s).`);fitGraph()}
        function openSelected(){let ids=selectedIds();if(ids.length!==1){graphBridge.message('Select exactly one node to open its link.');return}graphBridge.open(ids[0])}
        cy.on('select unselect','node',()=>graphBridge.selection(JSON.stringify(selectedIds())));cy.on('cxttap','node',e=>{let n=e.target,hidden=descendants(n).filter(':hidden');n.select();if(hidden.length)expandSelected();else collapseSelected()});
        window.setGraph=setGraph;window.fitGraph=fitGraph;window.zoomGraph=zoomGraph;window.collapseSelected=collapseSelected;window.expandSelected=expandSelected;window.openSelected=openSelected;
        </script></body></html>
        """;
}

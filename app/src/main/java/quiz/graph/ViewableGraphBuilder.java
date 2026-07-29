package quiz.graph;

import org.jgrapht.Graph;
import org.jgrapht.graph.SimpleGraph;
import objectview.Viewable;
import objectview.ViewableAdapter;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

public class ViewableGraphBuilder {

    public Graph<Viewable, ViewableEdge> build(Collection<? extends Viewable> roots) {
        Graph<Viewable, ViewableEdge> graph = new SimpleGraph<>(ViewableEdge.class);
        Set<Viewable> visited = Collections.newSetFromMap(new IdentityHashMap<>());

        if (roots != null) {
            for (Viewable q : roots) {
                visit(q, graph, visited);
            }
        }

        return graph;
    }

    private void visit(Viewable q,
                       Graph<Viewable, ViewableEdge> graph,
                       Set<Viewable> visited) {
        if (q == null) return;

        graph.addVertex(q);

        if (visited.contains(q)) return;
        visited.add(q);

        for (Field field : ViewableAdapter.getAllFields(q.getClass())) {
            if (Modifier.isStatic(field.getModifiers())) continue;

            field.setAccessible(true);

            Object value;
            try {
                value = field.get(q);
            } catch (Exception e) {
                continue;
            }

            collectEdges(q, field.getName(), value, graph, visited);
        }
    }

    private void collectEdges(Viewable from,
                              String fieldName,
                              Object value,
                              Graph<Viewable, ViewableEdge> graph,
                              Set<Viewable> visited) {
        if (value == null) return;

        if (value instanceof Viewable q) {
            graph.addVertex(q);

            if (!graph.containsEdge(from, q)) {
                graph.addEdge(from, q, new ViewableEdge(from, q, fieldName));
            }

            visit(q, graph, visited);
            return;
        }

        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                collectEdges(from, fieldName, item, graph, visited);
            }
            return;
        }

        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                collectEdges(from, fieldName + "[" + e.getKey() + "]",
                             e.getValue(), graph, visited);
            }
        }
    }
}
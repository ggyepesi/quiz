package quiz.graph;

import org.jgrapht.Graph;
import org.jgrapht.graph.SimpleGraph;
import quiz.Quizable;
import quiz.QuizableAdapter;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

public class QuizableGraphBuilder {

    public Graph<Quizable, QuizableEdge> build(Collection<? extends Quizable> roots) {
        Graph<Quizable, QuizableEdge> graph = new SimpleGraph<>(QuizableEdge.class);
        Set<Quizable> visited = Collections.newSetFromMap(new IdentityHashMap<>());

        if (roots != null) {
            for (Quizable q : roots) {
                visit(q, graph, visited);
            }
        }

        return graph;
    }

    private void visit(Quizable q,
                       Graph<Quizable, QuizableEdge> graph,
                       Set<Quizable> visited) {
        if (q == null) return;

        graph.addVertex(q);

        if (visited.contains(q)) return;
        visited.add(q);

        for (Field field : QuizableAdapter.getAllFields(q.getClass())) {
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

    private void collectEdges(Quizable from,
                              String fieldName,
                              Object value,
                              Graph<Quizable, QuizableEdge> graph,
                              Set<Quizable> visited) {
        if (value == null) return;

        if (value instanceof Quizable q) {
            graph.addVertex(q);

            if (!graph.containsEdge(from, q)) {
                graph.addEdge(from, q, new QuizableEdge(from, q, fieldName));
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
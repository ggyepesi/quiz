package nobel;

import java.util.*;

public class KnowledgeGraph {
    // Represents a graph of concepts
    public static class Graph {
        Map<String, Set<String>> edges = new HashMap<>();

        public void addEdge(String a, String b) {
            if (a.equals(b)) return;

            edges.computeIfAbsent(a, k -> new HashSet<>()).add(b);
            edges.computeIfAbsent(b, k -> new HashSet<>()).add(a);
        }

        public void printGraph() {
            for (String node : edges.keySet()) {
                System.out.println(node + " -> " + edges.get(node));
            }
        }
    }

    public static Graph buildGraph(List<MotivationParser.Motivation> motivations) {
        Graph g = new Graph();
        for (MotivationParser.Motivation m : motivations) {
            // use topics and keywords as nodes
            List<String> nodes = new ArrayList<>(m.topics);
            nodes.addAll(m.keywords);

            // connect each pair of nodes in this motivation
            for (int i = 0; i < nodes.size(); i++) {
                for (int j = i + 1; j < nodes.size(); j++) {
                    g.addEdge(nodes.get(i), nodes.get(j));
                }
            }
        }

        return g;
    }

    public static void main(String[] args) {
        List<String> motivations = List.of(
                "for the discovery of microRNA and its role in post-transcriptional gene regulation",
                "for their discoveries concerning organization and elicitation of individual and social behaviour patterns",
                "for the development of cryo-electron microscopy for the high-resolution structure determination of biomolecules in solution",
                "for efforts to build and disseminate greater knowledge about man-made climate change"
        );

        List<MotivationParser.Motivation> parsedList = new ArrayList<>();
        for (String s : motivations) {
            parsedList.add(MotivationParser.parse(s));
        }

        Graph g = buildGraph(parsedList);

        g.printGraph();
    }
}
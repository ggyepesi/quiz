package cleanup.deps;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JavaDependencyScanner {

    private static final Pattern IMPORT_PATTERN =
            Pattern.compile("^\\s*import\\s+(static\\s+)?([a-zA-Z_][\\w.]*(?:\\.\\*)?)\\s*;\\s*$");

    public static void main(String[] args) throws Exception {
        Path sourceRoot = args.length == 0
                ? Paths.get("src/main/java")
                : Paths.get(args[0]);

        JavaDependencyScanner scanner = new JavaDependencyScanner();
        ProjectGraph graph = scanner.scan(sourceRoot);

        graph.printSummary();
        graph.printSinkClusters();

        Path dot = Paths.get("deps.dot");
        graph.writePackageDot(dot);

        System.out.println();
        System.out.println("Wrote package graph: " + dot.toAbsolutePath());
        System.out.println("Render with:");
        System.out.println("dot -Tsvg deps.dot -o deps.svg");

        graph.runTerminalUi();
    }

    public ProjectGraph scan(Path sourceRoot) throws IOException {
        sourceRoot = sourceRoot.toAbsolutePath().normalize();

        List<JavaSourceFile> files = readSourceFiles(sourceRoot);

        Map<String, JavaSourceFile> byQualifiedName = new TreeMap<>();

        for (JavaSourceFile file : files) {
            byQualifiedName.put(file.qualifiedName, file);
        }

        ProjectGraph graph = new ProjectGraph();

        for (JavaSourceFile file : files) {
            graph.addNode(file.qualifiedName);
            graph.classToPackage.put(file.qualifiedName, file.packageName);

            for (String imported : file.imports) {
                if (imported.endsWith(".*")) {
                    continue;
                }

                JavaSourceFile target = byQualifiedName.get(imported);

                if (target != null) {
                    graph.addEdge(file.qualifiedName, target.qualifiedName);
                }
            }
        }

        return graph;
    }

    private List<JavaSourceFile> readSourceFiles(Path sourceRoot)
            throws IOException {

        List<JavaSourceFile> result = new ArrayList<>();

        try (var stream = Files.walk(sourceRoot)) {
            for (Path path : stream
                    .filter(p -> p.toString().endsWith(".java"))
                    .sorted()
                    .toList()) {

                JavaSourceFile file = parseJavaSource(sourceRoot, path);
                result.add(file);
            }
        }

        return result;
    }

    private JavaSourceFile parseJavaSource(Path sourceRoot, Path path)
            throws IOException {

        Set<String> imports = new TreeSet<>();

        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            Matcher im = IMPORT_PATTERN.matcher(line);
            if (im.matches()) {
                imports.add(im.group(2));
            }
        }

        Path relative = sourceRoot.relativize(path.toAbsolutePath().normalize());

        String fileName = relative.getFileName().toString();
        String simpleName = fileName.substring(
                0,
                fileName.length() - ".java".length()
                                              );

        Path packagePath = relative.getParent();

        String packageName = packagePath == null
                ? ""
                : packagePath.toString()
                             .replace('\\', '.')
                             .replace('/', '.');

        String qualifiedName = packageName.isBlank()
                ? simpleName
                : packageName + "." + simpleName;

        return new JavaSourceFile(
                path,
                packageName,
                simpleName,
                qualifiedName,
                imports
        );
    }

    private static class JavaSourceFile {
        final Path path;
        final String packageName;
        final String simpleName;
        final String qualifiedName;
        final Set<String> imports;

        JavaSourceFile(
                Path path,
                String packageName,
                String simpleName,
                String qualifiedName,
                Set<String> imports
                      ) {
            this.path = path;
            this.packageName = packageName;
            this.simpleName = simpleName;
            this.qualifiedName = qualifiedName;
            this.imports = imports;
        }
    }

    public static class ProjectGraph {
        private final Set<String> nodes = new TreeSet<>();
        private final Map<String, Set<String>> outgoing = new TreeMap<>();
        private final Map<String, Set<String>> incoming = new TreeMap<>();
        private final Map<String, String> classToPackage = new TreeMap<>();

        void addNode(String node) {
            nodes.add(node);
            outgoing.computeIfAbsent(node, k -> new TreeSet<>());
            incoming.computeIfAbsent(node, k -> new TreeSet<>());
        }

        void addEdge(String from, String to) {
            if (from.equals(to)) {
                return;
            }

            addNode(from);
            addNode(to);

            outgoing.get(from).add(to);
            incoming.get(to).add(from);
        }

        void printSummary() {
            int edgeCount = outgoing.values().stream()
                                    .mapToInt(Set::size)
                                    .sum();

            System.out.println("Classes:  " + nodes.size());
            System.out.println("Edges:    " + edgeCount);
            System.out.println("Packages: "
                                       + new TreeSet<>(classToPackage.values()).size());
        }

        void printSinkClusters() {
            List<Set<String>> sccs = stronglyConnectedComponents();

            List<Set<String>> sinkClusters = new ArrayList<>();

            for (Set<String> scc : sccs) {
                if (!hasIncomingFromOutside(scc)) {
                    sinkClusters.add(scc);
                }
            }

            sinkClusters.sort(Comparator
                                      .<Set<String>>comparingInt(Set::size)
                                      .reversed()
                                      .thenComparing(s -> s.iterator().next()));

            System.out.println();
            System.out.println("SINK CLUSTERS");
            System.out.println("=============");

            int index = 1;

            for (Set<String> cluster : sinkClusters) {
                System.out.println();
                System.out.println("[" + index++ + "] "
                                           + cluster.size()
                                           + " classes");

                Set<String> packages = packagesOf(cluster);
                System.out.println("Packages:");
                for (String p : packages) {
                    System.out.println("  " + p);
                }

                Set<String> dependsOnPackages = outgoingPackages(cluster);
                if (!dependsOnPackages.isEmpty()) {
                    System.out.println("Depends on packages:");
                    for (String p : dependsOnPackages) {
                        System.out.println("  " + p);
                    }
                }

                System.out.println("Classes:");
                for (String c : cluster) {
                    System.out.println("  " + c);
                }
            }
        }

        void runTerminalUi() {
            Map<String, Set<String>> classToCluster =
                    buildClassToClusterMap();

            System.out.println();
            System.out.println("Terminal UI");
            System.out.println("===========");
            System.out.println("Type class name, simple or qualified.");
            System.out.println("Examples: QuizFactory, quiz.QuizFactory");
            System.out.println("Commands: sinks, packages, quit");
            System.out.println();

            Scanner scanner = new Scanner(System.in);

            while (true) {
                System.out.print("> ");

                if (!scanner.hasNextLine()) {
                    break;
                }

                String input = scanner.nextLine().trim();

                if (input.isBlank()) {
                    continue;
                }

                if (input.equalsIgnoreCase("quit")
                        || input.equalsIgnoreCase("exit")
                        || input.equalsIgnoreCase("q")) {
                    break;
                }

                if (input.equalsIgnoreCase("sinks")) {
                    printSinkClusters();
                    continue;
                }

                if (input.equalsIgnoreCase("packages")) {
                    printPackages();
                    continue;
                }

                String className = resolveClassName(input);

                if (className == null) {
                    System.out.println("Class not found: " + input);
                    printSimilarClasses(input);
                    continue;
                }

                Set<String> cluster = classToCluster.get(className);

                if (cluster == null) {
                    System.out.println("No cluster found for: " + className);
                    continue;
                }

                printClusterForClass(className, cluster);
            }
        }

        void writePackageDot(Path path) throws IOException {
            Map<String, Set<String>> packageEdges = buildPackageGraph();

            StringBuilder sb = new StringBuilder();

            sb.append("digraph packages {\n");
            sb.append("  rankdir=LR;\n");
            sb.append("  node [shape=box];\n\n");

            for (String p : new TreeSet<>(classToPackage.values())) {
                sb.append("  \"")
                  .append(escape(p))
                  .append("\";\n");
            }

            sb.append("\n");

            for (Map.Entry<String, Set<String>> e : packageEdges.entrySet()) {
                for (String to : e.getValue()) {
                    sb.append("  \"")
                      .append(escape(e.getKey()))
                      .append("\" -> \"")
                      .append(escape(to))
                      .append("\";\n");
                }
            }

            sb.append("}\n");

            Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
        }

        private Map<String, Set<String>> buildPackageGraph() {
            Map<String, Set<String>> result = new TreeMap<>();

            for (String from : nodes) {
                String fromPackage = classToPackage.getOrDefault(from, "");

                for (String to : outgoing.getOrDefault(from, Set.of())) {
                    String toPackage = classToPackage.getOrDefault(to, "");

                    if (!fromPackage.equals(toPackage)) {
                        result.computeIfAbsent(
                                      fromPackage,
                                      k -> new TreeSet<>()
                                              )
                              .add(toPackage);
                    }
                }
            }

            return result;
        }

        private Map<String, Set<String>> buildClassToClusterMap() {
            Map<String, Set<String>> result = new TreeMap<>();

            for (Set<String> cluster : stronglyConnectedComponents()) {
                for (String c : cluster) {
                    result.put(c, cluster);
                }
            }

            return result;
        }

        private String resolveClassName(String input) {
            if (nodes.contains(input)) {
                return input;
            }

            List<String> matches = new ArrayList<>();

            for (String node : nodes) {
                if (simpleName(node).equals(input)) {
                    matches.add(node);
                }
            }

            if (matches.size() == 1) {
                return matches.get(0);
            }

            if (matches.size() > 1) {
                System.out.println("Ambiguous class name: " + input);
                for (String m : matches) {
                    System.out.println("  " + m);
                }
            }

            return null;
        }

        private void printSimilarClasses(String input) {
            String lower = input.toLowerCase(Locale.ROOT);

            List<String> matches = nodes.stream()
                                        .filter(n -> n.toLowerCase(Locale.ROOT).contains(lower)
                                                || simpleName(n)
                                                .toLowerCase(Locale.ROOT)
                                                .contains(lower))
                                        .limit(20)
                                        .toList();

            if (matches.isEmpty()) {
                return;
            }

            System.out.println("Similar classes:");
            for (String m : matches) {
                System.out.println("  " + m);
            }
        }

        private void printClusterForClass(
                String className,
                Set<String> cluster
                                         ) {
            System.out.println();
            System.out.println("Cluster for " + className);
            System.out.println("=".repeat(12 + className.length()));

            System.out.println("Classes: " + cluster.size());

            Set<String> packages = packagesOf(cluster);

            System.out.println();
            System.out.println("Packages:");
            for (String p : packages) {
                System.out.println("  " + p);
            }

            Set<String> incomingPackages = incomingPackages(cluster);
            if (!incomingPackages.isEmpty()) {
                System.out.println();
                System.out.println("Referenced from packages:");
                for (String p : incomingPackages) {
                    System.out.println("  " + p);
                }
            }

            Set<String> outgoingPackages = outgoingPackages(cluster);
            if (!outgoingPackages.isEmpty()) {
                System.out.println();
                System.out.println("Depends on packages:");
                for (String p : outgoingPackages) {
                    System.out.println("  " + p);
                }
            }

            System.out.println();
            System.out.println("Classes:");
            for (String c : cluster) {
                System.out.println("  " + c);
            }

            System.out.println();
        }

        private void printPackages() {
            Map<String, Long> counts = new TreeMap<>();

            for (String p : classToPackage.values()) {
                counts.put(p, counts.getOrDefault(p, 0L) + 1);
            }

            for (Map.Entry<String, Long> e : counts.entrySet()) {
                System.out.println(e.getKey() + " (" + e.getValue() + ")");
            }
        }

        private boolean hasIncomingFromOutside(Set<String> cluster) {
            for (String node : cluster) {
                for (String in : incoming.getOrDefault(node, Set.of())) {
                    if (!cluster.contains(in)) {
                        return true;
                    }
                }
            }

            return false;
        }

        private Set<String> packagesOf(Set<String> classes) {
            Set<String> result = new TreeSet<>();

            for (String c : classes) {
                result.add(classToPackage.getOrDefault(c, ""));
            }

            return result;
        }

        private Set<String> incomingPackages(Set<String> cluster) {
            Set<String> ownPackages = packagesOf(cluster);
            Set<String> result = new TreeSet<>();

            for (String c : cluster) {
                for (String in : incoming.getOrDefault(c, Set.of())) {
                    if (!cluster.contains(in)) {
                        String p = classToPackage.getOrDefault(in, "");

                        if (!ownPackages.contains(p)) {
                            result.add(p);
                        }
                    }
                }
            }

            return result;
        }

        private Set<String> outgoingPackages(Set<String> cluster) {
            Set<String> ownPackages = packagesOf(cluster);
            Set<String> result = new TreeSet<>();

            for (String c : cluster) {
                for (String out : outgoing.getOrDefault(c, Set.of())) {
                    if (!cluster.contains(out)) {
                        String p = classToPackage.getOrDefault(out, "");

                        if (!ownPackages.contains(p)) {
                            result.add(p);
                        }
                    }
                }
            }

            return result;
        }

        private List<Set<String>> stronglyConnectedComponents() {
            Tarjan tarjan = new Tarjan(outgoing);
            return tarjan.compute(nodes);
        }

        private String simpleName(String qualifiedName) {
            int dot = qualifiedName.lastIndexOf('.');
            return dot < 0
                    ? qualifiedName
                    : qualifiedName.substring(dot + 1);
        }

        private String escape(String s) {
            return s.replace("\\", "\\\\")
                    .replace("\"", "\\\"");
        }
    }

    private static class Tarjan {
        private final Map<String, Set<String>> graph;

        private final Map<String, Integer> index = new HashMap<>();
        private final Map<String, Integer> lowlink = new HashMap<>();
        private final Deque<String> stack = new ArrayDeque<>();
        private final Set<String> onStack = new HashSet<>();
        private final List<Set<String>> components = new ArrayList<>();

        private int nextIndex = 0;

        Tarjan(Map<String, Set<String>> graph) {
            this.graph = graph;
        }

        List<Set<String>> compute(Set<String> nodes) {
            for (String node : nodes) {
                if (!index.containsKey(node)) {
                    strongConnect(node);
                }
            }

            return components;
        }

        private void strongConnect(String v) {
            index.put(v, nextIndex);
            lowlink.put(v, nextIndex);
            nextIndex++;

            stack.push(v);
            onStack.add(v);

            for (String w : graph.getOrDefault(v, Set.of())) {
                if (!index.containsKey(w)) {
                    strongConnect(w);
                    lowlink.put(
                            v,
                            Math.min(lowlink.get(v), lowlink.get(w))
                               );
                } else if (onStack.contains(w)) {
                    lowlink.put(
                            v,
                            Math.min(lowlink.get(v), index.get(w))
                               );
                }
            }

            if (Objects.equals(lowlink.get(v), index.get(v))) {
                Set<String> component = new TreeSet<>();

                while (true) {
                    String w = stack.pop();
                    onStack.remove(w);
                    component.add(w);

                    if (w.equals(v)) {
                        break;
                    }
                }

                components.add(component);
            }
        }
    }
}
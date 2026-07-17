package quiz;

import aux.GridBagUtils;
import objectview.Card;
import objectview.viewconfig.ViewConfig;
import org.jgrapht.Graph;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.shortestpath.BFSShortestPath;
import quiz.graph.QuizableEdge;
import quiz.graph.QuizableGraphBuilder;

import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;

public class QuizSixDegrees extends Quiz {

    private static final int MAX_TRIES_TO_FIND_PAIR = 200;
    private static final int MIN_PATH_LENGTH = 1;
    private static final int MAX_PATH_LENGTH = 6;

    private Graph<Quizable, QuizableEdge> graph;
    private List<Quizable> nodes;

    private Quizable source;
    private Quizable target;
    private GraphPath<Quizable, QuizableEdge> currentPath;

    private JPanel pathPanel;
    private JButton revealNextButton;
    private JButton revealAllButton;
    private JButton nextButton;

    private int revealedEdges = 0;

    public QuizSixDegrees(ViewConfig viewConfig,
                          QuizableGroup group,
                          Map<String, ? extends Quizable> quizables) {
        super(viewConfig, viewConfig, group, quizables);
    }

    @Override
    public void run() {
        graph = new QuizableGraphBuilder().build(quizables.values());

        System.out.println("Graph: "
                                   + graph.vertexSet().size()
                                   + " vertices, "
                                   + graph.edgeSet().size()
                                   + " edges");
        int isolated = 0;
        int degreeOne = 0;

        for (Quizable q : graph.vertexSet()) {
            int d = graph.degreeOf(q);
            if (d == 0) isolated++;
            if (d == 1) degreeOne++;
        }

        System.out.println("isolated = " + isolated);
        System.out.println("degree 1 = " + degreeOne);

        nodes = new ArrayList<>(graph.vertexSet());

        SwingUtilities.invokeLater(() -> {
            frame.getContentPane().removeAll();
            frame.setLayout(new GridBagLayout());
            drawNextRound();
            frame.setVisible(true);
        });
    }

    private void drawNextRound() {
        if (!findPair()) {
            showDone("No connected pair found.");
            return;
        }

        revealedEdges = 0;

        frame.getContentPane().removeAll();

        JPanel main = new JPanel(new GridBagLayout());

        JLabel title = new JLabel("Find the connection", SwingConstants.CENTER);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 28f));

        main.add(title, GridBagUtils.gbc(
                0, 0, 2, 0.0, 0.0,
                GridBagConstraints.CENTER,
                GridBagConstraints.HORIZONTAL,
                new Insets(12, 12, 20, 12)));

        Card left = new Card(
                source,
                withRootClass(queryConfig, source),
                quizables.values(),
                false);

        Card right = new Card(
                target,
                withRootClass(queryConfig, target),
                quizables.values(),
                false);

        main.add(left, GridBagUtils.gbc(
                0, 1, 1.0, 0.35,
                GridBagConstraints.NORTHWEST,
                GridBagConstraints.BOTH,
                new Insets(8, 8, 8, 8)));

        main.add(right, GridBagUtils.gbc(
                1, 1, 1.0, 0.35,
                GridBagConstraints.NORTHWEST,
                GridBagConstraints.BOTH,
                new Insets(8, 8, 8, 8)));

        pathPanel = new JPanel();
        pathPanel.setLayout(new BoxLayout(pathPanel, BoxLayout.Y_AXIS));
        pathPanel.setBorder(BorderFactory.createTitledBorder("Shortest path"));

        JScrollPane pathScroll = new JScrollPane(pathPanel);

        main.add(pathScroll, GridBagUtils.gbc(
                0, 2, 2.0, 0.55,
                GridBagConstraints.NORTHWEST,
                GridBagConstraints.BOTH,
                new Insets(12, 8, 12, 8)));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 8));

        revealNextButton = new JButton("Reveal next step");
        revealAllButton = new JButton("Reveal full path");
        nextButton = new JButton("Next");

        revealNextButton.addActionListener(e -> revealNext());
        revealAllButton.addActionListener(e -> revealAll());
        nextButton.addActionListener(e -> drawNextRound());

        buttons.add(revealNextButton);
        buttons.add(revealAllButton);
        buttons.add(nextButton);

        main.add(buttons, GridBagUtils.gbc(
                0, 3, 2.0, 0.0,
                GridBagConstraints.CENTER,
                GridBagConstraints.HORIZONTAL,
                new Insets(8, 8, 12, 8)));

        frame.add(main, GridBagUtils.gbc(
                0, 0, 1.0, 1.0,
                GridBagConstraints.CENTER,
                GridBagConstraints.BOTH,
                new Insets(0, 0, 0, 0)));

        addStartHint();

        frame.revalidate();
        frame.repaint();
    }

    private boolean findPair() {
        if (nodes == null || nodes.size() < 2) return false;

        BFSShortestPath<Quizable, QuizableEdge> bfs =
                new BFSShortestPath<>(graph);

        for (int i = 0; i < MAX_TRIES_TO_FIND_PAIR; i++) {
            Quizable a = nodes.get(random.nextInt(nodes.size()));
            Quizable b = nodes.get(random.nextInt(nodes.size()));

            if (a == b) continue;

            GraphPath<Quizable, QuizableEdge> path = bfs.getPath(a, b);
            if (path == null) continue;

            int len = path.getLength();

            if (len >= MIN_PATH_LENGTH && len <= MAX_PATH_LENGTH) {
                source = a;
                target = b;
                currentPath = path;
                return true;
            }
        }

        return false;
    }

    private void addStartHint() {
        pathPanel.removeAll();

        JLabel label = new JLabel(
                "Try to guess the connection from "
                        + safeName(source)
                        + " to "
                        + safeName(target)
                        + ".");

        label.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        pathPanel.add(label);
        pathPanel.revalidate();
        pathPanel.repaint();
    }

    private void revealNext() {
        if (currentPath == null) return;

        if (revealedEdges >= currentPath.getEdgeList().size()) {
            revealNextButton.setEnabled(false);
            return;
        }

        QuizableEdge edge = currentPath.getEdgeList().get(revealedEdges);
        revealedEdges++;

        addEdgeRow(edge, revealedEdges);

        if (revealedEdges >= currentPath.getEdgeList().size()) {
            revealNextButton.setEnabled(false);
        }
    }

    private void revealAll() {
        if (currentPath == null) return;

        while (revealedEdges < currentPath.getEdgeList().size()) {
            revealNext();
        }
    }

    private void addEdgeRow(QuizableEdge edge, int step) {
        if (revealedEdges == 1) {
            pathPanel.removeAll();
        }

        JLabel label = new JLabel(
                step
                        + ". "
                        + safeName(edge.getFrom())
                        + " -- "
                        + edge.getField()
                        + " -- "
                        + safeName(edge.getTo()));

        label.setFont(label.getFont().deriveFont(Font.PLAIN, 18f));
        label.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));

        pathPanel.add(label);
        pathPanel.revalidate();
        pathPanel.repaint();
    }

    private void showDone(String text) {
        frame.getContentPane().removeAll();

        JLabel label = new JLabel(text, SwingConstants.CENTER);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 28f));

        frame.add(label, GridBagUtils.gbc(
                0, 0, 1.0, 1.0,
                GridBagConstraints.CENTER,
                GridBagConstraints.BOTH,
                new Insets(20, 20, 20, 20)));

        frame.revalidate();
        frame.repaint();
    }
}
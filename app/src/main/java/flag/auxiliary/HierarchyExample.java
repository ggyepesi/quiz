package flag.auxiliary;

import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;

public class HierarchyExample extends JFrame {

    public HierarchyExample() {
        super("Hierarchical Structure Example");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(400, 300);
        setLocationRelativeTo(null);

        // 1. Create the root node
        DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("World");

        // 2. Add child nodes representing continents
        DefaultMutableTreeNode asia = new DefaultMutableTreeNode("Asia");
        DefaultMutableTreeNode europe = new DefaultMutableTreeNode("Europe");
        DefaultMutableTreeNode africa = new DefaultMutableTreeNode("Africa");

        rootNode.add(asia);
        rootNode.add(europe);
        rootNode.add(africa);

        // 3. Add countries to continents (further levels of hierarchy)
        asia.add(new DefaultMutableTreeNode("China"));
        asia.add(new DefaultMutableTreeNode("India"));
        asia.add(new DefaultMutableTreeNode("Japan"));

        europe.add(new DefaultMutableTreeNode("Germany"));
        europe.add(new DefaultMutableTreeNode("France"));
        europe.add(new DefaultMutableTreeNode("Italy"));

        africa.add(new DefaultMutableTreeNode("Egypt"));
        africa.add(new DefaultMutableTreeNode("South Africa"));

        // 4. Create a TreeModel from the root node
        DefaultTreeModel treeModel = new DefaultTreeModel(rootNode);

        // 5. Create the JTree with the model
        JTree tree = new JTree(treeModel);

        // 6. Wrap the JTree in a JScrollPane for scrollability
        JScrollPane scrollPane = new JScrollPane(tree);

        // Add the scroll pane to the frame
        add(scrollPane, BorderLayout.CENTER);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new HierarchyExample().setVisible(true);
        });
    }
}
package quiz.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;

class DialogsTest {
    @Test void visibleModelessChildOwnsAModalPromptStartedFromItsMainFrame() {
        Node main = new Node(null);
        Node workflow = main.child();

        assertSame(workflow, owner(main, main, workflow));
    }

    @Test void unrelatedActiveWindowCannotStealThePrompt() {
        Node main = new Node(null);
        Node workflow = main.child();
        Node unrelated = new Node(null);

        assertSame(workflow, owner(main, unrelated, workflow));
    }

    private static Node owner(Node requested, Node active, Node visible) {
        return Dialogs.chooseOwner(requested, active, DialogsTest::root,
                node -> node.children, node -> node == visible);
    }

    private static Node root(Node node) {
        while (node.parent != null) node = node.parent;
        return node;
    }

    private static final class Node {
        final Node parent;
        final List<Node> children = new ArrayList<>();

        Node(Node parent) { this.parent = parent; }

        Node child() {
            Node child = new Node(this);
            children.add(child);
            return child;
        }
    }
}

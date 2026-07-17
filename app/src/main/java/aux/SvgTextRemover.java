package aux;

import org.apache.batik.swing.JSVGCanvas;

import javax.swing.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.opencv.core.Mat;
import org.w3c.dom.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

public class SvgTextRemover {

    public static void main(String[] args) throws Exception {
        String dir = "/Users/gyorgygyepesi/IdeaProjects/quiz/src/main/resources/flag/logos/svg/";
        File originalSvg = new File(dir + "Boston Celtics.svg");
        File cleanedSvg = new File(dir + "cleaned.svg");

        removeTextElements(originalSvg, cleanedSvg);

        SwingUtilities.invokeLater(() -> showSvgPair(originalSvg, cleanedSvg));
    }

    static void removeTextElements(File input, File output) throws Exception {
        Document doc = DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(input);

        NodeList textNodes = doc.getElementsByTagName("text");

        while (textNodes.getLength() > 0) {
            Node text = textNodes.item(0);
            text.getParentNode().removeChild(text);
        }

        Transformer transformer = TransformerFactory
                .newInstance()
                .newTransformer();

        transformer.setOutputProperty(OutputKeys.INDENT, "yes");

        transformer.transform(
                new DOMSource(doc),
                new StreamResult(output)
        );
    }

    static void showSvgPair(File before, File after) {
        JFrame frame = new JFrame("SVG Before / After");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new GridLayout(1, 2));

        JSVGCanvas beforeCanvas = new JSVGCanvas();
        beforeCanvas.setURI(before.toURI().toString());

        JSVGCanvas afterCanvas = new JSVGCanvas();
        afterCanvas.setURI(after.toURI().toString());

        frame.add(wrap("Before", beforeCanvas));
        frame.add(wrap("After", afterCanvas));

        frame.setSize(1000, 600);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    static JPanel wrap(String title, JComponent component) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(new JLabel(title, SwingConstants.CENTER), BorderLayout.NORTH);
        panel.add(component, BorderLayout.CENTER);
        return panel;
    }
}
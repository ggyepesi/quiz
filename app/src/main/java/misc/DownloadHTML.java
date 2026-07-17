package misc;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.jsoup.nodes.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import aux.UrlLineProcessor;
import aux.UrlReader;

// For trying to parse the HTML instead of raw text for wikipedia pages.
public class DownloadHTML {
    public static void main(String[] args) throws Exception {
        // DocumentBuilder uses sun xerces DOMParser which just sucks: source is hidden, doesn't tolerate unclosed "<link" - FORGET it.
        // Try Jsoup instead. (On the other hand Wikipedia should try to close thos "<link"-s).
        try {
            // DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            // DocumentBuilder db = dbf.newDocumentBuilder();
            // Document doc = db.parse(url);
            Document doc = Jsoup.connect(args[0]).get();
            org.w3c.dom.Document w3cDoc = W3CDom.convert(doc);
            System.out.println(printXML((w3cDoc.getDocumentElement())));
        } catch (Exception e) { System.out.println(e.getMessage());
            UrlReader<List<String>> reader = new UrlReader<>(new JustReadLineProcessor());
            List<String> read = reader.read(args[0]);

            String xml = new String();
            for (int i = 0; i < read.size(); ++i) {
                if (read.get(i).startsWith("<link") && read.get(i).endsWith("/>")) {
                    System.out.println(i + "\t" + read.get(i));
                    xml += xml + read.get(i).substring(0, read.get(i).length() - 1) + "/>";
                }
                xml += read.get(i) + "\n";
            }
            /*
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();

            InputSource is = new InputSource(new StringReader(xml));
            Document doc = db.parse(is);

            System.out.println(printXML(doc.getDocumentElement()));
            */
            System.exit(2);
        }
    }

    private static boolean skipNL;

    private static String printXML(Node rootNode) {
        String tab = "";
        skipNL = false;
        return(printXML(rootNode, tab));
    }

    private static String printXML(Node rootNode, String tab) {
        String print = "";
        if(rootNode.getNodeType()==Node.ELEMENT_NODE) {
            print += "\n"+tab+"<"+rootNode.getNodeName()+">";
        }
        NodeList nl = rootNode.getChildNodes();
        if(nl.getLength()>0) {
            for (int i = 0; i < nl.getLength(); i++) {
                print += printXML(nl.item(i), tab+"  ");    // \t
            }
        } else {
            if(rootNode.getNodeValue()!=null) {
                print = rootNode.getNodeValue();
            }
            skipNL = true;
        }
        if(rootNode.getNodeType()==Node.ELEMENT_NODE) {
            if(!skipNL) {
                print += "\n"+tab;
            }
            skipNL = false;
            print += "</"+rootNode.getNodeName()+">";
        }
        return(print);
    }
}

class JustReadLineProcessor implements UrlLineProcessor<List<String>> {
    private List<String> read = new ArrayList<>();

    public URL processLine(String line) {
        read.add(line);
        return null;
    }

    public boolean isDone() { return false; }

    public List<String> done() {
        return read;
    }
}

package misc;
import java.awt.Image;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Serializable;
import java.net.URL;
import java.util.Set;
import java.util.TreeSet;

import javax.imageio.ImageIO;

import org.apache.batik.anim.dom.SAXSVGDocumentFactory;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.JPEGTranscoder;
import org.apache.batik.util.XMLResourceDescriptor;
import org.w3c.dom.Document;

import aux.UrlLineProcessor;
import aux.UrlReader;

public class ImageAndDescription implements Serializable {
    private URL imageUrl;
    private transient Image image;
    private String description;
    private Set<String> categories = new TreeSet<>();

    public String getDescription() {
        return description;
    }
   
    public URL getImageUrl() {
        return imageUrl;
    }
 
    public Set<String> getCategories() {
        return categories;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setImageUrl(URL imageUrl) {
        this.imageUrl = imageUrl;
    }

    public void setCategories(Set<String> categories) {
        this.categories = categories;
    }

    public String toString() {
        return imageUrl + ", " + description;
    }

    public Image getImage()  throws Exception {
        if (image != null) return image;

        if (!App.localFileUrls.contains(imageUrl.toString())) {
            System.out.println("Read imageUrl ....");
            UrlReader<String> urlReader = new UrlReader<>(new ImageUrlLineProcessor());
            String line = urlReader.read(imageUrl);
            System.out.println("Read done");
            if (line == null) {
                System.out.println("Image NOT found for " + imageUrl);
                imageUrl = null;
                return null;
            }
            imageUrl = new URL(line);
        }

        // BufferedReader reader = new BufferedReader(new InputStreamReader(imageUrl.openStream()));                                      
        System.out.println("Image from url: " + imageUrl);

        if (imageUrl.toString().endsWith(".svg")) {
            // Look into the svgdoc before transcoding it.
            // Cannot use Jsoup for creating the document because the underlying batik libraray fails to use it.
            String parser = XMLResourceDescriptor.getXMLParserClassName();
            SAXSVGDocumentFactory f = new SAXSVGDocumentFactory(parser);
            Document doc = f.createDocument(imageUrl.toString());

            TranscoderInput svgImage = new TranscoderInput(doc);
            // PNGTranscoder transcoder = new PNGTranscoder();
            // transcoder.addTranscodingHint(PNGTranscoder.KEY_FORCE_TRANSPARENT_WHITE, Boolean.TRUE);
            // Funny: if png then background is black, if jpeg then it is white.
            JPEGTranscoder transcoder = new JPEGTranscoder();
            transcoder.addTranscodingHint(JPEGTranscoder.KEY_QUALITY, 1.0f);
       
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            TranscoderOutput output = new TranscoderOutput(out);
            transcoder.transcode(svgImage, output);
            out.close();
            System.out.println("Image from svg, size " + out.size());
            ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
            image = ImageIO.read(in);
        } else {
            image = ImageIO.read(imageUrl.openStream());
        }
        return image;
    }
}

class ImageUrlLineProcessor implements UrlLineProcessor<String> {
    private static final String imageBegin = "<div class=\"fullImageLink\" id=\"file\"><a href=\"";
    private static final String imageEnd = "\"";

    private String line;
    private boolean done = false;
    
    public URL processLine(String line) {
        int start = line.indexOf(imageBegin);
        if (start != -1) {
            line = line.substring(start);
            int end = line.indexOf(imageEnd, imageBegin.length());
            if (end == -1) return null;
            this.line = "https:" + line.substring(imageBegin.length(), end);
            done = true;
        }
        return null;
    }

    public boolean isDone() {
        return done;
    }

    public String done() {
        return line;
    }
}

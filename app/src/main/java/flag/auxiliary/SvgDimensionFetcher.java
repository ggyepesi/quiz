package flag.auxiliary;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * A utility class to fetch SVG dimensions from a URL.
 */
public class SvgDimensionFetcher {

    /**
     * Retrieves the width and height of an SVG image from the given URL.
     * The dimensions are returned as a Map with "width" and "height" keys.
     *
     * @param svgUrl The URL of the SVG image.
     * @return A Map containing "width" and "height" as String values, or an empty Map if dimensions
     * cannot be determined (e.g., URL is invalid, network error, or SVG parsing error).
     * @throws IOException if a network or I/O error occurs during fetching.
     * @throws ParserConfigurationException if a serious configuration error is detected.
     * @throws SAXException if any parse errors occur.
     */
    public static Map<String, String> getSvgDimensions(String svgUrl)
            throws IOException, ParserConfigurationException, SAXException {

        Map<String, String> dimensions = new HashMap<>();

        if (svgUrl == null || svgUrl.trim().isEmpty()) {
            System.err.println("Error: SVG URL cannot be null or empty.");
            return dimensions;
        }

        InputStream inputStream = null;
        try {
            // One polite HTTP path: UrlOpener supplies the contact User-Agent, 429/5xx
            // retry and redirect following (and falls back to openStream for file: URLs,
            // which the local-SVG test relies on).
            try {
                inputStream = objectview.utils.UrlOpener.open(svgUrl);
            } catch (IOException | RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("Failed to fetch SVG: " + svgUrl, e);
            }

            // Create a DocumentBuilderFactory
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Important: Disable DTD loading for security and performance
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            // Create a DocumentBuilder
            DocumentBuilder builder = factory.newDocumentBuilder();

            // Parse the input stream to get the XML Document
            Document document = builder.parse(inputStream);

            // Get the root element (which should be the <svg> tag)
            Element svgElement = document.getDocumentElement();

            // Check if the root element is indeed 'svg'
            if (svgElement != null && svgElement.getTagName().equalsIgnoreCase("svg")) {
                // Get the 'width' and 'height' attributes
                String width = svgElement.getAttribute("width");
                String height = svgElement.getAttribute("height");

                // Check for viewBox attribute if width/height are not explicitly set
                if (width.isEmpty() || height.isEmpty()) {
                    String viewBox = svgElement.getAttribute("viewBox");
                    if (!viewBox.isEmpty()) {
                        String[] parts = viewBox.split(" ");
                        if (parts.length == 4) {
                            // The last two parts of viewBox are width and height
                            if (width.isEmpty()) {
                                width = parts[2];
                            }
                            if (height.isEmpty()) {
                                height = parts[3];
                            }
                        }
                    }
                }

                // Add to the map if found
                if (!width.isEmpty()) {
                    dimensions.put("width", width);
                }
                if (!height.isEmpty()) {
                    dimensions.put("height", height);
                }
            } else {
                System.err.println("Error: Root element is not an SVG tag or is null.");
            }

        } catch (IOException e) {
            System.err.println("Network or I/O error: " + e.getMessage());
            throw e; // Re-throw to indicate failure to caller
        } catch (ParserConfigurationException e) {
            System.err.println("XML parser configuration error: " + e.getMessage());
            throw e; // Re-throw
        } catch (SAXException e) {
            System.err.println("SVG parsing error: " + e.getMessage());
            throw e; // Re-throw
        } finally {
            // Ensure the input stream is closed
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    System.err.println("Error closing input stream: " + e.getMessage());
                }
            }
        }
        return dimensions;
    }

    /**
     * Main method for testing the SvgDimensionFetcher.
     */
    public static void main(String[] args) {
        // Example usage:
        // You can replace this URL with any public SVG URL for testing.
        // For example: "https://www.w3.org/Icons/SVG/svg-icon-example.svg"
        // Or a simple SVG: "https://upload.wikimedia.org/wikipedia/commons/e/ea/SVG_logo.svg"
        String testSvgUrl = "file:///Users/gyorgygyepesi/vsprojects/quiz/src/flag/svg/Flag of Luxembourg.svg";

        try {
            Map<String, String> dims = getSvgDimensions(testSvgUrl);

            if (!dims.isEmpty()) {
                System.out.println("SVG Dimensions for " + testSvgUrl + ":");
                System.out.println("Width: " + dims.getOrDefault("width", "N/A"));
                System.out.println("Height: " + dims.getOrDefault("height", "N/A"));
            } else {
                System.out.println("Could not retrieve dimensions for " + testSvgUrl);
            }

            // Test with an SVG that uses viewBox
            String viewBoxSvgUrl = "https://developer.mozilla.org/static/media/firefox-logo.b9d88568e64c3997.svg";
            Map<String, String> viewBoxDims = getSvgDimensions(viewBoxSvgUrl);
            if (!viewBoxDims.isEmpty()) {
                System.out.println("\nSVG Dimensions for " + viewBoxSvgUrl + ":");
                System.out.println("Width: " + viewBoxDims.getOrDefault("width", "N/A"));
                System.out.println("Height: " + viewBoxDims.getOrDefault("height", "N/A"));
            } else {
                System.out.println("Could not retrieve dimensions for " + viewBoxSvgUrl);
            }


            // Test with a non-existent URL or invalid SVG (expected to throw exceptions)
            // String invalidUrl = "http://example.com/nonexistent.svg";
            // Map<String, String> invalidDims = getSvgDimensions(invalidUrl);
            // System.out.println("Invalid URL test: " + invalidDims);

        } catch (IOException | ParserConfigurationException | SAXException e) {
            System.err.println("An error occurred during SVG dimension fetching: " + e.getMessage());
            e.printStackTrace();
        }
    }
}

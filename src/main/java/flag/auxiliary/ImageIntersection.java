package flag.auxiliary;

import javax.swing.*;

import objectview.utils.swing.CachedImage;
import aux.Constants;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

public class ImageIntersection extends JFrame {
    private JLabel pixelInfoLabel;

    public ImageIntersection() {
        super("Image Intersection Example");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1000, 500);
        setLocationRelativeTo(null);

        JPanel imagePanel = new JPanel(new GridLayout(3, 1, 10, 10));

        BufferedImage image1 = null;
        BufferedImage image2 = null;
        BufferedImage intersectedImage = null;
        String country1 = "Luxembourg";
        String country2 = "Vatican City (2023–present)";
        try {
            image1 = bufferedImage(
                new CachedImage("file://" + Constants.flagSvgDirectory + "Flag of " + country1 + ".svg",
                                null, true).getFullImage());
            image2 = bufferedImage(
                new CachedImage("file://" + Constants.flagSvgDirectory + "Flag of " + country2 + ".svg",
                                null, true).getFullImage());
        } catch (Exception e) {
            e.printStackTrace();
        }
    
        if (image1 != null && image2 != null) {
            intersectedImage = intersectImages(image1, image2);
        } else {
            System.err.println("Failed to load or create test images.");
        }

        imagePanel.add(createImageLabel(image1, country1));
        imagePanel.add(createImageLabel(image2, country2));
        imagePanel.add(createImageLabel(intersectedImage, "Intersected Image"));

        add(imagePanel, BorderLayout.CENTER);
        pixelInfoLabel = new JLabel("Click on an image to see pixel color.", SwingConstants.CENTER);
        pixelInfoLabel.setFont(new Font("Arial", Font.PLAIN, 16));
        pixelInfoLabel.setBorder(BorderFactory.createEmptyBorder(10, 0, 10, 0));
        add(pixelInfoLabel, BorderLayout.SOUTH);
    }

    public static BufferedImage bufferedImage(Image img) {
        if (img instanceof BufferedImage) {
            return (BufferedImage) img;
        }

        // Create a buffered image with transparency
        BufferedImage bimage =
            new BufferedImage(img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_ARGB);

        // Draw the image onto the buffered image
        Graphics2D bGr = bimage.createGraphics();
        bGr.drawImage(img, 0, 0, null);
        bGr.dispose();

        // Return the buffered image
        return bimage;
    }

    /**
     * Creates a JLabel containing an image and a title.
     */
    public static JLabel createImageLabel(BufferedImage image, String title) {
        JLabel label = new JLabel();
        label.setHorizontalAlignment(SwingConstants.CENTER);
        label.setVerticalTextPosition(SwingConstants.BOTTOM);
        label.setHorizontalTextPosition(SwingConstants.CENTER);
        if (image != null) {
            label.setIcon(new ImageIcon(image));
            label.setText(title);
            //addPixelColorListener(label, image);
        } else {
            label.setText("Error: " + title);
        }
        return label;
    }

    /**
     * Produces the "intersection" of two images.
     * The resulting image will only show pixels where BOTH input images have
     * non-transparent (alpha > 0) pixels. The color data will be taken from image1.
     *
     * @param img1 The first input image.
     * @param img2 The second input image.
     * @return A new BufferedImage representing the intersection, or null if images are incompatible.
     */
    public static BufferedImage intersectImages(Image img1, Image img2) {
        return intersectImages(bufferedImage(img1), bufferedImage(img2));
    }

    public static BufferedImage intersectImages(BufferedImage img1, BufferedImage img2) {
            if (img1 == null || img2 == null) {
            System.err.println("One or both input images are null.");
            return null;
        }

        if (img1.getWidth() != img2.getWidth() || img1.getHeight() != img2.getHeight()) {
            System.err.println("Images must have the same dimensions for intersection: " +
                                img1.getWidth() + "x" + img1.getHeight() + ", " + img2.getWidth() + "x" + img2.getHeight());
            return null;
        }
        System.err.println("Images have the same dimensions: " +
                            img1.getWidth() + "x" + img1.getHeight() + ", " + img2.getWidth() + "x" + img2.getHeight());

        int width = img1.getWidth();
        int height = img1.getHeight();
        BufferedImage resultImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int numIntersectionPixels = 0;
        int numPixels = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                ++numPixels;
                if (similar(img1.getRGB(x, y), img2.getRGB(x, y))) {
                    ++numIntersectionPixels;
                    resultImage.setRGB(x, y, img1.getRGB(x, y));
                } else {
                    resultImage.setRGB(x, y, Color.GRAY.getRGB()); // Fully transparent black
                }
            }
        }
        double intersectionRatio = (double)numIntersectionPixels / (double)numPixels;
        System.out.println("numPixels " + numPixels + ", " + numIntersectionPixels + ", " + intersectionRatio);
        return resultImage;
    }

    /*
    private void addPixelColorListener(JLabel label, BufferedImage image) {
        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                // Get click coordinates relative to the JLabel
                int clickX = e.getX();
                int clickY = e.getY();

                // Get image dimensions
                int imageWidth = image.getWidth();
                int imageHeight = image.getHeight();

                // Calculate actual pixel coordinates within the image
                // Assuming the image is centered within the JLabel, which is typical for ImageIcon.
                // We need to account for any scaling or padding the JLabel might introduce.
                // For simplicity here, we assume no scaling and direct pixel mapping if JLabel size equals image size.
                // In a more complex scenario, you'd calculate based on JLabel's actual image rendering area.

                // For a JLabel with ImageIcon, the image is drawn in the center.
                // Calculate the top-left corner of the image within the label.
                int iconWidth = label.getIcon().getIconWidth();
                int iconHeight = label.getIcon().getIconHeight();

                int imageDrawX = (label.getWidth() - iconWidth) / 2;
                int imageDrawY = (label.getHeight() - iconHeight) / 2;

                int pixelX = clickX - imageDrawX;
                int pixelY = clickY - imageDrawY;

                // Check if the click is within the image bounds
                if (pixelX >= 0 && pixelX < imageWidth && pixelY >= 0 && pixelY < imageHeight) {
                    int pixelRGB = image.getRGB(pixelX, pixelY);

                    // Extract ARGB components
                    int alpha = (pixelRGB >> 24) & 0xFF;
                    int red = (pixelRGB >> 16) & 0xFF;
                    int green = (pixelRGB >> 8) & 0xFF;
                    int blue = (pixelRGB >> 0) & 0xFF;

                    // Update the info label
                    pixelInfoLabel.setText(String.format("Clicked at (%d, %d) in image. Pixel Color (A, R, G, B): (%d, %d, %d, %d)",
                            pixelX, pixelY, alpha, red, green, blue));
                } else {
                    pixelInfoLabel.setText("Clicked outside image bounds. Click on the image itself.");
                }
            }
        });
    }
    */

    public static Map<String, Color> colors = new TreeMap<>();
    static {
        try {
            colors.put("Black", new Color(Integer.decode("#000000")));
            colors.put("White", new Color(Integer.decode("#FFFFFF")));
            colors.put("Red", new Color(Integer.decode("#FF0000")));
            colors.put("Lime", new Color(Integer.decode("#00FF00")));
            colors.put("Blue", new Color(Integer.decode("#0000FF")));
            colors.put("Yellow", new Color(Integer.decode("#FFFF00")));
            colors.put("Cyan / Aqua", new Color(Integer.decode("#00FFFF")));
            colors.put("Magenta / Fuchsia", new Color(Integer.decode("#FF00FF")));
            colors.put("Silver", new Color(Integer.decode("#C0C0C0")));
            colors.put("Gray", new Color(Integer.decode("#808080")));
            colors.put("Maroon", new Color(Integer.decode("#800000")));
            //colors.put("Olive", new Color(Integer.decode("#808000")));
            colors.put("Green", new Color(Integer.decode("#008000")));
            colors.put("Purple", new Color(Integer.decode("#800080")));
            //colors.put("Teal", new Color(Integer.decode("#008080")));
            colors.put("Navy", new Color(Integer.decode("#000080")));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    };

    private static boolean similar(int rgb1, int rgb2) {
        return getNearestColor(rgb1) == getNearestColor(rgb2);
    }
    
    public static String getColorName(Color color) {
        for (Entry<String, Color> e : colors.entrySet()) {
            if (color.equals(e.getValue())) {
                return e.getKey();
            }
        }
        return null;
    }

    public static Color getNearestColor(int rgb) {
        Color nearestColor = null;
        long nearestDistance = 255 * 255 * 255;
        //int alpha = (rgb >> 24) & 0xFF;
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = (rgb >> 0) & 0xFF;
        for (Entry<String, Color> e : colors.entrySet()) {
            int crgb = e.getValue().getRGB();
            //int calpha = (crgb >> 24) & 0xFF;
            int cred = (crgb >> 16) & 0xFF;
            int cgreen = (crgb >> 8) & 0xFF;
            int cblue = (crgb >> 0) & 0xFF;
    
            int deltaR = red - cred;
            int deltaG = green - cgreen;
            int deltaB = blue - cblue;
      
            long distance = (deltaR * deltaR) + (deltaG * deltaG) + (deltaB * deltaB);
      
            // Actual distance is sqrt(distance) but we are only 
            // comparing them so the extra sqrt() doesn't change
            // anything.
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearestColor = e.getValue();
                //nearestName = e.getKey();
            }
        }
        return nearestColor;
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new ImageIntersection().setVisible(true);
        });
    }
}

package flag.auxiliary;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;
import java.util.HashMap;
import java.util.Map;

/**
 * A simple Java Swing application to conceptually draw a "world map"
 * with placeholder country names. This demonstrates basic drawing
 * with Graphics2D on a JPanel.
 *
 * NOTE: This is a highly simplified and conceptual example.
 * It does NOT use real geographic data or projections.
 * For actual world maps, dedicated geospatial libraries or
 * web-based mapping solutions are required.
 */
public class WorldMapGemini extends JFrame {

    public WorldMapGemini() {
        super("Conceptual World Map"); // Set the title of the JFrame
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE); // Set default close operation
        setSize(900, 600); // Set window size (adjust as needed)
        setLocationRelativeTo(null); // Center the window on the screen

        // Create an instance of our custom JPanel for drawing the map
        MapPanel mapPanel = new MapPanel();
        add(mapPanel, BorderLayout.CENTER); // Add the map panel to the center of the frame
    }

    /**
     * Custom JPanel where the "map" elements will be drawn.
     */
    static class MapPanel extends JPanel {

        // Store conceptual "country" shapes and their names
        private final Map<String, Shape> countries;
        private final Map<String, Point> countryLabels;

        public MapPanel() {
            setBackground(new Color(135, 206, 235)); // Sky blue background for "ocean"
            countries = new HashMap<>();
            countryLabels = new HashMap<>();
            initConceptualMap(); // Initialize our simplified map
        }

        /**
         * Initializes some conceptual country shapes and their label positions.
         * These are hardcoded arbitrary polygons and points for demonstration.
         * In a real application, this data would come from GeoJSON, Shapefiles, etc.
         */
        private void initConceptualMap() {
            // --- Conceptual North America ---
            Path2D.Double northAmerica = new Path2D.Double();
            northAmerica.moveTo(100, 200);
            northAmerica.lineTo(150, 100);
            northAmerica.lineTo(250, 80);
            northAmerica.lineTo(300, 180);
            northAmerica.lineTo(200, 250);
            northAmerica.closePath();
            countries.put("North America", northAmerica);
            countryLabels.put("North America", new Point(180, 150));

            // --- Conceptual South America ---
            Path2D.Double southAmerica = new Path2D.Double();
            southAmerica.moveTo(250, 300);
            southAmerica.lineTo(280, 450);
            southAmerica.lineTo(200, 500);
            southAmerica.lineTo(170, 350);
            southAmerica.closePath();
            countries.put("South America", southAmerica);
            countryLabels.put("South America", new Point(220, 400));

            // --- Conceptual Europe ---
            Path2D.Double europe = new Path2D.Double();
            europe.moveTo(450, 100);
            europe.lineTo(550, 120);
            europe.lineTo(500, 200);
            europe.lineTo(400, 180);
            europe.closePath();
            countries.put("Europe", europe);
            countryLabels.put("Europe", new Point(480, 150));

            // --- Conceptual Africa ---
            Path2D.Double africa = new Path2D.Double();
            africa.moveTo(480, 250);
            africa.lineTo(550, 300);
            africa.lineTo(520, 450);
            africa.lineTo(450, 400);
            africa.closePath();
            countries.put("Africa", africa);
            countryLabels.put("Africa", new Point(500, 350));

            // --- Conceptual Asia ---
            Path2D.Double asia = new Path2D.Double();
            asia.moveTo(600, 80);
            asia.lineTo(750, 100);
            asia.lineTo(800, 300);
            asia.lineTo(700, 400);
            asia.lineTo(650, 350);
            asia.lineTo(580, 200);
            asia.closePath();
            countries.put("Asia", asia);
            countryLabels.put("Asia", new Point(700, 250));

            // --- Conceptual Australia ---
            Path2D.Double australia = new Path2D.Double();
            australia.moveTo(700, 480);
            australia.lineTo(780, 450);
            australia.lineTo(750, 520);
            australia.lineTo(680, 500);
            australia.closePath();
            countries.put("Australia", australia);
            countryLabels.put("Australia", new Point(720, 490));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g); // Call JPanel's paintComponent to ensure proper background drawing
            Graphics2D g2d = (Graphics2D) g; // Cast to Graphics2D for advanced drawing capabilities

            // Enable anti-aliasing for smoother shapes and text
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            // Set land color
            g2d.setColor(new Color(152, 251, 152)); // Light green for "land"

            // Draw each conceptual country shape
            for (Shape shape : countries.values()) {
                g2d.fill(shape); // Fill the shape
                g2d.setColor(Color.DARK_GRAY); // Border color
                g2d.setStroke(new BasicStroke(1.5f)); // Thicker border
                g2d.draw(shape); // Draw the border
                g2d.setColor(new Color(152, 251, 152)); // Reset fill color
            }

            // Set font for country names
            g2d.setFont(new Font("Arial", Font.BOLD, 14));
            g2d.setColor(Color.BLACK); // Text color

            // Draw country names
            for (Map.Entry<String, Point> entry : countryLabels.entrySet()) {
                String countryName = entry.getKey();
                Point labelLocation = entry.getValue();

                // Get font metrics to center the text
                FontMetrics fm = g2d.getFontMetrics();
                int textWidth = fm.stringWidth(countryName);
                int textHeight = fm.getHeight();

                // Adjust label position to roughly center the text on the point
                int x = labelLocation.x - (textWidth / 2);
                int y = labelLocation.y + (textHeight / 4); // Adjust for baseline

                g2d.drawString(countryName, x, y);
            }
        }
    }

    public static void main(String[] args) {
        // Ensure Swing components are created and updated on the Event Dispatch Thread (EDT)
        SwingUtilities.invokeLater(() -> {
            WorldMapGemini viewer = new WorldMapGemini();
            viewer.setVisible(true);
        });
    }
}

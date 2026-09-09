package wikidata.explore.demo.constraint;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Dimension;

/** Window for the controlled, one-frontier-at-a-time constraint discovery demo. */
public final class FrontierConstraintDiscoveryFrame extends JFrame {
    public FrontierConstraintDiscoveryFrame() {
        super("Constrained shared-population discovery");
        setContentPane(new FrontierConstraintDiscoveryPanel());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(1050, 650));
        setSize(1400, 850);
        setLocationByPlatform(true);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // The platform-independent look and feel remains usable.
            }
            new FrontierConstraintDiscoveryFrame().setVisible(true);
        });
    }
}

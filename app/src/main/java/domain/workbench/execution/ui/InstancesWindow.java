package domain.workbench.execution.ui;

import javax.swing.JFrame;
import javax.swing.JComponent;
import java.awt.BorderLayout;

/** The standalone host for the shared instances viewer. */
public final class InstancesWindow {
    private InstancesWindow() { }

    public static JFrame create(QueryObjectResultPanel instances, JComponent toolbar) {
        var window = new JFrame("Generated instances");
        window.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        if (toolbar != null) window.add(toolbar, BorderLayout.NORTH);
        window.add(instances, BorderLayout.CENTER);
        window.setSize(1100, 850);
        window.setLocationByPlatform(true);
        return window;
    }
}

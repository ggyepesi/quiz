package wikidata.explore.generation;

import javax.swing.*;
import java.awt.*;

/** Small shared editor used by Generate, Remap and Enrich plan pages. */
public final class GenerationExecutionSettingsPanel extends JPanel {
    public GenerationExecutionSettingsPanel(GenerationExecutionSettings settings) {
        this(settings, true);
    }

    public GenerationExecutionSettingsPanel(
            GenerationExecutionSettings settings, boolean networked) {
        super(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 8, 6, 8); c.anchor = GridBagConstraints.WEST;
        c.gridx = 0; c.gridy = 0;
        add(new JLabel("Memory/cache profile"), c);
        JComboBox<GenerationExecutionSettings.MemoryProfile> memory =
                new JComboBox<>(GenerationExecutionSettings.MemoryProfile.values());
        memory.setSelectedItem(settings.memoryProfile());
        c.gridx = 1; add(memory, c);
        JSpinner custom = new JSpinner(new SpinnerNumberModel(
                settings.customMemoryMb(), 64, 8192, 64));
        c.gridx = 2; add(custom, c);
        JLabel resolved = new JLabel(); c.gridx = 3; add(resolved, c);

        c.gridy++; c.gridx = 0; add(new JLabel("Network intensity"), c);
        JComboBox<GenerationExecutionSettings.NetworkProfile> network =
                new JComboBox<>(GenerationExecutionSettings.NetworkProfile.values());
        network.setSelectedItem(settings.networkProfile());
        c.gridx = 1; add(network, c);
        JLabel concurrency = new JLabel(); c.gridx = 2; c.gridwidth = 2; add(concurrency, c);

        c.gridy++; c.gridx = 0; c.gridwidth = 4;
        JCheckBox complete = new JCheckBox("Require a complete result",
                settings.requireComplete()); add(complete, c);
        c.gridy++; add(new JLabel(networked
                ? "Checkpoint/resume: unavailable for this operation"
                : "Checkpoint/resume: not applicable to local remap"), c);
        c.gridy++; add(new JLabel("Log detail: phase summaries and individual requests"), c);
        c.gridy++; c.weighty = 1; add(Box.createVerticalGlue(), c);

        Runnable refresh = () -> {
            if (!networked) {
                custom.setEnabled(false);
                resolved.setText("Not used by local remap");
                concurrency.setText("No network acquisition");
                return;
            }
            boolean customSelected = memory.getSelectedItem()
                    == GenerationExecutionSettings.MemoryProfile.CUSTOM;
            custom.setEnabled(customSelected);
            resolved.setText("Resolved: " + settings.resolvedMemoryMb() + " MB");
            concurrency.setText(settings.concurrency() + " concurrent entity requests");
        };
        memory.addActionListener(e -> {
            settings.memoryProfile((GenerationExecutionSettings.MemoryProfile)
                    memory.getSelectedItem()); refresh.run();
        });
        custom.addChangeListener(e -> {
            settings.customMemoryMb(((Number) custom.getValue()).intValue()); refresh.run();
        });
        network.addActionListener(e -> {
            settings.networkProfile((GenerationExecutionSettings.NetworkProfile)
                    network.getSelectedItem()); refresh.run();
        });
        complete.addActionListener(e -> settings.requireComplete(complete.isSelected()));
        if (!networked) {
            memory.setEnabled(false);
            custom.setEnabled(false);
            network.setEnabled(false);
        }
        refresh.run();
    }
}

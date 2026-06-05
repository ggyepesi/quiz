package benchmark;

import oscar.OscarNomination;
import quiz.QuizableFieldPaths.FieldPath;
import wikidata.WikidataEntity;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Map;

public final class CompiledEmulatorView extends JPanel {

    // Accepts pre-grouped layout branches to completely eliminate runtime map allocations
    public CompiledEmulatorView(OscarNomination data, Map<String, List<FieldPath>> preGroupedPaths) {
        this.setLayout(new GridBagLayout());
        this.setOpaque(false);
        this.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY, 1));

        if (data == null || preGroupedPaths == null) return;

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        int mainRow = 0;

        for (Map.Entry<String, List<FieldPath>> entry : preGroupedPaths.entrySet()) {
            String rootKey = entry.getKey();
            List<FieldPath> groupPaths = entry.getValue();

            // 1. Handle flat primitive nodes directly
            if (groupPaths.size() == 1 && groupPaths.get(0).path().size() == 1) {
                String val = getPrimitiveValue(data, rootKey);
                this.add(createValuePanel(groupPaths.get(0).title(), val), layoutConstraints(mainRow++));
            }
            // 2. Handle composite nested panels without dynamic recursive reflection loops
            else {
                Object rootObj = getRootObject(data, rootKey);
                if (rootObj != null) {
                    JPanel subPanel = new JPanel(new GridBagLayout());
                    subPanel.setOpaque(false);
                    subPanel.setBorder(BorderFactory.createTitledBorder(rootKey));

                    GridBagConstraints subGbc = new GridBagConstraints();
                    subGbc.fill = GridBagConstraints.HORIZONTAL;
                    subGbc.weightx = 1.0;

                    int subRow = 0;
                    for (FieldPath fp : groupPaths) {
                        String leafKey = fp.path().get(1);
                        subGbc.gridy = subRow++;
                        subPanel.add(createValuePanel(fp.title(), getLeafValue(rootObj, leafKey)), subGbc);
                    }

                    gbc.gridy = mainRow++;
                    this.add(subPanel, gbc);
                }
            }
        }
    }

    private String getPrimitiveValue(OscarNomination data, String key) {
        return switch (key) {
            case "ceremonyYear" -> String.valueOf(data.getCeremonyYear());
            case "filmYear" -> String.valueOf(data.getFilmYear());
            case "winner" -> String.valueOf(data.isWinner());
            default -> "";
        };
    }

    private Object getRootObject(OscarNomination data, String key) {
        return switch (key) {
            case "nominee" -> data.getNominee();
            case "award" -> data.getAward();
            case "work" -> data.getWork();
            default -> null;
        };
    }

    private String getLeafValue(Object rootObj, String leafKey) {
        if (rootObj instanceof WikidataEntity entity) {
            return switch (leafKey) {
                case "name" -> entity.getName();
                case "qid" -> entity.getQid();
                case "url" -> "https://www.wikidata.org/wiki/" + entity.getQid();
                default -> "";
            };
        }
        return "";
    }

    private JPanel createValuePanel(String title, String value) {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.setBorder(BorderFactory.createTitledBorder(title));
        JLabel label = new JLabel(value);
        label.setForeground(new Color(0, 80, 180));
        p.add(label, BorderLayout.WEST);
        return p;
    }

    private GridBagConstraints layoutConstraints(int y) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = y;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        gbc.insets = new Insets(4, 4, 4, 4);
        return gbc;
    }
}
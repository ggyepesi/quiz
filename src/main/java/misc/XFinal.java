package misc;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;

public class XFinal {
    static final String[] places = {"Rcs", "R", "Gar", "En", "Ent", "Ing", "Iron", "Ko", "Pus",
                                    "Pass", "RaB", "Bo", "Bny", "JMa", "Ir", "Gy", "Gyű", "Co", "Com", "55CB", "Orf"};
    static final String[] weekDays = {"", "H", "K", "Sz", "Cs", "P"};
    static final String[] finalPlaces = {"A", "B", "C", "D", "E"}; //{"Kossuth", "Rácskert", "Enter", "Garrison", "Gyűrű"};

    public static void main(String[] args) throws Exception {
        XFinal xfinal = new XFinal();
        xfinal.readTeams();
        xfinal.show();
    }

    Map<String, Team> teams = new TreeMap<>();
    JList<String> teamList;
    Map<String, Semifinal> semis = new TreeMap<>();
    Set<String> semiFinalTeams = new TreeSet<>();
    
    public void readTeams() throws Exception {
        Set<String> dayPlaces = new TreeSet<>();
        for (int i = 0; i < places.length; ++i) {
            for (int j = 0; j < weekDays.length; ++j) {
                dayPlaces.add(places[i] + weekDays[j]);
            }
        }

        BufferedReader reader = new BufferedReader(new FileReader("teams2.txt"));
        String line;
        int n = 0;
        while ((line = reader.readLine()) != null) {
            ++n;
            if (n < 7) continue;
            if (n > 50) break;
            System.out.println(n + " " + line);
            String sn = n + "";
            // Strip n from the beginning and find the first '('.
            int l = line.indexOf(sn);
            line = line.substring(l + sn.length());
            l = line.indexOf("(");
            int r = line.indexOf(")");
            String bonus = line.substring(l + 1, r);

            line = line.substring(0, l);
            System.out.println(n + " " + line);
            // Strip points from the end.
            int e = line.length() - 1;
            while (Character.isDigit(line.charAt(e))) --e;
            ++e;
            String points = line.substring(e);

            line = line.substring(0, e);
            String team = "";
            String placeDay = "";
            // Strip place and weekday from the end.
            for (String dp : dayPlaces) {
                if (line.endsWith(dp)) {
                    team = line.substring(0, line.length() - dp.length());
                    placeDay = line.substring(line.length() - dp.length());
                    break;
                }
            }
            if (team.isEmpty()) System.out.println("Place is not registered " + line);
            // System.out.println("Put " + n + "th  " + team + " " + placeDay + " b=" + bonus + " p=" + points);
            teams.put(team, new Team(team, n, Integer.parseInt(bonus), Integer.parseInt(points), placeDay));
        }
        reader.close();
        for (Map.Entry<String, Team> e : teams.entrySet()) {
            System.out.println(e.getValue());
        }
    }

    Map<String, DefaultListModel<String>> placeLists = new TreeMap<>();
    Map<String, JPanel> placePanels = new TreeMap<>();

    void addPlaceBox(String place, JPanel panel, GridBagConstraints gbc) {
        DefaultListModel<String> model = new DefaultListModel<>();
        JList<String> list = new JList<>(model);
        JScrollPane listScrollPane = new JScrollPane();
        listScrollPane.setViewportView(list);
        list.setLayoutOrientation(JList.VERTICAL);
        list.setEnabled(false);
        Font font = list.getFont();
        list.setFont(font.deriveFont(font.getSize() * 1.3f));

        JPanel titledPanel = new JPanel();
        titledPanel.setBorder(BorderFactory.createTitledBorder(place));
        titledPanel.add(listScrollPane);
       
        panel.add(titledPanel, gbc);    
        list.addMouseListener(new MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                if (evt.getClickCount() == 2) {
                    String team = teamList.getSelectedValue();
                    System.out.println("DoubleClicked " + place + " for " + team);
                    if (team == null) { return; }
                    if (semiFinalTeams.contains(team)) {
                        System.out.println(team + " is already in semifinal");
                    } else {
                        semiFinalTeams.add(team);
                        placeLists.get(place).addElement(teams.get(team).toString());
                        semis.get(place).getTeams().add(teams.get(team));
                        placePanels.get(place).setBorder(BorderFactory.createTitledBorder(place + ", " + semis.get(place).getSumOfBonuses()));
                    }
                }
            }
        });
    
        listScrollPane.setPreferredSize(new Dimension(200, 200));

        placePanels.put(place, titledPanel);
        placeLists.put(place, model);
    }

    // List for teams, 5 lists for the places.
    void show() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        JPanel topPanel = new JPanel();  
        topPanel.setLayout(new FlowLayout());
        panel.add(topPanel);

        JPanel placesPanel = new JPanel();  
        placesPanel.setLayout(new GridBagLayout());
        panel.add(placesPanel);

        DefaultListModel<String> model = new DefaultListModel<>();
        teamList = new JList<>(model);
        JScrollPane listScrollPane = new JScrollPane();
        listScrollPane.setViewportView(teamList);
        teamList.setLayoutOrientation(JList.VERTICAL);
        listScrollPane.setPreferredSize(new Dimension(300, 300));

        topPanel.add(listScrollPane);
        // addPlacesBox(topPanel);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.BOTH; // components resize in both directions
        gbc.weightx = 1.0; // distribute space equally horizontally
        gbc.weighty = 1.0; // distribute space equally vertically
        gbc.gridx = 0; // position in a row
        gbc.gridy = 0;

        for (String place : finalPlaces) {
            semis.put(place, new Semifinal(place));
            addPlaceBox(place, placesPanel, gbc);
            ++gbc.gridx;
        }

        TeamSelectionListener listener = new TeamSelectionListener(teamList, this);
        teamList.addListSelectionListener(listener);

        for (Map.Entry<String, Team> e : teams.entrySet()) {
            model.addElement(e.getKey());
        }

        JFrame frame = new JFrame("Semifinals");

        frame.add(panel);
        frame.setSize(1200, 600);  
        frame.setLocationRelativeTo(null);  
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);  
        frame.setVisible(true);  
    }
}

class TeamSelectionListener implements ListSelectionListener {
    JList<String> list;
    XFinal xfinal;

    public TeamSelectionListener(JList<String> list, XFinal xfinal) {
        this.list = list;
        this.xfinal = xfinal;
    }

    @Override
    public void valueChanged(ListSelectionEvent e) {
        if (e.getValueIsAdjusting()) return;
        String name = list.getSelectedValue();
        Team team = xfinal.teams.get(name);
        System.out.println(team);
    }
    
}

class Team {
    String name;
    int pos;
    int bonus;
    int points;
    String placeDay;

    public Team(String name, int pos, int bonus, int points, String placeDay) {
        this.name = name;
        this.pos = pos;
        this.bonus = bonus;
        this.points = points;
        this.placeDay = placeDay;
    }

    public int getBonus() {
        return bonus;
    }

    public String toString() {
        return name + " (" + bonus + ", " + placeDay + ", " + points + ")";// + " pos=" + pos + " pd=" + placeDay;
    }
}

class Semifinal {
    String place;
    List<Team> teams = new ArrayList<>();
    
    public Semifinal(String place) {
        this.place = place;
    }

    public String getPlace() {
        return place;
    }

    public List<Team> getTeams() {
        return teams;
    }

    public int getSumOfBonuses() {
        int sumOfBonuses = 0;
        for (Team t : teams) {
            sumOfBonuses += t.getBonus();
        }
        return sumOfBonuses;
    }
}

package misc;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import javax.swing.text.Highlighter.HighlightPainter;

import aux.Pair;

public class App {
    static final Set<String> localFileUrls = new TreeSet<>();
    static final boolean isCoatOfArms = false;
    static final Set<String> fromFile = new TreeSet<>();
    static final private boolean debug = false;
    static final String[] categoryPrefixes = new String[] {" with the ", " with "}; //, " of the ", " of arms ", " of "};


    // Add svg urls batik cannot parse (it fails for various reasons: multiple images, bad version etc.
    // Try them with the batik browser Squiggle.
    static {
        fromFile.add("Cyprus");
        fromFile.add("Vietnam");
        fromFile.add("Liechtenstein");
        fromFile.add("Peru");
        fromFile.add("Senegal");
        fromFile.add("Serbia");
        fromFile.add("South Sudan");
    }

    public static ImageAndDescription getImageAndDescription(Country country) {
        return isCoatOfArms ? country.getCoatsOfArms().get(0) : country.getFlag();
    }

    public static String getInfobox(String name, String description) throws Exception {
        String[] lines = description.split("\n");
        String infobox = new String();
        boolean inInfobox = false;
        boolean infoboxDone = false;
        int numCurlyBucketPairs = 0;

        for (String line : lines) {
            if (infoboxDone) continue;
            numCurlyBucketPairs = TextParser.updateNumberOfOpenBlocks(line, numCurlyBucketPairs);
            if (TextParser.isInfoboxStart(line)) {
                inInfobox = true;
            }
            if (inInfobox) {
                infobox += line + "\n";
                if (numCurlyBucketPairs == 0) {
                    infoboxDone = true;
                }
            }
        }
        return infobox;
    }
    
    public static String getCategory(String name, String line, String w, Map<String, Set<String>> countriesOfCategoriesWithoutEnd) throws Exception {
        int start = line.indexOf(w);
        if (start == -1) return null;
        int end = line.indexOf("|", start + w.length());
        if (end != -1) {
            return w + line.substring(start + w.length(), end);
        }
        String category = line.substring(start + w.length());
        Set<String> countries = countriesOfCategoriesWithoutEnd.get(category);
        if (countries == null) {
            countries = new TreeSet<>();
            countriesOfCategoriesWithoutEnd.put(category, countries);
        }
        if (category.equalsIgnoreCase(name)) return null;
        return w + category;
    }
 
    public static Set<String> getCategories(String name, Set<String> categoryLines, Map<String, Set<String>> countriesOfCategoriesWithoutEnd) throws Exception {
        Set<String> categories = new TreeSet<>();
        for (String line : categoryLines) {
            /*
            for (int i = 0; i < categoryPrefixes.length; ++i) {
                String category = getCategory(name, line, categoryPrefixes[i], countriesOfCategoriesWithoutEnd);
                if (category != null) {
                    categories.add(category);
                    break;
                }
            }
            */
            categories.add(line);
        }
        return categories;
    }

    public static void setDescriptionForCoatOfArms(Country country) throws Exception {
        String infobox = getInfobox(country.getCountryInfo().getName(), country.getCoatsOfArms().get(0).getDescription());
        if (infobox.isEmpty()) {
            System.out.println(country.getCountryInfo().getName() + " has no infobox");
        } else {
            country.getCoatsOfArms().get(0).setDescription(infobox);
        }
    }

    static void Debug() throws Exception {
        Map<String, Set<String>> countriesOfCategoriesWithoutEnd = new TreeMap<>();
        Set<String> categories = new TreeSet<String>();
        categories.add("Coats of arms of states with limited recognition|Abkhazia");
        categories.add("Coats of arms with horses|Abkhazia");
        categories.add("House of Shervashidze");
        categories.add("National emblems|Abkhazia");
        categories.add("National symbols of Abkhazia");
        for (String category : getCategories("name", categories, countriesOfCategoriesWithoutEnd)) {
            System.out.println(category);
        }
    }

    // Deserialize countries from the specified file and shows them.
    // Vietnam, Turkmenistan erroneous svg.
    public static void main(String[] args) throws Exception {
        if (debug) {
            Debug();
            return;
        }
        ObjectInputStream in = new ObjectInputStream(
            new FileInputStream("/Users/gyorgygyepesi/vsprojects/quiz/resources/countries/countryinfo.ser"));
        Object[] objects = (Object[]) in.readObject();
        in.close();
        System.out.println(objects.length + " countries");
        DefaultListModel<String> model = new DefaultListModel<>();
        JList<String> list = new JList<>(model);
        JScrollPane listScrollPane = new JScrollPane();
        listScrollPane.setViewportView(list);
        list.setLayoutOrientation(JList.VERTICAL);

        ArrayList<Country> countries = new ArrayList<>(objects.length);
        Map<String, Integer> categoryCounts = new TreeMap<>();
        Map<String, Set<String>> countriesOfCategoriesWithoutEnd = new TreeMap<>();
        for (Object o : objects) {
            Country country = (Country)o;
            ImageAndDescription image = getImageAndDescription(country);
            // Fix image url of coat-of-farms: it can contain a |, cut it there.
            // Todo: do this in download!
            String url = image.getImageUrl().toString();
            // Guyana for example
            int start = url.indexOf("[[File:");
            if (start != -1) {
                System.out.println("Fix svg url in " + url);
                url = url.substring(0, start) + url.substring(start + 7);
            }
            
            if (!url.endsWith(".svg") && !url.endsWith(".jpg") && !url.endsWith(".png" )) {
                System.out.println("Not an svg " + url);
                int end = url.lastIndexOf(".svg");
                url = url.substring(0, end + 4);
                try {
                    getImageAndDescription(country).setImageUrl(new URL(url));
                } catch (Exception e) {
                    System.out.println(country.getCountryInfo().getName() + ": " + e.getMessage() + ": " + url);
                }
                System.out.println("Fixed " + url);
            }
            if (fromFile.contains(country.getCountryInfo().getName())) {
                String fileName = image.getImageUrl().toString();
                String prefix = "https://en.wikipedia.org/wiki/File:";
                fileName = "resources/countries/coat_of_arms/bad_svgs/" + fileName.substring(prefix.length()) + ".png";
                image.setImageUrl(new File(fileName).toURI().toURL());
                localFileUrls.add(image.getImageUrl().toString());
                // System.out.println("From file " + image.getImageUrl());
            }

            if (isCoatOfArms) {
                // setDescriptionForCoatOfArms(country);
                Set<String> categories = getCategories(country.getCountryInfo().getName(), image.getCategories(), countriesOfCategoriesWithoutEnd);
                String categoriesForDescription = new String();
                for (String category : categories) {
                    Integer count = categoryCounts.get(category);
                    count = count == null ? 1 : (count + 1);
                    categoryCounts.put(category, count);
                    categoriesForDescription += "\n" + category;
                    // image.setCategories(categories);
                }
                String description = getImageAndDescription(country).getDescription();
                getImageAndDescription(country).setDescription(description + categoriesForDescription);
            } else {
                String description = image.getDescription();
                String[] lines = description.split("\n");
                boolean found = false;
                Pattern pattern = Pattern.compile("\\s*\\|\\s*Design\\s*=.*");
                for (String line : lines) {
                    if (pattern.matcher(line).matches()) {
                        found = true;
                        break;
                    }
                }
                if (!found) System.out.println("No design " + country.getCountryInfo().getName());
            }
            countries.add(country);
        }
        System.out.println("Added " + countries.size() + " countries, list has " + model.getSize());
        for (Map.Entry<String, Integer> categoryCount : categoryCounts.entrySet()) {
            System.out.println(categoryCount.getKey() + ": " + categoryCount.getValue());
        }

        int l = countries.size();
        for (int i = l - 1; i >= 0; --i) {
            Country country = countries.get(i);
            String name = country.getCountryInfo().getName();
            if (country.getCoatsOfArms().size() > 1) {
                System.out.println(name + " " + country.getCoatsOfArms().size() + " coats of arms:");
                for (int j = country.getCoatsOfArms().size() - 1; j > 0; --j) {
                    ImageAndDescription img = country.getCoatsOfArms().get(j);
                    Country c = new Country();
                    c.getCountryInfo().setName(name + " (" + j + ")");
                    c.getCoatsOfArms().add(img);
                    System.out.println("Added    " + c.getCountryInfo().getName());
                    countries.add(i + 1, c);
                }
            }
        }
        for (Country country : countries) {
            model.addElement(country.getCountryInfo().getName());
        }

        JLabel label = new JLabel("Label");
        ImagePanel imagePanel = new ImagePanel(label);
        
        imagePanel.setPreferredSize(new Dimension(200, 200));
        imagePanel.setLayout(new FlowLayout());
        JPanel imagePane = new JPanel();  
        imagePane.setLayout(new BoxLayout(imagePane, BoxLayout.Y_AXIS));
        
        imagePane.add(label);
        imagePane.add(imagePanel);
        JTextArea text = new JTextArea(10, 30);
        text.setWrapStyleWord(true);
        text.setLineWrap(true);
        JScrollPane textScrollPane = new JScrollPane(text);

        JFrame frame = new JFrame("Countries");
        
        CountryListSelectionListener listSelectionListener = new CountryListSelectionListener(countries, frame, list, text, imagePanel);
        list.addListSelectionListener(listSelectionListener);
    
        JTextArea textField = new JTextArea();
        // textField.setMaximumSize(new Dimension(150, 40));
        JScrollPane textFieldScrollPane = new JScrollPane(textField);
        textFieldScrollPane.setMaximumSize(new Dimension(800, 60));
        textField.setEditable(true);
        textField.setLineWrap(true);
        JButton button = new JButton("Search");
        JPanel searchPanel = new JPanel();//new BorderLayout());
        searchPanel.add(textFieldScrollPane);//, BorderLayout.NORTH);
        searchPanel.add(button);//, BorderLayout.SOUTH);
        searchPanel.setLayout(new BoxLayout(searchPanel, BoxLayout.Y_AXIS));

        Search search = new Search(countries, textField, model, list);
        search.setHighlightsPerCountry(listSelectionListener.getHighlightsPerCountry());
        button.addActionListener(search);

        JPanel panel = new JPanel();
        GroupLayout layout = new GroupLayout(panel);
        panel.setLayout(layout);
        layout.setAutoCreateGaps(true);
        layout.setAutoCreateContainerGaps(true);
               
        layout.setHorizontalGroup(layout.createSequentialGroup()
            .addComponent(listScrollPane)
            .addComponent(textScrollPane)
            .addComponent(imagePane)
            //.addGroup(layout.createParallelGroup(GroupLayout.Alignment.LEADING)
            .addComponent(searchPanel));

        layout.setVerticalGroup(layout.createSequentialGroup()
            .addGroup(layout.createParallelGroup(GroupLayout.Alignment.BASELINE)
                .addComponent(listScrollPane)
                .addComponent(textScrollPane)
                .addComponent(imagePane)
                    .addComponent(searchPanel)));
            
        // frame.setLayout(new GroupLayout(frame));
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.add(panel);
        
        frame.setSize(1200, 600);
        frame.setLocationRelativeTo(null);
        frame.setResizable(true);
        list.setSelectedIndex(0);
        frame.setVisible(true); 
    }
}

class Search implements ActionListener {
    private java.util.List<Country> countries;
    private String search = new String();

    private DefaultListModel<String> listModel;
    private JList<String> list; 

    private JTextArea text;
    Map<String, java.util.List<Pair<Integer, Integer>>> highlightsPerCountry;

    public Map<String, java.util.List<Pair<Integer, Integer>>> getHighlightsPerCountry() {
        return highlightsPerCountry;
    }

    public void setHighlightsPerCountry(Map<String, java.util.List<Pair<Integer, Integer>>> highlightsPerCountry) {
        this.highlightsPerCountry = highlightsPerCountry;
    }

    public Search(java.util.List<Country> countries, JTextArea text, DefaultListModel<String> listModel, JList<String> list) {
        this.countries = countries;
        this.text = text;
        this.listModel = listModel;
        this.list = list;
    }
 
    private java.util.List<Pair<Integer, Integer>> find(Country country, Pattern pattern) {
        java.util.List<Pair<Integer, Integer>> hits = new ArrayList<>();    
        String description = App.getImageAndDescription(country).getDescription();
        Matcher matcher = pattern.matcher(description);
        while (matcher.find()) {
            hits.add(new Pair<Integer, Integer>(matcher.start(), matcher.end()));
        }
       return hits;
    }

    public void actionPerformed(ActionEvent e) {
        if (search == text.getText()) return;
        String[] searches = text.getText().split("\n");

        java.util.List<Pattern> patterns = new ArrayList<>();
        for (int i = 0; i < searches.length; ++i) {
            String search = searches[i].trim();
            if (!search.isEmpty()) {
                patterns.add(Pattern.compile("\\b" + search + "\\b", Pattern.CASE_INSENSITIVE));
            }
        }
        
        Set<String> matchingCountries = new TreeSet<>();
        for (Country country : countries) {
            boolean matchesAll = true;
            java.util.List<Pair<Integer, Integer>> highlightsForAll = new ArrayList<>();
            for (Pattern pattern : patterns) {
                java.util.List<Pair<Integer, Integer>> highlights = find(country, pattern);
                if (highlights.isEmpty()) {
                    matchesAll = false;
                    break;
                }
                highlightsForAll.addAll(highlights);
            }
            if (matchesAll) {
                highlightsPerCountry.put(country.getCountryInfo().getName(), highlightsForAll);
                matchingCountries.add(country.getCountryInfo().getName());
            }
        }
        listModel.clear();
        for (Country country : countries) {
            if (matchingCountries.contains(country.getCountryInfo().getName())) {
                listModel.addElement(country.getCountryInfo().getName());
            }
        }
        System.out.println("Found " + matchingCountries.size() + " countries");
        list.setSelectedIndex(0);
    }
}

class CountryListSelectionListener implements ListSelectionListener {
    private java.util.List<Country> countries;
    private JFrame frame;
    private JList<String> list; 
    private JTextArea text;
    private ImagePanel imagePanel;
    private  Map<String, java.util.List<Pair<Integer, Integer>>> highlightsPerCountry = new TreeMap<>();

    public Map<String, java.util.List<Pair<Integer, Integer>>> getHighlightsPerCountry() {
        return highlightsPerCountry;
    }

    public void setHighlightsPerCountry(Map<String, java.util.List<Pair<Integer, Integer>>> highlightsPerCountry) {
        this.highlightsPerCountry = highlightsPerCountry;
    }

    public CountryListSelectionListener(
        java.util.List<Country> countries, JFrame frame, JList<String> list,
        JTextArea text, ImagePanel imagePanel) {
        this.countries = countries;
        this.frame = frame;
        this.list = list;
        this.text = text;
        this.imagePanel = imagePanel;
    }

    public void valueChanged(ListSelectionEvent event) {
        if (event.getValueIsAdjusting()) return;
        String name = list.getSelectedValue();
        try {
            for (Country country : countries) {
                if (!country.getCountryInfo().getName().equals(name)) continue;
                text.setText(App.getImageAndDescription(country).getDescription());
                Highlighter highlighter = text.getHighlighter();
                HighlightPainter painter = new DefaultHighlighter.DefaultHighlightPainter(Color.pink);          
                highlighter.removeAllHighlights();
                java.util.List<Pair<Integer, Integer>> hits = highlightsPerCountry.get(country.getCountryInfo().getName());
                if (hits != null) {
                    for (Pair<Integer, Integer> hit : hits) {
                        highlighter.addHighlight(hit.getX(), hit.getY(), painter);
                    }
                }
                String label = App.getImageAndDescription(country).getImageUrl().toString();
                int start = label.lastIndexOf("/");
                int end = label. lastIndexOf("_of_");
                if (start == - 1 || end == -1) {
                    label = "";
                } else {
                    label = "(" + label.substring(start + 1, end) + ")";
                }
                imagePanel.setImageAndDescription(country.getCountryInfo().getName() + label, App.getImageAndDescription(country));
                frame.repaint();
                break;
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}

class ImagePanel extends JPanel {
    private Image image;
    private JLabel label;

    public ImagePanel(JLabel label) {
        super();
        this.label = label;
    }

    private BufferedImage getScaledImage() {
        BufferedImage scaledImage = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = (Graphics2D) scaledImage.createGraphics();
        g2d.addRenderingHints(new RenderingHints(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY));
        g2d.drawImage(image, 0, 0, getWidth(), getHeight(), null);

        return scaledImage;
    }
  
    public void setImageAndDescription(String title, ImageAndDescription imageAndDescription) throws Exception {
        image = imageAndDescription.getImage();
        label.setText(title);
        repaint();
        System.out.println("Image " + image);
    }

    public void paint(Graphics g) {
//        super.paint(g);
        if (image == null) return;
        
        ((Graphics2D)g).setBackground(Color.WHITE);
        // Dimension titleSize = ((TitledBorder)getBorder()).getMinimumSize(this);

        g.fillRect(0, 0, image.getWidth(null), image.getHeight(null)) ;
        double w = image.getWidth(null);
        double h = image.getHeight(null);
        double pw = getSize().getWidth();
        setSize(new Dimension((int)pw, (int)((pw / w) * h)));
        g.drawImage(getScaledImage(), 0, 0, this);
        g.setColor(Color.WHITE);
    }
}
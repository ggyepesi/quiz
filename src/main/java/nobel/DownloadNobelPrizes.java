package nobel;
import java.awt.event.MouseAdapter;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTable;

import aux.BlockKeeper;
import aux.UrlLineProcessor;
import aux.UrlReader;

public class DownloadNobelPrizes implements UrlLineProcessor<Collection<NobelPrize>> {
    public static void main(String[] args) throws Exception {
        new DownloadNobelPrizes().read();
    }
    /*
        Center for Civil Liberties (human rights organization) Centre for Civil Liberties
        Institut de Droit International
        International Federation of Red Cross and Red Crescent Societies League of Red Cross societies
        International Peace Bureau
        Memorial (society) Memorial
    */
    private static final Set<String> ORGANIZATIONS = Set.of(
        "Office of the United Nations High Commissioner for Refugees",
        "United Nations Children's Fund",
        "Pugwash Conferences on Science and World Affairs",
        "International Campaign to Abolish Nuclear Weapons",
        "Organisation for the Prohibition of Chemical Weapons",
        "Intergovernmental Panel on Climate Change",
        "International Atomic Energy Agency",
        "International Committee of the Red Cross",
        "National Dialogue Quartet",
        "Grameen Bank",
        "Doctors Without Borders",
        "International Physicians for the Prevention of Nuclear War",
        "Amnesty International",
        "Nansen International Office for Refugees",
        "United Nations Peacekeeping Forces",
        "League of Red Cross Societies",
        "International Campaign to Ban Landmines",
        "American Friends Service Committee",
        "Friends Service Council",
        "International Labour Organization",
        "Permanent International Peace Bureau",
        "Institute of International Law",
        "Médecins Sans Frontières",
        "United Nations",
        "European Union",
        "World Food Programme",
        "Memorial",
        "Center for Civil Liberties",
        "Nihon Hidankyo",
        "League of Nations"
    );

    void read() throws Exception {
        final String url = "https://en.wikipedia.org/wiki/List_of_Nobel_laureates";
        UrlReader<Collection<NobelPrize>> urlReader = new UrlReader<Collection<NobelPrize>>(new DownloadNobelPrizes());
        Collection<NobelPrize> nobelPrizes = urlReader.read(url + "?action=raw");
        
        Map<Integer, List<NobelPrize>> perYear = new TreeMap<>();
        for (NobelPrize prize : nobelPrizes) {
            List<NobelPrize> prizes = perYear.get(prize.getYear());
            if (prizes == null) {
                prizes = new ArrayList<>();
                perYear.put(prize.getYear(), prizes);
            }
            prizes.add(prize);
        }

        String[] columnNames = new String[] {"YEAR", "PHYSICS", "CHEMISTRY", "MEDICINE", "LITERATURE", "PEACE", "ECONOMICS"};
        String[][] rows = new String[perYear.size()][columnNames.length];
        
        int i = 0;
        Set<String> uniqueNames = new TreeSet<>();
        for (List<NobelPrize> prizes : perYear.values()) {
            for (NobelPrize prize : prizes) {
                fillRow(rows, i, prize);
                for (Laureate laureate : getLaureates(prize)) {
                    if (!ORGANIZATIONS.contains(laureate.getName())) {
                        uniqueNames.add(laureate.getName());
                    }
                }
            }
            ++i;
        }
        
        // No cell is editable
        JTable table = new JTable(rows, columnNames) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        table.addMouseListener(new MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                if (evt.getClickCount() != 2) return;
                int row = table.getSelectedRow();
                int column = table.getSelectedColumn();
                int year = Integer.parseInt(rows[row][0]);
                if (column == 0) {
                    System.out.println("Should show prizes for " + year);
                    return;
                }
                // No prize for economics until 1969.
                if (perYear.get(year).size() < column) return;
                NobelPrize prize = perYear.get(year).get(column - 1);
                final String wiki = "https://en.wikipedia.org/wiki/";
                System.out.println(row + ", " + column + ": " + year + ", " + prize.getDomain());
                for (Laureate laureate : getLaureates(prize)) {
                    String url = wiki + laureate.getName().replaceAll(" ", "_");
                    System.out.println(laureate.getName() + ", " + url);
                }
            }
        });

        for (String name : uniqueNames) {
            System.out.println(name);
        }
        System.out.println(uniqueNames.size() + " unique persons");

        JFrame frame = new JFrame();
        JScrollPane scrollPane = new JScrollPane(table,
                                                 JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                                                 JScrollPane.HORIZONTAL_SCROLLBAR_ALWAYS);
        frame.add(scrollPane);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);        
        frame.setSize(800, 600);
        frame.setLocationRelativeTo(null);
        frame.setResizable(true);
        frame.setVisible(true);
    }

    private static void addPersons(NobelPrize prize, Collection<String> persons) {
        LaureatesWithMotivation lwm = new LaureatesWithMotivation();
        lwm.setMotivation("");
        for (String person : persons) {
            lwm.getLaureates().add(new Laureate(person));
        }
        prize.getLaureatesWithMotivation().add(lwm);
    }

    private static Collection<Laureate> getLaureates(NobelPrize prize) {
        List<Laureate> p = new ArrayList<>();
        for (LaureatesWithMotivation lwm : prize.getLaureatesWithMotivation()) {
            p.addAll(lwm.getLaureates());
        }
        return p;
    }

    private static void fillRow(String[][] rows, int j, NobelPrize nobelPrize) {
        rows[j][0] = "" + nobelPrize.getYear();
        int i = 1;
        for (NobelPrize.Domain domain : NobelPrize.Domain.values()) {
            if (nobelPrize.getDomain().equals(domain)) {
                rows[j][i] = personsToString(getLaureates(nobelPrize));
                break;
            }
            ++i;
        }
    }

    private static String personsToString(Collection<Laureate> laureates) {
        String s = "";
        for (Laureate laureate : laureates) {
            String name = laureate.getName();
            s += s.isEmpty() ? name : (", " + name);
        }
        return s;
    }

    /*
    |align=center bgcolor="eeeeee" | ''None'' stands for none,
    5 'sortname' or none is expected until Economics appears in 1969,
    there was no prize issued in 1940, 1941 and 1942

    |align=center| 1906
    |{{sortname|J. J.|Thomson}}
    |{{sortname|Henri|Moissan}}
    |{{sortname|Camillo|Golgi}};<br>{{sortname|Santiago|Ramón y Cajal}}
    |{{sortname|Giosuè|Carducci}}
    |{{sortname|Theodore|Roosevelt}}

    name links are on wiki with _ separated list of nametags, like https://en.wikipedia.org/wiki/J._J._Thomson

    variations to handle:
    |{{sortname|Ernesto Teodoro|Moneta}};<br>{{sortname|Louis|Renault|dab=jurist}}
    |{{sortname|Lord|Rayleigh|John Strutt, 3rd Baron Rayleigh}}
    |{{sort|Todd, Alexander R.|[[Alexander R. Todd|The Lord Todd]]}}
    |{{sortname|Auguste|Beernaert}};<br>{{sort|Estournelles de Constant, Paul Henri Balluet d'|[[Paul Henri Balluet d'Estournelles de Constant]]}}
    |[[UNICEF|United Nations International Children's Emergency Fund]]
    |[[Yang Chen-Ning]];<br>{{sortname|Tsung-Dao|Lee}}

    if dab=x is part of the name then (x) has to be appended to the wiki link like https://en.wikipedia.org/wiki/Michael_Houghton_(virologist)
    if an organization name has 2 tags separated with | then the second one is the current name, the first tag is the link name like
    https://en.wikipedia.org/wiki/International_Federation_of_Red_Cross_and_Red_Crescent_Societies for
    [[International Federation of Red Cross and Red Crescent Societies|League of Red Cross societies]]
    */
    private static Pattern yearPattern =
        Pattern.compile("!scope\\=row align=center\s*\\|[^\\d]*(?<year>\\d{4})");

    // A name is a |-separated list of first, middle and last name.
    private static Pattern namePattern =
        Pattern.compile("(\\[\\[(?<organization>[^]]+)\\]\\])|(\\{\\{sortname\\|(?<name>[^}]+)\\}\\})");
    private static Pattern nonePattern = Pattern.compile(".*None");
    private static final String none = "-";

    private static List<NobelPrize.Domain> domains = Arrays.asList(NobelPrize.Domain.values());

    private Collection<NobelPrize> nobelPrizes = new ArrayList<>();
    private Map<Integer, Map<NobelPrize.Domain, List<String>>> prizesPerYear = new TreeMap<>();

    private int year = -1;
    // Index of expected domain in domains.
    private int domain = domains.size();
    private BlockKeeper bk = new BlockKeeper();

    private boolean debug = false;

    private void readPerson(String person) throws Exception {
    }

    private List<String> parseNames(String line) throws Exception {
        List<String> persons = new ArrayList<>();
        Matcher matcher = nonePattern.matcher(line);
        if (matcher.matches()) {
            persons.add(none);
            return persons;
        }
        matcher = namePattern.matcher(line);
        while (matcher.find()) {
            String name = matcher.group("name");
            boolean is_person = true;
            if (name == null) {
                is_person = false;
                name = matcher.group("organization");
            }
            System.out.println("    " + name);
            String[] names = name.split("\\|");
            int length = names.length;
            // if last tag is dab=x then remove it.
            if (names[length - 1].startsWith("dab=")) {
                --length;
            }

            if (length > 2 && names[length - 1].indexOf(' ') != -1) {
                --length;
            }
            name = "";
            for (int i = 0; i < length; ++i) {
                name += i == 0 ? names[i] : (" " + names[i]);
            }
            System.out.println("    " + name);
            if (is_person) {
                    readPerson(name);
            }
            persons.add(name);
        }
        return persons;
    }

    @Override
    public URL processLine(String line) throws Exception {
        line = bk.update(line);
        if (debug) System.out.println(line);
        // Parse year if domain is not valid. If successfully parsed then set domain to zero.
        // Parse name if domain is valid (between 0 and domains.size() - 1). Check if None.
        // Increment domain if successfully parsed otherwise set it to -1 (see the year 1940 for example).
        if (domain == domains.size()) {
            Matcher matcher = yearPattern.matcher(line);
            boolean found = matcher.find();
            if (debug) {
                System.out.println("Find " + found + " in " + line);
            }
            if (found) {
                year = Integer.parseInt(matcher.group("year"));
                domain = 0;
                System.out.println("FOUND YEAR " + year + " in {" + line + "}");
                if (year == 2024) debug = true;
            }
            return null;
        }
        if (domain >= 0 && domain < domains.size()) {
            System.out.println("  " + domains.get(domain));
            List<String> persons = parseNames(line);
            if (persons.isEmpty()) {
                domain = domains.size(); // wait for year
            } else { //if (year >= 1960 && year < 1970) {
                NobelPrize prize = new NobelPrize();
                prize.setDomain(domains.get(domain));
                addPersons(prize, persons);
                Map<NobelPrize.Domain, List<String>> prizesPerDomain = prizesPerYear.get(year);
                if (prizesPerDomain == null) {
                    prizesPerDomain = new TreeMap<>();
                    prizesPerYear.put(year, prizesPerDomain);
                }
                if (prizesPerDomain.put(prize.getDomain(), persons) != null) {
                    throw new Exception("Multiple lists for " + domains.get(domain) + " in " + year);
                }
                ++domain;
            }
        }
        return null;
    }

    @Override
    public boolean isDone() {
        return false;
    }

    @Override
    public Collection<NobelPrize> done() throws Exception {
        for (Entry<Integer, Map<NobelPrize.Domain, List<String>>> entry : prizesPerYear.entrySet()) {
            for (Entry<NobelPrize.Domain, List<String>> d : entry.getValue().entrySet()) {
                NobelPrize prize = new NobelPrize();
                prize.setYear(entry.getKey());
                prize.setDomain(d.getKey());
                addPersons(prize, d.getValue());
                nobelPrizes.add(prize);
            }
        }
        return nobelPrizes;
    }
}

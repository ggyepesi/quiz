package nobel;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import aux.CachedImage;
import aux.Constants;

import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import aux.ResourceFinder;
import quiz.QuizableGroup;
import objectview.QuizableGroupView;
import objectview.ImagePane;
import quiz.Quizable;
import objectview.QuizableViews;

public class NobelPrizes implements QuizableViews {
    public final static Map<String, NobelPrize.Domain> domainAndYearStarts = Map.of(
            "The Nobel Prize in Physics", NobelPrize.Domain.PHYSICS,
            "The Nobel Prize in Chemistry", NobelPrize.Domain.CHEMISTRY,
            "The Nobel Prize in Physiology or Medicine", NobelPrize.Domain.PHYSIOLOGY_OR_MEDICINE,
            "The Nobel Prize in Literature", NobelPrize.Domain.LITERATURE,
            "The Nobel Peace Prize", NobelPrize.Domain.PEACE,
            "The Sveriges Riksbank Prize in Economic Sciences in Memory of Alfred Nobel", NobelPrize.Domain.ECONOMICS,
            "“No Nobel Prize was awarded this year.", NobelPrize.Domain.NONE);
    
    private final QuizableGroup rootGroup = new QuizableGroup("All");
    private QuizableGroupView groupView;
    private final Map<String, NobelPrize> nobelPrizes = new TreeMap<>();
    private final Map<String, Laureate> laureatesByName = new TreeMap<>();

    private final Map<String, Integer> uniquePersons = new TreeMap<>();
    // private Map<NobelPrize.Domain, Map<String, Integer>> personsPerDomain = new TreeMap<>();

    private final Map<String, Integer> orgs = new TreeMap<>();
    private final Map<NobelPrize.Domain, Set<Integer>> years = new TreeMap<>();

    public static void main(String[] args) throws Exception {
        NobelPrizes nobelPrizes = new NobelPrizes();
        nobelPrizes.buildViews();
        nobelPrizes.getGroupView().showFrame();
    }

    public static boolean isSingleYear(String line) {
        String[] chunks = line.split(",|\\band\\b");
        if (chunks.length == 1) {
            try {
                Integer.parseInt(chunks[0]);
                return true;
            } catch (NumberFormatException ignored) {}
        }
        return false;
    }

    // year
    // repeated domainAndYear
    //   repeated list of names
    //            motivation
    // empty line means end of file
    public void readNobelPrices(String filename) throws Exception {
        BufferedReader reader = Constants.getBufferedReaderForResource(filename);
        String line;
        NobelPrize currentPrize = null;
        List<Laureate> currentLaureates = new ArrayList<>();
        int year = 0;
        Map<NobelPrize.Domain, Integer> numPrizes = new TreeMap<>();
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) break;
            if (isSingleYear(line)) continue;
            boolean processed = false;
            for (Entry<String, NobelPrize.Domain> e : domainAndYearStarts.entrySet()) {
                if (line.startsWith(e.getKey())) {
                    NobelPrize.Domain domain = e.getValue();
                    if (domain.equals(NobelPrize.Domain.NONE)) {
                        if (currentPrize == null) {
                            reader.close();
                            throw new RuntimeException("No year for 'No Nobel Prize");
                        }
                        numPrizes.put(currentPrize.getDomain(), numPrizes.get(currentPrize.getDomain()) - 1);
                        years.get(currentPrize.getDomain()).remove(currentPrize.getYear());
                        currentPrize = null;
                        processed = true;
                    } else {
                        numPrizes.compute(domain, (k, i) -> i == null ? 1 : i + 1);
                        // parse year from end of line
                        String[] tags = line.split(" ");
                        year = Integer.parseInt(tags[tags.length - 1]);
                        store(currentPrize);
                        currentPrize = new NobelPrize();
                        currentPrize.setDomain(domain);
                        currentPrize.setYear(year);
                        years.computeIfAbsent(domain, x -> new TreeSet<>()).add(year);
                        processed = true;
                    }
                    break;
                }
            }
            if (processed) continue;        

            if (currentPrize == null) {
                reader.close();
                throw new RuntimeException("ERROR: expected domain and year " + line);
            }
            if (line.startsWith("“")) {
                if (currentLaureates.isEmpty()) {
                    System.out.println("No laureates at " + line);
                }
                LaureatesWithMotivation lwm = new LaureatesWithMotivation();
                lwm.getLaureates().addAll(currentLaureates);
                lwm.setMotivation(line);
                lwm.setPrize(currentPrize);
                currentPrize.getLaureatesWithMotivation().add(lwm);
                currentLaureates.clear();
            } else {
                NobelLaureateParser.Result names = NobelLaureateParser.parse(line);
                for (String name : names.organizations) {
                    orgs.compute(name, (k, i) -> i == null ? 1 : i + 1);
                    currentLaureates.add(laureatesByName.computeIfAbsent(name, l -> new Laureate(name)));
                }
                for (String name : names.people) {
                    if (name.equals("Jr.")) {
                        reader.close();
                        throw new RuntimeException("Jr. " + line);
                    }
                    uniquePersons.compute(name, (k, i) -> i == null ? 1 : i + 1);
                    currentLaureates.add(laureatesByName.computeIfAbsent(name, l -> new Laureate(name)));
                }
            }
        }
        reader.close();
        store(currentPrize);
        System.out.println("Reading portraits ...");
        readPortraits();
        //readPortraitsFromFile();

        int noPortrait = 0;
        for (Laureate laureate : laureatesByName.values()) {
            if (laureate.getPortrait() == null) {
                ++noPortrait;
                System.out.println("No portrait " + laureate.getName());
            }
        }
        System.out.println(noPortrait + " laureates without portrait");
    }

    private String fileNameWithExtension(String filename) {
        for (String ext : DownloadPortraits.imageExtensions) {
            if (ResourceFinder.resourceExists(filename + ext)) {
                return filename + ext;
            }
        }
        return null;
    }

    private void readPortraits() throws Exception {
        List<PortraitReader> readers = new ArrayList<>();
        for (Laureate laureate : laureatesByName.values()) {
            readers.add(new PortraitReader(laureate));            
        }
        System.out.println("Reading " + readers.size() + " portraits");
        for (PortraitReader pr : readers) {
            pr.start();
        }
        for (PortraitReader pr : readers) {
            pr.join();
        }
    }

    class PortraitReader extends Thread {
        private final Laureate laureate;

        public PortraitReader(Laureate laureate) {
            super(laureate.getName());
            this.laureate = laureate;
        }

        public void run() {
            try {
                String portraitFilename = fileNameWithExtension(Constants.nobelPortraitsDirectory + laureate.getName());
                if (portraitFilename != null) {
                    String url = ResourceFinder.toURL(portraitFilename).toString();
                    CachedImage cim =
                            new CachedImage(ResourceFinder.toURL(portraitFilename).toString(), null, false);
                    ImagePane ip = new ImagePane("", laureate, cim, true);
                    laureate.setPortrait(ip);
                }
            } catch (Exception e) {
                System.out.println("PortraitReader " + laureate.getName() + " failed: " + e.getMessage());
            }
        }
    }

    @Override
    public void buildViews() throws Exception {
        String filename = Constants.nobelDirectory + "nobelprizewithmotivation.txt"; // nobeltest 
        readNobelPrices(filename);
        groupView = new QuizableGroupView(rootGroup);
    }

    private void store(NobelPrize prize) {
        if (prize == null) return;
        nobelPrizes.put(prize.getName(), prize);
        QuizableGroup domainGroup = rootGroup.getOrCreateChild(prize.getDomain().name());
        domainGroup.addMember(prize);
    }

    @Override
    public QuizableGroupView getGroupView() {
        return groupView;
    }

    @Override
    public Map<String, ? extends Quizable> getQuizables() {
        return nobelPrizes;
    }
}
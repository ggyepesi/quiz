package oscar;

import aux.Constants;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import objectview.viewconfig.DomainViews;
import objectview.Viewable;
import quiz.group.ViewableGroup;
import wikidata.explore.extract.WikidataDynamicObject;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OscarNominations implements DomainViews {
    private static final String CACHE_FILE = Constants.oscarDataDirectory + "oscar-winners.json";

    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, OscarNomination> nominations = new LinkedHashMap<>();

    private ViewableGroup root = new ViewableGroup("All");


    public OscarNominations() {
    }

    // ------------------------------------------------------------------
    // Reads from Wikipedia, saves to file.
    // ------------------------------------------------------------------
    public static void main(String[] args) throws Exception {
        OscarNominations oscars = new OscarNominations();
        oscars.readWinners();
        oscars.saveToFile();

        oscars.getGroupView().showFrame();
    }

    public void readWinnersCached() throws Exception {
        File file = new File(CACHE_FILE);
        if (file.exists()) {
            loadFromFile();
            return;
        }

        readWinners();
        saveToFile();
    }

    public void mergeWinnersFromWikidata() throws Exception {
        loadFromFile();
        OscarWikidataReader reader = new OscarWikidataReader();
        List<OscarNomination> fresh = reader.readAllWinners();

        for (OscarNomination nomination : fresh) {
            merge(nomination);
        }

        rebuildGroups();
        saveToFile();
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    public void readWinners() throws Exception {
        nominations.clear();
        root = new ViewableGroup("All");

        OscarWikidataReader reader = new OscarWikidataReader();
        List<OscarNomination> read = reader.readAllWinners();
        for (OscarNomination n : read) {
            add(n);
        }

    }

    public void saveToFile() throws Exception {
        File file = new File(CACHE_FILE);

        File parent = file.getParentFile();

        if (parent != null) {
            parent.mkdirs();
        }

        mapper.writerWithDefaultPrettyPrinter().writeValue(file, nominations);
        System.out.println("Saved Oscar cache: " + file.getAbsolutePath());
    }

    // ------------------------------------------------------------------
    // Merge
    // ------------------------------------------------------------------

    public void loadFromFile() throws Exception {
        File file = new File(CACHE_FILE);

        if (!file.exists()) {
            return;
        }

        Map<String, OscarNomination> loaded = mapper.readValue(file, new TypeReference<LinkedHashMap<String, OscarNomination>>() {
        });

        nominations.clear();
        nominations.putAll(loaded);

        rebuildGroups();

        System.out.println("Loaded Oscar cache: " + file.getAbsolutePath());
    }

    // ------------------------------------------------------------------
    // Groups
    // ------------------------------------------------------------------

    private void merge(OscarNomination fresh) {
        String key = uniqueKey(fresh);

        OscarNomination old = nominations.get(key);

        if (old == null) {
            nominations.put(key, fresh);
            return;
        }

        if (old.getNominee() == null) {
            old.setNominee(fresh.getNominee());
        }

        if (old.getAward() == null) {
            old.setAward(fresh.getAward());
        }

        if (old.getWork() == null) {
            old.setWork(fresh.getWork());
        }

        if (old.getCeremonyYear() == 0) {
            old.setCeremonyYear(fresh.getCeremonyYear());
        }

        if (old.getFilmYear() == 0) {
            old.setFilmYear(fresh.getFilmYear());
        }

        old.setWinner(old.isWinner() || fresh.isWinner());
    }

    private void rebuildGroups() {
        root = new ViewableGroup("All");

        for (Map.Entry<String, OscarNomination> e : nominations.entrySet()) {

            addToGroups(e.getKey(), e.getValue());
        }

    }

    private void add(OscarNomination n) {
        String key = uniqueKey(n);

        while (nominations.containsKey(key)) {
            key += "#";
        }

        nominations.put(key, n);

        addToGroups(key, n);
    }

    // ------------------------------------------------------------------
    // Keys
    // ------------------------------------------------------------------

    private void addToGroups(String key, OscarNomination n) {
        root.addMember(n);

        if (n.getCeremonyYear() > 0) {
            root.getOrCreateChild("Ceremony Year").getOrCreateChild(String.valueOf(n.getCeremonyYear())).addMember(n);
        }

        WikidataDynamicObject award = n.getAward();
        if (award != null && award.getName() != null && !award.getName().isBlank()) {
            root.getOrCreateChild("Award").getOrCreateChild(award.getName()).addMember(n);
        }

        root.getOrCreateChild("Result").getOrCreateChild(n.isWinner() ? "Winners" : "Nominees").addMember(n);
    }

    private String uniqueKey(OscarNomination n) {
        return safeQid(n.getNominee()) + "|" + safeQid(n.getAward()) + "|" + safeQid(n.getWork()) + "|" + n.getCeremonyYear() + "|" + n.isWinner();
    }

    // ------------------------------------------------------------------
    // Views
    // ------------------------------------------------------------------

    private String safeQid(WikidataDynamicObject e) {
        if (e == null) {
            return "";
        }

        String qid = e.getIdentifier();

        return qid == null ? "" : qid;
    }

    @Override
    public Map<String, ? extends Viewable> getViewables() {
        return nominations;
    }

    @Override
    public void buildViews() throws Exception {
        loadFromFile();
    }

    @Override
    public java.util.List<objectview.viewconfig.DomainGroupRoot> getGroupRootBindings() {
        return java.util.List.of(new objectview.viewconfig.DomainGroupRoot(
                OscarNomination.class.getSimpleName(), root));
    }
}

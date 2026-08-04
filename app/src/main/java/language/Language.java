package language;

import objectview.annotations.Minor;
import quiz.source.ManualEntity;
import objectview.annotations.Reference;

import java.util.ArrayList;
import java.util.List;

public class Language extends ManualEntity {
    private final String name;

    private String nativeName;
    @Minor
    private String writingSystem;
    @Minor
    private String region;
    private String ethnicity;
    private String speakers;

    @Minor
    private String iso6391;
    @Minor
    private String iso6392;
    @Minor
    private String iso6393;
    @Minor
    private String glottolog;
    @Minor
    private String wikipediaTitle;
    @Minor
    private String wikipediaUrl;

    private final List<String> countries = new ArrayList<>();
    @Minor
    private final List<String> scripts = new ArrayList<>();
    @Reference
    private final List<LanguageFamily> leafFamilies = new ArrayList<>();

    public Language(String name) {
        this.name = name;
    }

    public String getNativeName() { return nativeName; }
    public String getWritingSystem() { return writingSystem; }
    public String getRegion() { return region; }
    public String getEthnicity() { return ethnicity; }
    public String getSpeakers() { return speakers; }
    public String getIso6391() { return iso6391; }
    public String getIso6392() { return iso6392; }
    public String getIso6393() { return iso6393; }
    public String getGlottolog() { return glottolog; }
    public String getWikipediaTitle() { return wikipediaTitle; }
    public String getWikipediaUrl() { return wikipediaUrl; }

    public List<String> getCountries() { return countries; }
    public List<String> getScripts() { return scripts; }
    public List<LanguageFamily> getLeafFamilies() { return leafFamilies; }

    public void setNativeName(String nativeName) { this.nativeName = clean(nativeName); }
    public void setWritingSystem(String writingSystem) { this.writingSystem = clean(writingSystem); }
    public void setRegion(String region) { this.region = clean(region); }
    public void setEthnicity(String ethnicity) { this.ethnicity = clean(ethnicity); }
    public void setSpeakers(String speakers) { this.speakers = clean(speakers); }
    public void setIso6391(String iso6391) { this.iso6391 = clean(iso6391); }
    public void setIso6392(String iso6392) { this.iso6392 = clean(iso6392); }
    public void setIso6393(String iso6393) { this.iso6393 = clean(iso6393); }
    public void setGlottolog(String glottolog) { this.glottolog = clean(glottolog); }
    public void setWikipediaTitle(String wikipediaTitle) { this.wikipediaTitle = clean(wikipediaTitle); }
    public void setWikipediaUrl(String wikipediaUrl) { this.wikipediaUrl = clean(wikipediaUrl); }

    public void addCountry(String country) {
        addUnique(countries, country);
    }

    public void addScript(String script) {
        addUnique(scripts, script);
    }

    public void addLeafFamily(LanguageFamily family) {
        if (family != null && !leafFamilies.contains(family)) {
            leafFamilies.add(family);
        }
    }

    public List<String> getLeafFamilyNames() {
        List<String> names = new ArrayList<>();

        for (LanguageFamily family : leafFamilies) {
            names.add(family.getName());
        }

        return names;
    }

    private static void addUnique(List<String> list, String value) {
        value = clean(value);

        if (value != null && !list.contains(value)) {
            list.add(value);
        }
    }

    private static String clean(String s) {
        if (s == null) return null;

        s = s.trim();

        if (s.isEmpty() || s.equals("-") || s.equals("—")) {
            return null;
        }

        return s;
    }

    @Override
    public String getIdentifier() { return name; }

    @Override
    public String getDisplayName() { return name; }

}

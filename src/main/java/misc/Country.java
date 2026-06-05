package misc;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class Country implements Serializable {
    private CountryInfo countryInfo = new CountryInfo();
    private ImageAndDescription flag = new ImageAndDescription();
    private List<ImageAndDescription> coatsOfArms = new ArrayList<>();

    public CountryInfo getCountryInfo() {
        return countryInfo;
    }
    public ImageAndDescription getFlag() {
        return flag;
    }
    public List<ImageAndDescription> getCoatsOfArms() {
        return coatsOfArms;
    }
    public void setFlag(ImageAndDescription flag) {
        this.flag = flag;
    }
    public void addCoatOfArms(ImageAndDescription coatOfArms) {
        coatsOfArms.add(coatOfArms);
    }
}

class Currency implements Serializable {
    private String currency = new String();
    private String qualifier = new String();
    private String code = new String();
    private int numericCode = 0;

     public Currency() {}
    
    public Currency(String currency, String qualifier) {
        this.currency = currency;
        this.qualifier = qualifier;
    }

    public String getCurrency() {
        return currency;
    }
    public void setCurrency(String currency) {
        this.currency = currency;
    }
    public String getQualifier() {
        return qualifier;
    }
    public void setQualifier(String qualifier) {
        this.qualifier = qualifier;
    }
    public String getCode() {
        return code;
    }
    public void setCode(String code) {
        this.code = code;
    }
    public int getNumericCode() {
        return numericCode;
    }
    public void setNumericCode(int numericCode) {
        this.numericCode = numericCode;
    }

    public String toString() {
        String s = "currency " + currency + ", code " + code + ", numeric " + numericCode;
        if  (!qualifier.isEmpty()) s += ", qualifier " + qualifier;
        return s;
    }
}

class CurrencyInfo implements Serializable {
    private Currency currency = new Currency();
    private List<String> countries = new ArrayList<>();

    public Currency getCurrency() {
        return currency;
    }
    public void setCurrency(Currency currency) {
        this.currency = currency;
    }
    public List<String> getCountries() {
        return countries;
    }

    public String toString() {
        String s = "currency " + currency + " " + countries.size() + " countries";
        for (String country : countries) {
            s += "\n  " + country;
        }
        return s;
    }
}

class Population implements Serializable {
    double population = 0;
    int estimate_year = 0;
}

class CountryInfo implements Serializable {
    private String name;
    private String sovereignty;
    private String iso1 = new String();  // 2 char code
    private String iso2 = new String();  // 3 char code
    private int iso3 = 0;
    private String internetTLD = new String();
    private String callingCode = new String();
    private Population population = new Population();
    private List<String> categories = new ArrayList<>();

    private List<QualifiedValue> capitals = new ArrayList<>();
    private List<QualifiedValue> capitalsExile = new ArrayList<>();
 
    private List<QualifiedValue> official_languages = new ArrayList<>();
    private List<QualifiedValue> national_languages = new ArrayList<>();
    private List<QualifiedValue> languages = new ArrayList<>();

    private List<Currency> currencies = new ArrayList<>();

    public String getName() {
        return name;
    }
    public void setName(String name) {
        this.name = name;
    }
      public String getSovereignty() {
        return sovereignty;
    }
    public void setSovereignty(String sovereignty) {
        this.sovereignty = sovereignty;
    }
    public String getIso1() {
        return iso1;
    }
    public void setIso1(String iso1) {
        this.iso1 = iso1;
    }
    public String getIso2() {
        return iso2;
    }
    public void setIso2(String iso2) {
        this.iso2 = iso2;
    }
    public int getIso3() {
        return iso3;
    }
    public void setIso3(int iso3) {
        this.iso3 = iso3;
    }
    public String getInternetTLD() {
        return internetTLD;
    }
    public void setInternetTLD(String internetTLD) {
        this.internetTLD = internetTLD;
    }
    public String getCallingCode() {
        return callingCode;
    }
    public void setCallingCode(String callingCode) {
        this.callingCode = callingCode;
    }
    public Population getPopulation() {
        return population;
    }
    public void setPopulation(Population population) {
        this.population = population;
    }
    public List<QualifiedValue> getCapitals() {
        return capitals;
    }
    public List<QualifiedValue> getCapitalsExile() {
        return capitalsExile;
    }
    public List<String> getCategories() {
        return categories;
    }
    public List<QualifiedValue> getOfficialLanguages() {
        return official_languages;
    }
    public List<QualifiedValue> getNationalLanguages() {
        return national_languages;
    }
    public List<QualifiedValue> getLanguages() {
        return languages;
    }
    public List<Currency> getCurrencies() {
        return currencies;
    }
}

class Language implements Serializable {
    private String name;
    private List<String> families  = new ArrayList<>();
    private List<String> ancestors  = new ArrayList<>();
    private List<String> children  = new ArrayList<>();
    private List<String> dialects  = new ArrayList<>();
    private List<String> countries  = new ArrayList<>();

    public String getName() {
        return name;
    }
    public void setName(String name) {
        this.name = name;
    }
    public List<String> getFamilies() {
        return families;
    }
    public void setFamilies(List<String> families) {
        this.families = families;
    }
    public List<String> getAncestors() {
        return ancestors;
    }
    public void setAncestors(List<String> ancestors) {
        this.ancestors = ancestors;
    }
    public List<String> getChildren() {
        return children;
    }
    public void setChildren(List<String> children) {
        this.children = children;
    }
    public List<String> getDialects() {
        return dialects;
    }
    public void setDialects(List<String> dialects) {
        this.dialects = dialects;
    }    public List<String> getCountries() {
        return countries;
    }
    public void setCountries(List<String> countries) {
        this.countries = countries;
    }
}

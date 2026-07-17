package oscar;

import wikidata.query.WikidataQueryBuilder;

public final class OscarWikidataQueryBuilder {

    private OscarWikidataQueryBuilder() {}

    public static String allOscarAwards() {
        return new WikidataQueryBuilder()
                .selectEntity("award")
                .rawWhere("""
                        VALUES ?academyAwardRoot { wd:Q19020 }
                        ?award wdt:P31/wdt:P279* ?academyAwardRoot .
                        """)
                .orderBy("awardLabel")
                .build();
    }

    public static String winnersForAward(String awardQid,
                                         int limit,
                                         int offset) {
        return new WikidataQueryBuilder()
                .bindEntity("award", awardQid)
                .selectEntity("nominee")
                .selectEntity("award")
                .select("time")
                .statement("nominee", "P166", "statement", "award")
                .optionalQualifier("statement", "P585", "time")
                .bindBoolean("winner", true)
                .orderBy("time", "nomineeLabel")
                .limit(limit)
                .offset(offset)
                .build();
    }

    public static String nominationsForAward(String awardQid,
                                             int limit,
                                             int offset) {
        return new WikidataQueryBuilder()
                .bindEntity("award", awardQid)
                .selectEntity("nominee")
                .selectEntity("award")
                .selectEntity("work")
                .select("time", "releaseDate")
                .statement("nominee", "P1411", "statement", "award")
                .optionalQualifier("statement", "P1686", "work")
                .optionalQualifier("statement", "P585", "time")
                .optionalTruthy("work", "P577", "releaseDate")
                .exists("winner", """
                        ?nominee p:P166 ?awardStatement .
                        ?awardStatement ps:P166 ?award .
                        """)
                .orderBy("time", "nomineeLabel")
                .limit(limit)
                .offset(offset)
                .build();
    }

    public static String portraitForPerson(String personQid) {
        return new WikidataQueryBuilder()
                .bindEntity("person", personQid)
                .select("image")
                .truthy("person", "P18", "image")
                .limit(1)
                .build();
    }
}
package nobel;

import java.util.ArrayList;
import java.util.List;

public class NobelLaureateParser {
    // Organizations sorted by length (longest first)
    private static final List<String> ORGANIZATIONS = new ArrayList<>(List.of(
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
    ));

    public static class Result {
        List<String> people = new ArrayList<>();
        List<String> organizations = new ArrayList<>();
    }

    public static Result parse(String text) {
        Result result = new Result();
        String remaining = text;

        // 1. Match organizations (longest first)
        for (String org : ORGANIZATIONS) {
            if (remaining.contains(org)) {
                result.organizations.add(org);
                remaining = remaining.replace(org, ""); // remove matched org
            }
        }

        // 2. Split remaining by comma and "and"
        String[] chunks = remaining.split(",|\\band\\b");
        for (String chunk : chunks) {
            chunk = chunk.trim();
            if (!chunk.isEmpty()) {
                result.people.add(chunk);
            }
        }

        return result;
    }
}


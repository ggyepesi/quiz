package myth;

import java.io.*;
import java.util.*;

import aux.UrlOpener;

public class MythologyEngine {

    /* =========================
       ENTITY
    ========================= */

    static class Entity {

        String name;
        String type;
        String description;

        Map<String,Entity> relations = new HashMap<>();
        List<Event> events = new ArrayList<>();

        Entity(String n,String t){
            name=n;
            type=t;
        }

        void relate(String r,Entity e){
            relations.put(r,e);
        }

        void addEvent(Event e){
            events.add(e);
        }
    }

    /* =========================
       EVENT
    ========================= */

    static class Event {

        String name;
        String description;
        List<Entity> participants = new ArrayList<>();

        Event(String n,String d){
            name=n;
            description=d;
        }

        void add(Entity e){
            participants.add(e);
        }
    }

    /* =========================
       GRAPH
    ========================= */

    static class Graph {

        Map<String,Entity> entities = new HashMap<>();
        List<Event> events = new ArrayList<>();

        void add(Entity e){
            entities.put(e.name.toLowerCase(),e);
        }

        Entity get(String name){
            return entities.get(name.toLowerCase());
        }

        void addEvent(Event e){
            events.add(e);
        }
    }

    /* =========================
       WIKIPEDIA UTILITIES
    ========================= */

    static class Wiki {

        static String page(String title){

            try{

                String url =
                "https://en.wikipedia.org/wiki/" +
                title.replace(" ","_");

                BufferedReader br = new BufferedReader(new InputStreamReader(UrlOpener.open(url)));

                StringBuilder html = new StringBuilder();
                String line;

                while((line = br.readLine()) != null)
                    html.append(line);

                return html.toString();

            }catch(Exception e){
                return "";
            }
        }

        static String intro(String html){

            int s = html.indexOf("<p>");
            int e = html.indexOf("</p>",s);

            if(s<0) return "";

            String p = html.substring(s,e);

            return p.replaceAll("<[^>]+>","")
                    .replaceAll("&[^;]+;","")
                    .replaceAll("\\s+"," ")
                    .trim();
        }

        static String parents(String html){

            int i = html.toLowerCase().indexOf("parents");

            if(i<0) return "";

            int start = html.indexOf("<td",i);
            int end = html.indexOf("</td>",start);

            if(start<0) return "";

            String cell = html.substring(start,end);

            return cell.replaceAll("<[^>]+>","")
                    .replaceAll("&[^;]+;","")
                    .trim();
        }
    }

    /* =========================
       SCRAPE CREATURE LIST
    ========================= */

    static List<Entity> scrapeCreatures(){

        List<Entity> list = new ArrayList<>();

        try{

            String url = "https://en.wikipedia.org/wiki/List_of_Greek_mythological_creatures";

            BufferedReader br = new BufferedReader(new InputStreamReader(UrlOpener.open(url)));

            StringBuilder html = new StringBuilder();
            String line;
            while((line = br.readLine()) != null)
                html.append(line);

            String page = html.toString();

            int i=0;

            while((i = page.indexOf("<li>",i)) != -1){

                int end = page.indexOf("</li>",i);
                if(end==-1) break;

                String item = page.substring(i+4,end);
                item = item.replaceAll("<[^>]+>","");

                if(!item.contains(":")){
                    i=end;
                    continue;
                }

                String name = item.split(":")[0].trim();

                if(name.length()<2 || name.length()>40){
                    i=end;
                    continue;
                }

                Entity e = new Entity(name,"creature");
                list.add(e);

                i=end;
            }

        }catch(Exception e){
            e.printStackTrace();
        }

        return list;
    }

    /* =========================
       QUERY ENGINE
    ========================= */

    static class Query {

        Graph graph;

        Query(Graph g){
            graph=g;
        }

        void listCreatures(){

            System.out.println("\nCreatures:");

            for(Entity e:graph.entities.values())
                if(e.type.equals("creature"))
                    System.out.println(e.name);
        }

        void whoKilled(String monster){

            System.out.println("\nWho killed "+monster+"?");

            for(Event e:graph.events)
                if(e.name.toLowerCase().contains(monster))
                    for(Entity p:e.participants)
                        if(p.type.equals("hero"))
                            System.out.println(p.name);
        }

        void eventsOf(String name){

            System.out.println("\nEvents of "+name);

            for(Event e:graph.events)
                for(Entity p:e.participants)
                    if(p.name.equalsIgnoreCase(name))
                        System.out.println(e.name);
        }

        void ask(String q){

            q=q.toLowerCase();

            if(q.contains("creatures"))
                listCreatures();

            else if(q.startsWith("who killed"))
                whoKilled(q.replace("who killed","").trim());

            else if(q.startsWith("events of"))
                eventsOf(q.replace("events of","").trim());

            else
                System.out.println("Unknown query");
        }
    }

    /* =========================
       GRAPH EXPORT
    ========================= */

    static void export(Graph g) throws Exception{

        PrintWriter out = new PrintWriter("mythology.dot");

        out.println("digraph mythology {");

        for(Entity e:g.entities.values())
            for(String r:e.relations.keySet())
                out.println("\""+e.name+"\" -> \""+
                        e.relations.get(r).name+
                        "\" [label=\""+r+"\"];");

        out.println("}");

        out.close();
    }

    /* =========================
       LOAD BASE DATA
    ========================= */

    static void loadCore(Graph g){

        Entity zeus = new Entity("Zeus","god");
        Entity perseus = new Entity("Perseus","hero");
        Entity heracles = new Entity("Heracles","hero");
        Entity medusa = new Entity("Medusa","creature");

        g.add(zeus);
        g.add(perseus);
        g.add(heracles);
        g.add(medusa);

        Event medusaKill =
        new Event("Kill Medusa","Perseus slays Medusa");

        medusaKill.add(perseus);
        medusaKill.add(medusa);

        g.addEvent(medusaKill);
    }

    /* =========================
       MAIN
    ========================= */

    public static void main(String[] args) throws Exception{

        Graph graph = new Graph();

        loadCore(graph);

        List<Entity> creatures = scrapeCreatures();

        for(Entity e:creatures) {
            String html = Wiki.page(e.name);
            e.description = Wiki.intro(html);

            graph.add(e);
        }

        System.out.println("Creatures loaded: "+creatures.size());

        Query q = new Query(graph);

        q.ask("list creatures");
        q.ask("who killed medusa");
        q.ask("events of perseus");

        export(graph);
        System.out.println("\nGraph exported to mythology.dot");
    }
}
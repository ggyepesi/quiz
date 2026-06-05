package aux;

import java.awt.Color;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.List;

public class ShadesOfGreen {
    public final static List<Shade> shadesOfGreen = List.of(
        new Shade("Aqua", new Color(65535)),
        new Shade("Aquamarine", new Color(8388564)),
        new Shade("Army Green", new Color(4541211)),
        new Shade("Blue Green", new Color(561039)),
        new Shade("Bright Green", new Color(11206400)),
        new Shade("Cadet Blue", new Color(6266528)),
        new Shade("Cadmium Green", new Color(620905)),
        new Shade("Celadon", new Color(11526575)),
        new Shade("Chartreuse", new Color(14679808)),
        new Shade("Citrine", new Color(14995466)),
        new Shade("Cyan", new Color(65535)),
        new Shade("Dark Green", new Color(143392)),
        new Shade("Electric Blue", new Color(8255999)),
        new Shade("Emerald Green", new Color(5294200)),
        new Shade("Eucalyptus", new Color(6260085)),
        new Shade("Fern Green", new Color(5208386)),
        new Shade("Forest Green", new Color(2263842)),
        new Shade("Grass Green", new Color(8190976)),
        new Shade("Green", new Color(32768)),
        new Shade("Hunter Green", new Color(3497531)),
        new Shade("Jade", new Color(41836)),
        new Shade("Jungle Green", new Color(2796170)),
        new Shade("Kelly Green", new Color(5028631)),
        new Shade("Light Green", new Color(9498256)),
        new Shade("Lime Green", new Color(3329330)),
        new Shade("Lincoln Green", new Color(4687736)),
        new Shade("Malachite", new Color(776785)),
        new Shade("Mint Green", new Color(10025880)),
        new Shade("Moss Green", new Color(9083483)),
        new Shade("Neon Green", new Color(1048400)),
        new Shade("Nyanza", new Color(15531996)),
        new Shade("Olive Green", new Color(8421376)),
        new Shade("Pastel Green", new Color(12706241)),
        new Shade("Pear", new Color(13225023)),
        new Shade("Peridot", new Color(11846692)),
        new Shade("Pistachio", new Color(9684338)),
        new Shade("Robin Egg Blue", new Color(9887441)),
        new Shade("Sage Green", new Color(9083483)),
        new Shade("Sea Green", new Color(3050327)),
        new Shade("Seafoam Green", new Color(10478271)),
        new Shade("Shamrock Green", new Color(40544)),
        new Shade("Spring Green", new Color(65407)),
        new Shade("Teal", new Color(32896)),
        new Shade("Turquoise", new Color(4251856)),
        new Shade("Vegas Gold", new Color(12891220)),
        new Shade("Verdigris", new Color(4240813)),
        new Shade("Viridian", new Color(4227693))
    );

    public static void main(String args[]) throws Exception {
        BufferedReader reader = Constants.getBufferedReaderForResource(Constants.flagDirectory + "green.txt");
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] tags = line.split("\t");
            System.out.println("\t\tnew Shade(\"" + tags[0] + "\", new Color(" + Integer.decode(tags[1]) + ")),");
        }
        reader.close();
    }
}

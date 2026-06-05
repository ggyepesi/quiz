package aux;

import java.awt.Color;

public class Shade {
    private String name;
    private Color color;
    
    public Shade(String name, Color color) {
        this.name = name;
        this.color = color;
    }

    public String getName() {
        return name;
    }

    public Color getColor() {
        return color;
    }
}
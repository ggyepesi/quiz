package quiz;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.Random;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import aux.CachedImage;
import flag.State;
import flag.auxiliary.ImageIntersection;
import objectview.media.ImagePane;

public class QuizIntersection implements Runnable {
    private HashMap<List<Integer>, List<ImagePane>> imagePanesBySize = new HashMap<>();
    private Random random = new Random();

    // List of sizes (size a List of length 2 for width and height)
    private List<List<Integer>> sizes = new ArrayList<>();

    // indexPairsToSize is a map size -> list of pair of indices List<ImagePanes>-s in imagePanesBysize
    // a pair of indices is removed when the intersection of the corresponding images has selected and shwon
    // a size is removed from sizes when the associated list of index-pairs becomes empty
    // The quiz is doen when keys becomes empty.
    private HashMap<List<Integer>, List<List<Integer>>> indexPairsToSize = new HashMap<>();

    // <name0, name1> -> index of key in 
    // for countries with flags having
    //private HashMap<List<String>, List<Integer>> indicesTonamePairs = new HashMap<>();

    private JPanel imagePanel;

    private JPanel textPanel = new JPanel();
    private JTextField field0 = new JTextField();
    private JTextField field1 = new JTextField();

    public QuizIntersection(HashMap<List<Integer>, List<ImagePane>> imagePanesBySize) {
        // compute sizes and indexPairsToSize
        for (Entry<List<Integer>, List<ImagePane>> e : imagePanesBySize.entrySet()) {
            if (e.getValue().size() >= 2) {
                this.imagePanesBySize.put(e.getKey(), e.getValue());
                sizes.add(e.getKey());
                List<List<Integer>> indexPairs = new ArrayList<>();
                indexPairsToSize.put(e.getKey(), indexPairs);
                for (int i = 0; i < e.getValue().size() - 1; ++i) {
                    for (int j = i + 1; j < e.getValue().size(); ++j) {
                        List<Integer> indexPair = new ArrayList<>();
                        indexPair.add(i);
                        indexPair.add(j);
                        indexPairs.add(indexPair);
                        ++numPairs;
                    }
                }
            }
        }
    }

    public void show() {
        JFrame frame = new JFrame();
        Thread thread = new Thread(this);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent we) {
                stopped = true;
                thread.interrupt();
                try { thread.join(); } catch (InterruptedException ie) {}
            }
        });        frame.setSize(400, 1200);
        frame.setLocationRelativeTo(null);
        frame.add(textPanel, BorderLayout.NORTH);

        textPanel.setLayout(new GridBagLayout());
        GridBagConstraints textGbc = Quiz.createGridBagConstraints();

        Font font = field0.getFont();
        font = font.deriveFont(font.getSize() * 1.5f);
        field0.setFont(font);
        field1.setFont(font);

        textPanel.add(field0, textGbc);
        textGbc.gridx++;
        textPanel.add(field1, textGbc);

        imagePanel = new JPanel(new GridLayout(3, 1, 10, 10));
        imagePanel.setBackground(Color.DARK_GRAY);
        frame.add(imagePanel, BorderLayout.CENTER);

        JButton button = new JButton("Next");
        button.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                synchronized (QuizIntersection.this) {
                    next = true;
                    QuizIntersection.this.notify();
                }
            }    
        });
        frame.add(button, BorderLayout.SOUTH);
        SwingUtilities.invokeLater(() -> {
            frame.setVisible(true);
        });

        thread.start();
    }

    private boolean next = false;
    private boolean stopped = false;
    private int numPairs = 0;

    public void run() {
        while (!sizes.isEmpty()) {
            next = false;
            nextIntersection();
            synchronized (this) {
                while (!next && !stopped) {
                    try {
                        wait();
                    } catch (InterruptedException e) {}
                }
                if (stopped) {
                    System.out.println("Quiz has stopped.");
                    return;
                }
            }
        }
        System.out.println("All pairs have been shown.");
    }

    private void nextIntersection() {
        ImagePane imagePane0 = null;
        ImagePane imagePane1 = null;
        int sizeIndex = -1;
        int index = -1;
        List<List<Integer>> indexPairs = null;
        String name0 = field0.getText();
        String name1 = field1.getText();
        System.out.println("Intersect countries " + name0 + ", " + name1);
        boolean found = false;

        if (!name0.isEmpty() && !name1.isEmpty()) {
            for (sizeIndex = 0; sizeIndex < sizes.size(); ++sizeIndex) {
                for (index = 0; index < indexPairsToSize.get(sizes.get(sizeIndex)).size(); ++index) {
                    indexPairs = indexPairsToSize.get(sizes.get(sizeIndex));

                    int index0 = indexPairs.get(index).get(0);
                    int index1 = indexPairs.get(index).get(1);
    
                    imagePane0 = imagePanesBySize.get(sizes.get(sizeIndex)).get(index0);
                    imagePane1 = imagePanesBySize.get(sizes.get(sizeIndex)).get(index1);
                    if ((imagePane0.getName().equals(name0) && imagePane1.getName().equals(name1)) ||
                        (imagePane0.getName().equals(name1) && imagePane1.getName().equals(name0))) {
                            found = true;
                            break;
                    }
                }
                if (found) break;
            }
            if (!found) {
                synchronized (QuizIntersection.this) {
                    next = true;
                    QuizIntersection.this.notify();
                }
                field0.setText("");
                return;
            }
        }
        if (!found) {
            // select random key and 2 random images belonging to the selected key
            sizeIndex = random.nextInt(sizes.size());
            indexPairs = indexPairsToSize.get(sizes.get(sizeIndex));
            index = random.nextInt(indexPairs.size());
        }
        List<Integer> size = sizes.get(sizeIndex);
        int index0 = indexPairs.get(index).get(0);
        int index1 = indexPairs.get(index).get(1);

        imagePane0 = imagePanesBySize.get(sizes.get(sizeIndex)).get(index0);
        imagePane1 = imagePanesBySize.get(sizes.get(sizeIndex)).get(index1);
        indexPairs.remove(index);
        if (indexPairs.isEmpty()) {
            sizes.remove(sizeIndex);
        }
        System.out.println("There are " + sizes.size() + " different sizes and " + numPairs + " flag pairs");

        String country0 = imagePane0.getName();
        String country1 = imagePane1.getName();

        field0.setText(country0);
        field1.setText(country1);

        Image image0 = null;
        Image image1 = null;
        Image intersection = null;
        String name = null;
        try {
            name = country0;
            image0 = imagePane0.getCachedImage().getFullImage();
            name = imagePane1.getName();
            image1 = imagePane1.getCachedImage().getFullImage();
        } catch (Exception e) {
            System.out.println("Couldn't get image from CachedImage for " + name);
            e.printStackTrace();
            return;
        }

        // if the imagePane0 and imagePane1 have different sizes then scale the smaller one
        // they have the same aspectratio
        int w0 = image0.getWidth(null);
        int h0 = image0.getHeight(null);
        int w1 = image1.getWidth(null);
        int h1 = image1.getHeight(null);
    
        if (w0 != w1) {
            if (w0 < w1) {
                System.out.println("Rescale " + country0);
                image1 = CachedImage.scaleImage(image1, w0, h0);
            } else {
                System.out.println("Rescale " + country1);
                image0 = CachedImage.scaleImage(image0, w1, h1);
            }
        }

        System.err.println("Image sizes of " + country0 + ", " + country1 + ": ratio " + size + ", " +
                            w0 + "x" + h0 + ", " + w1 + "x" + h1 + " -> " + image0.getWidth(null) + ", " +
                            image1.getHeight(null));
        
        intersection = ImageIntersection.intersectImages(image0, image1);

        String title = "Intersection of " + country0 + " and " + country1;
        State intersectionState = new State(title);
        ImagePane intersectionPane = null;
        try {
            name = country0;
            imagePane0 = new ImagePane(country0, imagePane0.getViewable(),
                                        new CachedImage(image0), true);
            name = country1;
            imagePane1 = new ImagePane(country1, imagePane1.getViewable(),
                                        new CachedImage(image1), true);
            name = "intersection";
            intersectionPane = new ImagePane(title, intersectionState,
                                            new CachedImage(intersection), true);
        } catch (Exception e) {
            System.out.println("Couldn't create imagePane for " + name);
            e.printStackTrace();
        }

        imagePanel.removeAll();
        imagePanel.add(imagePane0);
        imagePanel.add(imagePane1);
        imagePanel.add(intersectionPane);

        SwingUtilities.invokeLater(() -> { imagePanel.revalidate(); textPanel.revalidate(); });
    }
}

package demo;

import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.function.Consumer;

/**
 * Replays a {@link ClickRecorder} recording with {@link Robot} — real OS-level
 * mouse press/release at the recorded screen coordinates, preserving the inter-
 * event timing. Runs on its own thread (Robot delays must not block the EDT);
 * the physical mouse should be left alone during replay.
 */
public final class ClickReplayer {

    private ClickReplayer() {}

    /** Loads {@code file} and replays it on a background thread after a short
     *  countdown; {@code status} receives progress messages (EDT-safe caller's
     *  choice). */
    public static void replay(File file, Consumer<String> status) throws Exception {
        List<String> lines = Files.readAllLines(file.toPath());
        Robot robot = new Robot();
        robot.setAutoDelay(0);

        Thread t = new Thread(() -> {
            try {
                for (int i = 3; i >= 1; i--) {
                    say(status, "Replay starting in " + i + " … (don't touch the mouse)");
                    Thread.sleep(700);
                }
                long prev = 0;
                int n = 0;
                for (String line : lines) {
                    String[] p = line.split(",", 5);
                    if (p.length < 4) {
                        continue;
                    }
                    long time = Long.parseLong(p[0].trim());
                    int x = Integer.parseInt(p[1].trim());
                    int y = Integer.parseInt(p[2].trim());
                    int id = Integer.parseInt(p[3].trim());
                    String label = p.length >= 5 ? p[4].trim() : "";

                    long wait = time - prev;
                    prev = time;
                    if (wait > 0) {
                        Thread.sleep(Math.min(wait, 5000));
                    }

                    robot.mouseMove(x, y);
                    if (id == MouseEvent.MOUSE_PRESSED) {
                        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                    } else if (id == MouseEvent.MOUSE_RELEASED) {
                        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
                        n++;
                        say(status, "click " + n + " on '" + label + "'");
                    }
                }
                say(status, "Replay done (" + n + " clicks).");
            } catch (Exception ex) {
                ex.printStackTrace();
                say(status, "Replay failed: " + ex.getMessage());
            }
        }, "click-replay");
        t.setDaemon(true);
        t.start();
    }

    private static void say(Consumer<String> status, String msg) {
        if (status != null) {
            status.accept(msg);
        }
        System.err.println("[replay] " + msg);
    }
}

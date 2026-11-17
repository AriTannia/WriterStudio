package writerstudio;

import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;

public class GuiCheck {
    static MainFrame mf;
    static BinderPanel binder;

    static void onEdt(Runnable r) throws Exception { SwingUtilities.invokeAndWait(r); }

    static void shot(String name) throws Exception {
        Thread.sleep(600);
        onEdt(() -> {
            BufferedImage img = new BufferedImage(mf.getWidth(), mf.getHeight(), BufferedImage.TYPE_INT_RGB);
            mf.paint(img.createGraphics());
            try { javax.imageio.ImageIO.write(img, "png", new File("/home/claude/shots/" + name + ".png")); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    static void mode(String m) throws Exception {
        Method setMode = null;
        for (Method x : MainFrame.class.getDeclaredMethods()) if (x.getName().equals("setMode")) setMode = x;
        setMode.setAccessible(true);
        Class<?> modeCls = Class.forName("writerstudio.MainFrame$Mode");
        Object val = null;
        for (Object c : modeCls.getEnumConstants()) if (c.toString().equals(m)) val = c;
        final Method fm = setMode; final Object fv = val;
        onEdt(() -> { try { fm.invoke(mf, fv); } catch (Exception e) { throw new RuntimeException(e); } });
    }

    public static void main(String[] a) throws Exception {
        new File("/home/claude/shots").mkdirs();
        File dir = Files.createTempDirectory("ws-gui").toFile();
        Project p = Project.createNew(new File(dir, "novel"), "novel", true);
        File png = new File(dir, "map.png");
        BufferedImage bi = new BufferedImage(400, 250, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = bi.createGraphics();
        g.setPaint(new java.awt.GradientPaint(0, 0, java.awt.Color.CYAN, 400, 250, java.awt.Color.BLUE));
        g.fillRect(0, 0, 400, 250); g.dispose();
        javax.imageio.ImageIO.write(bi, "png", png);
        Item img = p.importFile(png);
        p.insert(img, p.research(), p.research().getChildCount());

        onEdt(() -> { mf = new MainFrame(p); mf.setVisible(true); });
        Field bf = MainFrame.class.getDeclaredField("binder");
        bf.setAccessible(true);
        binder = (BinderPanel) bf.get(mf);

        Item ch1 = p.draft().itemAt(0);
        Item sc1 = ch1.itemAt(0);

        onEdt(() -> binder.select(sc1));
        mode("EDITOR");   shot("1_editor");
        onEdt(() -> binder.select(ch1));
        mode("SCRIVENINGS"); shot("2_scrivenings");
        mode("CORKBOARD"); shot("3_corkboard");
        mode("OUTLINER"); shot("4_outliner");
        onEdt(() -> binder.select(p.draft()));
        mode("CORKBOARD"); shot("5_cork_draft");
        mode("OUTLINER"); shot("6_outliner_draft");
        mode("EDITOR");
        onEdt(() -> binder.select(img));
        shot("7_image");

        // binder operations
        onEdt(() -> binder.select(ch1.itemAt(1)));
        onEdt(binder::moveUp);
        System.out.println("after moveUp: " + ch1.itemAt(0).getTitle() + " | " + ch1.itemAt(1).getTitle());
        onEdt(binder::moveDown);
        System.out.println("after moveDown: " + ch1.itemAt(0).getTitle() + " | " + ch1.itemAt(1).getTitle());
        int n = p.draft().itemAt(0).getChildCount();
        System.out.println("children before trash: " + n);
        Item victim = ch1.itemAt(1);
        onEdt(() -> binder.select(victim));
        onEdt(binder::deleteSelected);
        System.out.println("trash count: " + p.trash().getChildCount() + ", chapter children: " + ch1.getChildCount() + ", victim zone=" + victim.zone());

        // corkboard card reorder through the model (what dragging does)
        Item c2 = p.draft().itemAt(1);
        System.out.println("order before: " + p.draft().itemAt(0).getTitle() + ", " + p.draft().itemAt(1).getTitle());
        onEdt(() -> { p.model().removeNodeFromParent(c2); p.model().insertNodeInto(c2, p.draft(), 0); });
        Thread.sleep(300);
        System.out.println("order after: " + p.draft().itemAt(0).getTitle() + ", " + p.draft().itemAt(1).getTitle());
        System.exit(0);
    }
}

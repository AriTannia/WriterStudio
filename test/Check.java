package writerstudio;

import javax.swing.JTextPane;
import javax.swing.text.AbstractDocument;
import javax.swing.text.Document;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.List;

public class Check {
    static int failures = 0;
    static void ok(boolean cond, String msg) {
        System.out.println((cond ? "PASS  " : "FAIL  ") + msg);
        if (!cond) failures++;
    }

    public static void main(String[] a) throws Exception {
        // ---------- 1. persistence round-trip ----------
        File dir = Files.createTempDirectory("ws-test").toFile();
        File pdir = new File(dir, "novel");
        Project p = Project.createNew(pdir, "novel", true);
        ok(p.draft().getChildCount() == 2, "sample draft has 2 chapters");
        Item ch1 = p.draft().itemAt(0);
        Item s1 = ch1.itemAt(0);
        s1.setContent("Xin chào thế giới — đêm mưa\nDòng hai");
        s1.setLabel("Scene"); s1.setStatus("Done"); s1.setSynopsis("Dòng 1\nDòng 2");
        p.markDirty();
        p.save();
        Project q = Project.load(pdir);
        Item s1b = q.draft().itemAt(0).itemAt(0);
        ok(s1b.getContent().equals(s1.getContent()), "content round-trips (Vietnamese + newline)");
        ok(s1b.getSynopsis().equals("Dòng 1\nDòng 2"), "multi-line synopsis round-trips");
        ok(s1b.getLabel().equals("Scene") && s1b.getStatus().equals("Done"), "label/status round-trip");
        ok(q.research().getChildCount() == 2, "research items round-trip");
        ok(q.trash().getChildCount() == 0, "trash exists and empty");

        // ---------- 2. trash / delete ----------
        Item s2b = q.draft().itemAt(0).itemAt(1);
        q.moveToTrash(s2b);
        ok(q.trash().getChildCount() == 1 && s2b.zone() == Item.Special.TRASH, "move to trash");
        q.save();
        ok(new File(new File(pdir, "data"), s2b.getId() + ".txt").exists(), "trashed item data kept");
        q.deleteForever(s2b);
        q.save();
        ok(!new File(new File(pdir, "data"), s2b.getId() + ".txt").exists(), "deleted item data removed from disk");

        // ---------- 3. import ----------
        File txt = new File(dir, "note.txt");
        Files.writeString(txt.toPath(), "ghi chú nghiên cứu");
        File bin = new File(dir, "pic.png");
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(40, 30, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", bin);
        Item t = q.importFile(txt);
        Item f = q.importFile(bin);
        ok(t.getKind() == Item.Kind.TEXT && t.getContent().contains("nghiên cứu"), "import .txt as text document");
        ok(f.getKind() == Item.Kind.FILE && q.resolve(f).exists(), "import image as file item");
        q.insert(t, q.research(), 0);
        q.insert(f, q.research(), 1);
        q.save();
        Project r = Project.load(pdir);
        Item fr = r.research().itemAt(1);
        ok(fr.getKind() == Item.Kind.FILE && r.resolve(fr).exists(), "file item survives reload");

        // ---------- 4. compile ----------
        File out = new File(dir, "out.md");
        r.compile(out, true, true);
        String md = Files.readString(out.toPath());
        ok(md.startsWith("# Chương 1"), "compile markdown heading");
        ok(md.contains("Xin chào thế giới"), "compile includes text");

        // ---------- 5. scrivenings editor ----------
        Project sp = Project.createNew(new File(dir, "scriv"), "scriv", false);
        Item d1 = new Item(Item.Kind.TEXT, "Một"); d1.setContent("alpha\nbeta");
        Item d2 = new Item(Item.Kind.TEXT, "Hai"); d2.setContent("");
        Item d3 = new Item(Item.Kind.TEXT, "Ba");  d3.setContent("gamma\n");
        sp.insert(d1, sp.draft(), 0); sp.insert(d2, sp.draft(), 1); sp.insert(d3, sp.draft(), 2);
        int[] flushed = {0};
        EditorPanel ed = new EditorPanel(sp, () -> flushed[0]++);
        ed.showSections(List.of(d1, d2, d3));
        Field pf = EditorPanel.class.getDeclaredField("pane");
        pf.setAccessible(true);
        JTextPane pane = (JTextPane) pf.get(ed);
        Document doc = pane.getDocument();
        String text = doc.getText(0, doc.getLength());
        System.out.println("---- editor text ----\n" + text.replace("\n", "⏎\n") + "\n---------------------");

        ed.flush();
        ok(d1.getContent().equals("alpha\nbeta") && d2.getContent().equals("") && d3.getContent().equals("gamma\n"),
                "flush without edits leaves content unchanged");

        int firstBody = text.indexOf("alpha");
        doc.insertString(firstBody, "X", null);
        ed.flush();
        ok(d1.getContent().equals("Xalpha\nbeta"), "typing at start of section 1 goes to doc 1");

        // typing inside separator is blocked
        int before = doc.getLength();
        doc.insertString(3, "ZZ", null);
        ok(doc.getLength() == before, "typing inside a separator line is blocked");
        doc.insertString(0, "ZZ", null);
        ok(doc.getLength() == before, "typing before the first separator is blocked");

        // deleting the line break directly before the next separator is blocked
        text = doc.getText(0, doc.getLength());
        int sep2 = text.indexOf("\u2500\u2500  Hai");
        doc.remove(sep2 - 1, 1);
        ok(doc.getLength() == before + 0, "deleting the terminator before a separator is blocked");
        // deleting across a separator is blocked
        doc.remove(firstBody, sep2 - firstBody + 3);
        ok(doc.getLength() == before, "deleting across a separator is blocked");
        // deleting body text only is fine
        doc.remove(firstBody, 5);   // "Xalph"
        ed.flush();
        ok(d1.getContent().equals("a\nbeta"), "deleting body text only works -> " + d1.getContent().replace("\n", "\\n"));

        // typing into the empty doc 2 body
        text = doc.getText(0, doc.getLength());
        int sep2end = text.indexOf("\n", text.indexOf("\u2500\u2500  Hai")) + 1;
        doc.insertString(sep2end, "hello", null);
        ed.flush();
        ok(d2.getContent().equals("hello"), "typing into empty section 2 -> '" + d2.getContent() + "'");

        // typing at very end goes to last doc
        doc.insertString(doc.getLength(), "END", null);
        ed.flush();
        ok(d3.getContent().equals("gamma\nEND"), "typing at the end goes to the last doc -> '" + d3.getContent().replace("\n", "\\n") + "'");
        ok(d1.getContent().equals("a\nbeta") && d2.getContent().equals("hello"), "other sections untouched");

        // Enter inside body
        text = doc.getText(0, doc.getLength());
        int p1 = text.indexOf("beta") + 4;
        doc.insertString(p1, "\n", null);
        ed.flush();
        ok(d1.getContent().equals("a\nbeta\n"), "newline at end of body kept in doc 1 -> '" + d1.getContent().replace("\n", "\\n") + "'");

        // replace selection that spans only body text
        text = doc.getText(0, doc.getLength());
        int g = text.indexOf("gamma");
        ((AbstractDocument) doc).replace(g, 5, "delta", null);
        ed.flush();
        ok(d3.getContent().startsWith("delta"), "replace within a body works");

        // single document mode (no separators)
        ed.showSections(List.of(d1));
        Document doc1 = pane.getDocument();
        ok(doc1.getText(0, doc1.getLength()).equals(d1.getContent()), "single mode shows exactly the content");
        doc1.insertString(0, "Q", null);
        ed.flush();
        ok(d1.getContent().startsWith("Q"), "single mode edits saved");

        // ---------- 6. views construct & fill (headless) ----------
        OutlinerPanel op = new OutlinerPanel(sp, it -> { });
        op.show(sp.draft(), sp.draft().items());
        CorkboardPanel cp = new CorkboardPanel(sp, it -> { }, () -> { });
        cp.show(sp.draft(), sp.draft().items(), true);
        InspectorPanel ip = new InspectorPanel(sp);
        ip.setItem(d1);
        BinderPanel bp = new BinderPanel(sp, () -> { }, () -> { });
        bp.select(d2);
        ok(bp.selectedItems().size() == 1 && bp.selectedItems().get(0) == d2, "binder selection works");
        ok(true, "outliner / corkboard / inspector / binder construct without errors");

        System.out.println(failures == 0 ? "\nALL OK" : "\nFAILURES: " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }
}

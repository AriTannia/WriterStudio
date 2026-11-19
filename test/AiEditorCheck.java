package writerstudio;

import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.text.Document;
import java.awt.event.ActionEvent;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Tests the /assist: and /chat: commands inside a real EditorPanel, against a fake AI server. */
public class AiEditorCheck {
    static int failures = 0;
    static FakeAi fake;
    static Project project;
    static JTextPane pane;
    static EditorPanel ed;
    static AiCommandController ai;
    static final List<Object[]> created = new ArrayList<>();

    static void ok(boolean cond, String msg) {
        System.out.println((cond ? "PASS  " : "FAIL  ") + msg);
        if (!cond) failures++;
    }

    static void edt(Runnable r) throws Exception { SwingUtilities.invokeAndWait(r); }

    static boolean waitFor(BooleanSupplier c, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (c.getAsBoolean()) return true;
            Thread.sleep(30);
        }
        return c.getAsBoolean();
    }

    static String text() {
        final String[] t = new String[1];
        try {
            edt(() -> {
                try {
                    Document d = pane.getDocument();
                    t[0] = d.getText(0, d.getLength());
                } catch (Exception e) { throw new RuntimeException(e); }
            });
        } catch (Exception e) { throw new RuntimeException(e); }
        return t[0];
    }

    static boolean hasResult() { return ai.ghostRange() != null; }

    static void enterAt(int caret) throws Exception {
        edt(() -> {
            pane.setCaretPosition(caret < 0 ? pane.getDocument().getLength() : caret);
            pane.getActionMap().get("ws-ai-enter").actionPerformed(new ActionEvent(pane, ActionEvent.ACTION_PERFORMED, ""));
        });
    }

    static void press(String actionName) throws Exception {
        edt(() -> pane.getActionMap().get(actionName).actionPerformed(new ActionEvent(pane, ActionEvent.ACTION_PERFORMED, "")));
    }

    /** Loads one document with the given text into a fresh editor. */
    static Item single(String content) throws Exception {
        Item it = new Item(Item.Kind.TEXT, "Cảnh");
        it.setContent(content);
        it.setSynopsis("Tóm tắt của cảnh");
        edt(() -> ed.showSections(List.of(it)));
        return it;
    }

    static void reply(String... outputs) {
        AtomicInteger n = new AtomicInteger();
        fake.requests.clear();
        fake.handler = r -> {
            if (r.path.endsWith("/models")) return FakeAi.Resp.json(200, FakeAi.models("some-model"));
            int i = Math.min(n.getAndIncrement(), outputs.length - 1);
            return FakeAi.Resp.json(200, FakeAi.completion(outputs[i]));
        };
    }

    static String lastChatBody() {
        for (int i = fake.requests.size() - 1; i >= 0; i--) if (fake.requests.get(i).path.endsWith("chat/completions")) return fake.requests.get(i).body;
        return "";
    }

    public static void main(String[] a) throws Exception {
        fake = new FakeAi();
        File dir = Files.createTempDirectory("ai-ed").toFile();
        project = Project.createNew(new File(dir, "p"), "p", false);
        AiSettings s = new AiSettings();
        s.apiKey = "AIzaTESTKEY-0123456789";
        s.provider = AiSettings.Provider.CUSTOM;
        s.baseUrl = fake.baseUrl();
        s.modelOverride = "some-model";
        AiService svc = new AiService(s);
        edt(() -> {
            ed = new EditorPanel(project, () -> { });
            ed.setAi(svc, (parent, title, content) -> {
                created.add(new Object[] {parent, title, content});
                return null;
            });
        });
        ai = ed.aiController();
        java.lang.reflect.Field pf = EditorPanel.class.getDeclaredField("pane");
        pf.setAccessible(true);
        pane = (JTextPane) pf.get(ed);

        try {
            run();
        } finally {
            fake.stop();
        }
        System.out.println(failures == 0 ? "\nALL OK" : "\nFAILURES: " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    static void run() throws Exception {
        // ---- normal Enter is untouched ----
        Item d = single("Một dòng");
        edt(() -> pane.setCaretPosition(pane.getDocument().getLength()));
        press("ws-ai-enter");
        ok(text().equals("Một dòng\n"), "Enter on a normal line still inserts a line break");
        edt(() -> { pane.setCaretPosition(0); });
        press("ws-ai-tab");
        ok(text().startsWith("\t"), "Tab still inserts a tab when no AI result is pending");

        // ---- /assist inline: Yes ----
        reply("Cô khẽ thở dài.");
        String start = "Dòng một.\nMai ngồi ở toa tàu. /assist: viết tiếp 1 câu";
        d = single(start);
        String before = text();
        enterAt(-1);
        ok(waitFor(AiEditorCheck::hasResult, 4000), "assist: result arrives");
        ok(!pane.isEditable(), "editor is read-only while the result is shown");
        javax.swing.text.Highlighter.Highlight[] hl = pane.getHighlighter().getHighlights();
        int[] g = ai.ghostRange();
        boolean cmdOk = false, ghostOk = false;
        for (javax.swing.text.Highlighter.Highlight x : hl) {
            if (x.getEndOffset() <= g[0]) cmdOk = true;                      // command highlight stops where the result starts
            if (x.getStartOffset() == g[0] + 1 && x.getEndOffset() == g[1]) ghostOk = true;   // result highlight covers exactly the result
        }
        ok(hl.length == 2 && cmdOk && ghostOk, "highlights: command and result are coloured separately");
        String shown = text();
        ok(shown.contains("/assist: viết tiếp 1 câu\nCô khẽ thở dài."), "assist: command stays and result appears after it");
        ed.flush();
        ok(d.getContent().equals(start), "flush while result is shown does NOT save the temporary text");
        String body = lastChatBody();
        ok(body.contains("Mai ngồi ở toa tàu.") && body.contains("viết tiếp 1 câu") && body.contains("Tóm tắt của cảnh"), "request carries the prefix text, instruction and synopsis");
        ok(body.contains("chính tả"), "request carries the style prompt");
        ok(body.contains("\"temperature\":0.72") && body.contains("\"max_tokens\":2048"), "request carries temperature and max_tokens from the style");
        press("ws-ai-tab");   // Tab = Yes
        ok(text().equals("Dòng một.\nMai ngồi ở toa tàu. Cô khẽ thở dài."), "Yes: command removed, result joined inline -> " + text().replace("\n", "⏎"));
        ok(pane.isEditable() && !ai.isActive(), "editor editable again, controller idle");
        ed.flush();
        ok(d.getContent().equals("Dòng một.\nMai ngồi ở toa tàu. Cô khẽ thở dài."), "accepted text is saved into the document");
        edt(ed::undo);
        ok(text().equals(before), "ONE Ctrl+Z restores the exact text before accepting (command back) -> " + text().replace("\n", "⏎"));
        edt(ed::redo);
        ok(text().equals("Dòng một.\nMai ngồi ở toa tàu. Cô khẽ thở dài."), "redo re-applies the result");

        // ---- /assist: No ----
        reply("Một câu thừa.");
        d = single("Đoạn một.\n/assist: viết thêm\nĐoạn cuối.");
        before = text();
        enterAt(text().indexOf("viết thêm") + "viết thêm".length());
        ok(waitFor(AiEditorCheck::hasResult, 4000), "assist (own line): result arrives");
        ok(text().contains("Một câu thừa."), "ghost visible");
        press("ws-ai-esc");   // Esc = No
        ok(text().equals("Đoạn một.\nĐoạn cuối."), "No: command line and result both removed -> " + text().replace("\n", "⏎"));
        edt(ed::undo);
        ok(text().equals(before), "Undo after No brings the command line back");

        // ---- /assist own line: Yes replaces the line ----
        reply("Câu mới hoàn toàn.");
        d = single("Đoạn một.\n/assist: tiếp\nĐoạn cuối.");
        enterAt(text().indexOf("tiếp") + 4);
        ok(waitFor(AiEditorCheck::hasResult, 4000), "own-line assist result");
        press("ws-ai-tab");
        ok(text().equals("Đoạn một.\nCâu mới hoàn toàn.\nĐoạn cuối."), "Yes on own line: the line is replaced by the result -> " + text().replace("\n", "⏎"));

        // ---- command on the very last line ----
        reply("Thừa.");
        d = single("Đoạn một.\n/assist: x");
        before = text();
        enterAt(-1);
        ok(waitFor(AiEditorCheck::hasResult, 4000), "last-line assist: result");
        press("ws-ai-esc");
        ok(text().equals("Đoạn một."), "No on a last-line command leaves no empty line -> " + text().replace("\n", "⏎"));
        edt(ed::undo);
        ok(text().equals(before), "Undo restores the last-line command");
        created.clear();
        reply("Trả lời.");
        d = single("Đoạn một.\n/chat: hỏi gì đó");
        enterAt(-1);
        ok(waitFor(() -> created.size() == 1, 4000) && text().equals("Đoạn một."), "chat on the last line leaves no empty line -> " + text().replace("\n", "⏎"));

        // ---- retry ----
        reply("Bản A.", "Bản B.");
        d = single("Mở đầu. /assist:");
        enterAt(-1);
        ok(waitFor(AiEditorCheck::hasResult, 4000) && text().contains("Bản A."), "retry: first result A");
        press("ws-ai-retry");
        ok(waitFor(() -> hasResult() && text().contains("Bản B.") && !text().contains("Bản A."), 4000), "retry: result replaced by B");
        ok(fake.requests.stream().filter(r -> r.path.endsWith("chat/completions")).count() == 2, "retry sent exactly one more request");
        ok(lastChatBody().contains("Viết tiếp một cách tự nhiên"), "empty instruction means 'continue naturally'");
        press("ws-ai-tab");
        ok(text().equals("Mở đầu. Bản B."), "accepting the retried result -> " + text().replace("\n", "⏎"));

        // ---- spacing when no whitespace between prefix and result ----
        reply("Tiếp theo.");
        d = single("Hết câu./assist: x");
        enterAt(-1);
        ok(waitFor(AiEditorCheck::hasResult, 4000), "inline without space: result");
        press("ws-ai-tab");
        ok(text().equals("Hết câu. Tiếp theo."), "a space is added between prefix and result when needed -> " + text());

        // ---- cancel while waiting ----
        fake.requests.clear();
        fake.handler = r -> { FakeAi.Resp x = FakeAi.Resp.json(200, FakeAi.completion("muộn")); x.delayMs = 1500; return x; };
        d = single("Văn bản. /assist: chậm");
        before = text();
        enterAt(-1);
        ok(waitFor(() -> ai.isActive(), 1000) && ai.ghostRange() == null, "waiting state entered");
        press("ws-ai-esc");
        ok(!ai.isActive() && text().equals(before), "Esc while waiting cancels and leaves the text alone");
        Thread.sleep(2000);
        ok(text().equals(before) && !ai.isActive(), "a late answer after cancel is ignored");

        // ---- error keeps the command ----
        fake.handler = r -> FakeAi.Resp.json(401, "{\"error\":\"bad\"}");
        d = single("Văn bản. /assist: lỗi");
        before = text();
        enterAt(-1);
        ok(waitFor(() -> !ai.isActive(), 4000), "error: controller returns to idle");
        ok(text().equals(before) && pane.isEditable(), "error: command text kept, editor editable");

        // ---- editing the command while waiting discards the result ----
        fake.handler = r -> { FakeAi.Resp x = FakeAi.Resp.json(200, FakeAi.completion("không dùng")); x.delayMs = 700; return x; };
        d = single("Văn bản. /assist: sửa lệnh");
        enterAt(-1);
        ok(waitFor(() -> ai.isActive(), 1000), "waiting (edit test)");
        edt(() -> { try { pane.getDocument().insertString(pane.getDocument().getLength(), "!", null); } catch (Exception e) { throw new RuntimeException(e); } });
        ok(waitFor(() -> !ai.isActive(), 4000) && !text().contains("không dùng"), "command changed while waiting -> result discarded, nothing inserted");

        // ---- second command while one is running ----
        fake.requests.clear();
        fake.handler = r -> { FakeAi.Resp x = FakeAi.Resp.json(200, FakeAi.completion("A")); x.delayMs = 800; return x; };
        d = single("a /assist: một\nb /assist: hai");
        enterAt(text().indexOf("một") + 3);
        ok(waitFor(() -> ai.isActive(), 1000), "first command running");
        enterAt(-1);
        Thread.sleep(200);
        ok(fake.requests.stream().filter(r -> r.path.endsWith("chat/completions")).count() == 1, "a second command is not started while one runs");
        press("ws-ai-esc");

        // ---- caret not at the end of the command line: normal Enter ----
        reply("x");
        d = single("Xin /assist: tiếp chào");
        enterAt(text().indexOf("/assist"));
        Thread.sleep(200);
        ok(!ai.isActive() && text().equals("Xin \n/assist: tiếp chào"), "Enter before the command splits the line normally");

        // ---- /chat ----
        created.clear();
        reply("- Vịnh Mây\n- Cảng Gió\n- Làng Sương");
        d = single("Đoạn trước.\n/chat: gợi ý 3 tên cho thị trấn biển\nĐoạn sau.");
        before = text();
        enterAt(text().indexOf("biển") + 4);
        ok(waitFor(() -> created.size() == 1, 4000), "chat: child note requested");
        ok(created.get(0)[0] == d && ((String) created.get(0)[1]).startsWith("AI: gợi ý 3 tên"), "chat: parent is the current card, title built from the question");
        ok(((String) created.get(0)[2]).contains("Hỏi: gợi ý 3 tên cho thị trấn biển") && ((String) created.get(0)[2]).contains("Vịnh Mây"), "chat: note holds the question and the answer");
        ok(text().equals("Đoạn trước.\nĐoạn sau."), "chat: command line removed from the manuscript -> " + text().replace("\n", "⏎"));
        ok(!ai.isActive() && pane.isEditable(), "chat: idle afterwards");
        edt(ed::undo);
        ok(text().equals(before), "chat: Undo brings the command line back");
        ok(lastChatBody().contains("Đoạn trước.") && !lastChatBody().contains("Đoạn sau."), "chat request carries the text before, not after");

        // ---- style file is used ----
        AiStyle st = new AiStyle();
        st.systemPrompt = "PROMPT-RIÊNG-123";
        st.creativity = 100;
        st.length = AiStyle.Length.LONG;
        st.save(project.getDir());
        reply("ok");
        d = single("Chữ. /assist: dùng style");
        enterAt(-1);
        ok(waitFor(AiEditorCheck::hasResult, 4000), "style test result");
        String b = lastChatBody();
        ok(b.contains("PROMPT-RIÊNG-123") && b.contains("\"temperature\":1.0") && b.contains("250 chữ"), "saved project style (prompt, temperature, length) is what gets sent");
        press("ws-ai-esc");

        // ---- Scrivenings ----
        Item s1 = new Item(Item.Kind.TEXT, "Một"); s1.setContent("Nội dung MỘT.");
        Item s2 = new Item(Item.Kind.TEXT, "Hai"); s2.setContent("Đầu HAI.\nMai /assist: tiếp trong hai\nCuối HAI.");
        Item s3 = new Item(Item.Kind.TEXT, "Ba"); s3.setContent("Nội dung BA.");
        s2.setSynopsis("Synopsis của HAI");
        reply("Đoạn do AI viết.");
        edt(() -> ed.showSections(List.of(s1, s2, s3)));
        String full = text();
        enterAt(full.indexOf("tiếp trong hai") + "tiếp trong hai".length());
        ok(waitFor(AiEditorCheck::hasResult, 4000), "scrivenings: result arrives");
        b = lastChatBody();
        ok(b.contains("Đầu HAI.") && !b.contains("Nội dung MỘT") && !b.contains("Nội dung BA") && b.contains("Cuối HAI.") && b.contains("Synopsis của HAI"),
                "scrivenings: only the section's own text (before/after) and its synopsis are sent");
        ed.flush();
        ok(s1.getContent().equals("Nội dung MỘT.") && s3.getContent().equals("Nội dung BA.") && s2.getContent().equals("Đầu HAI.\nMai /assist: tiếp trong hai\nCuối HAI."),
                "scrivenings: flush with a result showing saves no temporary text in any section");
        press("ws-ai-tab");
        ed.flush();
        ok(s2.getContent().equals("Đầu HAI.\nMai Đoạn do AI viết.\nCuối HAI.") && s1.getContent().equals("Nội dung MỘT.") && s3.getContent().equals("Nội dung BA."),
                "scrivenings: accepted text lands in section 2 only -> " + s2.getContent().replace("\n", "⏎"));
        ok(text().contains("Hai") && text().indexOf("Ba") > text().indexOf("Cuối HAI."), "scrivenings: separators intact");
        // reject in scrivenings on an own-line command that is the entire body of a section (before a separator)
        Item t1 = new Item(Item.Kind.TEXT, "Mot"); t1.setContent("/assist: chỉ có lệnh");
        Item t2 = new Item(Item.Kind.TEXT, "Hai"); t2.setContent("Nội dung hai.");
        reply("Kết quả.");
        edt(() -> ed.showSections(List.of(t1, t2)));
        enterAt(text().indexOf("chỉ có lệnh") + "chỉ có lệnh".length());
        ok(waitFor(AiEditorCheck::hasResult, 4000), "scrivenings (command is whole body): result");
        press("ws-ai-esc");
        ed.flush();
        ok(t1.getContent().equals("") && t2.getContent().equals("Nội dung hai."), "No on a body that is only the command keeps the separator structure -> '" + t1.getContent() + "'");
        ok(text().contains("Mot") && text().contains("Hai"), "separators still present after reject");

        // ---- switching document resets everything ----
        fake.handler = r -> { FakeAi.Resp x = FakeAi.Resp.json(200, FakeAi.completion("không bao giờ hiện")); x.delayMs = 600; return x; };
        Item u1 = single("A /assist: x");
        enterAt(-1);
        ok(waitFor(() -> ai.isActive(), 1000), "pending before switching");
        Item u2 = new Item(Item.Kind.TEXT, "Khác"); u2.setContent("Tài liệu khác");
        edt(() -> ed.showSections(List.of(u2)));
        Thread.sleep(1200);
        ok(!ai.isActive() && text().equals("Tài liệu khác") && pane.isEditable(), "switching documents cancels the pending command; late answer is ignored");
        ok(u1.getContent().equals("A /assist: x"), "command line text kept in the first document");
    }
}

package writerstudio;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.ActionMap;
import javax.swing.BorderFactory;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.event.UndoableEditListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Element;
import javax.swing.text.Highlighter;
import javax.swing.text.Position;
import javax.swing.text.StyledDocument;
import javax.swing.undo.CompoundEdit;
import javax.swing.undo.UndoManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Typed AI commands in the editor:
 * <ul>
 *   <li><b>/assist: instruction</b> + Enter: the AI writes text after the command, shown in green;
 *       Yes (Tab) keeps it and removes the command, No (Esc) removes both, Retry (Ctrl+R) asks again.</li>
 *   <li><b>/chat: question</b> + Enter: the answer becomes a new child note of the current card.</li>
 * </ul>
 * While a result is shown the editor is read-only, so the temporary text can be taken out without
 * disturbing the undo history; it is never saved into the document.
 */
final class AiCommandController {
    interface ChildCreator {
        Item create(Item parent, String title, String content);
    }

    private interface Edit {
        void run() throws BadLocationException;
    }

    private enum State { IDLE, WAITING, RESULT }

    private enum Kind { ASSIST, CHAT }

    private static final Pattern CMD = Pattern.compile("(?i)(?<![/:])/(assist|chat)\\s*:");
    private static final Highlighter.HighlightPainter CMD_PAINTER =
            new DefaultHighlighter.DefaultHighlightPainter(new Color(0xFFF1A8));
    private static final Highlighter.HighlightPainter GHOST_PAINTER =
            new DefaultHighlighter.DefaultHighlightPainter(new Color(0xCFEFC8));

    private final EditorPanel editor;
    private final JTextPane pane;
    private final JPanel bar = new JPanel(new BorderLayout());
    private final JLabel message = new JLabel(" ");
    private final JButton yesBtn = new JButton("Yes (Tab)");
    private final JButton noBtn = new JButton("No (Esc)");
    private final JButton retryBtn = new JButton("Thử lại (Ctrl+R)");
    private final JButton cancelBtn = new JButton("Hủy (Esc)");
    private final Timer hideTimer;

    private AiService service;
    private ChildCreator childCreator;

    private State state = State.IDLE;
    private Kind kind;
    private String instruction = "";
    private String cmdText = "";
    private Position cmdStart;
    private int cmdLen;
    private Item item;
    private List<AiClient.Msg> msgs;
    private double temperature;
    private int maxTokens;

    private boolean applying;
    private Position ghostFrom, ghostTo;
    private String resultText = "";
    private Object hlCmd, hlGhost;
    private AiClient.Call call;
    private SwingWorker<AiClient.Reply, Void> worker;
    private long token;

    AiCommandController(EditorPanel editor, JTextPane pane) {
        this.editor = editor;
        this.pane = pane;

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 3));
        buttons.setOpaque(false);
        for (JButton b : new JButton[] {yesBtn, noBtn, retryBtn, cancelBtn}) {
            b.setFocusable(false);
            buttons.add(b);
        }
        yesBtn.addActionListener(e -> accept());
        noBtn.addActionListener(e -> reject());
        retryBtn.addActionListener(e -> retry());
        cancelBtn.addActionListener(e -> escape());
        message.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 0));
        bar.setBackground(new Color(0xFFF8E1));
        bar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0xE6D9A8)));
        bar.add(message, BorderLayout.CENTER);
        bar.add(buttons, BorderLayout.EAST);
        bar.setVisible(false);
        hideTimer = new Timer(6000, e -> bar.setVisible(false));
        hideTimer.setRepeats(false);
        install();
    }

    void configure(AiService service, ChildCreator creator) {
        this.service = service;
        this.childCreator = creator;
    }

    JComponent bar() { return bar; }

    boolean isActive() { return state != State.IDLE; }

    boolean isApplying() { return applying; }

    /** Range [from, to) of the temporary result text, or null. */
    int[] ghostRange() {
        if (state != State.RESULT || ghostFrom == null || ghostTo == null) return null;
        int a = ghostFrom.getOffset(), b = ghostTo.getOffset();
        return b > a ? new int[] {a, b} : null;
    }

    // ---- key bindings -----------------------------------------------------------------------------------

    private void install() {
        InputMap im = pane.getInputMap();
        ActionMap am = pane.getActionMap();
        final Action origEnter = am.get(DefaultEditorKit.insertBreakAction);
        final Action origTab = am.get(DefaultEditorKit.insertTabAction);

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "ws-ai-enter");
        am.put("ws-ai-enter", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (handleEnter()) return;
                if (origEnter != null) origEnter.actionPerformed(e);
                else pane.replaceSelection("\n");
            }
        });
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "ws-ai-tab");
        am.put("ws-ai-tab", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (state == State.RESULT) AiCommandController.this.accept();
                else if (origTab != null) origTab.actionPerformed(e);
            }
        });
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "ws-ai-esc");
        am.put("ws-ai-esc", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { escape(); }
            @Override public boolean isEnabled() { return state != State.IDLE; }
        });
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_R, Util.menuMask()), "ws-ai-retry");
        am.put("ws-ai-retry", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { retry(); }
            @Override public boolean isEnabled() { return state == State.RESULT; }
        });
    }

    // ---- starting a command --------------------------------------------------------------------------------

    /** Called when Enter is pressed in the editor. @return true if the key press was used as a command. */
    boolean handleEnter() {
        if (service == null || pane.getSelectionStart() != pane.getSelectionEnd()) return false;
        try {
            StyledDocument doc = pane.getStyledDocument();
            int caret = pane.getCaretPosition();
            Element p = doc.getParagraphElement(caret);
            int ls = p.getStartOffset();
            int le = Math.min(p.getEndOffset() - 1, doc.getLength());
            if (caret > le) return false;
            String line = doc.getText(ls, le - ls);
            Matcher m = CMD.matcher(line);
            if (!m.find()) return false;
            int cs = ls + m.start();
            if (caret < cs || !doc.getText(caret, le - caret).isBlank()) return false;

            if (state != State.IDLE) {
                Toolkit.getDefaultToolkit().beep();
                say("Đang xử lý một lệnh khác. Hãy chờ hoặc nhấn Esc.", false, false);
                return true;
            }
            Item section = editor.sectionItemAt(cs);
            if (section == null) return false;
            if (!service.ensureConfigured(pane)) return true;

            int[] body = editor.sectionBody(cs);
            String before = tailAtParagraph(doc.getText(body[0], cs - body[0]), AiPrompts.MAX_BEFORE_CHARS);
            String after = headAtParagraph(doc.getText(le, Math.max(0, body[1] - le)), AiPrompts.MAX_AFTER_CHARS);

            kind = "assist".equalsIgnoreCase(m.group(1)) ? Kind.ASSIST : Kind.CHAT;
            instruction = line.substring(m.end()).trim();
            cmdText = doc.getText(cs, le - cs);
            cmdStart = doc.createPosition(cs);
            cmdLen = le - cs;
            item = section;

            AiStyle style = AiStyle.load(editor.project().getDir());
            if (kind == Kind.ASSIST) {
                msgs = AiPrompts.assist(style, instruction, before, after, section.getSynopsis(), section.getTitle());
                temperature = style.temperature();
                maxTokens = style.maxTokens();
            } else {
                msgs = AiPrompts.chat(instruction, before, section.getSynopsis(), section.getTitle());
                temperature = 0.7;
                maxTokens = 2048;
            }
            hlCmd = pane.getHighlighter().addHighlight(cs, cs + cmdLen, CMD_PAINTER);
            launch();
            return true;
        } catch (BadLocationException e) {
            return false;
        }
    }

    private void launch() {
        state = State.WAITING;
        final long mine = ++token;
        call = new AiClient.Call();
        final AiClient.Call myCall = call;
        say("\u23f3 AI đang " + (kind == Kind.ASSIST ? "viết" : "trả lời") + "\u2026  (Esc để hủy)", false, true);
        worker = new SwingWorker<>() {
            @Override protected AiClient.Reply doInBackground() throws Exception {
                return service.client().chat(msgs, temperature, maxTokens, myCall);
            }

            @Override protected void done() {
                if (mine != token || isCancelled()) return;
                try {
                    onResult(get().text);
                } catch (ExecutionException e) {
                    Throwable c = e.getCause();
                    onError(c instanceof AiException ? (AiException) c
                            : new AiException(AiException.Kind.OTHER, 0, String.valueOf(c)));
                } catch (InterruptedException | CancellationException e) {
                    // cancelled meanwhile
                }
            }
        };
        worker.execute();
    }

    // ---- results ------------------------------------------------------------------------------------------------

    private void onResult(String raw) {
        if (state != State.WAITING) return;
        String text = normalize(raw);
        try {
            StyledDocument doc = pane.getStyledDocument();
            int cs = cmdStart.getOffset();
            if (cs + cmdLen > doc.getLength() || !doc.getText(cs, cmdLen).equals(cmdText)
                    || doc.getParagraphElement(cs).getEndOffset() - 1 != cs + cmdLen) {
                reset();
                say("Dòng lệnh đã bị sửa nên mình bỏ kết quả. Gõ lại lệnh và nhấn Enter.", false, false);
                return;
            }
            if (kind == Kind.CHAT) {
                finishChat(text);
            } else {
                showGhost(doc, cs + cmdLen, text);
            }
        } catch (BadLocationException e) {
            reset();
            say("Không thể hiển thị kết quả: " + e.getMessage(), false, false);
        }
    }

    private void onError(AiException e) {
        if (state != State.WAITING) return;
        if (e.kind == AiException.Kind.CANCELLED) {
            reset();
            return;
        }
        reset();
        say(e.friendly(), false, false);
        hideTimer.setInitialDelay(10_000);
        hideTimer.restart();
    }

    private void showGhost(StyledDocument doc, int cmdEnd, String text) throws BadLocationException {
        resultText = text;
        silently(() -> doc.insertString(cmdEnd, "\n" + text, editor.attrsFor(item)));
        ghostFrom = doc.createPosition(cmdEnd);
        ghostTo = doc.createPosition(cmdEnd + 1 + text.length());
        // inserting at the end of the command's highlight stretches it over the new text: redraw it exactly
        Highlighter h = pane.getHighlighter();
        if (hlCmd != null) h.removeHighlight(hlCmd);
        hlCmd = h.addHighlight(cmdStart.getOffset(), cmdEnd, CMD_PAINTER);
        hlGhost = h.addHighlight(cmdEnd + 1, cmdEnd + 1 + text.length(), GHOST_PAINTER);
        state = State.RESULT;
        pane.setEditable(false);
        pane.setCaretPosition(Math.min(doc.getLength(), cmdEnd + 1 + text.length()));
        say("Giữ đoạn văn màu xanh này?", true, false);
        scrollToGhost();
        pane.requestFocusInWindow();
    }

    private void scrollToGhost() {
        try {
            Rectangle r = pane.modelToView2D(ghostTo.getOffset()).getBounds();
            r.height += 30;
            pane.scrollRectToVisible(r);
        } catch (BadLocationException | RuntimeException ignored) {
            // purely cosmetic
        }
    }

    private void finishChat(String text) throws BadLocationException {
        String title = "AI: " + shorten(instruction.isBlank() ? "gợi ý" : instruction, 40);
        String content = (instruction.isBlank() ? "" : "Hỏi: " + instruction + "\n\n") + text;
        removeCommandRecorded();
        if (childCreator != null) childCreator.create(item, title, content);
        reset();
        say("Đã tạo ghi chú \u201c" + title + "\u201d trong Binder.", false, false);
    }

    // ---- user decisions -----------------------------------------------------------------------------------------

    private void escape() {
        if (state == State.WAITING) {
            if (call != null) call.cancel();
            if (worker != null) worker.cancel(true);
            reset();
            say("Đã hủy.", false, false);
        } else if (state == State.RESULT) {
            reject();
        }
    }

    void accept() {
        if (state != State.RESULT) return;
        try {
            StyledDocument doc = pane.getStyledDocument();
            String out = resultText;
            removeGhostSilently();
            pane.setEditable(true);
            int cs = cmdStart.getOffset();
            int ls = doc.getParagraphElement(cs).getStartOffset();
            String before = doc.getText(ls, cs - ls);
            String insert = out;
            if (!before.isBlank() && !out.isEmpty()) {
                char last = before.charAt(before.length() - 1);
                char first = out.charAt(0);
                if (!Character.isWhitespace(last) && !Character.isWhitespace(first) && ".,;:!?)\u2026".indexOf(first) < 0) {
                    insert = " " + out;
                }
            }
            final String ins = insert;
            final int len = cmdLen;
            asOneUndo(() -> {
                ((AbstractDocument) doc).replace(cs, len, ins, editor.attrsFor(item));
                editor.restyleParagraphs();
            });
            reset();
            pane.setCaretPosition(Math.min(doc.getLength(), cs + ins.length()));
            say("Đã chèn. (Ctrl+Z để hoàn tác)", false, false);
        } catch (BadLocationException e) {
            reset();
            say("Không chèn được: " + e.getMessage(), false, false);
        }
    }

    void reject() {
        if (state != State.RESULT) return;
        try {
            removeGhostSilently();
            pane.setEditable(true);
            removeCommandRecorded();
            reset();
            say("Đã bỏ kết quả.", false, false);
        } catch (BadLocationException e) {
            reset();
        }
    }

    void retry() {
        if (state != State.RESULT) return;
        try {
            removeGhostSilently();
            pane.setEditable(true);
            launch();
        } catch (BadLocationException e) {
            reset();
        }
    }

    /** Drops any pending command without touching the command text (used when the editor loads other content). */
    void reset() {
        token++;
        if (call != null) call.cancel();
        call = null;
        worker = null;
        try {
            removeGhostSilently();
        } catch (BadLocationException | RuntimeException ignored) {
            // the document is being replaced anyway
        }
        Highlighter h = pane.getHighlighter();
        if (hlCmd != null) h.removeHighlight(hlCmd);
        if (hlGhost != null) h.removeHighlight(hlGhost);
        hlCmd = null;
        hlGhost = null;
        ghostFrom = null;
        ghostTo = null;
        state = State.IDLE;
        pane.setEditable(true);
        bar.setVisible(false);
    }

    // ---- document edits ---------------------------------------------------------------------------------------------

    /** Edits that are not recorded for undo and ignore the editor's filters (temporary text only). */
    private void silently(Edit edit) throws BadLocationException {
        AbstractDocument doc = (AbstractDocument) pane.getDocument();
        UndoManager um = editor.undoManager();
        applying = true;
        doc.removeUndoableEditListener(um);
        try {
            edit.run();
        } finally {
            doc.addUndoableEditListener(um);
            applying = false;
        }
    }

    /** Edits recorded as ONE undo step. */
    private void asOneUndo(Edit edit) throws BadLocationException {
        AbstractDocument doc = (AbstractDocument) pane.getDocument();
        UndoManager um = editor.undoManager();
        CompoundEdit ce = new CompoundEdit();
        UndoableEditListener collector = e -> ce.addEdit(e.getEdit());
        applying = true;
        doc.removeUndoableEditListener(um);
        doc.addUndoableEditListener(collector);
        try {
            edit.run();
        } finally {
            doc.removeUndoableEditListener(collector);
            doc.addUndoableEditListener(um);
            applying = false;
            ce.end();
            if (ce.isSignificant()) um.addEdit(ce);
        }
    }

    private void removeGhostSilently() throws BadLocationException {
        if (ghostFrom == null || ghostTo == null) return;
        int a = ghostFrom.getOffset(), b = ghostTo.getOffset();
        ghostFrom = null;
        ghostTo = null;
        if (hlGhost != null) {
            pane.getHighlighter().removeHighlight(hlGhost);
            hlGhost = null;
        }
        StyledDocument doc = pane.getStyledDocument();
        if (b > a && b <= doc.getLength()) silently(() -> doc.remove(a, b - a));
    }

    /** Removes the command (and its line if it was alone on it) as an undoable step. */
    private void removeCommandRecorded() throws BadLocationException {
        StyledDocument doc = pane.getStyledDocument();
        int cs = cmdStart.getOffset();
        int end = cs + cmdLen;
        int ls = doc.getParagraphElement(cs).getStartOffset();
        int from = cs, to = end;
        if (doc.getText(ls, cs - ls).isBlank()) {
            if (end < doc.getLength()) {
                Element next = doc.getParagraphElement(end + 1);
                boolean beforeSeparator = next.getStartOffset() == end + 1 && EditorPanel.isSeparator(next);
                if (!beforeSeparator) {
                    from = ls;
                    to = end + 1;       // also take the line break so no empty line is left behind
                }
            } else if (ls > 0 && !EditorPanel.isSeparator(doc.getParagraphElement(ls - 1))) {
                from = ls - 1;          // last line of the text: take the line break before it instead
                to = end;
            }
        }
        final int f = from, t = to;
        asOneUndo(() -> ((AbstractDocument) doc).remove(f, t - f));
    }

    // ---- status bar ----------------------------------------------------------------------------------------------------------

    private void say(String text, boolean decision, boolean cancellable) {
        message.setText(text);
        yesBtn.setVisible(decision);
        noBtn.setVisible(decision);
        retryBtn.setVisible(decision);
        cancelBtn.setVisible(cancellable);
        bar.setVisible(true);
        hideTimer.stop();
        if (!decision && !cancellable) {
            hideTimer.setInitialDelay(6000);
            hideTimer.restart();
        }
        bar.revalidate();
    }

    // ---- text helpers ---------------------------------------------------------------------------------------------------------

    static String normalize(String s) {
        String t = s == null ? "" : s.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            int end = t.lastIndexOf("```");
            if (nl > 0 && end > nl) t = t.substring(nl + 1, end).strip();
        }
        return t.replaceAll("\n{3,}", "\n\n");
    }

    static String shorten(String s, int max) {
        String t = s.replaceAll("\\s+", " ").trim();
        if (t.length() <= max) return t;
        int cut = t.lastIndexOf(' ', max);
        return t.substring(0, cut > max / 2 ? cut : max).trim() + "\u2026";
    }

    static String tailAtParagraph(String s, int max) {
        if (s.length() <= max) return s;
        int cut = s.length() - max;
        int nl = s.indexOf('\n', cut);
        return nl >= 0 && nl < s.length() - 1 ? s.substring(nl + 1) : s.substring(cut);
    }

    static String headAtParagraph(String s, int max) {
        if (s.length() <= max) return s;
        int nl = s.lastIndexOf('\n', max);
        return nl > 0 ? s.substring(0, nl) : s.substring(0, max);
    }
}

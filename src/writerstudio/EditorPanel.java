package writerstudio;

import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.JToolBar;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.DocumentFilter;
import javax.swing.text.Element;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import javax.swing.text.StyledEditorKit;
import javax.swing.text.AttributeSet;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.UndoManager;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The text editor. It shows either one document, or - in Scrivenings mode - several
 * documents flowed together into one continuous text. Each document is introduced by a
 * read-only separator line; edits are written back to the right document.
 */
public class EditorPanel extends JPanel {
    /** Paragraph attribute marking a read-only separator line in Scrivenings mode. */
    static final Object HDR = "writerstudio.separator";

    private final Project project;
    private final Runnable onFlushed;
    private final JTextPane pane = new JTextPane();
    private final CardLayout cards = new CardLayout();
    private final JPanel stack = new JPanel(cards);
    private final JLabel emptyLabel = new JLabel("", SwingConstants.CENTER);
    private final FileViewer viewer = new FileViewer();
    private final JToolBar formatBar = new JToolBar();
    private final JLabel saveNotice = new JLabel(" ");
    private final Timer saveNoticeTimer;
    private final Map<String, EditorStyle> cardStyles = new HashMap<>();
    private JComboBox<String> fontBox;
    private JComboBox<Integer> sizeBox;
    private JToggleButton boldToggle;
    private JToggleButton italicToggle;
    private JToggleButton underlineToggle;
    private boolean updatingFormatControls;
    private final Timer flushTimer;

    private final SimpleAttributeSet body = new SimpleAttributeSet();
    private final SimpleAttributeSet separatorChars = new SimpleAttributeSet();
    private final SimpleAttributeSet paraBody = new SimpleAttributeSet();
    private final SimpleAttributeSet paraBlank = new SimpleAttributeSet();
    private final SimpleAttributeSet paraSeparator = new SimpleAttributeSet();
    private final SimpleAttributeSet structureTitle = new SimpleAttributeSet();
    private final SimpleAttributeSet structureHeading = new SimpleAttributeSet();
    private final SimpleAttributeSet structureLabel = new SimpleAttributeSet();

    private List<Item> sections = new ArrayList<>();
    private UndoManager undo = new UndoManager();

    public EditorPanel(Project project, Runnable onFlushed) {
        super(new BorderLayout());
        this.project = project;
        this.onFlushed = onFlushed;

        StyleConstants.setFontFamily(body, "Serif");
        StyleConstants.setFontSize(body, 17);
        StyleConstants.setForeground(body, pane.getForeground());

        StyleConstants.setFontFamily(separatorChars, "SansSerif");
        StyleConstants.setFontSize(separatorChars, 12);
        StyleConstants.setBold(separatorChars, true);
        StyleConstants.setForeground(separatorChars, new Color(0x8A8A8A));

        StyleConstants.setLineSpacing(paraBody, 0.12f);
        StyleConstants.setFirstLineIndent(paraBody, 24f);
        StyleConstants.setSpaceBelow(paraBody, 0f);

        StyleConstants.setLineSpacing(paraBlank, 0f);
        StyleConstants.setFirstLineIndent(paraBlank, 0f);
        StyleConstants.setSpaceAbove(paraBlank, 7f);
        StyleConstants.setSpaceBelow(paraBlank, 7f);

        StyleConstants.setAlignment(paraSeparator, StyleConstants.ALIGN_CENTER);
        StyleConstants.setSpaceAbove(paraSeparator, 16f);
        StyleConstants.setSpaceBelow(paraSeparator, 6f);
        paraSeparator.addAttribute(HDR, Boolean.TRUE);

        StyleConstants.setFontFamily(structureTitle, "SansSerif");
        StyleConstants.setFontSize(structureTitle, 28);
        StyleConstants.setBold(structureTitle, true);
        StyleConstants.setSpaceAbove(structureTitle, 8f);
        StyleConstants.setSpaceBelow(structureTitle, 18f);

        StyleConstants.setFontFamily(structureHeading, "SansSerif");
        StyleConstants.setFontSize(structureHeading, 18);
        StyleConstants.setBold(structureHeading, true);
        StyleConstants.setForeground(structureHeading, new Color(0x596579));
        StyleConstants.setSpaceAbove(structureHeading, 18f);
        StyleConstants.setSpaceBelow(structureHeading, 8f);

        StyleConstants.setFontFamily(structureLabel, "SansSerif");
        StyleConstants.setFontSize(structureLabel, 17);
        StyleConstants.setBold(structureLabel, true);

        setBackground(new Color(0xEEF1F5));
        pane.setMargin(new Insets(24, 48, 24, 48));
        pane.setBackground(new Color(0xFFFDF7));
        int mask = Util.menuMask();
        pane.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, mask), "ws-undo");
        pane.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, mask), "ws-redo");
        pane.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, mask | KeyEvent.SHIFT_DOWN_MASK), "ws-redo");
        pane.getActionMap().put("ws-undo", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { undo(); }
        });
        pane.getActionMap().put("ws-redo", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { redo(); }
        });
        pane.addCaretListener(e -> updateFormatControls());
        buildFormatBar();

        flushTimer = new Timer(700, e -> flush());
        flushTimer.setRepeats(false);
        saveNoticeTimer = new Timer(2600, e -> saveNotice.setText(" "));
        saveNoticeTimer.setRepeats(false);

        JScrollPane editorScroll = new JScrollPane(pane);
        editorScroll.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xD6DBE3)),
                BorderFactory.createEmptyBorder(4, 4, 4, 4)));
        stack.add(editorScroll, "text");
        stack.add(viewer, "file");
        stack.add(emptyLabel, "empty");
        add(formatBar, BorderLayout.NORTH);
        add(stack, BorderLayout.CENTER);
        showEmpty("Select a document in the Binder.");
    }

    // ---- showing content ---------------------------------------------------------------------

    public void showEmpty(String message) {
        flush();
        sections = new ArrayList<>();
        emptyLabel.setText(message);
        setFormatBarEnabled(false);
        cards.show(stack, "empty");
    }

    public void showFile(Item item, File file) {
        flush();
        sections = new ArrayList<>();
        viewer.show(item, file);
        setFormatBarEnabled(false);
        cards.show(stack, "file");
    }

    /** Shows a Three-Act Structure card in Scrivenings mode without flattening its beats. */
    public void showThreeActStructure(Item item) {
        flush();
        sections = new ArrayList<>();
        setFormatBarEnabled(false);
        pane.setEditable(false);
        DefaultStyledDocument doc = new DefaultStyledDocument();
        try {
            appendStructureLine(doc, item.getTitle(), structureTitle);
            appendStructureLine(doc, "Summary", structureHeading);
            appendStructureLine(doc, item.getContent().isEmpty() ? "[empty]" : item.getContent(), body);
            appendStructureLine(doc, "", body);
            appendStructureGroup(doc, "ACT I - SETUP  0-25%",
                    new String[] {"1. Opening Image", "2. Theme Stated", "3. Set-Up",
                            "4. Catalyst", "5. Debate", "6. Break into Two"}, item);
            appendStructureGroup(doc, "ACT II - CONFRONTATION  25-75%",
                    new String[] {"7. B Story", "8. Fun and Games", "9. Midpoint",
                            "10. Bad Guys Close In", "11. All Is Lost",
                            "12. Dark Night of the Soul", "13. Break into Three"}, item);
            appendStructureGroup(doc, "ACT III - RESOLUTION  80-100%",
                    new String[] {"14. Finale", "15. Final Image"}, item);
        } catch (BadLocationException e) {
            throw new IllegalStateException(e);
        }
        pane.setDocument(doc);
        pane.setCaretPosition(0);
        cards.show(stack, "text");
    }

    private void appendStructureGroup(DefaultStyledDocument doc, String title,
                                      String[] fields, Item item) throws BadLocationException {
        appendStructureLine(doc, title, structureHeading);
        for (String field : fields) {
            String value = item.getFields().get(field);
            appendStructureLine(doc, field + ":", structureLabel);
            appendStructureLine(doc, value == null || value.isEmpty() ? "[empty]" : value, body);
        }
        appendStructureLine(doc, "", body);
    }

    private void appendStructureLine(DefaultStyledDocument doc, String text,
                                     SimpleAttributeSet style) throws BadLocationException {
        doc.insertString(doc.getLength(), text + "\n", style);
    }

    /**
     * Shows the given documents. One document is edited directly; several are flowed
     * together with a separator line before each (Scrivenings).
     */
    public void showSections(List<Item> items) {
        flush();
        if (items.isEmpty()) {
            showEmpty("Nothing to show here yet.");
            return;
        }
        setFormatBarEnabled(true);
        pane.setEditable(true);
        sections = new ArrayList<>(items);
        boolean headers = items.size() > 1;

        DefaultStyledDocument doc = new DefaultStyledDocument();
        List<Integer> separatorStarts = new ArrayList<>();
        int firstBody = 0;
        try {
            for (int i = 0; i < items.size(); i++) {
                Item it = items.get(i);
                if (headers) {
                    separatorStarts.add(doc.getLength());
                    doc.insertString(doc.getLength(), "\u2500\u2500  " + it.getTitle() + "  \u2500\u2500\n", separatorChars);
                    if (i == 0) firstBody = doc.getLength();
                }
                boolean last = i == items.size() - 1;
                doc.insertString(doc.getLength(), it.getContent() + (last ? "" : "\n"),
                        cardStyles.computeIfAbsent(it.getId(), k -> new EditorStyle()).attributes());
            }
            doc.setParagraphAttributes(0, doc.getLength(), paraBody, true);
            applyNovelParagraphSpacing(doc);
            for (int s : separatorStarts) doc.setParagraphAttributes(s, 1, paraSeparator, false);
        } catch (BadLocationException e) {
            throw new IllegalStateException(e);
        }

        pane.setDocument(doc);
        undo = new UndoManager();
        undo.setLimit(500);
        doc.addUndoableEditListener(undo);
        doc.addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { flushTimer.restart(); }
            @Override public void removeUpdate(DocumentEvent e) { flushTimer.restart(); }
            @Override public void changedUpdate(DocumentEvent e) { }
        });
        if (headers) doc.setDocumentFilter(new SectionFilter());
        pane.setCaretPosition(Math.min(firstBody, doc.getLength()));
        cards.show(stack, "text");
        pane.requestFocusInWindow();
        updateFormatControls();
    }

    private void applyNovelParagraphSpacing(StyledDocument doc) {
        Element root = doc.getDefaultRootElement();
        for (int i = 0; i < root.getElementCount(); i++) {
            Element paragraph = root.getElement(i);
            try {
                String text = doc.getText(paragraph.getStartOffset(),
                        Math.max(0, paragraph.getEndOffset() - paragraph.getStartOffset())).trim();
                if (text.isEmpty()) {
                    doc.setParagraphAttributes(paragraph.getStartOffset(), 1, paraBlank, true);
                }
            } catch (BadLocationException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private void buildFormatBar() {
        formatBar.setFloatable(false);
        formatBar.setBackground(new Color(0xE4E9F0));
        formatBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xC8D0DB)),
                BorderFactory.createEmptyBorder(5, 8, 5, 8)));
        formatBar.add(button("\u21B6", e -> undo(), "Undo"));
        formatBar.add(button("\u21B7", e -> redo(), "Redo"));
        formatBar.addSeparator();
        boldToggle = toggleButton("B", "Bold", StyleConstants.Bold);
        italicToggle = toggleButton("I", "Italic", StyleConstants.Italic);
        underlineToggle = toggleButton("U", "Underline", StyleConstants.Underline);
        formatBar.add(boldToggle);
        formatBar.add(italicToggle);
        formatBar.add(underlineToggle);
        formatBar.addSeparator();
        formatBar.add(actionButton("Left", new StyledEditorKit.AlignmentAction("Left", StyleConstants.ALIGN_LEFT),
                "Align left"));
        formatBar.add(actionButton("Center", new StyledEditorKit.AlignmentAction("Center", StyleConstants.ALIGN_CENTER),
                "Align center"));
        formatBar.add(actionButton("Right", new StyledEditorKit.AlignmentAction("Right", StyleConstants.ALIGN_RIGHT),
                "Align right"));
        formatBar.addSeparator();

        fontBox = new JComboBox<>(new String[] {
                "Serif", "SansSerif", "Monospaced",
                "JetBrains Sans", "JetBrains Sans NL", "JetBrains Mono", "JetBrains Mono NL"
        });
        fontBox.setToolTipText("Font");
        fontBox.addActionListener(e -> {
            if (updatingFormatControls) return;
            String family = (String) fontBox.getSelectedItem();
            if (family != null) {
                applyStyle(StyleConstants.FontFamily, family);
            }
        });
        formatBar.add(fontBox);

        sizeBox = new JComboBox<>(new Integer[] {12, 14, 16, 17, 18, 20, 24, 28, 32});
        sizeBox.setSelectedItem(17);
        sizeBox.setEditable(true);
        sizeBox.setToolTipText("Font size");
        sizeBox.addActionListener(e -> {
            if (updatingFormatControls) return;
            Object value = sizeBox.getEditor().getItem();
            try {
                int size = Integer.parseInt(String.valueOf(value).trim());
                if (size < 8 || size > 96) throw new NumberFormatException();
                applyStyle(StyleConstants.FontSize, size);
            } catch (NumberFormatException ex) {
                Toolkit.getDefaultToolkit().beep();
                updateFormatControls();
            }
        });
        formatBar.add(sizeBox);
        formatBar.addSeparator();
        formatBar.add(button("Cut", e -> pane.cut()));
        formatBar.add(button("Copy", e -> pane.copy()));
        formatBar.add(button("Paste", e -> pane.paste()));
        formatBar.add(Box.createHorizontalGlue());
        saveNotice.setForeground(new Color(0x197A45));
        saveNotice.setFont(saveNotice.getFont().deriveFont(java.awt.Font.BOLD, 12f));
        formatBar.add(saveNotice);
    }

    private JButton button(String text, java.awt.event.ActionListener listener) {
        return button(text, listener, null);
    }

    private JButton button(String text, java.awt.event.ActionListener listener, String tip) {
        JButton b = new JButton(text);
        b.setFocusable(false);
        b.setMargin(new Insets(2, 8, 2, 8));
        if (tip != null) b.setToolTipText(tip);
        b.addActionListener(listener);
        return b;
    }

    private JButton actionButton(String text, Action action, String tip) {
        JButton b = button(text, e -> {
            pane.requestFocusInWindow();
            action.actionPerformed(new ActionEvent(pane, ActionEvent.ACTION_PERFORMED, text));
        });
        b.setToolTipText(tip);
        return b;
    }

    private JToggleButton toggleButton(String text, String tip, Object attribute) {
        JToggleButton b = new JToggleButton(text);
        b.setFocusable(false);
        b.setToolTipText(tip);
        b.setMargin(new Insets(2, 8, 2, 8));
        b.addActionListener(e -> {
            if (!updatingFormatControls) applyStyle(attribute, b.isSelected());
        });
        return b;
    }

    private void applyStyle(Object attribute, Object value) {
        pane.requestFocusInWindow();
        StyledDocument doc = pane.getStyledDocument();
        int start = pane.getSelectionStart();
        int length = pane.getSelectionEnd() - start;
        if (length == 0) {
            start = 0;
            length = doc.getLength();
        }
        if (length > 0) {
            SimpleAttributeSet attrs = new SimpleAttributeSet();
            attrs.addAttribute(attribute, value);
            doc.setCharacterAttributes(start, length, attrs, false);
        }
        for (Item item : sections) {
            EditorStyle style = cardStyles.computeIfAbsent(item.getId(), k -> new EditorStyle());
            style.set(attribute, value);
        }
        updateFormatControls();
    }

    private void updateFormatControls() {
        if (updatingFormatControls || pane.getDocument().getLength() == 0) return;
        int position = Math.min(pane.getCaretPosition(), pane.getDocument().getLength() - 1);
        AttributeSet attrs = pane.getStyledDocument().getCharacterElement(position).getAttributes();
        updatingFormatControls = true;
        try {
            fontBox.setSelectedItem(StyleConstants.getFontFamily(attrs));
            sizeBox.setSelectedItem(StyleConstants.getFontSize(attrs));
            boldToggle.setSelected(StyleConstants.isBold(attrs));
            italicToggle.setSelected(StyleConstants.isItalic(attrs));
            underlineToggle.setSelected(StyleConstants.isUnderline(attrs));
        } finally {
            updatingFormatControls = false;
        }
    }

    private void setFormatBarEnabled(boolean enabled) {
        for (java.awt.Component c : formatBar.getComponents()) c.setEnabled(enabled);
    }

    public void showSaveNotice(String message) {
        saveNotice.setEnabled(true);
        saveNotice.setText(message);
        saveNoticeTimer.restart();
    }

    private static class EditorStyle {
        String family = "Serif";
        int size = 17;
        boolean bold;
        boolean italic;
        boolean underline;

        SimpleAttributeSet attributes() {
            SimpleAttributeSet attrs = new SimpleAttributeSet();
            StyleConstants.setFontFamily(attrs, family);
            StyleConstants.setFontSize(attrs, size);
            StyleConstants.setBold(attrs, bold);
            StyleConstants.setItalic(attrs, italic);
            StyleConstants.setUnderline(attrs, underline);
            return attrs;
        }

        void set(Object attribute, Object value) {
            if (StyleConstants.FontFamily.equals(attribute)) family = String.valueOf(value);
            else if (StyleConstants.FontSize.equals(attribute)) size = (Integer) value;
            else if (StyleConstants.Bold.equals(attribute)) bold = (Boolean) value;
            else if (StyleConstants.Italic.equals(attribute)) italic = (Boolean) value;
            else if (StyleConstants.Underline.equals(attribute)) underline = (Boolean) value;
        }
    }

    // ---- writing changes back to the documents ----------------------------------------------------

    /** Copies the editor text back into the items it came from. */
    public void flush() {
        flushTimer.stop();
        if (sections.isEmpty()) return;
        try {
            StyledDocument doc = pane.getStyledDocument();
            String text = doc.getText(0, doc.getLength());
            boolean changed = false;
            if (sections.size() == 1) {
                changed = apply(sections.get(0), text);
            } else {
                List<int[]> seps = separatorRanges(doc);
                if (seps.size() != sections.size()) return;     // should never happen
                for (int k = 0; k < sections.size(); k++) {
                    int start = seps.get(k)[1];
                    int end = k + 1 < seps.size() ? seps.get(k + 1)[0] - 1 : text.length();
                    if (end < start) end = start;
                    changed |= apply(sections.get(k), text.substring(start, end));
                }
            }
            if (changed) onFlushed.run();
        } catch (BadLocationException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean apply(Item item, String text) {
        if (text.equals(item.getContent())) return false;
        item.setContent(text);
        project.markDirty();
        return true;
    }

    private static List<int[]> separatorRanges(StyledDocument doc) {
        List<int[]> out = new ArrayList<>();
        Element root = doc.getDefaultRootElement();
        for (int i = 0; i < root.getElementCount(); i++) {
            Element p = root.getElement(i);
            if (isSeparator(p)) out.add(new int[] {p.getStartOffset(), p.getEndOffset()});
        }
        return out;
    }

    private static boolean isSeparator(Element paragraph) {
        return paragraph.getAttributes().containsAttribute(HDR, Boolean.TRUE);
    }

    public void undo() {
        try {
            if (undo.canUndo()) undo.undo();
        } catch (CannotUndoException ignored) {
            // nothing to undo
        }
    }

    public void redo() {
        try {
            if (undo.canRedo()) undo.redo();
        } catch (CannotRedoException ignored) {
            // nothing to redo
        }
    }

    // ---- protects the separator lines in Scrivenings mode ----------------------------------------------

    private class SectionFilter extends DocumentFilter {
        private boolean separatorAt(StyledDocument d, int offset) {
            return isSeparator(d.getParagraphElement(offset));
        }

        /** True if removing [off, off+len) would touch a separator or the line break just before one. */
        private boolean blocked(StyledDocument d, int off, int len) {
            int end = off + len;
            int i = off;
            while (i < end) {
                Element p = d.getParagraphElement(i);
                if (isSeparator(p)) return true;
                i = Math.max(p.getEndOffset(), i + 1);
            }
            if (len > 0 && end <= d.getLength()) {
                Element q = d.getParagraphElement(end);
                if (q.getStartOffset() == end && isSeparator(q)) return true;
            }
            return false;
        }

        @Override public void insertString(FilterBypass fb, int offset, String string, javax.swing.text.AttributeSet attr)
                throws BadLocationException {
            StyledDocument d = (StyledDocument) fb.getDocument();
            if (separatorAt(d, offset)) { Toolkit.getDefaultToolkit().beep(); return; }
            super.insertString(fb, offset, string, body);
        }

        @Override public void remove(FilterBypass fb, int offset, int length) throws BadLocationException {
            StyledDocument d = (StyledDocument) fb.getDocument();
            if (blocked(d, offset, length)) { Toolkit.getDefaultToolkit().beep(); return; }
            super.remove(fb, offset, length);
        }

        @Override public void replace(FilterBypass fb, int offset, int length, String text,
                javax.swing.text.AttributeSet attrs) throws BadLocationException {
            StyledDocument d = (StyledDocument) fb.getDocument();
            if (length > 0 ? blocked(d, offset, length) : separatorAt(d, offset)) {
                Toolkit.getDefaultToolkit().beep();
                return;
            }
            super.replace(fb, offset, length, text, body);
        }
    }

    // ---- viewer for imported research files -------------------------------------------------------------

    private static class FileViewer extends JPanel {
        private final JLabel info = new JLabel(" ");
        private final JButton openButton = new JButton("Open with default application");
        private final ImagePanel image = new ImagePanel();
        private final JLabel noPreview = new JLabel("No preview for this file type.", SwingConstants.CENTER);
        private final CardLayout cards = new CardLayout();
        private final JPanel center = new JPanel(cards);
        private File file;

        FileViewer() {
            super(new BorderLayout(8, 8));
            JPanel top = new JPanel(new BorderLayout(8, 0));
            top.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 10, 0, 10));
            top.add(info, BorderLayout.CENTER);
            top.add(openButton, BorderLayout.EAST);
            center.add(image, "image");
            center.add(noPreview, "none");
            add(top, BorderLayout.NORTH);
            add(center, BorderLayout.CENTER);
            openButton.addActionListener(e -> {
                try {
                    if (file != null && Desktop.isDesktopSupported()) Desktop.getDesktop().open(file);
                } catch (IOException | UnsupportedOperationException ex) {
                    javax.swing.JOptionPane.showMessageDialog(this, "Could not open the file: " + ex.getMessage());
                }
            });
        }

        void show(Item item, File f) {
            this.file = f;
            info.setText(item.getTitle() + "   (" + (f.length() / 1024) + " KB)");
            BufferedImage img = null;
            try {
                img = ImageIO.read(f);
            } catch (IOException ignored) {
                // not an image
            }
            image.setImage(img);
            cards.show(center, img != null ? "image" : "none");
        }
    }

    private static class ImagePanel extends JComponent {
        private BufferedImage img;

        void setImage(BufferedImage i) {
            img = i;
            repaint();
        }

        @Override public Dimension getPreferredSize() { return new Dimension(200, 200); }

        @Override protected void paintComponent(Graphics g) {
            if (img == null) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            double scale = Math.min(1.0, Math.min((getWidth() - 20.0) / img.getWidth(), (getHeight() - 20.0) / img.getHeight()));
            int w = (int) (img.getWidth() * scale), h = (int) (img.getHeight() * scale);
            g2.drawImage(img, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null);
            g2.dispose();
        }
    }
}

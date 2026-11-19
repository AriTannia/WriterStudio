package writerstudio;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import javax.swing.event.TreeModelEvent;
import javax.swing.event.TreeModelListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The application window: Binder on the left, the Editor / Scrivenings / Corkboard /
 * Outliner views in the middle and the Inspector on the right.
 */
public class MainFrame extends JFrame {
    private enum Mode { EDITOR, SCRIVENINGS, CORKBOARD, OUTLINER }

    private final Project project;
    private final AiService aiService = new AiService();
    private final BinderPanel binder;
    private final EditorPanel editor;
    private final ResearchEditorPanel researchEditor;
    private final CorkboardPanel cork;
    private final RelationshipPanel relationships;
    private final OutlinerPanel outliner;
    private final InspectorPanel inspector;
    private final CardLayout viewCards = new CardLayout();
    private final JPanel views = new JPanel(viewCards);
    private final JLabel status = new JLabel(" ");
    private final Map<Mode, JToggleButton> modeButtons = new EnumMap<>(Mode.class);
    private final Map<Mode, JMenuItem> modeMenuItems = new EnumMap<>(Mode.class);
    private final Timer refreshTimer;
    private final Timer autosaveTimer;
    private JSplitPane mainSplit;
    private JToggleButton binderToggle;
    private JToggleButton inspectorToggle;
    private JToggleButton focusToggle;
    private int binderWidth = 260;

    private Mode mode = Mode.EDITOR;
    private List<Item> selection = new ArrayList<>();

    public MainFrame(Project project) {
        super(project.getName() + " \u2014 Writer's Studio");
        this.project = project;

        editor = new EditorPanel(project, this::updateStatus);
        editor.setAi(aiService, this::createAiNote);
        researchEditor = new ResearchEditorPanel(project, this::openInEditor);
        cork = new CorkboardPanel(project, this::openInEditor, this::newCard);
        outliner = new OutlinerPanel(project, this::openInEditor);
        inspector = new InspectorPanel(project);
        binder = new BinderPanel(project, editor::flush, this::refreshAll);
        relationships = new RelationshipPanel(project, this::openInEditor);
        binder.setSelectionListener(this::onSelection);

        views.add(editor, "editor");
        views.add(researchEditor, "research-editor");
        views.add(cork, "cork");
        views.add(relationships, "relationships");
        views.add(outliner, "outliner");

        JPanel center = new JPanel(new BorderLayout());
        center.add(buildToolbar(), BorderLayout.NORTH);
        center.add(views, BorderLayout.CENTER);

        binder.setPreferredSize(new Dimension(binderWidth, 600));
        mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, binder, center);
        mainSplit.setDividerLocation(binderWidth);
        mainSplit.setResizeWeight(0);
        mainSplit.setContinuousLayout(true);

        status.setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 10));
        getContentPane().add(mainSplit, BorderLayout.CENTER);
        getContentPane().add(inspector, BorderLayout.EAST);
        getContentPane().add(status, BorderLayout.SOUTH);
        setJMenuBar(buildMenuBar());

        // Rebuild corkboard / outliner / scrivenings after structural changes made elsewhere.
        refreshTimer = new Timer(120, e -> {
            if (mode != Mode.EDITOR) refreshActiveView();
            updateStatus();
        });
        refreshTimer.setRepeats(false);
        project.model().addTreeModelListener(new TreeModelListener() {
            @Override public void treeNodesChanged(TreeModelEvent e) { schedule(); }
            @Override public void treeNodesInserted(TreeModelEvent e) { schedule(); }
            @Override public void treeNodesRemoved(TreeModelEvent e) { schedule(); }
            @Override public void treeStructureChanged(TreeModelEvent e) { schedule(); }
            private void schedule() {
                if (!project.muted) refreshTimer.restart();
            }
        });

        autosaveTimer = new Timer(30_000, e -> saveProject(false));
        autosaveTimer.start();

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { closeWindow(true); }
        });
        setSize(1280, 800);
        setLocationRelativeTo(null);
        refreshActiveView();
    }

    // ---- UI construction ----------------------------------------------------------------------

    private JToolBar buildToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        ButtonGroup group = new ButtonGroup();
        addModeButton(bar, group, Mode.EDITOR, "Editor", "Edit one document");
        addModeButton(bar, group, Mode.SCRIVENINGS, "Scrivenings", "Edit a folder / several documents as one text");
        addModeButton(bar, group, Mode.CORKBOARD, "Corkboard", "Index cards with synopses");
        addModeButton(bar, group, Mode.OUTLINER, "Outliner", "Outline with metadata");
        modeButtons.get(Mode.EDITOR).setSelected(true);
        bar.addSeparator();
        binderToggle = sidePanelButton(bar, "Binder", "Show or hide the Binder panel", true,
                () -> setBinderVisible(binderToggle.isSelected()));
        inspectorToggle = sidePanelButton(bar, "Inspector", "Show or hide the Inspector panel", true,
                () -> setInspectorVisible(inspectorToggle.isSelected()));
        focusToggle = new JToggleButton("Focus");
        focusToggle.setToolTipText("Show or hide both side panels");
        focusToggle.setFocusable(false);
        focusToggle.addActionListener(e -> setSidebarsVisible(!focusToggle.isSelected()));
        bar.add(focusToggle);
        return bar;
    }

    private static JToggleButton sidePanelButton(JToolBar bar, String text, String tip,
                                                  boolean selected, Runnable action) {
        JToggleButton button = new JToggleButton(text, selected);
        button.setToolTipText(tip);
        button.setFocusable(false);
        button.addActionListener(e -> action.run());
        bar.add(button);
        return button;
    }

    private void addModeButton(JToolBar bar, ButtonGroup group, Mode m, String text, String tip) {
        JToggleButton b = new JToggleButton(text);
        b.setToolTipText(tip);
        b.setFocusable(false);
        b.addActionListener(e -> setMode(m));
        group.add(b);
        bar.add(b);
        modeButtons.put(m, b);
    }

    private JMenuBar buildMenuBar() {
        int mask = Util.menuMask();
        JMenuBar bar = new JMenuBar();

        JMenu file = new JMenu("File");
        file.add(item("New Project\u2026", null, this::newProject));
        file.add(item("Open Project\u2026", KeyStroke.getKeyStroke(KeyEvent.VK_O, mask), this::openProject));
        file.addSeparator();
        file.add(item("Save", KeyStroke.getKeyStroke(KeyEvent.VK_S, mask), () -> saveProject(true)));
        file.add(item("Import Files into Research\u2026", null, binder::importFiles));
        file.add(item("Compile Draft\u2026", KeyStroke.getKeyStroke(KeyEvent.VK_E, mask | KeyEvent.SHIFT_DOWN_MASK), this::compile));
        file.add(item("Export Selected Content to DOCX\u2026", null, () -> exportDocument(false)));
        file.add(item("Export Selected Content to PDF\u2026", null, () -> exportDocument(true)));
        file.addSeparator();
        file.add(item("Quit", KeyStroke.getKeyStroke(KeyEvent.VK_Q, mask), () -> closeWindow(true)));

        JMenu edit = new JMenu("Edit");
        edit.add(item("Undo", null, editor::undo));
        edit.add(item("Redo", null, editor::redo));
        edit.addSeparator();
        edit.add(item("New Document", KeyStroke.getKeyStroke(KeyEvent.VK_N, mask), binder::newDocument));
        edit.add(item("New Folder", KeyStroke.getKeyStroke(KeyEvent.VK_N, mask | KeyEvent.SHIFT_DOWN_MASK), binder::newFolder));
        edit.add(item("New Research Item\u2026", null, binder::newResearchItem));
        edit.add(item("New Relationships Folder", null, binder::newRelationshipFolder));
        edit.add(item("Rename", KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), binder::renameSelected));
        edit.add(item("Move to Trash", null, binder::deleteSelected));
        edit.add(item("Move Up", KeyStroke.getKeyStroke(KeyEvent.VK_UP, mask | KeyEvent.ALT_DOWN_MASK), binder::moveUp));
        edit.add(item("Move Down", KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, mask | KeyEvent.ALT_DOWN_MASK), binder::moveDown));
        edit.add(item("Empty Trash", null, binder::emptyTrash));

        JMenu view = new JMenu("View");
        addModeMenuItem(view, Mode.EDITOR, "Editor", KeyStroke.getKeyStroke(KeyEvent.VK_1, mask));
        addModeMenuItem(view, Mode.SCRIVENINGS, "Scrivenings", KeyStroke.getKeyStroke(KeyEvent.VK_2, mask));
        addModeMenuItem(view, Mode.CORKBOARD, "Corkboard", KeyStroke.getKeyStroke(KeyEvent.VK_3, mask));
        addModeMenuItem(view, Mode.OUTLINER, "Outliner", KeyStroke.getKeyStroke(KeyEvent.VK_4, mask));
        view.addSeparator();
        view.add(item("Show / Hide Inspector", KeyStroke.getKeyStroke(KeyEvent.VK_I, mask | KeyEvent.ALT_DOWN_MASK),
                () -> setInspectorVisible(!inspector.isVisible())));
        view.add(item("Show / Hide Binder", KeyStroke.getKeyStroke(KeyEvent.VK_B, mask | KeyEvent.ALT_DOWN_MASK),
                () -> setBinderVisible(!binder.isVisible())));
        view.add(item("Focus Mode (hide both panels)", null, () -> setSidebarsVisible(false)));
        view.add(item("Show Both Panels", null, () -> setSidebarsVisible(true)));

        bar.add(file);
        bar.add(edit);
        bar.add(view);
        bar.add(buildAiMenu());
        return bar;
    }

    private void addModeMenuItem(JMenu menu, Mode mode, String text, KeyStroke key) {
        JMenuItem menuItem = item(text, key, () -> setMode(mode));
        modeMenuItems.put(mode, menuItem);
        menu.add(menuItem);
    }

    private JMenu buildAiMenu() {
        JMenu ai = new JMenu("AI");
        ai.add(item("Dán API key\u2026", null, () -> AiKeyDialog.show(this, aiService)));
        ai.add(item("Phong cách viết\u2026", null, () -> AiStyleDialog.show(this, aiService, project.getDir(),
                editor.selectedText())));
        ai.addSeparator();
        ai.add(item("Hướng dẫn lệnh /assist và /chat", null, this::showAiHelp));
        return ai;
    }

    private void showAiHelp() {
        JOptionPane.showMessageDialog(this,
                "Gõ lệnh trong editor rồi nhấn Enter:\n\n"
                        + "/assist: yêu cầu của bạn\n"
                        + "    AI viết tiếp / viết lại dựa trên đoạn văn phía trước.\n"
                        + "    Kết quả hiện màu xanh: Tab = giữ (Yes), Esc = bỏ (No), Ctrl+R = thử lại.\n"
                        + "    Để trống yêu cầu (/assist:) = viết tiếp tự nhiên.\n\n"
                        + "/chat: câu hỏi của bạn\n"
                        + "    AI trả lời và tạo một ghi chú con (nhãn Idea) dưới thẻ hiện tại.\n\n"
                        + "Esc khi đang chờ = hủy. Menu AI \u2192 Phong cách viết để chỉnh giọng văn.",
                "Lệnh AI", JOptionPane.INFORMATION_MESSAGE);
    }

    /** Called by the editor when /chat: produced an answer: adds it as a child note of the current card. */
    private Item createAiNote(Item parent, String title, String content) {
        Item note = new Item(Item.Kind.TEXT, title);
        note.setContent(content);
        note.setLabel("Idea");
        project.insert(note, parent, parent.getChildCount());
        binder.expand(parent);
        return note;
    }

    private static JMenuItem item(String text, KeyStroke accel, Runnable r) {
        JMenuItem m = new JMenuItem(text);
        if (accel != null) m.setAccelerator(accel);
        m.addActionListener(e -> r.run());
        return m;
    }

    private void setBinderVisible(boolean visible) {
        if (visible && mainSplit.getDividerLocation() > 0) {
            binderWidth = mainSplit.getDividerLocation();
        }
        binder.setVisible(visible);
        mainSplit.setDividerLocation(visible ? Math.max(180, binderWidth) : 0);
        if (binderToggle != null) binderToggle.setSelected(visible);
        if (focusToggle != null && visible) focusToggle.setSelected(false);
        revalidate();
        repaint();
    }

    private void setInspectorVisible(boolean visible) {
        inspector.setVisible(visible);
        if (inspectorToggle != null) inspectorToggle.setSelected(visible);
        if (focusToggle != null && visible) focusToggle.setSelected(false);
        revalidate();
        repaint();
    }

    private void setSidebarsVisible(boolean visible) {
        setBinderVisible(visible);
        setInspectorVisible(visible);
        if (focusToggle != null) focusToggle.setSelected(!visible);
    }

    // ---- mode & selection -------------------------------------------------------------------------

    private void setMode(Mode m) {
        if (!isModeAvailable(m)) return;
        editor.flush();
        mode = m;
        modeButtons.get(m).setSelected(true);
        refreshActiveView();
    }

    private void onSelection(List<Item> sel) {
        editor.flush();
        selection = sel;
        inspector.setItem(sel.size() == 1 ? sel.get(0) : null);
        refreshActiveView();
    }

    /** Opens an item (from a card or outline row) in the normal editor. */
    private void openInEditor(Item it) {
        editor.flush();
        mode = Mode.EDITOR;
        modeButtons.get(Mode.EDITOR).setSelected(true);
        binder.select(it);
        refreshActiveView();
    }

    private void newCard() {
        if (selection.size() != 1 || !selection.get(0).isContainer()) return;
        Item parent = selection.get(0);
        if (parent.zone() == Item.Special.TRASH) return;
        Item card = new Item(Item.Kind.TEXT, "New Card");
        project.insert(card, parent, parent.getChildCount());
        binder.expand(parent);
    }

    /** After the Binder structure changed: save editor text, redraw everything. */
    private void refreshAll() {
        editor.flush();
        refreshActiveView();
    }

    private void refreshActiveView() {
        updateModeAvailability();
        switch (mode) {
            case EDITOR:
                if (isResearchDocumentSelection()) {
                    researchEditor.show(selection.get(0));
                    viewCards.show(views, "research-editor");
                } else {
                    showEditor(false);
                    viewCards.show(views, "editor");
                }
                break;
            case SCRIVENINGS:
                showEditor(true);
                viewCards.show(views, "editor");
                break;
            case CORKBOARD: {
                Item container = containerOf(selection);
                if (container != null && container.isRelationshipFolder()) {
                    relationships.show(container);
                    viewCards.show(views, "relationships");
                } else {
                    cork.show(container, cardItems(selection), container != null || canAddTo(selection));
                    viewCards.show(views, "cork");
                }
                break;
            }
            case OUTLINER:
                outliner.show(containerOf(selection), cardItems(selection));
                viewCards.show(views, "outliner");
                break;
            default:
                break;
        }
        updateStatus();
    }

    private void updateModeAvailability() {
        for (Mode m : Mode.values()) {
            boolean available = isModeAvailable(m);
            JToggleButton button = modeButtons.get(m);
            if (button != null) button.setEnabled(available);
            JMenuItem menuItem = modeMenuItems.get(m);
            if (menuItem != null) menuItem.setEnabled(available);
        }
        if (selection.isEmpty()) return;
        if (isModeAvailable(mode)) return;
        if (isModeAvailable(Mode.SCRIVENINGS)) mode = Mode.SCRIVENINGS;
        else if (isModeAvailable(Mode.EDITOR)) mode = Mode.EDITOR;
        else if (isModeAvailable(Mode.CORKBOARD)) mode = Mode.CORKBOARD;
        else mode = Mode.OUTLINER;
        JToggleButton button = modeButtons.get(mode);
        if (button != null) button.setSelected(true);
    }

    private boolean isModeAvailable(Mode m) {
        if (selection.isEmpty()) return false;
        switch (m) {
            case EDITOR:
                return selection.size() == 1 && !selection.get(0).isFolder();
            case SCRIVENINGS:
                return !isResearchDocumentSelection();
            case CORKBOARD:
                Item it = selection.size() == 1 ? selection.get(0) : null;
                return it != null && it.zone() != Item.Special.TRASH
                        && it.getKind() != Item.Kind.FILE;
            case OUTLINER:
                return true;
            default:
                return false;
        }
    }

    private boolean isResearchDocumentSelection() {
        return selection.size() == 1
                && selection.get(0).getKind() == Item.Kind.TEXT
                && selection.get(0).zone() == Item.Special.RESEARCH;
    }

    private void showEditor(boolean scrivenings) {
        if (selection.isEmpty()) {
            editor.showEmpty("Select a document in the Binder.");
            return;
        }
        if (!scrivenings) {
            Item it = selection.get(0);
            if (it.getKind() == Item.Kind.FILE) editor.showFile(it, project.resolve(it));
            else if (it.isSpecial()) editor.showEmpty("\u201c" + it.getTitle() + "\u201d is a top-level folder. Select a document, or use Scrivenings / Corkboard / Outliner to see its contents.");
            else editor.showSections(List.of(it));
            return;
        }
        if (selection.size() == 1 && selection.get(0).getKind() == Item.Kind.FILE) {
            Item it = selection.get(0);
            editor.showFile(it, project.resolve(it));
            return;
        }
        Set<Item> docs = new LinkedHashSet<>();
        for (Item sel : selection) {
            for (Item d : sel.subtree()) {
                if (d.getKind() == Item.Kind.FILE) continue;
                if (d.isSpecial() && d.getContent().isEmpty()) continue;
                docs.add(d);
            }
        }
        if (docs.isEmpty()) editor.showEmpty("This folder is empty. Add a document to it first.");
        else editor.showSections(new ArrayList<>(docs));
    }

    /** The folder whose children fill the corkboard / outliner, or null for a multi-selection. */
    private static Item containerOf(List<Item> sel) {
        if (sel.size() != 1) return null;
        Item item = sel.get(0);
        return item.isContainer() && (item.getChildCount() > 0 || item.isRelationshipFolder())
                ? item : null;
    }

    private static boolean canAddTo(List<Item> sel) {
        return sel.size() == 1 && sel.get(0).isContainer() && sel.get(0).zone() != Item.Special.TRASH;
    }

    private static List<Item> cardItems(List<Item> sel) {
        Item c = containerOf(sel);
        if (c != null) return visibleCards(c.items());
        List<Item> out = new ArrayList<>();
        for (Item it : sel) if (!it.isSpecial() && it.getKind() != Item.Kind.FILE) out.add(it);
        return out;
    }

    private static List<Item> visibleCards(List<Item> items) {
        List<Item> cards = new ArrayList<>();
        for (Item item : items) if (item.getKind() != Item.Kind.FILE) cards.add(item);
        return cards;
    }

    private void updateStatus() {
        int viewWords = 0;
        for (Item it : selection) viewWords += it.totalWords();
        status.setText("Selection: " + viewWords + " words    |    Draft: " + project.draftWords()
                + " words    |    " + project.getDir().getAbsolutePath());
        Item single = selection.size() == 1 ? selection.get(0) : null;
        if (single != null) inspector.refreshStats();
    }

    // ---- file operations -------------------------------------------------------------------------------------------

    private void saveProject(boolean announce) {
        editor.flush();
        if (!project.isDirty() && !announce) return;
        try {
            project.save();
            if (announce) {
                editor.showSaveNotice("\u2713 Saved");
                status.setText("Saved");
            }
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Could not save the project:\n" + ex.getMessage(),
                    "Save failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void compile() {
        editor.flush();
        JFileChooser fc = new JFileChooser(project.getDir());
        fc.setDialogTitle("Compile Draft");
        fc.addChoosableFileFilter(new FileNameExtensionFilter("Markdown (*.md)", "md"));
        fc.addChoosableFileFilter(new FileNameExtensionFilter("Plain text (*.txt)", "txt"));
        fc.setFileFilter(fc.getChoosableFileFilters()[1]);
        fc.setSelectedFile(new File(project.getDir(), project.getName() + ".md"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File out = fc.getSelectedFile();
        boolean markdown = !out.getName().toLowerCase().endsWith(".txt");
        int r = JOptionPane.showConfirmDialog(this, "Include document titles as headings?", "Compile",
                JOptionPane.YES_NO_CANCEL_OPTION);
        if (r == JOptionPane.CANCEL_OPTION || r == JOptionPane.CLOSED_OPTION) return;
        try {
            project.compile(out, markdown, r == JOptionPane.YES_OPTION);
            status.setText("Compiled to " + out.getAbsolutePath());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Compile failed:\n" + ex.getMessage(), "Compile",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void exportDocument(boolean pdf) {
            editor.flush();
            Set<Item> selected = ExportSelectionDialog.choose(this, project, selection);
            if (selected == null || selected.isEmpty()) return;

            JFileChooser fc = new JFileChooser(project.getDir());
            fc.setDialogTitle(pdf ? "Export selected content to PDF" : "Export selected content to DOCX");
            String extension = pdf ? "pdf" : "docx";
            fc.setFileFilter(new FileNameExtensionFilter(extension.toUpperCase() + " (*." + extension + ")", extension));
            fc.setSelectedFile(new File(project.getDir(), project.getName() + "." + extension));
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

            File out = fc.getSelectedFile();
            if (!out.getName().toLowerCase().endsWith("." + extension)) {
                out = new File(out.getParentFile(), out.getName() + "." + extension);
            }
            try {
                if (pdf) ExportService.exportPdf(out, project, selected);
                else ExportService.exportDocx(out, project, selected);
                status.setText("Exported to " + out.getAbsolutePath());
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Export failed:\n" + ex.getMessage(),
                        "Export failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void newProject() {
        Project p = Main.promptNewProject(this);
        if (p != null) switchTo(p);
    }

    private void openProject() {
        Project p = Main.promptOpenProject(this);
        if (p != null) switchTo(p);
    }

    private void switchTo(Project p) {
        saveProject(false);
        Main.show(p);
        closeWindow(false);
    }

    private void closeWindow(boolean exit) {
        saveProject(false);
        refreshTimer.stop();
        autosaveTimer.stop();
        dispose();
        if (exit) System.exit(0);
    }
}

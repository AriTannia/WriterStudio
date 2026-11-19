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
import javax.swing.SwingWorker;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import javax.swing.event.TreeModelEvent;
import javax.swing.event.TreeModelListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GridBagLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

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
    private final JPanel busyGlass = new JPanel(new GridBagLayout());
    private final JLabel busyMessage = new JLabel("Working\u2026");
    private final Map<Component, Boolean> enabledBeforeTask = new IdentityHashMap<>();
    private final Map<Mode, JToggleButton> modeButtons = new EnumMap<>(Mode.class);
    private final Map<Mode, JMenuItem> modeMenuItems = new EnumMap<>(Mode.class);
    private final Timer refreshTimer;
    private JSplitPane mainSplit;
    private JSplitPane workspaceSplit;
    private JToggleButton binderToggle;
    private JToggleButton inspectorToggle;
    private JToggleButton focusToggle;
    private int binderWidth = 260;
    private int inspectorWidth = 270;
    private boolean inspectorVisible = true;

    private Mode mode = Mode.EDITOR;
    private List<Item> selection = new ArrayList<>();
    private boolean backgroundTaskRunning;

    @FunctionalInterface
    private interface BackgroundOperation {
        void run() throws Exception;
    }

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
        workspaceSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, center, inspector);
        workspaceSplit.setResizeWeight(1.0);
        workspaceSplit.setContinuousLayout(true);
        workspaceSplit.setDividerLocation(0.74);
        workspaceSplit.setOneTouchExpandable(true);

        mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, binder, workspaceSplit);
        mainSplit.setDividerLocation(binderWidth);
        mainSplit.setResizeWeight(0);
        mainSplit.setContinuousLayout(true);
        mainSplit.setOneTouchExpandable(true);

        status.setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 10));
        getContentPane().add(mainSplit, BorderLayout.CENTER);
        getContentPane().add(status, BorderLayout.SOUTH);
        setJMenuBar(buildMenuBar());
        busyGlass.setOpaque(true);
        busyGlass.setBackground(new java.awt.Color(238, 241, 245, 220));
        busyGlass.setFocusable(true);
        busyGlass.add(busyMessage);
        busyGlass.addMouseListener(new MouseAdapter() { });
        setGlassPane(busyGlass);
        busyGlass.setVisible(false);

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

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                if (!backgroundTaskRunning) closeWindow(true);
            }
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
        addModeButton(bar, group, Mode.SCRIVENINGS, "Combined Draft",
                "Read and edit selected documents as one continuous draft");
        addModeButton(bar, group, Mode.CORKBOARD, "Corkboard", "Index cards with synopses");
        addModeButton(bar, group, Mode.OUTLINER, "Outliner", "Outline with metadata");
        modeButtons.get(Mode.EDITOR).setSelected(true);
        bar.addSeparator();
        binderToggle = sidePanelButton(bar, "Project", "Show or hide project contents", true,
                () -> setBinderVisible(binderToggle.isSelected()));
        inspectorToggle = sidePanelButton(bar, "Details", "Show or hide details for the selected item", true,
                () -> setInspectorVisible(inspectorToggle.isSelected()));
        focusToggle = new JToggleButton("Focus Mode");
        focusToggle.setToolTipText("Hide both side panels to make more room for writing");
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
        file.add(item("Export Draft\u2026", KeyStroke.getKeyStroke(KeyEvent.VK_E, mask | KeyEvent.SHIFT_DOWN_MASK), this::compile));
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
        addModeMenuItem(view, Mode.SCRIVENINGS, "Combined Draft", KeyStroke.getKeyStroke(KeyEvent.VK_2, mask));
        addModeMenuItem(view, Mode.CORKBOARD, "Corkboard", KeyStroke.getKeyStroke(KeyEvent.VK_3, mask));
        addModeMenuItem(view, Mode.OUTLINER, "Outliner", KeyStroke.getKeyStroke(KeyEvent.VK_4, mask));
        view.addSeparator();
        view.add(item("Show / Hide Details", KeyStroke.getKeyStroke(KeyEvent.VK_I, mask | KeyEvent.ALT_DOWN_MASK),
                () -> setInspectorVisible(!inspectorVisible)));
        view.add(item("Show / Hide Project", KeyStroke.getKeyStroke(KeyEvent.VK_B, mask | KeyEvent.ALT_DOWN_MASK),
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
        if (visible && !inspectorVisible) {
            inspector.setVisible(true);
            int width = workspaceSplit.getWidth();
            if (width > 0) workspaceSplit.setDividerLocation(Math.max(360, width - inspectorWidth));
        } else if (!visible && inspectorVisible) {
            int width = workspaceSplit.getWidth();
            if (width > 0) inspectorWidth = Math.max(220, width - workspaceSplit.getDividerLocation());
            inspector.setVisible(false);
        }
        inspectorVisible = visible;
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
                return !isResearchDocumentSelection() || isThreeActStructureSelection();
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

    private boolean isThreeActStructureSelection() {
        return isResearchDocumentSelection()
                && selection.get(0).getResearchType() == Item.ResearchType.THREE_ACT_STRUCTURE;
    }

    private void showEditor(boolean scrivenings) {
        if (selection.isEmpty()) {
            editor.showEmpty("Select a document in Project.");
            return;
        }
        if (!scrivenings) {
            Item it = selection.get(0);
            if (it.getKind() == Item.Kind.FILE) editor.showFile(it, project.resolve(it));
            else if (it.isSpecial()) editor.showEmpty("\u201c" + it.getTitle()
                    + "\u201d is a top-level folder. Select a document, or use Combined Draft, Corkboard, or Outliner to see its contents.");
            else editor.showSections(List.of(it));
            return;
        }
        if (selection.size() == 1 && selection.get(0).getKind() == Item.Kind.FILE) {
            Item it = selection.get(0);
            editor.showFile(it, project.resolve(it));
            return;
        }
        if (selection.size() == 1 && isThreeActStructureSelection()) {
            editor.showThreeActStructure(selection.get(0));
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
        saveProject(announce, null);
    }

    private void saveProject(boolean announce, Runnable afterSuccess) {
        editor.flush();
        if (!project.isDirty() && !announce) {
            if (afterSuccess != null) afterSuccess.run();
            return;
        }
        runInBackground("Saving project\u2026", "Save failed", project::save, () -> {
            if (announce) {
                editor.showSaveNotice("\u2713 Saved");
                status.setText("Saved");
            }
            if (afterSuccess != null) afterSuccess.run();
        });
    }

    private void compile() {
        editor.flush();
        JFileChooser fc = new JFileChooser(project.getDir());
        fc.setDialogTitle("Export Draft");
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
        runInBackground("Compiling draft\u2026", "Compile failed",
                () -> project.compile(out, markdown, r == JOptionPane.YES_OPTION),
                () -> status.setText("Exported Draft to " + out.getAbsolutePath()));
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
            File exportFile = out;
            runInBackground("Exporting document\u2026", "Export failed", () -> {
                if (pdf) ExportService.exportPdf(exportFile, project, selected);
                else ExportService.exportDocx(exportFile, project, selected);
            }, () -> status.setText("Exported to " + exportFile.getAbsolutePath()));
    }

    private void runInBackground(String message, String errorTitle,
                                 BackgroundOperation operation, Runnable afterSuccess) {
        if (backgroundTaskRunning) return;
        backgroundTaskRunning = true;
        busyMessage.setText(message);
        setUiBusy(true);
        busyGlass.setVisible(true);
        busyGlass.requestFocusInWindow();
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                operation.run();
                return null;
            }

            @Override protected void done() {
                backgroundTaskRunning = false;
                busyGlass.setVisible(false);
                setUiBusy(false);
                try {
                    get();
                    afterSuccess.run();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    JOptionPane.showMessageDialog(MainFrame.this,
                            "The operation was interrupted.", errorTitle, JOptionPane.ERROR_MESSAGE);
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    JOptionPane.showMessageDialog(MainFrame.this,
                            cause.getMessage() == null ? cause.toString() : cause.getMessage(),
                            errorTitle, JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void setUiBusy(boolean busy) {
        if (busy) {
            enabledBeforeTask.clear();
            setEnabledRecursively(getContentPane(), false);
            if (getJMenuBar() != null) setEnabledRecursively(getJMenuBar(), false);
        } else {
            for (Map.Entry<Component, Boolean> entry : enabledBeforeTask.entrySet()) {
                entry.getKey().setEnabled(entry.getValue());
            }
            enabledBeforeTask.clear();
        }
    }

    private void setEnabledRecursively(Component component, boolean enabled) {
        enabledBeforeTask.put(component, component.isEnabled());
        component.setEnabled(enabled);
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                setEnabledRecursively(child, enabled);
            }
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
        confirmSaveBeforeLeaving(() -> {
            Main.show(p);
            finishClose(false);
        });
    }

    private void closeWindow(boolean exit) {
        confirmSaveBeforeLeaving(() -> finishClose(exit));
    }

    private void confirmSaveBeforeLeaving(Runnable continueAction) {
        editor.flush();
        if (!project.isDirty()) {
            continueAction.run();
            return;
        }
        Object[] choices = {"Save", "Don't Save", "Cancel"};
        int choice = JOptionPane.showOptionDialog(this,
                "Save changes to this project before leaving?",
                "Unsaved changes", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                null, choices, choices[0]);
        if (choice == 0) saveProject(true, continueAction);
        else if (choice == 1) continueAction.run();
    }

    private void finishClose(boolean exit) {
        refreshTimer.stop();
        dispose();
        if (exit) System.exit(0);
    }
}

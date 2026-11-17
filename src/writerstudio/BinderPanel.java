package writerstudio;

import javax.swing.AbstractAction;
import javax.swing.DropMode;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JToolBar;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.TransferHandler;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.function.Consumer;

/** The Binder: the project tree (Draft, Research, Trash) shown on the left. */
public class BinderPanel extends JPanel {
    private final Project project;
    private final JTree tree;
    private final Runnable beforeChange;
    private final Runnable afterStructure;
    private Consumer<List<Item>> selectionListener = l -> { };

    /**
     * @param beforeChange   called before the structure is modified (so editors can save their text)
     * @param afterStructure called after the structure was modified (so views can refresh)
     */
    public BinderPanel(Project project, Runnable beforeChange, Runnable afterStructure) {
        super(new BorderLayout());
        this.project = project;
        this.beforeChange = beforeChange;
        this.afterStructure = afterStructure;

        tree = new JTree(project.model()) {
            @Override public boolean isPathEditable(TreePath path) {
                Object o = path.getLastPathComponent();
                return o instanceof Item && !((Item) o).isSpecial() && super.isPathEditable(path);
            }
        };
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setEditable(true);
        tree.setRowHeight(22);
        tree.setCellRenderer(new Renderer());
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        tree.setDragEnabled(true);
        tree.setDropMode(DropMode.ON_OR_INSERT);
        tree.setTransferHandler(new MoveHandler());
        tree.addTreeSelectionListener(e -> selectionListener.accept(selectedItems()));

        tree.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "binder-trash");
        tree.getActionMap().put("binder-trash", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { deleteSelected(); }
        });

        JPopupMenu menu = new JPopupMenu();
        menu.add(menuItem("New Document", this::newDocument));
        menu.add(menuItem("New Folder", this::newFolder));
        menu.add(menuItem("New Research Item\u2026", this::newResearchItem));
        menu.add(menuItem("New Relationships Folder", this::newRelationshipFolder));
        menu.add(menuItem("Import Files into Research\u2026", this::importFiles));
        menu.addSeparator();
        menu.add(menuItem("Rename", this::renameSelected));
        menu.add(menuItem("Move Up", this::moveUp));
        menu.add(menuItem("Move Down", this::moveDown));
        menu.addSeparator();
        menu.add(menuItem("Move to Trash / Delete", this::deleteSelected));
        menu.add(menuItem("Empty Trash", this::emptyTrash));
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { popup(e); }
            @Override public void mouseReleased(MouseEvent e) { popup(e); }
            private void popup(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                TreePath p = tree.getPathForLocation(e.getX(), e.getY());
                if (p != null && !tree.isPathSelected(p)) tree.setSelectionPath(p);
                menu.show(tree, e.getX(), e.getY());
            }
        });

        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.add(button("+ Doc", this::newDocument));
        bar.add(button("+ Folder", this::newFolder));

        add(bar, BorderLayout.NORTH);
        add(new JScrollPane(tree), BorderLayout.CENTER);

        tree.expandPath(pathOf(project.draft()));
        tree.expandPath(pathOf(project.research()));
    }

    private static JButton button(String text, Runnable r) {
        JButton b = new JButton(text);
        b.setFocusable(false);
        b.addActionListener(e -> r.run());
        return b;
    }

    private static JMenuItem menuItem(String text, Runnable r) {
        JMenuItem m = new JMenuItem(text);
        m.addActionListener(e -> r.run());
        return m;
    }

    public void setSelectionListener(Consumer<List<Item>> l) { this.selectionListener = l; }

    // ---- selection helpers ---------------------------------------------------------

    private TreePath pathOf(Item it) { return new TreePath(project.model().getPathToRoot(it)); }

    public List<Item> selectedItems() {
        List<Item> out = new ArrayList<>();
        TreePath[] paths = tree.getSelectionPaths();
        if (paths == null) return out;
        Arrays.sort(paths, Comparator.comparingInt(tree::getRowForPath));
        for (TreePath p : paths) {
            Object o = p.getLastPathComponent();
            if (o instanceof Item && o != project.root()) out.add((Item) o);
        }
        return out;
    }

    /** Selected items without those whose ancestor is also selected. */
    private List<Item> topLevelSelected() {
        List<Item> sel = selectedItems();
        List<Item> out = new ArrayList<>();
        for (Item it : sel) {
            boolean nested = false;
            for (Item other : sel) {
                if (other != it && other.isNodeDescendant(it)) { nested = true; break; }
            }
            if (!nested) out.add(it);
        }
        return out;
    }

    private Item firstSelected() {
        List<Item> sel = selectedItems();
        return sel.isEmpty() ? null : sel.get(0);
    }

    public void select(Item it) {
        TreePath p = pathOf(it);
        tree.setSelectionPath(p);
        tree.scrollPathToVisible(p);
    }

    public void expand(Item it) { tree.expandPath(pathOf(it)); }

    // ---- actions -----------------------------------------------------------------------

    public void newDocument() { newItem(Item.Kind.TEXT, "New Document"); }

    public void newFolder() { newItem(Item.Kind.FOLDER, "New Folder"); }

    public void newResearchItem() {
        Item.ResearchType type = (Item.ResearchType) JOptionPane.showInputDialog(
                this, "Choose a Research template:", "New Research Item",
                JOptionPane.PLAIN_MESSAGE, null, Item.ResearchType.documentTypes(), Item.ResearchType.NOTE);
        if (type == null) return;
        createResearchItem(type);
    }

    public void newRelationshipFolder() {
        beforeChange.run();
        Item parent = researchParent();
        Item folder = new Item(Item.Kind.FOLDER, "Relationships");
        folder.setRelationshipFolder(true);
        project.insert(folder, parent, parent.getChildCount());
        expand(parent);
        select(folder);
        afterStructure.run();
    }

    private void createResearchItem(Item.ResearchType type) {
        beforeChange.run();
        Item parent = researchParent();
        Item it = new Item(Item.Kind.TEXT, researchTitle(type));
        it.setResearchType(type);
        it.setContent(researchTemplate(type));
        project.insert(it, parent, parent.getChildCount());
        expand(parent);
        select(it);
        tree.startEditingAtPath(pathOf(it));
        afterStructure.run();
    }

    private void newItem(Item.Kind kind, String title) {
        beforeChange.run();
        Item sel = firstSelected();
        Item parent;
        int index;
        if (sel == null || sel.zone() == Item.Special.TRASH || sel == project.root()) {
            parent = project.draft();
            index = parent.getChildCount();
        } else if (sel.isFolder()) {
            parent = sel;
            index = sel.getChildCount();
        } else {
            parent = sel.parentItem();
            index = parent.getIndex(sel) + 1;
        }
        Item it = new Item(kind, title);
        project.insert(it, parent, index);
        expand(parent);
        select(it);
        tree.startEditingAtPath(pathOf(it));
        afterStructure.run();
    }

    private Item researchParent() {
        Item sel = firstSelected();
        if (sel != null && sel.zone() == Item.Special.RESEARCH) {
            if (sel.isFolder()) return sel;
            if (sel.parentItem() != null) return sel.parentItem();
        }
        return project.research();
    }

    private static String researchTitle(Item.ResearchType type) {
        switch (type) {
            case CHARACTER: return "New Character";
            case LOCATION: return "New Location";
            case ORGANIZATION: return "New Organization";
            case EVENT: return "New Event";
            case REFERENCE: return "New Reference";
            default: return "New Research Note";
        }
    }

    private static String researchTemplate(Item.ResearchType type) {
        switch (type) {
            case CHARACTER:
                return "Overview\n\nPersonality\n\nGoals\n\nBackstory\n\nRelationships\n\nNotes\n";
            case LOCATION:
                return "Description\n\nAtmosphere\n\nImportant details\n\nAppears in\n\nNotes\n";
            case ORGANIZATION:
                return "Purpose\n\nMembers\n\nStructure\n\nConflicts\n\nNotes\n";
            case EVENT:
                return "When / where\n\nWhat happens\n\nConsequences\n\nNotes\n";
            case REFERENCE:
                return "Source\n\nKey information\n\nLinks / citations\n\nNotes\n";
            default:
                return "";
        }
    }

    public void renameSelected() {
        TreePath p = tree.getSelectionPath();
        if (p != null) tree.startEditingAtPath(p);
    }

    public void deleteSelected() {
        List<Item> sel = topLevelSelected();
        sel.removeIf(Item::isSpecial);
        if (sel.isEmpty()) return;
        beforeChange.run();
        boolean permanent = sel.stream().allMatch(i -> i.zone() == Item.Special.TRASH);
        if (permanent) {
            int r = JOptionPane.showConfirmDialog(this,
                    "Permanently delete " + sel.size() + " item(s)? This cannot be undone.",
                    "Delete", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return;
        }
        Item fallback = sel.get(0).parentItem();
        for (Item it : sel) {
            if (permanent) project.deleteForever(it);
            else if (it.zone() != Item.Special.TRASH) project.moveToTrash(it);
        }
        if (fallback != null && fallback != project.root()) select(fallback);
        afterStructure.run();
    }

    public void emptyTrash() {
        if (project.trash().getChildCount() == 0) return;
        int r = JOptionPane.showConfirmDialog(this, "Permanently delete everything in the Trash?",
                "Empty Trash", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return;
        beforeChange.run();
        project.emptyTrash();
        afterStructure.run();
    }

    public void moveUp() { move(-1); }

    public void moveDown() { move(1); }

    private void move(int delta) {
        List<Item> sel = topLevelSelected();
        if (sel.size() != 1 || sel.get(0).isSpecial()) return;
        Item it = sel.get(0);
        Item parent = it.parentItem();
        int target = parent.getIndex(it) + delta;
        if (target < 0 || target >= parent.getChildCount()) return;
        beforeChange.run();
        relocate(it, parent, target);
        select(it);
        afterStructure.run();
    }

    /** Moves a node while preserving the expanded/collapsed state of its subtree. */
    private void relocate(Item it, Item newParent, int index) {
        Enumeration<TreePath> exp = tree.getExpandedDescendants(pathOf(it));
        List<TreePath> keep = exp == null ? new ArrayList<>() : Collections.list(exp);
        project.model().removeNodeFromParent(it);
        project.model().insertNodeInto(it, newParent, index);
        for (TreePath p : keep) tree.expandPath(p);
        project.markDirty();
    }

    /** Imports files into the Research folder (or the selected research sub-folder). */
    public void importFiles() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Import files into Research");
        fc.setMultiSelectionEnabled(true);
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        importFilesFromChooser(fc);
    }

    private void importFilesFromChooser(JFileChooser fc) {
        beforeChange.run();
        Item parent = project.research();
        Item sel = firstSelected();
        if (sel != null && sel.zone() == Item.Special.RESEARCH) {
            parent = sel.isContainer() ? sel : sel.parentItem();
        }
        Item last = null;
        StringBuilder errors = new StringBuilder();
        for (File f : fc.getSelectedFiles()) {
            try {
                Item it = project.importFile(f);
                project.insert(it, parent, parent.getChildCount());
                last = it;
            } catch (IOException ex) {
                errors.append(f.getName()).append(": ").append(ex.getMessage()).append('\n');
            }
        }
        expand(parent);
        if (last != null) select(last);
        afterStructure.run();
        if (errors.length() > 0) {
            JOptionPane.showMessageDialog(this, errors.toString(), "Import problems", JOptionPane.WARNING_MESSAGE);
        }
    }

    // ---- rendering -----------------------------------------------------------------------

    private static class Renderer extends DefaultTreeCellRenderer {
        @Override public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel,
                boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            if (value instanceof Item) {
                Item it = (Item) value;
                setIcon(Icons.forItem(it));
                setText(it.getTitle());
                Font base = tree.getFont();
                setFont(it.isSpecial() ? base.deriveFont(Font.BOLD) : base);
                if (!it.isInclude() && !sel) setForeground(java.awt.Color.GRAY);
            }
            return this;
        }
    }

    // ---- drag and drop (reorder / move between folders) ------------------------------------

    private class MoveHandler extends TransferHandler {
        private List<Item> dragged = new ArrayList<>();

        @Override public int getSourceActions(JComponent c) { return MOVE; }

        @Override protected Transferable createTransferable(JComponent c) {
            List<Item> sel = topLevelSelected();
            sel.removeIf(Item::isSpecial);
            if (sel.isEmpty()) return null;
            dragged = sel;
            return new StringSelection("binder-items");
        }

        @Override public boolean canImport(TransferSupport support) {
            if (!support.isDrop() || dragged.isEmpty()) return false;
            support.setShowDropLocation(true);
            JTree.DropLocation dl = (JTree.DropLocation) support.getDropLocation();
            TreePath p = dl.getPath();
            if (p == null) return false;
            Item target = (Item) p.getLastPathComponent();
            if (target == project.root() || !target.isContainer()) return false;
            for (Item it : dragged) {
                if (it.isNodeDescendant(target)) return false;   // not into itself / its own children
            }
            return true;
        }

        @Override public boolean importData(TransferSupport support) {
            if (!canImport(support)) return false;
            JTree.DropLocation dl = (JTree.DropLocation) support.getDropLocation();
            Item target = (Item) dl.getPath().getLastPathComponent();
            int index = dl.getChildIndex();
            if (index < 0) index = target.getChildCount();

            beforeChange.run();
            List<TreePath> newPaths = new ArrayList<>();
            for (Item it : new ArrayList<>(dragged)) {
                Item oldParent = it.parentItem();
                int oldIndex = oldParent.getIndex(it);
                int at = index;
                if (oldParent == target && oldIndex < at) at--;
                relocate(it, target, at);
                index = at + 1;
                newPaths.add(pathOf(it));
            }
            expand(target);
            tree.setSelectionPaths(newPaths.toArray(new TreePath[0]));
            afterStructure.run();
            return true;
        }

        @Override protected void exportDone(JComponent source, Transferable data, int action) {
            dragged = new ArrayList<>();
        }
    }
}

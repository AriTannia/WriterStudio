package writerstudio;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Rectangle;
import java.awt.Frame;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Lets users choose exact Draft folders/cards, including nested cards, for export. */
final class ExportSelectionDialog extends JDialog {
    private final Set<Item> checked = Collections.newSetFromMap(new IdentityHashMap<>());
    private final JTree tree;
    private boolean approved;

    private ExportSelectionDialog(Frame owner, Project project, List<Item> initial) {
        super(owner, "Choose content to export", Dialog.ModalityType.APPLICATION_MODAL);
        seed(project, initial);
        tree = new JTree(new DefaultTreeModel(project.draft()));
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new CheckRenderer());
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                if (path == null) return;
                Rectangle bounds = tree.getPathBounds(path);
                if (bounds == null || e.getX() < bounds.x) return;
                Object value = path.getLastPathComponent();
                if (value instanceof Item) {
                    toggle((Item) value);
                    tree.repaint();
                }
            }
        });
        JScrollPane scroll = new JScrollPane(tree);
        scroll.setPreferredSize(new Dimension(420, 420));

        JButton export = new JButton("Export");
        export.addActionListener(e -> { approved = true; dispose(); });
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancel);
        buttons.add(export);

        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(new JLabel("Select folders/cards. Uncheck nested cards you want to exclude."), BorderLayout.NORTH);
        content.add(scroll, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        pack();
        setLocationRelativeTo(owner);
    }

    static Set<Item> choose(Frame owner, Project project, List<Item> initial) {
        ExportSelectionDialog dialog = new ExportSelectionDialog(owner, project, initial);
        dialog.setVisible(true);
        return dialog.approved ? dialog.checked : null;
    }

    private void seed(Project project, List<Item> initial) {
        if (initial.isEmpty()) {
            for (Item item : project.draft().items()) checkTree(item, true);
        } else {
            for (Item item : initial) checkTree(item, true);
        }
    }

    private void toggle(Item item) {
        boolean on = !checked.contains(item);
        checkTree(item, on);
    }

    private void checkTree(Item item, boolean on) {
        if (on) checked.add(item);
        else checked.remove(item);
        for (Item child : item.items()) checkTree(child, on);
    }

    private final class CheckRenderer extends JCheckBox implements javax.swing.tree.TreeCellRenderer {
        @Override public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
                boolean expanded, boolean leaf, int row, boolean hasFocus) {
            Item item = (Item) value;
            setText(item.getTitle());
            setSelected(checked.contains(item));
            setOpaque(false);
            return this;
        }
    }
}

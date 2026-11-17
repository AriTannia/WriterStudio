package writerstudio;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The outliner: the selected items and all their sub-documents as table rows, with
 * editable title, synopsis, label, status and compile flag, plus a word count.
 */
public class OutlinerPanel extends JPanel {
    private static final String[] COLUMNS = {"Title", "Synopsis", "Label", "Status", "Words", "Compile"};

    private static final class Row {
        final Item item;
        final int depth;
        Row(Item item, int depth) { this.item = item; this.depth = depth; }
    }

    private final Project project;
    private final Consumer<Item> openItem;
    private final List<Row> rows = new ArrayList<>();
    private final JLabel heading = new JLabel(" ");
    private final JTable table;
    private final AbstractTableModel model = new AbstractTableModel() {
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int c) { return COLUMNS[c]; }

        @Override public Class<?> getColumnClass(int c) {
            if (c == 4) return Integer.class;
            if (c == 5) return Boolean.class;
            return String.class;
        }

        @Override public boolean isCellEditable(int r, int c) { return c != 4; }

        @Override public Object getValueAt(int r, int c) {
            Item it = rows.get(r).item;
            switch (c) {
                case 0: return it.getTitle();
                case 1: return flat(it.getSynopsis());
                case 2: return it.getLabel();
                case 3: return it.getStatus();
                case 4: return it.totalWords();
                default: return it.isInclude();
            }
        }

        @Override public void setValueAt(Object v, int r, int c) {
            Item it = rows.get(r).item;
            switch (c) {
                case 0:
                    it.setUserObject(String.valueOf(v));      // ignores blank titles
                    break;
                case 1:
                    String text = String.valueOf(v);
                    if (!text.equals(flat(it.getSynopsis()))) it.setSynopsis(text);   // keep line breaks if untouched
                    break;
                case 2:
                    it.setLabel(String.valueOf(v));
                    break;
                case 3:
                    it.setStatus(String.valueOf(v));
                    break;
                case 5:
                    it.setInclude(Boolean.TRUE.equals(v));
                    break;
                default:
                    return;
            }
            project.changedQuietly(it);
            fireTableRowsUpdated(r, r);
        }
    };

    public OutlinerPanel(Project project, Consumer<Item> openItem) {
        super(new BorderLayout());
        this.project = project;
        this.openItem = openItem;

        table = new JTable(model);
        table.setRowHeight(26);
        table.setFillsViewportHeight(true);
        table.setShowGrid(false);
        table.setIntercellSpacing(new java.awt.Dimension(0, 1));
        table.getColumnModel().getColumn(0).setPreferredWidth(240);
        table.getColumnModel().getColumn(1).setPreferredWidth(420);
        table.getColumnModel().getColumn(2).setPreferredWidth(90);
        table.getColumnModel().getColumn(3).setPreferredWidth(110);
        table.getColumnModel().getColumn(4).setPreferredWidth(60);
        table.getColumnModel().getColumn(5).setPreferredWidth(70);

        table.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel,
                    boolean focus, int row, int col) {
                super.getTableCellRendererComponent(t, v, sel, focus, row, col);
                Row r = rows.get(row);
                setIcon(Icons.forItem(r.item));
                setIconTextGap(6);
                setBorder(BorderFactory.createEmptyBorder(0, 6 + r.depth * 18, 0, 4));
                setFont(r.item.isFolder() ? t.getFont().deriveFont(Font.BOLD) : t.getFont());
                return this;
            }
        });
        DefaultTableCellRenderer chipRenderer = new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel,
                    boolean focus, int row, int col) {
                super.getTableCellRendererComponent(t, v, sel, focus, row, col);
                Color c = col == 2 ? Project.labelColor(String.valueOf(v)) : null;
                setIcon(c != null ? Icons.chip(c) : null);
                return this;
            }
        };
        table.getColumnModel().getColumn(2).setCellRenderer(chipRenderer);
        table.getColumnModel().getColumn(2).setCellEditor(new DefaultCellEditor(new JComboBox<>(Project.LABELS)));
        table.getColumnModel().getColumn(3).setCellEditor(new DefaultCellEditor(new JComboBox<>(Project.STATUSES)));

        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2) return;
                int row = table.rowAtPoint(e.getPoint());
                int col = table.columnAtPoint(e.getPoint());
                if (row >= 0 && col == 4) openItem.accept(rows.get(row).item);   // double-click the word count
            }
        });

        JButton open = new JButton("Open in Editor");
        open.setFocusable(false);
        open.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) openItem.accept(rows.get(row).item);
        });

        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        heading.setFont(heading.getFont().deriveFont(Font.BOLD));
        top.add(heading, BorderLayout.CENTER);
        top.add(open, BorderLayout.EAST);

        add(top, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
    }

    private static String flat(String s) {
        return s.replace('\n', ' ');
    }

    /** Shows the given items and, below them, all of their sub-documents. */
    public void show(Item container, List<Item> roots) {
        if (table.isEditing()) table.getCellEditor().stopCellEditing();
        rows.clear();
        for (Item it : roots) addRows(it, 0);
        heading.setText(container != null
                ? "Outliner \u2014 " + container.getTitle()
                : "Outliner \u2014 " + roots.size() + " selected item(s)");
        model.fireTableDataChanged();
    }

    private void addRows(Item it, int depth) {
        rows.add(new Row(it, depth));
        for (Item ch : it.items()) addRows(ch, depth + 1);
    }
}

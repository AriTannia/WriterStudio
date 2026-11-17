package writerstudio;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Line2D;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Relationship graph whose nodes are real Swing components, including avatars. */
public final class RelationshipPanel extends JPanel {
    private static final Color BACKGROUND = new Color(0xF5F0E7);
    private final Project project;
    private final Consumer<Item> openItem;
    private final JLabel heading = new JLabel("Relationships");
    private final GraphCanvas graph = new GraphCanvas();
    private final JButton zoomOut = new JButton("-");
    private final JButton zoomValue = new JButton("100%");
    private final JButton zoomIn = new JButton("+");
    private double zoom = 1.0;

    public RelationshipPanel(Project project, Consumer<Item> openItem) {
        super(new BorderLayout());
        this.project = project;
        this.openItem = openItem;
        setBackground(BACKGROUND);
        JPanel top = new JPanel(new BorderLayout());
        top.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        heading.setFont(heading.getFont().deriveFont(Font.BOLD));
        top.add(heading, BorderLayout.CENTER);
        JPanel zoomControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
        addZoomButton(zoomControls, zoomOut, "Zoom out", () -> setZoom(zoom - 0.1));
        addZoomButton(zoomControls, zoomValue, "Reset zoom", () -> setZoom(1.0));
        addZoomButton(zoomControls, zoomIn, "Zoom in", () -> setZoom(zoom + 0.1));
        top.add(zoomControls, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);
        add(new JScrollPane(graph), BorderLayout.CENTER);
    }

    private void addZoomButton(JPanel parent, JButton button, String tooltip, Runnable action) {
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.setMargin(new java.awt.Insets(2, 7, 2, 7));
        button.addActionListener(event -> action.run());
        parent.add(button);
    }

    private void setZoom(double value) {
        zoom = Math.max(0.7, Math.min(1.6, Math.round(value * 10.0) / 10.0));
        zoomValue.setText((int) Math.round(zoom * 100) + "%");
        zoomOut.setEnabled(zoom > 0.7);
        zoomIn.setEnabled(zoom < 1.6);
        graph.setCharacters(new ArrayList<>(graph.characters));
    }

    public void show(Item folder) {
        heading.setText("Relationships \u2014 " + folder.getTitle());
        graph.setCharacters(characters(folder));
    }

    private List<Item> characters(Item relationshipFolder) {
        List<Item> result = new ArrayList<>();
        for (Item item : relationshipFolder.items()) {
            if (item.getKind() == Item.Kind.TEXT
                    && item.getResearchType() == Item.ResearchType.CHARACTER) result.add(item);
        }
        return result;
    }

    private final class GraphCanvas extends JPanel {
        private final List<Item> characters = new ArrayList<>();
        private final Map<String, CharacterNode> nodes = new HashMap<>();

        GraphCanvas() {
            super(null);
            setBackground(BACKGROUND);
            setPreferredSize(new Dimension(900, 620));
        }

        void setCharacters(List<Item> items) {
            characters.clear();
            characters.addAll(items);
            nodes.clear();
            removeAll();
            for (Item item : characters) {
                CharacterNode node = new CharacterNode(item);
                nodes.put(item.getId(), node);
                add(node);
            }
            layoutNodes();
            revalidate();
            repaint();
        }

        private void layoutNodes() {
            int columns = Math.min(3, Math.max(1, characters.size()));
            int nodeWidth = scaled(270);
            int nodeHeight = scaled(116);
            int gapX = scaled(90);
            int gapY = scaled(110);
            int rows = Math.max(1, (characters.size() + columns - 1) / columns);
            int width = Math.max(scaled(900), columns * nodeWidth + (columns - 1) * gapX + scaled(80));
            int height = Math.max(scaled(620), rows * nodeHeight + (rows - 1) * gapY + scaled(100));
            setPreferredSize(new Dimension(width, height));
            for (int i = 0; i < characters.size(); i++) {
                int row = i / columns;
                int column = i % columns;
                int x = scaled(40) + column * (nodeWidth + gapX);
                int y = scaled(40) + row * (nodeHeight + gapY);
                nodes.get(characters.get(i).getId()).setBounds(x, y, nodeWidth, nodeHeight);
            }
        }

        private int scaled(int value) {
            return Math.max(1, (int) Math.round(value * zoom));
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setStroke(new BasicStroke(2f));
            Map<String, Item> byId = new HashMap<>();
            for (Item item : characters) byId.put(item.getId(), item);
            for (RelationshipLink link : project.relationships()) {
                if (!byId.containsKey(link.getFromId()) || !byId.containsKey(link.getToId())) continue;
                CharacterNode from = nodes.get(link.getFromId());
                CharacterNode to = nodes.get(link.getToId());
                int fromCenterX = from.getX() + from.getWidth() / 2;
                int fromCenterY = from.getY() + from.getHeight() / 2;
                int toCenterX = to.getX() + to.getWidth() / 2;
                int toCenterY = to.getY() + to.getHeight() / 2;
                double dx = toCenterX - fromCenterX;
                double dy = toCenterY - fromCenterY;
                double scaleFrom = Math.min(from.getWidth() / 2.0 / Math.max(1, Math.abs(dx)),
                        from.getHeight() / 2.0 / Math.max(1, Math.abs(dy)));
                double scaleTo = Math.min(to.getWidth() / 2.0 / Math.max(1, Math.abs(dx)),
                        to.getHeight() / 2.0 / Math.max(1, Math.abs(dy)));
                int x1 = (int) (fromCenterX + dx * scaleFrom);
                int y1 = (int) (fromCenterY + dy * scaleFrom);
                int x2 = (int) (toCenterX - dx * scaleTo);
                int y2 = (int) (toCenterY - dy * scaleTo);
                g.setColor(new Color(0x8B6F58));
                g.draw(new Line2D.Double(x1, y1, x2, y2));
                String relationship = link.getType().trim();
                if (!relationship.isEmpty()) {
                    int labelX = (x1 + x2) / 2;
                    int labelY = (y1 + y2) / 2;
                    java.awt.FontMetrics metrics = g.getFontMetrics();
                    int labelWidth = metrics.stringWidth(relationship);
                    g.setColor(BACKGROUND);
                    g.fillRect(labelX - labelWidth / 2 - 5, labelY - metrics.getAscent(),
                            labelWidth + 10, metrics.getHeight());
                    g.setColor(new Color(0x5C4B3E));
                    g.drawString(relationship, labelX - labelWidth / 2, labelY);
                }
            }
            if (characters.isEmpty()) {
                g.setColor(new Color(0x6E6258));
                g.drawString("Create Character items inside this folder to build the graph.", 24, 32);
            }
            g.dispose();
        }
    }

    private final class CharacterNode extends JPanel {
        CharacterNode(Item item) {
            super(new BorderLayout(10, 0));
            setBackground(new Color(0xFFFBEA));
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(0x8E5BD0), 2),
                    BorderFactory.createEmptyBorder(scaled(10), scaled(10), scaled(10), scaled(10))));
            JLabel avatar = avatar(item);
            add(avatar, BorderLayout.WEST);

            JPanel details = new JPanel(new BorderLayout(0, 4));
            details.setOpaque(false);
            JLabel title = new JLabel(displayTitle(item));
            title.setFont(title.getFont().deriveFont(Font.BOLD, scaledFont(15f)));
            JLabel classAndRoles = new JLabel(roleText(item));
            classAndRoles.setForeground(new Color(0x6E6258));
            details.add(title, BorderLayout.CENTER);
            details.add(classAndRoles, BorderLayout.SOUTH);
            add(details, BorderLayout.CENTER);
            setToolTipText("Double-click to open character");
            installOpenHandler(this, item);
        }

        private JLabel avatar(Item item) {
            JLabel avatar = new JLabel();
            avatar.setHorizontalAlignment(JLabel.CENTER);
            avatar.setPreferredSize(new Dimension(scaled(72), scaled(92)));
            File file = new File(project.getDir(), item.getPrimaryImageFile());
            if (file.isFile()) {
                ImageIcon source = new ImageIcon(file.getAbsolutePath());
                if (source.getIconWidth() > 0 && source.getIconHeight() > 0) {
                    double scale = Math.min(scaled(68) / (double) source.getIconWidth(),
                            scaled(82) / (double) source.getIconHeight());
                    avatar.setIcon(new ImageIcon(source.getImage().getScaledInstance(
                            Math.max(1, (int) (source.getIconWidth() * scale)),
                            Math.max(1, (int) (source.getIconHeight() * scale)),
                            java.awt.Image.SCALE_SMOOTH)));
                }
            } else {
                avatar.setText("No image");
            }
            avatar.setBorder(BorderFactory.createLineBorder(new Color(0xD6C9B0)));
            return avatar;
        }

        private String roleText(Item item) {
            if (item.getRoles().isEmpty()) return item.getCharacterClass().toString();
            return item.getCharacterClass() + " \u00b7 " + String.join(", ", item.getRoles());
        }

        private int scaled(int value) {
            return Math.max(1, (int) Math.round(value * zoom));
        }

        private float scaledFont(float value) {
            return Math.max(8f, (float) (value * zoom));
        }

        private String displayTitle(Item item) {
            String title = item.getTitle();
            if (title.regionMatches(true, 0, "Nhân vật:", 0, "Nhân vật:".length())) {
                return title.substring("Nhân vật:".length()).trim();
            }
            return title;
        }

        private void installOpenHandler(Component component, Item item) {
            component.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent event) {
                    if (event.getClickCount() == 2) openItem.accept(item);
                }
            });
            if (component instanceof JPanel) {
                for (Component child : ((JPanel) component).getComponents()) {
                    installOpenHandler(child, item);
                }
            }
        }
    }
}

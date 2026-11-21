package writerstudio;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JComboBox;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.JTextField;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.Shape;
import java.awt.Point;
import java.awt.geom.Line2D;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final JToggleButton editGraph = new JToggleButton("Edit graph");
    private String currentFolderTitle = "";
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
        editGraph.setFocusable(false);
        editGraph.setToolTipText("Drag cards and line endpoints; double-click a line to add a bend point; right-click a line to edit or delete it.");
        editGraph.addActionListener(event -> {
            graph.setEditMode(editGraph.isSelected());
            updateHeading();
        });
        zoomControls.add(editGraph);
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
        currentFolderTitle = folder.getTitle();
        updateHeading();
        graph.setCharacters(characters(folder));
    }

    private void updateHeading() {
        heading.setText("Relationships \u2014 " + currentFolderTitle
                + (editGraph.isSelected() ? "  |  Drag cards/endpoints; double-click lines to add bends; right-click lines to edit or delete." : ""));
        heading.setToolTipText(editGraph.isSelected()
                ? "Drag cards and edit relationship lines. Changes are saved with the project." : null);
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
        private final Map<String, Shape> edgeHitAreas = new HashMap<>();
        private final Map<String, double[]> edgeEndpoints = new HashMap<>();
        private final Map<String, double[]> edgeHandlePositions = new HashMap<>();
        private RelationshipLink selectedLink;
        private RelationshipLink draggingRouteLink;
        private int draggingRoutePoint = -1;
        private int draggingEndpoint = -1;
        private boolean editMode;
        private Point dragOffset;
        private CharacterNode draggingNode;
        private boolean nodeMoved;

        GraphCanvas() {
            super(null);
            setBackground(BACKGROUND);
            setPreferredSize(new Dimension(900, 620));
            setToolTipText("Enable Edit graph to move cards and customize relationship lines.");
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent event) {
                    if (SwingUtilities.isRightMouseButton(event)) {
                        int handle = editMode ? waypointAt(event.getPoint(), selectedLink) : -1;
                        if (handle >= 0) {
                            List<Point> points = selectedLink.getRoutePoints();
                            points.remove(handle);
                            selectedLink.setRoutePoints(points);
                            project.markDirty();
                            repaint();
                            return;
                        }
                        selectedLink = linkAt(event.getPoint());
                        repaint();
                        if (selectedLink != null) showRelationshipMenu(event, selectedLink);
                        return;
                    }
                    if (!editMode) return;
                    int endpoint = endpointAt(event.getPoint(), selectedLink);
                    if (endpoint >= 0) {
                        draggingEndpoint = endpoint;
                        return;
                    }
                    int handle = waypointAt(event.getPoint(), selectedLink);
                    if (handle >= 0) {
                        draggingRouteLink = selectedLink;
                        draggingRoutePoint = handle;
                        return;
                    }
                    selectedLink = linkAt(event.getPoint());
                    repaint();
                }

                @Override public void mouseReleased(MouseEvent event) {
                    if (draggingRouteLink != null) project.markDirty();
                    draggingRouteLink = null;
                    draggingRoutePoint = -1;
                    draggingEndpoint = -1;
                }

                @Override public void mouseClicked(MouseEvent event) {
                    if (!editMode || event.getClickCount() != 2 || selectedLink == null) return;
                    Shape hit = edgeHitAreas.get(selectedLink.getId());
                    if (hit == null || !hit.contains(event.getPoint())) return;
                    List<Point> points = selectedLink.getRoutePoints();
                    points.add(new Point((int) Math.round(event.getX() / zoom),
                            (int) Math.round(event.getY() / zoom)));
                    selectedLink.setRoutePoints(points);
                    project.markDirty();
                    repaint();
                }
            });
            addMouseMotionListener(new MouseAdapter() {
                @Override public void mouseDragged(MouseEvent event) {
                    if (draggingEndpoint >= 0 && selectedLink != null) {
                        CharacterNode node = nodes.get(draggingEndpoint == 0
                                ? selectedLink.getFromId() : selectedLink.getToId());
                        if (node == null) return;
                        Point anchor = normalizedBoundaryAnchor(node, event.getPoint());
                        if (draggingEndpoint == 0) selectedLink.setFromAnchor(anchor);
                        else selectedLink.setToAnchor(anchor);
                        project.markDirty();
                        repaint();
                        return;
                    }
                    if (draggingRouteLink == null || draggingRoutePoint < 0) return;
                    List<Point> points = draggingRouteLink.getRoutePoints();
                    if (draggingRoutePoint >= points.size()) return;
                    points.set(draggingRoutePoint, new Point((int) Math.round(event.getX() / zoom),
                            (int) Math.round(event.getY() / zoom)));
                    draggingRouteLink.setRoutePoints(points);
                    repaint();
                }
            });
        }

        void setEditMode(boolean enabled) {
            editMode = enabled;
            if (!enabled) {
                selectedLink = null;
                draggingRouteLink = null;
                draggingRoutePoint = -1;
                draggingEndpoint = -1;
            }
            repaint();
        }

        void setCharacters(List<Item> items) {
            characters.clear();
            characters.addAll(items);
            selectedLink = null;
            edgeHitAreas.clear();
            edgeEndpoints.clear();
            edgeHandlePositions.clear();
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
            int nodeWidth = scaled(270);
            int nodeHeight = scaled(116);
            int count = characters.size();
            if (count == 0) {
                setPreferredSize(new Dimension(scaled(900), scaled(620)));
                return;
            }

            int columns = Math.max(1, (int) Math.ceil(Math.sqrt(count * 1.4)));
            int rows = (count + columns - 1) / columns;
            int width = Math.max(scaled(900), columns * (nodeWidth + scaled(150)) + scaled(80));
            int height = Math.max(scaled(620), rows * (nodeHeight + scaled(150)) + scaled(100));
            setPreferredSize(new Dimension(width, height));

            // Stable ID order makes the layout independent of Binder order.
            List<Item> layoutItems = new ArrayList<>(characters);
            layoutItems.sort(Comparator.comparing(Item::getId));
            int n = layoutItems.size();
            double[] x = new double[n];
            double[] y = new double[n];
            Map<String, Integer> indexById = new HashMap<>();
            for (int i = 0; i < n; i++) indexById.put(layoutItems.get(i).getId(), i);

            double centerX = width / 2.0;
            double centerY = height / 2.0;
            double radiusX = Math.max(scaled(40), (width - nodeWidth - scaled(100)) * 0.34);
            double radiusY = Math.max(scaled(40), (height - nodeHeight - scaled(100)) * 0.34);
            for (int i = 0; i < n; i++) {
                long hash = layoutItems.get(i).getId().hashCode() & 0xffffffffL;
                double angle = (hash % 100000) / 100000.0 * Math.PI * 2;
                x[i] = centerX + Math.cos(angle) * radiusX;
                y[i] = centerY + Math.sin(angle) * radiusY;
            }

            Set<String> connectedPairs = new HashSet<>();
            for (RelationshipLink link : project.relationships()) {
                Integer from = indexById.get(link.getFromId());
                Integer to = indexById.get(link.getToId());
                if (from == null || to == null || from.equals(to)) continue;
                String first = link.getFromId().compareTo(link.getToId()) < 0
                        ? link.getFromId() : link.getToId();
                String second = first.equals(link.getFromId()) ? link.getToId() : link.getFromId();
                connectedPairs.add(first + "\u0000" + second);
            }

            double idealDistance = nodeWidth + scaled(105);
            double repulsion = idealDistance * idealDistance;
            double[] fx = new double[n];
            double[] fy = new double[n];
            for (int iteration = 0; iteration < 420; iteration++) {
                Arrays.fill(fx, 0);
                Arrays.fill(fy, 0);
                for (int i = 0; i < n; i++) {
                    for (int j = i + 1; j < n; j++) {
                        double dx = x[i] - x[j];
                        double dy = y[i] - y[j];
                        double distance = Math.max(1.0, Math.hypot(dx, dy));
                        double ux = dx / distance;
                        double uy = dy / distance;
                        double push = repulsion / distance;
                        double overlapX = nodeWidth + scaled(36) - Math.abs(dx);
                        double overlapY = nodeHeight + scaled(36) - Math.abs(dy);
                        if (overlapX > 0 && overlapY > 0) {
                            push += Math.min(overlapX, overlapY) * 1.8;
                        }
                        fx[i] += ux * push;
                        fy[i] += uy * push;
                        fx[j] -= ux * push;
                        fy[j] -= uy * push;

                        String first = layoutItems.get(i).getId().compareTo(layoutItems.get(j).getId()) < 0
                                ? layoutItems.get(i).getId() : layoutItems.get(j).getId();
                        String second = first.equals(layoutItems.get(i).getId())
                                ? layoutItems.get(j).getId() : layoutItems.get(i).getId();
                        if (connectedPairs.contains(first + "\u0000" + second)) {
                            double pull = (distance - idealDistance) * 0.018;
                            fx[i] -= ux * pull;
                            fy[i] -= uy * pull;
                            fx[j] += ux * pull;
                            fy[j] += uy * pull;
                        }
                    }
                }
                double temperature = scaled(15) * (1.0 - iteration / 420.0) + 0.5;
                for (int i = 0; i < n; i++) {
                    double force = Math.max(0.001, Math.hypot(fx[i], fy[i]));
                    double step = Math.min(temperature, force * 0.025);
                    x[i] += fx[i] / force * step;
                    y[i] += fy[i] / force * step;
                }
            }

            double minX = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < n; i++) {
                minX = Math.min(minX, x[i]);
                maxX = Math.max(maxX, x[i]);
                minY = Math.min(minY, y[i]);
                maxY = Math.max(maxY, y[i]);
            }
            double availableWidth = width - nodeWidth - scaled(80);
            double availableHeight = height - nodeHeight - scaled(80);
            double spanX = Math.max(1, maxX - minX);
            double spanY = Math.max(1, maxY - minY);
            double fit = Math.min(1.0, Math.min(availableWidth / spanX, availableHeight / spanY));
            double offsetX = (width - nodeWidth - spanX * fit) / 2.0;
            double offsetY = (height - nodeHeight - spanY * fit) / 2.0;
            for (int i = 0; i < n; i++) {
                int left = (int) Math.round((x[i] - minX) * fit + offsetX);
                int top = (int) Math.round((y[i] - minY) * fit + offsetY);
                Point saved = project.relationshipNodePosition(layoutItems.get(i).getId());
                if (saved != null) {
                    left = (int) Math.round(saved.x * zoom);
                    top = (int) Math.round(saved.y * zoom);
                }
                nodes.get(layoutItems.get(i).getId()).setBounds(left, top, nodeWidth, nodeHeight);
            }
        }

        private void saveNodePositions() {
            for (Map.Entry<String, CharacterNode> entry : nodes.entrySet()) {
                CharacterNode node = entry.getValue();
                project.setRelationshipNodePosition(entry.getKey(), new Point(
                        (int) Math.round(node.getX() / zoom), (int) Math.round(node.getY() / zoom)));
            }
        }

        private int scaled(int value) {
            return Math.max(1, (int) Math.round(value * zoom));
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            edgeHitAreas.clear();
            edgeEndpoints.clear();
            edgeHandlePositions.clear();
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Map<String, Item> byId = new HashMap<>();
            for (Item item : characters) byId.put(item.getId(), item);
            List<RelationshipLink> visibleLinks = new ArrayList<>();
            Map<String, List<RelationshipLink>> linksByPair = new HashMap<>();
            List<Rectangle> placedLabels = new ArrayList<>();
            List<LabelRequest> labelRequests = new ArrayList<>();
            for (RelationshipLink link : project.relationships()) {
                if (!byId.containsKey(link.getFromId()) || !byId.containsKey(link.getToId())) continue;
                visibleLinks.add(link);
                linksByPair.computeIfAbsent(pairKey(link), unused -> new ArrayList<>()).add(link);
            }
            Map<String, Double> pairLanes = pairLanes(linksByPair);
            Map<String, Double> outgoingFan = fanOffsets(visibleLinks, true);
            Map<String, Double> incomingFan = fanOffsets(visibleLinks, false);
            for (RelationshipLink link : visibleLinks) {
                CharacterNode from = nodes.get(link.getFromId());
                CharacterNode to = nodes.get(link.getToId());
                if (from == null || to == null) continue;
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
                double length = Math.max(1.0, Math.hypot(dx, dy));
                double normalX = -dy / length;
                double normalY = dx / length;
                double lane = pairLanes.getOrDefault(edgeKey(link), 0.0);
                Point fromAnchor = link.getFromAnchor();
                Point toAnchor = link.getToAnchor();
                if (fromAnchor == null) {
                    x1 += (int) Math.round(normalX * lane);
                    y1 += (int) Math.round(normalY * lane);
                } else {
                    x1 = from.getX() + (int) Math.round(from.getWidth() * fromAnchor.x / 10000.0);
                    y1 = from.getY() + (int) Math.round(from.getHeight() * fromAnchor.y / 10000.0);
                }
                if (toAnchor == null) {
                    x2 += (int) Math.round(normalX * lane);
                    y2 += (int) Math.round(normalY * lane);
                } else {
                    x2 = to.getX() + (int) Math.round(to.getWidth() * toAnchor.x / 10000.0);
                    y2 = to.getY() + (int) Math.round(to.getHeight() * toAnchor.y / 10000.0);
                }
                double startFan = outgoingFan.getOrDefault(directedKey(link), 0.0);
                double endFan = incomingFan.getOrDefault(directedKey(link), 0.0);
                double c1x = x1 + dx * 0.34 + normalX * startFan;
                double c1y = y1 + dy * 0.34 + normalY * startFan;
                double c2x = x2 - dx * 0.34 + normalX * endFan;
                double c2y = y2 - dy * 0.34 + normalY * endFan;
                double tangentHandle = Math.max(35 * zoom, Math.min(length * 0.3, 120 * zoom));
                if (fromAnchor != null) {
                    double[] outward = anchorOutwardNormal(fromAnchor);
                    c1x = x1 + outward[0] * tangentHandle;
                    c1y = y1 + outward[1] * tangentHandle;
                }
                if (toAnchor != null) {
                    double[] outward = anchorOutwardNormal(toAnchor);
                    c2x = x2 + outward[0] * tangentHandle;
                    c2y = y2 + outward[1] * tangentHandle;
                }
                Path2D edge = new Path2D.Double();
                edge.moveTo(x1, y1);
                int arrowTipX;
                int arrowTipY;
                double ux;
                double uy;
                double startUx;
                double startUy;
                List<Point2D.Double> manualRoute = new ArrayList<>();
                manualRoute.add(new Point2D.Double(x1, y1));
                for (Point point : link.getRoutePoints()) {
                    Point2D.Double scaledPoint = new Point2D.Double(point.x * zoom, point.y * zoom);
                    manualRoute.add(scaledPoint);
                }
                manualRoute.add(new Point2D.Double(x2, y2));
                if (link.getRoutePoints().isEmpty()) {
                    double tangentX = x2 - c2x;
                    double tangentY = y2 - c2y;
                    double tangentLength = Math.max(1.0, Math.hypot(tangentX, tangentY));
                    ux = tangentX / tangentLength;
                    uy = tangentY / tangentLength;
                    double startLength = Math.max(1.0, Math.hypot(c1x - x1, c1y - y1));
                    startUx = (c1x - x1) / startLength;
                    startUy = (c1y - y1) / startLength;
                    arrowTipX = (int) Math.round(x2 - ux * 11 * zoom);
                    arrowTipY = (int) Math.round(y2 - uy * 11 * zoom);
                    if (link.getArrowDirection() == RelationshipLink.ArrowDirection.RECEIVED) {
                        edge.curveTo(c1x, c1y, c2x, c2y, x2, y2);
                    } else {
                        edge.curveTo(c1x, c1y, c2x, c2y, arrowTipX, arrowTipY);
                    }
                } else {
                    Point2D.Double beforeTarget = manualRoute.get(manualRoute.size() - 2);
                    double tangentX = x2 - beforeTarget.x;
                    double tangentY = y2 - beforeTarget.y;
                    double tangentLength = Math.max(1.0, Math.hypot(tangentX, tangentY));
                    ux = tangentX / tangentLength;
                    uy = tangentY / tangentLength;
                    Point2D.Double afterSource = manualRoute.get(1);
                    double startLength = Math.max(1.0, Math.hypot(afterSource.x - x1, afterSource.y - y1));
                    startUx = (afterSource.x - x1) / startLength;
                    startUy = (afterSource.y - y1) / startLength;
                    arrowTipX = (int) Math.round(x2 - ux * 11 * zoom);
                    arrowTipY = (int) Math.round(y2 - uy * 11 * zoom);
                    for (int i = 1; i < manualRoute.size() - 1; i++) {
                        edge.lineTo(manualRoute.get(i).x, manualRoute.get(i).y);
                    }
                    if (link.getArrowDirection() == RelationshipLink.ArrowDirection.RECEIVED) edge.lineTo(x2, y2);
                    else edge.lineTo(arrowTipX, arrowTipY);
                }
                edgeEndpoints.put(link.getId(), new double[] {x1, y1, x2, y2});
                edgeHandlePositions.put(link.getId(), new double[] {
                        x1 + startUx * 14 * zoom, y1 + startUy * 14 * zoom,
                        x2 - ux * 14 * zoom, y2 - uy * 14 * zoom});
                boolean selected = selectedLink != null && selectedLink.getId().equals(link.getId());
                g.setStroke(new BasicStroke(selected ? 3.8f : 2.6f,
                        BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(selected ? new Color(0x7B3FB3) : new Color(0x435D83));
                g.draw(edge);
                edgeHitAreas.put(link.getId(), new BasicStroke(Math.max(12f, 14f * (float) zoom),
                        BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(edge));
                if (link.getArrowDirection() != RelationshipLink.ArrowDirection.RECEIVED) {
                    drawArrowHead(g, arrowTipX, arrowTipY, ux, uy);
                }
                if (link.getArrowDirection() != RelationshipLink.ArrowDirection.TO) {
                    int sourceTipX = (int) Math.round(x1 + startUx * 11 * zoom);
                    int sourceTipY = (int) Math.round(y1 + startUy * 11 * zoom);
                    drawArrowHead(g, sourceTipX, sourceTipY, -startUx, -startUy);
                }
                String relationship = link.getType().trim();
                if (!relationship.isEmpty()) {
                    double[] labelStart;
                    double[] labelEnd;
                    if (link.getRoutePoints().isEmpty()) {
                        labelStart = cubicPoint(x1, y1, c1x, c1y, c2x, c2y,
                                arrowTipX, arrowTipY, 0.25);
                        labelEnd = cubicPoint(x1, y1, c1x, c1y, c2x, c2y,
                                arrowTipX, arrowTipY, 0.75);
                    } else {
                        labelStart = pointOnRoute(manualRoute, 0.38);
                        labelEnd = pointOnRoute(manualRoute, 0.62);
                    }
                    labelRequests.add(new LabelRequest(link.getId(), relationship,
                            (int) Math.round(labelStart[0]), (int) Math.round(labelStart[1]),
                            (int) Math.round(labelEnd[0]), (int) Math.round(labelEnd[1])));
                }
            }
            for (LabelRequest request : labelRequests) {
                Rectangle labelBounds = drawLinkLabel(g, request.text, request.x1, request.y1, request.x2, request.y2,
                        nodes.values(), placedLabels);
                if (labelBounds != null) {
                    Shape currentHit = edgeHitAreas.get(request.linkId);
                    Area expandedHit = new Area(currentHit);
                    expandedHit.add(new Area(labelBounds));
                    edgeHitAreas.put(request.linkId, expandedHit);
                }
            }
            if (editMode && selectedLink != null) {
                double[] endpoints = edgeEndpoints.get(selectedLink.getId());
                double[] handles = edgeHandlePositions.get(selectedLink.getId());
                if (endpoints != null && handles != null) {
                    drawEndpointHandle(g, handles[0], handles[1], new Color(0x29865A));
                    drawEndpointHandle(g, handles[2], handles[3], new Color(0xB34A3F));
                }
                for (Point point : selectedLink.getRoutePoints()) {
                    double x = point.x * zoom;
                    double y = point.y * zoom;
                    Ellipse2D handle = new Ellipse2D.Double(x - 6 * zoom, y - 6 * zoom,
                            12 * zoom, 12 * zoom);
                    g.setColor(new Color(0xFFFFFF));
                    g.fill(handle);
                    g.setColor(new Color(0x7B3FB3));
                    g.setStroke(new BasicStroke(2f));
                    g.draw(handle);
                }
            }
            if (characters.isEmpty()) {
                g.setColor(new Color(0x6E6258));
                g.drawString("Create Character items inside this folder to build the graph.", 24, 32);
            }
            g.dispose();
        }

        private final class LabelRequest {
            final String linkId;
            final String text;
            final int x1;
            final int y1;
            final int x2;
            final int y2;

            LabelRequest(String linkId, String text, int x1, int y1, int x2, int y2) {
                this.linkId = linkId;
                this.text = text;
                this.x1 = x1;
                this.y1 = y1;
                this.x2 = x2;
                this.y2 = y2;
            }
        }

        private String pairKey(RelationshipLink link) {
            String first = link.getFromId();
            String second = link.getToId();
            return first.compareTo(second) < 0 ? first + "\u0000" + second : second + "\u0000" + first;
        }

        private String directedKey(RelationshipLink link) {
            return edgeKey(link);
        }

        private String edgeKey(RelationshipLink link) {
            return link.getFromId() + "\u0000" + link.getToId() + "\u0000" + link.getType();
        }

        private Map<String, Double> pairLanes(Map<String, List<RelationshipLink>> linksByPair) {
            Map<String, Double> lanes = new HashMap<>();
            for (Map.Entry<String, List<RelationshipLink>> entry : linksByPair.entrySet()) {
                List<RelationshipLink> group = entry.getValue();
                group.sort((a, b) -> edgeKey(a).compareTo(edgeKey(b)));
                String canonicalFirst = group.get(0).getFromId().compareTo(group.get(0).getToId()) < 0
                        ? group.get(0).getFromId() : group.get(0).getToId();
                for (int i = 0; i < group.size(); i++) {
                    RelationshipLink link = group.get(i);
                    double lane = (i - (group.size() - 1) / 2.0) * 18 * zoom;
                    if (!canonicalFirst.equals(link.getFromId())) lane = -lane;
                    lanes.put(edgeKey(link), lane);
                }
            }
            return lanes;
        }

        private Map<String, Double> fanOffsets(List<RelationshipLink> links, boolean outgoing) {
            Map<String, List<RelationshipLink>> grouped = new HashMap<>();
            for (RelationshipLink link : links) {
                String endpoint = outgoing ? link.getFromId() : link.getToId();
                grouped.computeIfAbsent(endpoint, unused -> new ArrayList<>()).add(link);
            }
            Map<String, Double> offsets = new HashMap<>();
            for (List<RelationshipLink> group : grouped.values()) {
                group.sort((a, b) -> {
                    String aOther = outgoing ? a.getToId() : a.getFromId();
                    String bOther = outgoing ? b.getToId() : b.getFromId();
                    int byOther = aOther.compareTo(bOther);
                    return byOther != 0 ? byOther : edgeKey(a).compareTo(edgeKey(b));
                });
                for (int i = 0; i < group.size(); i++) {
                    double lane = (i - (group.size() - 1) / 2.0) * 42 * zoom;
                    offsets.put(directedKey(group.get(i)), lane);
                }
            }
            return offsets;
        }

        private double[] cubicPoint(double x1, double y1, double x2, double y2,
                                    double x3, double y3, double x4, double y4, double t) {
            double u = 1 - t;
            double uu = u * u;
            double tt = t * t;
            double x = uu * u * x1 + 3 * uu * t * x2 + 3 * u * tt * x3 + tt * t * x4;
            double y = uu * u * y1 + 3 * uu * t * y2 + 3 * u * tt * y3 + tt * t * y4;
            return new double[] {x, y};
        }

        private double[] pointOnRoute(List<Point2D.Double> points, double fraction) {
            double total = 0;
            for (int i = 1; i < points.size(); i++) {
                total += points.get(i - 1).distance(points.get(i));
            }
            if (total <= 0) return new double[] {points.get(0).x, points.get(0).y};
            double target = total * fraction;
            double traversed = 0;
            for (int i = 1; i < points.size(); i++) {
                Point2D.Double start = points.get(i - 1);
                Point2D.Double end = points.get(i);
                double segment = start.distance(end);
                if (traversed + segment >= target) {
                    double along = (target - traversed) / Math.max(1, segment);
                    return new double[] {start.x + (end.x - start.x) * along,
                            start.y + (end.y - start.y) * along};
                }
                traversed += segment;
            }
            Point2D.Double last = points.get(points.size() - 1);
            return new double[] {last.x, last.y};
        }

        private void drawArrowHead(Graphics2D g, int tipX, int tipY, double ux, double uy) {
            double size = 11 * zoom;
            double baseX = tipX - ux * size;
            double baseY = tipY - uy * size;
            double wingX = -uy * size * 0.58;
            double wingY = ux * size * 0.58;
            Path2D arrow = new Path2D.Double();
            arrow.moveTo(tipX, tipY);
            arrow.lineTo(baseX + wingX, baseY + wingY);
            arrow.lineTo(baseX - wingX, baseY - wingY);
            arrow.closePath();
            g.fill(arrow);
        }

        private void drawEndpointHandle(Graphics2D g, double x, double y, Color color) {
            double size = 13 * zoom;
            Ellipse2D handle = new Ellipse2D.Double(x - size / 2, y - size / 2, size, size);
            g.setColor(Color.WHITE);
            g.fill(handle);
            g.setColor(color);
            g.setStroke(new BasicStroke(2f));
            g.draw(handle);
        }

        private Rectangle drawLinkLabel(Graphics2D g, String text, int x1, int y1, int x2, int y2,
                                   java.util.Collection<CharacterNode> allNodes,
                                   List<Rectangle> placedLabels) {
            java.awt.FontMetrics metrics = g.getFontMetrics();
            int padX = (int) Math.round(7 * zoom);
            int padY = (int) Math.round(3 * zoom);
            double dx = x2 - x1;
            double dy = y2 - y1;
            double length = Math.max(1, Math.hypot(dx, dy));
            int maxTextWidth = Math.max(scaled(44),
                    Math.min(scaled(230), (int) length - padX * 2 - scaled(12)));
            List<String> lines = wrapLabel(text, metrics, maxTextWidth);
            int textWidth = 0;
            for (String line : lines) textWidth = Math.max(textWidth, metrics.stringWidth(line));
            int boxWidth = textWidth + padX * 2;
            int boxHeight = lines.size() * metrics.getHeight() + padY * 2;
            double normalX = -dy / length;
            double normalY = dx / length;
            double[] fractions = {0.50, 0.40, 0.60, 0.30, 0.70};
            int[] offsets = {0, -18, 18, -36, 36};
            Rectangle box = null;
            for (int offset : offsets) {
                for (double fraction : fractions) {
                    int centerX = (int) Math.round(x1 + dx * fraction + normalX * offset * zoom);
                    int centerY = (int) Math.round(y1 + dy * fraction + normalY * offset * zoom);
                    Rectangle candidate = new Rectangle(centerX - boxWidth / 2,
                            centerY - boxHeight / 2, boxWidth, boxHeight);
                    if (overlapsNode(candidate, allNodes) || overlapsLabel(candidate, placedLabels)) continue;
                    box = candidate;
                    break;
                }
                if (box != null) break;
            }
            if (box == null) return null;
            placedLabels.add(box);
            g.setColor(new Color(0xFFFEFA));
            g.fill(new RoundRectangle2D.Double(box.x, box.y, box.width, box.height, 10 * zoom, 10 * zoom));
            g.setColor(new Color(0x435D83));
            g.setStroke(new BasicStroke(1f));
            g.draw(new RoundRectangle2D.Double(box.x, box.y, box.width, box.height, 10 * zoom, 10 * zoom));
            g.setColor(new Color(0x263B5B));
            int baseline = box.y + padY + metrics.getAscent();
            for (String line : lines) {
                g.drawString(line, box.x + (box.width - metrics.stringWidth(line)) / 2, baseline);
                baseline += metrics.getHeight();
            }
            return box;
        }

        private List<String> wrapLabel(String text, java.awt.FontMetrics metrics, int maxWidth) {
            List<String> lines = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : text.trim().split("\\s+")) {
                String candidate = line.length() == 0 ? word : line + " " + word;
                if (line.length() > 0 && metrics.stringWidth(candidate) <= maxWidth) {
                    line.append(' ').append(word);
                    continue;
                }
                if (line.length() > 0) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                for (int i = 0; i < word.length(); i++) {
                    if (metrics.stringWidth(line.toString() + word.charAt(i)) > maxWidth && line.length() > 0) {
                        lines.add(line.toString());
                        line.setLength(0);
                    }
                    line.append(word.charAt(i));
                }
            }
            if (line.length() > 0) lines.add(line.toString());
            return lines;
        }

        private boolean overlapsNode(Rectangle label, java.util.Collection<CharacterNode> allNodes) {
            Rectangle expanded = new Rectangle(label.x - scaled(3), label.y - scaled(3),
                    label.width + scaled(6), label.height + scaled(6));
            for (CharacterNode node : allNodes) {
                if (expanded.intersects(node.getBounds())) return true;
            }
            return false;
        }

        private boolean overlapsLabel(Rectangle candidate, List<Rectangle> placedLabels) {
            Rectangle expanded = new Rectangle(candidate.x - scaled(6), candidate.y - scaled(4),
                    candidate.width + scaled(12), candidate.height + scaled(8));
            for (Rectangle other : placedLabels) if (expanded.intersects(other)) return true;
            return false;
        }

        private int waypointAt(Point point, RelationshipLink link) {
            if (link == null) return -1;
            List<Point> points = link.getRoutePoints();
            for (int i = 0; i < points.size(); i++) {
                double x = points.get(i).x * zoom;
                double y = points.get(i).y * zoom;
                if (Point.distance(point.x, point.y, x, y) <= 11 * zoom) return i;
            }
            return -1;
        }

        private int endpointAt(Point point, RelationshipLink link) {
            if (link == null) return -1;
            double[] handles = edgeHandlePositions.get(link.getId());
            if (handles == null) return -1;
            double sourceDistance = Point.distance(point.x, point.y, handles[0], handles[1]);
            double targetDistance = Point.distance(point.x, point.y, handles[2], handles[3]);
            double threshold = 12 * zoom;
            if (sourceDistance <= threshold || targetDistance <= threshold) {
                return sourceDistance <= targetDistance ? 0 : 1;
            }
            return -1;
        }

        private Point normalizedBoundaryAnchor(CharacterNode node, Point point) {
            double left = node.getX();
            double right = node.getX() + node.getWidth();
            double top = node.getY();
            double bottom = node.getY() + node.getHeight();
            double x = Math.max(left, Math.min(right, point.x));
            double y = Math.max(top, Math.min(bottom, point.y));
            double[] distances = {Math.abs(x - left), Math.abs(right - x),
                    Math.abs(y - top), Math.abs(bottom - y)};
            int side = 0;
            for (int i = 1; i < distances.length; i++) if (distances[i] < distances[side]) side = i;
            if (side == 0) x = left;
            else if (side == 1) x = right;
            else if (side == 2) y = top;
            else y = bottom;
            return new Point((int) Math.round((x - left) * 10000.0 / node.getWidth()),
                    (int) Math.round((y - top) * 10000.0 / node.getHeight()));
        }

        private double[] anchorOutwardNormal(Point anchor) {
            if (anchor.x <= 0) return new double[] {-1, 0};
            if (anchor.x >= 10000) return new double[] {1, 0};
            if (anchor.y <= 0) return new double[] {0, -1};
            return new double[] {0, 1};
        }

        private void showRelationshipMenu(MouseEvent event, RelationshipLink link) {
            JPopupMenu menu = new JPopupMenu();
            JMenuItem edit = new JMenuItem("Edit relationship...");
            edit.addActionListener(action -> editRelationship(link));
            menu.add(edit);
            JMenuItem delete = new JMenuItem("Delete relationship");
            delete.addActionListener(action -> {
                if (JOptionPane.showConfirmDialog(RelationshipPanel.this,
                        "Delete relationship \"" + link.getType() + "\"?", "Delete relationship",
                        JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                    project.removeRelationship(link);
                    if (selectedLink != null && selectedLink.getId().equals(link.getId())) selectedLink = null;
                    project.markDirty();
                    repaint();
                }
            });
            menu.add(delete);
            menu.show(this, event.getX(), event.getY());
        }

        private void editRelationship(RelationshipLink link) {
            JTextField name = new JTextField(link.getType());
            JComboBox<RelationshipLink.ArrowDirection> direction =
                    new JComboBox<>(RelationshipLink.ArrowDirection.values());
            direction.setSelectedItem(link.getArrowDirection());
            JPanel form = new JPanel(new java.awt.GridLayout(0, 1, 4, 4));
            form.add(new JLabel("Relationship name"));
            form.add(name);
            form.add(new JLabel("Type (arrow direction)"));
            form.add(direction);
            if (JOptionPane.showConfirmDialog(RelationshipPanel.this, form,
                    "Edit relationship", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            link.setType(name.getText().trim());
            link.setArrowDirection((RelationshipLink.ArrowDirection) direction.getSelectedItem());
            project.markDirty();
            repaint();
        }

        private RelationshipLink linkAt(Point point) {
            List<RelationshipLink> links = project.relationships();
            for (int i = links.size() - 1; i >= 0; i--) {
                RelationshipLink link = links.get(i);
                Shape hitArea = edgeHitAreas.get(link.getId());
                if (hitArea != null && hitArea.contains(point)) return link;
            }
            return null;
        }

        private void ensureContainsNodes() {
            int width = getPreferredSize().width;
            int height = getPreferredSize().height;
            for (CharacterNode node : nodes.values()) {
                width = Math.max(width, node.getX() + node.getWidth() + scaled(40));
                height = Math.max(height, node.getY() + node.getHeight() + scaled(40));
            }
            if (width != getPreferredSize().width || height != getPreferredSize().height) {
                setPreferredSize(new Dimension(width, height));
                revalidate();
            }
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
                    if (!graph.editMode && event.getClickCount() == 2) openItem.accept(item);
                }

                @Override public void mousePressed(MouseEvent event) {
                    if (!graph.editMode || !SwingUtilities.isLeftMouseButton(event)) return;
                    graph.draggingNode = graph.nodes.get(item.getId());
                    Point point = SwingUtilities.convertPoint(component, event.getPoint(), graph);
                    graph.dragOffset = new Point(point.x - graph.draggingNode.getX(), point.y - graph.draggingNode.getY());
                    graph.nodeMoved = false;
                    graph.selectedLink = null;
                    repaint();
                }

                @Override public void mouseReleased(MouseEvent event) {
                    if (graph.draggingNode == null) return;
                    if (graph.nodeMoved) {
                        graph.saveNodePositions();
                        project.markDirty();
                    }
                    graph.draggingNode = null;
                    graph.dragOffset = null;
                    graph.nodeMoved = false;
                }
            });
            component.addMouseMotionListener(new MouseAdapter() {
                @Override public void mouseDragged(MouseEvent event) {
                    if (!graph.editMode || graph.draggingNode == null || graph.dragOffset == null) return;
                    Point point = SwingUtilities.convertPoint(component, event.getPoint(), graph);
                    int x = Math.max(scaled(20), point.x - graph.dragOffset.x);
                    int y = Math.max(scaled(20), point.y - graph.dragOffset.y);
                    if (x != graph.draggingNode.getX() || y != graph.draggingNode.getY()) graph.nodeMoved = true;
                    graph.draggingNode.setLocation(x, y);
                    graph.ensureContainsNodes();
                    repaint();
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

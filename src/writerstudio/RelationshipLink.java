package writerstudio;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A directed, user-defined connection between two Character research items. */
public final class RelationshipLink {
    public enum ArrowDirection {
        RECEIVED("Received: arrow toward this card"),
        TO("To: arrow toward target card"),
        BOTH("Both: arrows at both ends");

        private final String label;
        ArrowDirection(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private final String id;
    private final String fromId;
    private final String toId;
    private String type;
    private String status;
    private String notes;
    private ArrowDirection arrowDirection = ArrowDirection.TO;
    private Point fromAnchor;
    private Point toAnchor;
    private final List<Point> routePoints = new ArrayList<>();

    public RelationshipLink(String fromId, String toId, String type, String status, String notes) {
        this(UUID.randomUUID().toString(), fromId, toId, type, status, notes);
    }

    public RelationshipLink(String id, String fromId, String toId, String type, String status, String notes) {
        this.id = id == null || id.isEmpty() ? UUID.randomUUID().toString() : id;
        this.fromId = fromId;
        this.toId = toId;
        this.type = type == null ? "Connected" : type;
        this.status = status == null ? "" : status;
        this.notes = notes == null ? "" : notes;
    }

    public String getId() { return id; }
    public String getFromId() { return fromId; }
    public String getToId() { return toId; }
    public String getType() { return type; }
    public String getStatus() { return status; }
    public String getNotes() { return notes; }
    public ArrowDirection getArrowDirection() { return arrowDirection; }
    public Point getFromAnchor() { return fromAnchor == null ? null : new Point(fromAnchor); }
    public Point getToAnchor() { return toAnchor == null ? null : new Point(toAnchor); }
    public List<Point> getRoutePoints() {
        List<Point> copy = new ArrayList<>();
        for (Point point : routePoints) copy.add(new Point(point));
        return copy;
    }
    public void setRoutePoints(List<Point> points) {
        routePoints.clear();
        if (points != null) for (Point point : points) {
            if (point != null) routePoints.add(new Point(point));
        }
    }
    public void setArrowDirection(ArrowDirection value) {
        arrowDirection = value == null ? ArrowDirection.TO : value;
    }
    public void setFromAnchor(Point value) { fromAnchor = value == null ? null : new Point(value); }
    public void setToAnchor(Point value) { toAnchor = value == null ? null : new Point(value); }
    public void setType(String value) { type = value == null ? "Connected" : value; }
    public void setStatus(String value) { status = value == null ? "" : value; }
    public void setNotes(String value) { notes = value == null ? "" : value; }
}

package writerstudio;

/** A directed, user-defined connection between two Character research items. */
public final class RelationshipLink {
    private final String fromId;
    private final String toId;
    private String type;
    private String status;
    private String notes;

    public RelationshipLink(String fromId, String toId, String type, String status, String notes) {
        this.fromId = fromId;
        this.toId = toId;
        this.type = type == null ? "Connected" : type;
        this.status = status == null ? "" : status;
        this.notes = notes == null ? "" : notes;
    }

    public String getFromId() { return fromId; }
    public String getToId() { return toId; }
    public String getType() { return type; }
    public String getStatus() { return status; }
    public String getNotes() { return notes; }
    public void setType(String value) { type = value == null ? "Connected" : value; }
    public void setStatus(String value) { status = value == null ? "" : value; }
    public void setNotes(String value) { notes = value == null ? "" : value; }
}

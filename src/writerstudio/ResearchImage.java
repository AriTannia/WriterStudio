package writerstudio;

/** An image attached to a Research card with a user-facing caption. */
public final class ResearchImage {
    private final String file;
    private final String caption;

    public ResearchImage(String file, String caption) {
        this.file = file == null ? "" : file;
        this.caption = caption == null ? "" : caption;
    }

    public String getFile() { return file; }
    public String getCaption() { return caption; }
}

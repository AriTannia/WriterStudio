package writerstudio;

import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.MutableTreeNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One entry in the Binder. Every item can hold text and a synopsis; folders
 * and text documents may have children, imported research files may not.
 */
public class Item extends DefaultMutableTreeNode {
    public enum Kind { FOLDER, TEXT, FILE }
    public enum ResearchType {
        NOTE("Note"), CHARACTER("Character"), LOCATION("Location"), ORGANIZATION("Organization"),
        EVENT("Event"), REFERENCE("Reference"), THREE_ACT_STRUCTURE("Three-Act Structure"),
        RELATIONSHIP("Relationship");

        private final String label;

        ResearchType(String label) { this.label = label; }
        @Override public String toString() { return label; }

        public static ResearchType[] documentTypes() {
            return new ResearchType[] {
                    NOTE, CHARACTER, LOCATION, ORGANIZATION, EVENT, REFERENCE, THREE_ACT_STRUCTURE
            };
        }
    }
    public enum CharacterClass {
        MAIN("Main Character"), SIDE("Side Character"), SUPPORTING("Supporting Character");
        private final String label;
        CharacterClass(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    /** The three fixed top-level folders of every project. */
    public enum Special { NONE, DRAFT, RESEARCH, TRASH }

    private final String id;
    private final Kind kind;
    private Special special = Special.NONE;
    private String title;
    private String synopsis = "";
    private String content = "";
    private String label = Project.NONE;
    private String status = Project.NONE;
    private boolean include = true;
    private ResearchType researchType = ResearchType.NOTE;
    private boolean relationshipFolder;
    private CharacterClass characterClass = CharacterClass.SUPPORTING;
    private final List<String> roles = new ArrayList<>();
    private final List<String> linkedCardIds = new ArrayList<>();
    private final List<ResearchImage> images = new ArrayList<>();
    private String primaryImageFile = "";
    private final Map<String, String> fields = new LinkedHashMap<>();
    private String file = "";           // relative path inside the project (FILE items only)
    private boolean contentDirty = true;
    private boolean wordsDirty = true;
    private boolean totalWordsDirty = true;
    private int wordsCache;
    private int totalWordsCache;

    public Item(Kind kind, String title) {
        this(UUID.randomUUID().toString(), kind, title);
    }

    public Item(String id, Kind kind, String title) {
        super(title);
        this.id = id;
        this.kind = kind;
        this.title = title;
        if (kind == Kind.FILE) setAllowsChildren(false);
    }

    // ---- identity / type -------------------------------------------------

    public String getId() { return id; }
    public Kind getKind() { return kind; }
    public Special getSpecial() { return special; }
    public void setSpecial(Special s) { this.special = s; }
    public boolean isSpecial() { return special != Special.NONE; }
    public boolean isFolder() { return kind == Kind.FOLDER; }
    public boolean isContainer() { return kind != Kind.FILE; }
    public ResearchType getResearchType() { return researchType; }
    public void setResearchType(ResearchType type) {
        researchType = type == null ? ResearchType.NOTE : type;
    }
    public boolean isRelationshipFolder() { return relationshipFolder; }
    public void setRelationshipFolder(boolean value) { relationshipFolder = value; }
    public CharacterClass getCharacterClass() { return characterClass; }
    public void setCharacterClass(CharacterClass value) {
        characterClass = value == null ? CharacterClass.SUPPORTING : value;
    }
    public List<String> getRoles() { return new ArrayList<>(roles); }
    public void setRoles(List<String> values) {
        roles.clear();
        if (values != null) for (String value : values) {
            if (value != null && !value.trim().isEmpty()) roles.add(value.trim());
        }
    }
    public List<String> getLinkedCardIds() { return new ArrayList<>(linkedCardIds); }
    public void setLinkedCardIds(List<String> values) {
        linkedCardIds.clear();
        if (values != null) for (String value : values) {
            if (value != null && !value.isEmpty() && !linkedCardIds.contains(value)) linkedCardIds.add(value);
        }
    }
    public List<ResearchImage> getImages() { return new ArrayList<>(images); }
    public void addImage(ResearchImage image) {
        if (image != null) {
            images.add(image);
            if (primaryImageFile.isEmpty()) primaryImageFile = image.getFile();
        }
    }
    public void setImages(List<ResearchImage> values) {
        images.clear();
        if (values != null) images.addAll(values);
        if (!hasImageFile(primaryImageFile)) primaryImageFile = images.isEmpty() ? "" : images.get(0).getFile();
    }
    public String getPrimaryImageFile() {
        if (!primaryImageFile.isEmpty()) return primaryImageFile;
        return images.isEmpty() ? "" : images.get(0).getFile();
    }
    public void setPrimaryImageFile(String value) {
        if (value != null && !value.isEmpty() && hasImageFile(value)) {
            primaryImageFile = value;
        } else {
            primaryImageFile = images.isEmpty() ? "" : images.get(0).getFile();
        }
    }
    public boolean isPrimaryImage(ResearchImage image) {
        return image != null && image.getFile().equals(primaryImageFile);
    }
    private boolean hasImageFile(String value) {
        if (value == null || value.isEmpty()) return false;
        for (ResearchImage image : images) if (value.equals(image.getFile())) return true;
        return false;
    }
    public Map<String, String> getFields() { return new LinkedHashMap<>(fields); }
    public void setFields(Map<String, String> values) {
        fields.clear();
        if (values != null) fields.putAll(values);
    }

    /** Which top-level zone (Draft / Research / Trash) this item lives in. */
    public Special zone() {
        Item n = this;
        while (n.getParent() != null && n.getParent().getParent() != null) n = (Item) n.getParent();
        return n.special;
    }

    // ---- simple properties -------------------------------------------------

    public String getTitle() { return title; }

    public void setTitle(String t) {
        this.title = t;
        this.userObject = t;
    }

    /** Called by JTree when the user renames a node in place. */
    @Override public void setUserObject(Object o) {
        String t = o == null ? "" : o.toString().trim();
        if (t.isEmpty()) return;
        this.title = t;
        super.setUserObject(t);
    }

    @Override public String toString() { return title; }

    public String getSynopsis() { return synopsis; }
    public void setSynopsis(String s) { this.synopsis = s == null ? "" : s; }

    public String getLabel() { return label; }
    public void setLabel(String l) { this.label = (l == null || l.isEmpty()) ? Project.NONE : l; }

    public String getStatus() { return status; }
    public void setStatus(String s) { this.status = (s == null || s.isEmpty()) ? Project.NONE : s; }

    public boolean isInclude() { return include; }
    public void setInclude(boolean b) { this.include = b; }

    public String getFile() { return file; }
    public void setFile(String f) { this.file = f == null ? "" : f; }

    // ---- content -----------------------------------------------------------

    public String getContent() { return content; }

    public void setContent(String c) {
        c = c == null ? "" : c;
        if (!c.equals(content)) {
            content = c;
            contentDirty = true;
            invalidateWordCounts();
        }
    }

    /** Used when loading from disk (the item is then not dirty). */
    void setContentLoaded(String c) {
        content = c == null ? "" : c;
        contentDirty = false;
        invalidateWordCounts();
    }

    public boolean isContentDirty() { return contentDirty; }
    void clearDirty() { contentDirty = false; }

    public int words() {
        if (wordsDirty) {
            wordsCache = Util.words(content);
            wordsDirty = false;
        }
        return wordsCache;
    }

    public int totalWords() {
        if (totalWordsDirty) {
            int n = words();
            for (int i = 0; i < getChildCount(); i++) n += itemAt(i).totalWords();
            totalWordsCache = n;
            totalWordsDirty = false;
        }
        return totalWordsCache;
    }

    private void invalidateWordCounts() {
        wordsDirty = true;
        Item node = this;
        while (node != null) {
            node.totalWordsDirty = true;
            node = node.getParent() instanceof Item ? (Item) node.getParent() : null;
        }
    }

    private void invalidateTotalWords() {
        Item node = this;
        while (node != null) {
            node.totalWordsDirty = true;
            node = node.getParent() instanceof Item ? (Item) node.getParent() : null;
        }
    }

    @Override public void insert(MutableTreeNode child, int index) {
        super.insert(child, index);
        invalidateTotalWords();
    }

    @Override public void remove(int childIndex) {
        super.remove(childIndex);
        invalidateTotalWords();
    }

    @Override public void removeAllChildren() {
        super.removeAllChildren();
        invalidateTotalWords();
    }

    // ---- tree helpers --------------------------------------------------------

    public Item itemAt(int i) { return (Item) getChildAt(i); }

    public Item parentItem() { return (Item) getParent(); }

    public List<Item> items() {
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < getChildCount(); i++) out.add(itemAt(i));
        return out;
    }

    /** This item followed by all descendants, in binder (pre-)order. */
    public List<Item> subtree() {
        List<Item> out = new ArrayList<>();
        collect(out);
        return out;
    }

    private void collect(List<Item> out) {
        out.add(this);
        for (int i = 0; i < getChildCount(); i++) itemAt(i).collect(out);
    }
}

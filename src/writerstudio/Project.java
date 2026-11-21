package writerstudio;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.imageio.ImageIO;
import javax.swing.text.BadLocationException;
import javax.swing.text.rtf.RTFEditorKit;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.event.TreeModelEvent;
import javax.swing.event.TreeModelListener;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.awt.Color;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;

/**
 * A writing project. On disk it is a plain folder:
 * <pre>
 *   MyNovel/
 *     project.xml        the Binder structure + metadata
 *     data/&lt;uuid&gt;.txt    the text of every document
 *     files/             imported research files (images, PDFs, ...)
 * </pre>
 */
public final class Project {
    public static final String NONE = "\u2014";
    public static final String[] LABELS = {NONE, "Chapter", "Scene", "Idea", "Research", "To-Do"};
    public static final String[] STATUSES = {NONE, "To Do", "In Progress", "First Draft", "Revised Draft", "Final Draft", "Done"};
    private static final Color[] LABEL_COLORS = {
            null, new Color(0x4A90D9), new Color(0x58B368), new Color(0xE8B93A),
            new Color(0x9B6BD6), new Color(0xE0584B)};

    public static Color labelColor(String label) {
        for (int i = 0; i < LABELS.length; i++) if (LABELS[i].equals(label)) return LABEL_COLORS[i];
        return null;
    }

    private final File dir;
    private final String name;
    private final Item root, draft, research, trash;
    private final DefaultTreeModel model;
    private final Map<String, Item> itemsById = new HashMap<>();
    private final List<RelationshipLink> relationships = new ArrayList<>();
    private final Map<String, Point> relationshipNodePositions = new HashMap<>();
    private boolean dirty;

    /** While true the UI ignores tree-model events (used for edits made inside a view). */
    public boolean muted;

    private Project(File dir, String name, Item root) {
        this.dir = dir;
        this.name = name;
        this.root = root;
        this.draft = special(root, Item.Special.DRAFT, "Draft");
        this.research = special(root, Item.Special.RESEARCH, "Research");
        this.trash = special(root, Item.Special.TRASH, "Trash");
        this.model = new DefaultTreeModel(root);
        indexSubtree(root);
        this.model.addTreeModelListener(new TreeModelListener() {
            @Override public void treeNodesChanged(TreeModelEvent event) { }

            @Override public void treeNodesInserted(TreeModelEvent event) {
                Object[] children = event.getChildren();
                if (children != null) for (Object child : children) {
                    if (child instanceof Item) indexSubtree((Item) child);
                }
            }

            @Override public void treeNodesRemoved(TreeModelEvent event) {
                Object[] children = event.getChildren();
                if (children != null) for (Object child : children) {
                    if (child instanceof Item) unindexSubtree((Item) child);
                }
            }

            @Override public void treeStructureChanged(TreeModelEvent event) {
                itemsById.clear();
                indexSubtree(root);
            }
        });
    }

    private void indexSubtree(Item item) {
        for (Item node : item.subtree()) itemsById.put(node.getId(), node);
    }

    private void unindexSubtree(Item item) {
        for (Item node : item.subtree()) itemsById.remove(node.getId(), node);
    }

    private static Item special(Item root, Item.Special s, String title) {
        for (Item it : root.items()) if (it.getSpecial() == s) return it;
        Item it = new Item(Item.Kind.FOLDER, title);
        it.setSpecial(s);
        root.add(it);
        return it;
    }

    // ---- accessors ---------------------------------------------------------------

    public File getDir() { return dir; }
    public String getName() { return name; }
    public Item root() { return root; }
    public Item draft() { return draft; }
    public Item research() { return research; }
    public Item trash() { return trash; }
    public DefaultTreeModel model() { return model; }
    public List<RelationshipLink> relationships() { return new ArrayList<>(relationships); }
    public Point relationshipNodePosition(String itemId) {
        Point point = relationshipNodePositions.get(itemId);
        return point == null ? null : new Point(point);
    }
    public void setRelationshipNodePosition(String itemId, Point point) {
        if (itemId == null || point == null) return;
        relationshipNodePositions.put(itemId, new Point(point));
        markDirty();
    }
    public Item findItem(String id) {
        if (id == null || id.isEmpty()) return null;
        return itemsById.get(id);
    }
    public void addRelationship(RelationshipLink link) {
        relationships.removeIf(existing -> existing.getFromId().equals(link.getFromId())
                && existing.getToId().equals(link.getToId())
                && existing.getType().equals(link.getType()));
        relationships.add(link);
        markDirty();
    }
    public void removeRelationship(RelationshipLink link) {
        if (link == null) return;
        relationships.removeIf(existing -> existing.getId().equals(link.getId()));
        markDirty();
    }
    public void removeRelationship(String fromId, String toId) {
        relationships.removeIf(existing -> existing.getFromId().equals(fromId)
                && existing.getToId().equals(toId));
        markDirty();
    }
    public boolean isDirty() { return dirty; }
    public void markDirty() { dirty = true; }
    File dataDir() { return new File(dir, "data"); }
    File filesDir() { return new File(dir, "files"); }
    public File resolve(Item it) { return new File(dir, it.getFile()); }

    /** Notify tree listeners of a change without making the views rebuild themselves. */
    public void changedQuietly(Item it) {
        muted = true;
        try {
            model.nodeChanged(it);
        } finally {
            muted = false;
        }
        markDirty();
    }

    // ---- structure operations ----------------------------------------------------

    public void insert(Item child, Item parent, int index) {
        model.insertNodeInto(child, parent, index);
        markDirty();
    }

    public void moveToTrash(Item it) {
        model.removeNodeFromParent(it);
        model.insertNodeInto(it, trash, trash.getChildCount());
        markDirty();
    }

    public void deleteForever(Item it) {
        model.removeNodeFromParent(it);
        markDirty();
    }

    public void emptyTrash() {
        while (trash.getChildCount() > 0) model.removeNodeFromParent(trash.itemAt(0));
        markDirty();
    }

    public String importResearchImage(File src) throws IOException {
        Files.createDirectories(new File(filesDir(), "research-images").toPath());
        String stored = UUID.randomUUID() + "-" + src.getName().replaceAll("[^A-Za-z0-9._-]", "_");
        File destination = new File(filesDir(), "research-images" + File.separator + stored);
        Files.copy(src.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return "files/research-images/" + stored;
    }

    public String importResearchImage(BufferedImage image) throws IOException {
        Files.createDirectories(new File(filesDir(), "research-images").toPath());
        String stored = UUID.randomUUID() + "-crop.png";
        File destination = new File(filesDir(), "research-images" + File.separator + stored);
        if (!ImageIO.write(image, "png", destination)) {
            throw new IOException("Could not save the cropped image.");
        }
        return "files/research-images/" + stored;
    }

    public void deleteResearchImageIfUnused(String relativePath) {
        if (relativePath == null || relativePath.isEmpty() || imageIsReferenced(relativePath)) return;
        File file = new File(dir, relativePath);
        if (file.isFile()) file.delete();
    }

    private boolean imageIsReferenced(String relativePath) {
        for (Item item : root.subtree()) {
            for (ResearchImage image : item.getImages()) {
                if (relativePath.equals(image.getFile())) return true;
            }
        }
        return false;
    }

    public int draftWords() { return draft.totalWords(); }

    // ---- create / load -----------------------------------------------------------

    public static Project createNew(File dir, String name, boolean sample) throws IOException {
        Files.createDirectories(dir.toPath());
        Project p = new Project(dir, name, new Item("root", Item.Kind.FOLDER, name));
        if (sample) p.addSample();
        p.markDirty();
        p.save();
        return p;
    }

    private static Item doc(String title, String synopsis, String text, String label, String status) {
        Item it = new Item(Item.Kind.TEXT, title);
        it.setSynopsis(synopsis);
        it.setContent(text);
        it.setLabel(label);
        it.setStatus(status);
        return it;
    }

    private void addSample() {
        Item c1 = new Item(Item.Kind.FOLDER, "Ch\u01b0\u01a1ng 1");
        c1.setLabel("Chapter");
        c1.setSynopsis("Mai r\u1eddi th\u00e0nh ph\u1ed1 v\u00e0 \u0111\u1ebfn th\u1ecb tr\u1ea5n ven bi\u1ec3n.");
        c1.add(doc("C\u1ea3nh 1 \u2013 Chuy\u1ebfn t\u00e0u \u0111\u00eam",
                "Mai r\u1eddi th\u00e0nh ph\u1ed1 b\u1eb1ng chuy\u1ebfn t\u00e0u cu\u1ed1i c\u00f9ng.",
                "\u0110\u00eam \u0111\u00f3 m\u01b0a r\u1ea5t l\u1edbn. Mai ng\u1ed3i s\u00e1t c\u1eeda s\u1ed5 toa t\u00e0u, nh\u00ecn nh\u1eefng \u00e1nh \u0111\u00e8n th\u00e0nh ph\u1ed1 l\u00f9i d\u1ea7n ph\u00eda sau.\n\nC\u00f4 ch\u01b0a bi\u1ebft r\u1eb1ng m\u00ecnh s\u1ebd kh\u00f4ng quay l\u1ea1i n\u1eefa.",
                "Scene", "First Draft"));
        c1.add(doc("C\u1ea3nh 2 \u2013 B\u1ee9c th\u01b0",
                "Mai \u0111\u1ecdc l\u1ea1i b\u1ee9c th\u01b0 c\u0169 trong nh\u00e0 ga v\u1eafng.",
                "B\u1ee9c th\u01b0 \u0111\u00e3 nh\u00e0u n\u00e1t \u1edf c\u00e1c g\u00f3c. Mai g\u1ea5p n\u00f3 l\u1ea1i, b\u1ecf v\u00e0o t\u00fai \u00e1o.",
                "Scene", "To Do"));
        Item c2 = new Item(Item.Kind.FOLDER, "Ch\u01b0\u01a1ng 2");
        c2.setLabel("Chapter");
        c2.add(doc("C\u1ea3nh 1 \u2013 Th\u1ecb tr\u1ea5n bi\u1ec3n",
                "Mai g\u1eb7p ng\u01b0\u1eddi ch\u1ee7 nh\u00e0 tr\u1ecd.", "", "Scene", "To Do"));
        insert(c1, draft, draft.getChildCount());
        insert(c2, draft, draft.getChildCount());
        insert(doc("Nh\u00e2n v\u1eadt: Mai",
                "Ghi ch\u00fa v\u1ec1 nh\u00e2n v\u1eadt ch\u00ednh.",
                "Mai, 28 tu\u1ed5i, bi\u00ean t\u1eadp vi\u00ean. Hay im l\u1eb7ng, th\u00edch nghe m\u01b0a.", "Research", NONE),
                research, research.getChildCount());
        insert(doc("B\u1ed1i c\u1ea3nh: th\u1ecb tr\u1ea5n bi\u1ec3n",
                "", "M\u00f9a gi\u00f3 l\u1edbn, nh\u00e0 g\u1ed7 s\u01a1n xanh, ch\u1ee3 c\u00e1 bu\u1ed5i s\u00e1ng.", "Research", NONE),
                research, research.getChildCount());
    }

    public static Project load(File dir) throws Exception {
        File xml = new File(dir, "project.xml");
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        try {
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (ParserConfigurationException ignored) {
            // parser without that feature: local files only, acceptable
        }
        org.w3c.dom.Document d = f.newDocumentBuilder().parse(xml);
        Element pe = d.getDocumentElement();
        String name = pe.getAttribute("name");
        if (name.isEmpty()) name = dir.getName();
        Item root = new Item("root", Item.Kind.FOLDER, name);
        NodeList kids = pe.getElementsByTagName("binder");
        if (kids.getLength() > 0) {
            NodeList items = kids.item(0).getChildNodes();
            for (int i = 0; i < items.getLength(); i++) {
                if (items.item(i) instanceof Element && "item".equals(((Element) items.item(i)).getTagName())) {
                    root.add(fromXml((Element) items.item(i), dir));
                }
            }
        }
        Project project = new Project(dir, name, root);
        NodeList links = pe.getElementsByTagName("relationship");
        for (int i = 0; i < links.getLength(); i++) {
            if (!(links.item(i) instanceof Element)) continue;
            Element link = (Element) links.item(i);
            String notes = "";
            List<Point> routePoints = new ArrayList<>();
            Point sourceAnchor = null;
            Point targetAnchor = null;
            NodeList children = link.getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                if (!(children.item(j) instanceof Element)) continue;
                Element child = (Element) children.item(j);
                if ("notes".equals(child.getTagName())) notes = child.getTextContent();
                else if ("point".equals(child.getTagName())) {
                    try {
                        routePoints.add(new Point(Integer.parseInt(child.getAttribute("x")),
                                Integer.parseInt(child.getAttribute("y"))));
                    } catch (NumberFormatException ignored) { }
                } else if ("sourceAnchor".equals(child.getTagName())
                        || "targetAnchor".equals(child.getTagName())) {
                    try {
                        Point anchor = new Point(Integer.parseInt(child.getAttribute("x")),
                                Integer.parseInt(child.getAttribute("y")));
                        if ("sourceAnchor".equals(child.getTagName())) sourceAnchor = anchor;
                        else targetAnchor = anchor;
                    } catch (NumberFormatException ignored) { }
                }
            }
            if (children.getLength() == 0 || notes.isEmpty()) {
                String legacyNotes = "";
                for (int j = 0; j < children.getLength(); j++) {
                    if (!(children.item(j) instanceof Element)) legacyNotes += children.item(j).getTextContent();
                }
                if (!legacyNotes.trim().isEmpty()) notes = legacyNotes.trim();
            }
            RelationshipLink relationship = new RelationshipLink(link.getAttribute("id"),
                    link.getAttribute("from"), link.getAttribute("to"),
                    link.getAttribute("type"), link.getAttribute("status"), notes);
            try {
                if (!link.getAttribute("direction").isEmpty()) {
                    relationship.setArrowDirection(RelationshipLink.ArrowDirection.valueOf(
                            link.getAttribute("direction")));
                }
            } catch (IllegalArgumentException ignored) { }
            relationship.setRoutePoints(routePoints);
            relationship.setFromAnchor(sourceAnchor);
            relationship.setToAnchor(targetAnchor);
            project.relationships.add(relationship);
        }
        NodeList layouts = pe.getElementsByTagName("graphLayout");
        if (layouts.getLength() > 0) {
            NodeList positions = ((Element) layouts.item(0)).getElementsByTagName("node");
            for (int i = 0; i < positions.getLength(); i++) {
                if (!(positions.item(i) instanceof Element)) continue;
                Element position = (Element) positions.item(i);
                try {
                    project.relationshipNodePositions.put(position.getAttribute("item"),
                            new Point(Integer.parseInt(position.getAttribute("x")),
                                    Integer.parseInt(position.getAttribute("y"))));
                } catch (NumberFormatException ignored) { }
            }
        }
        return project;
    }

    private static Item fromXml(Element e, File dir) throws IOException {
        String id = e.getAttribute("id");
        if (id.isEmpty()) id = UUID.randomUUID().toString();
        Item.Kind kind = Item.Kind.valueOf(e.getAttribute("kind"));
        String title = e.getAttribute("title");
        Item it = new Item(id, kind, title.isEmpty() ? "Untitled" : title);
        String sp = e.getAttribute("special");
        if (!sp.isEmpty()) it.setSpecial(Item.Special.valueOf(sp));
        it.setLabel(e.getAttribute("label"));
        it.setStatus(e.getAttribute("status"));
        String researchType = e.getAttribute("researchType");
        if (!researchType.isEmpty()) {
            try {
                it.setResearchType(Item.ResearchType.valueOf(researchType));
            } catch (IllegalArgumentException ignored) {
                it.setResearchType(Item.ResearchType.NOTE);
            }
        }
        it.setInclude(!"false".equals(e.getAttribute("include")));
        it.setRelationshipFolder("true".equals(e.getAttribute("relationshipFolder")));
        String characterClass = e.getAttribute("characterClass");
        if (!characterClass.isEmpty()) {
            try {
                it.setCharacterClass(Item.CharacterClass.valueOf(characterClass));
            } catch (IllegalArgumentException ignored) {
                it.setCharacterClass(Item.CharacterClass.SUPPORTING);
            }
        }
        it.setFile(e.getAttribute("file"));
        NodeList kids = e.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (!(kids.item(i) instanceof Element)) continue;
            Element c = (Element) kids.item(i);
            if ("synopsis".equals(c.getTagName())) it.setSynopsis(c.getTextContent());
            else if ("item".equals(c.getTagName()) && kind != Item.Kind.FILE) it.add(fromXml(c, dir));
            else if ("image".equals(c.getTagName())) {
                List<ResearchImage> images = it.getImages();
                images.add(new ResearchImage(c.getAttribute("file"), c.getAttribute("caption")));
                it.setImages(images);
            } else if ("field".equals(c.getTagName())) {
                String name = c.getAttribute("name");
                if (it.getResearchType() == Item.ResearchType.THREE_ACT_STRUCTURE
                        && isThreeActField(name)) {
                    Map<String, String> fields = it.getThreeActFields();
                    fields.put(name, c.getTextContent());
                    it.setThreeActFields(fields);
                } else {
                    Map<String, String> fields = it.getFields();
                    fields.put(name, c.getTextContent());
                    it.setFields(fields);
                }
            } else if ("threeActField".equals(c.getTagName())) {
                Map<String, String> fields = it.getThreeActFields();
                fields.put(c.getAttribute("name"), c.getTextContent());
                it.setThreeActFields(fields);
            }
        }
        List<String> roles = new ArrayList<>();
        List<String> links = new ArrayList<>();
        for (int i = 0; i < kids.getLength(); i++) {
            if (!(kids.item(i) instanceof Element)) continue;
            Element c = (Element) kids.item(i);
            if ("role".equals(c.getTagName())) roles.add(c.getTextContent());
            if ("link".equals(c.getTagName())) links.add(c.getAttribute("id"));
        }
        it.setRoles(roles);
        it.setLinkedCardIds(links);
        it.setPrimaryImageFile(e.getAttribute("primaryImage"));
        List<Item> imageChildren = new ArrayList<>();
        for (Item child : it.items()) {
            for (ResearchImage image : it.getImages()) {
                if (child.getKind() == Item.Kind.FILE && image.getFile().equals(child.getFile())) {
                    imageChildren.add(child);
                    break;
                }
            }
        }
        for (Item child : imageChildren) it.remove(child);
        if (kind != Item.Kind.FILE) {
            File txt = new File(new File(dir, "data"), id + ".txt");
            if (txt.exists()) it.setContentLoaded(new String(Files.readAllBytes(txt.toPath()), StandardCharsets.UTF_8));
            else it.setContentLoaded("");
        }
        return it;
    }

    // ---- save ----------------------------------------------------------------------

    public void save() throws IOException {
        Files.createDirectories(dataDir().toPath());
        Files.createDirectories(filesDir().toPath());

        Set<String> ids = new HashSet<>();
        Set<String> storedFiles = new HashSet<>();
        for (Item it : root.subtree()) {
            if (it == root) continue;
            ids.add(it.getId());
            for (ResearchImage image : it.getImages()) {
                storedFiles.add(normalizeStoredPath(image.getFile()));
            }
            if (it.getKind() == Item.Kind.FILE) {
                storedFiles.add(normalizeStoredPath(it.getFile()));
            } else if (it.isContentDirty()) {
                File txt = new File(dataDir(), it.getId() + ".txt");
                Files.write(txt.toPath(), it.getContent().getBytes(StandardCharsets.UTF_8));
                it.clearDirty();
            }
        }

        try {
            org.w3c.dom.Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            Element pe = doc.createElement("project");
            pe.setAttribute("name", Util.xmlSafe(name));
            pe.setAttribute("version", "1");
            doc.appendChild(pe);
            Element be = doc.createElement("binder");
            pe.appendChild(be);
            for (Item top : root.items()) be.appendChild(toXml(doc, top));
            Element re = doc.createElement("relationships");
            pe.appendChild(re);
            for (RelationshipLink link : relationships) {
                Element e = doc.createElement("relationship");
                e.setAttribute("id", link.getId());
                e.setAttribute("from", link.getFromId());
                e.setAttribute("to", link.getToId());
                e.setAttribute("type", Util.xmlSafe(link.getType()));
                e.setAttribute("status", Util.xmlSafe(link.getStatus()));
                e.setAttribute("direction", link.getArrowDirection().name());
                Element notes = doc.createElement("notes");
                notes.setTextContent(link.getNotes());
                e.appendChild(notes);
                for (Point point : link.getRoutePoints()) {
                    Element routePoint = doc.createElement("point");
                    routePoint.setAttribute("x", Integer.toString(point.x));
                    routePoint.setAttribute("y", Integer.toString(point.y));
                    e.appendChild(routePoint);
                }
                Point sourceAnchor = link.getFromAnchor();
                if (sourceAnchor != null) {
                    Element anchor = doc.createElement("sourceAnchor");
                    anchor.setAttribute("x", Integer.toString(sourceAnchor.x));
                    anchor.setAttribute("y", Integer.toString(sourceAnchor.y));
                    e.appendChild(anchor);
                }
                Point targetAnchor = link.getToAnchor();
                if (targetAnchor != null) {
                    Element anchor = doc.createElement("targetAnchor");
                    anchor.setAttribute("x", Integer.toString(targetAnchor.x));
                    anchor.setAttribute("y", Integer.toString(targetAnchor.y));
                    e.appendChild(anchor);
                }
                re.appendChild(e);
            }
            Element graphLayout = doc.createElement("graphLayout");
            pe.appendChild(graphLayout);
            for (Map.Entry<String, Point> entry : relationshipNodePositions.entrySet()) {
                Element node = doc.createElement("node");
                node.setAttribute("item", entry.getKey());
                node.setAttribute("x", Integer.toString(entry.getValue().x));
                node.setAttribute("y", Integer.toString(entry.getValue().y));
                graphLayout.appendChild(node);
            }

            File tmp = new File(dir, "project.xml.tmp");
            Transformer t = TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.INDENT, "yes");
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            try (OutputStream out = new FileOutputStream(tmp)) {
                t.transform(new DOMSource(doc), new StreamResult(out));
            }
            Files.move(tmp.toPath(), new File(dir, "project.xml").toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (ParserConfigurationException | TransformerException e) {
            throw new IOException("Could not write project.xml", e);
        }

        // remove text / research files that no longer belong to any item
        File[] texts = dataDir().listFiles();
        if (texts != null) {
            for (File f : texts) {
                String n = f.getName();
                if (n.endsWith(".txt") && !ids.contains(n.substring(0, n.length() - 4))) f.delete();
            }
        }
        deleteUnusedStoredFiles(filesDir(), storedFiles);
        dirty = false;
    }

    private void deleteUnusedStoredFiles(File directory, Set<String> storedFiles) {
        File[] entries = directory.listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            if (entry.isDirectory()) {
                deleteUnusedStoredFiles(entry, storedFiles);
                File[] children = entry.listFiles();
                if (children != null && children.length == 0) entry.delete();
            } else {
                String relative = normalizeStoredPath(dir.toPath().relativize(entry.toPath()).toString());
                if (!storedFiles.contains(relative)) entry.delete();
            }
        }
    }

    private static String normalizeStoredPath(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }

    private static Element toXml(org.w3c.dom.Document doc, Item it) {
        Element e = doc.createElement("item");
        e.setAttribute("id", it.getId());
        e.setAttribute("kind", it.getKind().name());
        if (it.isSpecial()) e.setAttribute("special", it.getSpecial().name());
        e.setAttribute("title", Util.xmlSafe(it.getTitle()));
        e.setAttribute("label", Util.xmlSafe(it.getLabel()));
        e.setAttribute("status", Util.xmlSafe(it.getStatus()));
        e.setAttribute("researchType", it.getResearchType().name());
        e.setAttribute("relationshipFolder", Boolean.toString(it.isRelationshipFolder()));
        e.setAttribute("characterClass", it.getCharacterClass().name());
        e.setAttribute("primaryImage", Util.xmlSafe(it.getPrimaryImageFile()));
        e.setAttribute("include", Boolean.toString(it.isInclude()));
        if (it.getKind() == Item.Kind.FILE) e.setAttribute("file", Util.xmlSafe(it.getFile()));
        Element syn = doc.createElement("synopsis");
        syn.setTextContent(Util.xmlSafe(it.getSynopsis()));
        e.appendChild(syn);
        for (String role : it.getRoles()) {
            Element roleElement = doc.createElement("role");
            roleElement.setTextContent(role);
            e.appendChild(roleElement);
        }
        for (String id : it.getLinkedCardIds()) {
            Element link = doc.createElement("link");
            link.setAttribute("id", Util.xmlSafe(id));
            e.appendChild(link);
        }
        for (ResearchImage image : it.getImages()) {
            Element imageElement = doc.createElement("image");
            imageElement.setAttribute("file", Util.xmlSafe(image.getFile()));
            imageElement.setAttribute("caption", Util.xmlSafe(image.getCaption()));
            e.appendChild(imageElement);
        }
        for (Map.Entry<String, String> field : it.getFields().entrySet()) {
            Element fieldElement = doc.createElement("field");
            fieldElement.setAttribute("name", Util.xmlSafe(field.getKey()));
            fieldElement.setTextContent(field.getValue());
            e.appendChild(fieldElement);
        }
        for (Map.Entry<String, String> field : it.getThreeActFields().entrySet()) {
            Element fieldElement = doc.createElement("threeActField");
            fieldElement.setAttribute("name", Util.xmlSafe(field.getKey()));
            fieldElement.setTextContent(field.getValue());
            e.appendChild(fieldElement);
        }
        for (Item ch : it.items()) e.appendChild(toXml(doc, ch));
        return e;
    }

    private static boolean isThreeActField(String name) {
        for (ResearchEditorPanel.ThreeActField field : ResearchEditorPanel.THREE_ACT_FIELDS) {
            if (field.name.equals(name)) return true;
        }
        return false;
    }

    // ---- research import -------------------------------------------------------------

    /**
     * Turns a file on disk into a (not yet inserted) item: text-like files become
     * editable documents, everything else is copied into the project and shown
     * with a viewer (images) or opened with the system application.
     */
    public Item importFile(File src) throws IOException {
        String lower = src.getName().toLowerCase();
        String base = Util.baseName(src.getName());
        if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".markdown")) {
            Item it = new Item(Item.Kind.TEXT, base);
            it.setContent(new String(Files.readAllBytes(src.toPath()), StandardCharsets.UTF_8));
            return it;
        }
        if (lower.endsWith(".rtf")) {
            Item it = new Item(Item.Kind.TEXT, base);
            it.setContent(rtfToText(src));
            return it;
        }
        Files.createDirectories(filesDir().toPath());
        String stored = UUID.randomUUID() + "-" + src.getName().replaceAll("[^A-Za-z0-9._-]", "_");
        Files.copy(src.toPath(), new File(filesDir(), stored).toPath(), StandardCopyOption.REPLACE_EXISTING);
        Item it = new Item(Item.Kind.FILE, src.getName());
        it.setFile("files/" + stored);
        return it;
    }

    private static String rtfToText(File f) throws IOException {
        RTFEditorKit kit = new RTFEditorKit();
        javax.swing.text.Document d = kit.createDefaultDocument();
        try (InputStream in = Files.newInputStream(f.toPath())) {
            kit.read(in, d, 0);
            return d.getText(0, d.getLength());
        } catch (BadLocationException e) {
            throw new IOException("Could not read RTF file", e);
        }
    }

    // ---- compile ---------------------------------------------------------------------

    /** Writes the whole Draft folder as one Markdown or plain-text file. */
    public void compile(File out, boolean markdown, boolean titles) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Item it : draft.items()) compileItem(sb, it, 1, markdown, titles);
        Files.write(out.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void compileItem(StringBuilder sb, Item it, int depth, boolean md, boolean titles) {
        if (!it.isInclude()) return;
        if (titles) {
            if (md) sb.append("#".repeat(Math.min(depth, 6))).append(' ');
            sb.append(it.getTitle()).append("\n\n");
        }
        String text = it.getContent().strip();
        if (!text.isEmpty()) sb.append(text).append("\n\n");
        for (Item ch : it.items()) compileItem(sb, ch, depth + 1, md, titles);
    }
}

package writerstudio;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JComponent;
import javax.swing.JTree;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/** Structured editor for Research cards; unlike EditorPanel it has no free-form document mode. */
public final class ResearchEditorPanel extends JPanel {
    private final Project project;
    private final JPanel form = new JPanel();
    private final JTextField title = new JTextField();
    private final JComboBox<Item.ResearchType> type =
            new JComboBox<>(Item.ResearchType.documentTypes());
    private final JTextArea content = new JTextArea(8, 60);
    private final CollapsibleSection characterSection = new CollapsibleSection("Character Profile");
    private final JComboBox<Item.CharacterClass> characterClass =
            new JComboBox<>(Item.CharacterClass.values());
    private final JTextField roles = new JTextField();
    private final JPanel imageGallery = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 12, 8));
    private final JButton primaryImageButton = button("Set primary image", this::choosePrimaryImage);
    private final JTree linksTree = new JTree(new DefaultMutableTreeNode("Linked Cards"));
    private final JPanel relationshipRows = new JPanel();
    private final JPanel fieldRows = new JPanel();
    private Item item;
    private boolean updating;
    private final Consumer<Item> openItem;

    public ResearchEditorPanel(Project project, Consumer<Item> openItem) {
        super(new BorderLayout());
        this.project = project;
        this.openItem = openItem;
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBorder(BorderFactory.createEmptyBorder(18, 30, 30, 30));

        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD, 22f));
        addField("Title", title);
        addField("Research Type", type);
        content.setLineWrap(true);
        content.setWrapStyleWord(true);
        addField("Content", new JScrollPane(content));

        addResearchSection();
        addCharacterSection();

        JScrollPane scroll = new JScrollPane(form);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);

        title.addActionListener(e -> saveBasics());
        type.addActionListener(e -> saveBasics());
        content.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { saveContent(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { saveContent(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { saveContent(); }
        });
        characterClass.addActionListener(e -> saveCharacter());
    }

    private void addField(String label, Component component) {
        JLabel heading = new JLabel(label);
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(heading);
        if (component instanceof JComponent) {
            ((JComponent) component).setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        if (component instanceof JScrollPane) component.setPreferredSize(new Dimension(700, 180));
        form.add(component);
        form.add(Box.createVerticalStrut(10));
    }

    private void addResearchSection() {
        CollapsibleSection section = section("Images");
        imageGallery.setBackground(new java.awt.Color(0xF5F0E7));
        JScrollPane imageScroll = new JScrollPane(imageGallery);
        imageScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        imageScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        imageScroll.setPreferredSize(new Dimension(700, 205));
        section.addContent(imageScroll);
        section.addContent(primaryImageButton);
        section.addContent(button("+ Add image", this::addImage));
        CollapsibleSection linksSection = section("Linked Cards");
        linksTree.setRootVisible(false);
        linksTree.setShowsRootHandles(true);
        linksTree.setPreferredSize(new Dimension(700, 160));
        linksTree.addTreeSelectionListener(event -> {
            Object value = linksTree.getLastSelectedPathComponent();
            if (value instanceof LinkedNode && ((LinkedNode) value).item != null) {
                openItem.accept(((LinkedNode) value).item);
            }
        });
        linksSection.addContent(new JScrollPane(linksTree));
        linksSection.addContent(button("+ Link cards", this::linkCards));
        CollapsibleSection fieldsSection = section("Optional Fields");
        fieldRows.setLayout(new BoxLayout(fieldRows, BoxLayout.Y_AXIS));
        fieldsSection.addContent(fieldRows);
        fieldsSection.addContent(button("+ Add field", this::addField));
        form.add(section);
        form.add(linksSection);
        form.add(fieldsSection);
    }

    private void addCharacterSection() {
        characterSection.addContent(label("Character Class"));
        characterClass.setAlignmentX(Component.LEFT_ALIGNMENT);
        characterSection.addContent(characterClass);
        characterSection.addContent(label("Roles (comma separated)"));
        roles.setAlignmentX(Component.LEFT_ALIGNMENT);
        characterSection.addContent(roles);
        roles.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { saveCharacter(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { saveCharacter(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { saveCharacter(); }
        });
        characterSection.addContent(button("+ Add relationship", this::addRelationship));
        relationshipRows.setLayout(new BoxLayout(relationshipRows, BoxLayout.Y_AXIS));
        characterSection.addContent(relationshipRows);
        form.add(characterSection);
    }

    private CollapsibleSection section(String title) {
        return new CollapsibleSection(title);
    }

    private JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private JButton button(String text, Runnable action) {
        JButton button = new JButton(text);
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setFocusable(false);
        button.addActionListener(e -> action.run());
        return button;
    }

    public void show(Item item) {
        updating = true;
        try {
            this.item = item;
            title.setText(item.getTitle());
            type.setSelectedItem(item.getResearchType());
            content.setText(item.getContent());
            characterSection.setVisible(item.getResearchType() == Item.ResearchType.CHARACTER);
            primaryImageButton.setVisible(item.getResearchType() == Item.ResearchType.CHARACTER);
            refreshLists();
        } finally {
            updating = false;
        }
        form.revalidate();
        form.repaint();
    }

    private void saveBasics() {
        if (updating || item == null) return;
        String value = title.getText().trim();
        if (!value.isEmpty() && !value.equals(item.getTitle())) item.setTitle(value);
        item.setResearchType((Item.ResearchType) type.getSelectedItem());
        characterSection.setVisible(item.getResearchType() == Item.ResearchType.CHARACTER);
        primaryImageButton.setVisible(item.getResearchType() == Item.ResearchType.CHARACTER);
        project.changedQuietly(item);
        form.revalidate();
    }

    private void saveContent() {
        if (updating || item == null) return;
        item.setContent(content.getText());
        project.markDirty();
    }

    private void saveCharacter() {
        if (updating || item == null || item.getResearchType() != Item.ResearchType.CHARACTER) return;
        item.setCharacterClass((Item.CharacterClass) characterClass.getSelectedItem());
        List<String> values = new ArrayList<>();
        for (String role : roles.getText().split(",")) if (!role.trim().isEmpty()) values.add(role.trim());
        item.setRoles(values);
        project.markDirty();
    }

    private void refreshLists() {
        roles.setText(String.join(", ", item.getRoles()));
        imageGallery.removeAll();
        List<ResearchImage> images = item.getImages();
        images.sort((left, right) -> Boolean.compare(!item.isPrimaryImage(left), !item.isPrimaryImage(right)));
        for (ResearchImage image : images) addImageCard(image);
        refreshLinkedGroups();
        refreshRelationships();
        refreshFields();
        characterClass.setSelectedItem(item.getCharacterClass());
        imageGallery.revalidate();
        imageGallery.repaint();
    }

    private void addImageCard(ResearchImage image) {
        JPanel card = new JPanel(new BorderLayout(0, 6));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new java.awt.Color(0xC8B99A)),
                BorderFactory.createEmptyBorder(6, 6, 6, 6)));
        card.setBackground(new java.awt.Color(0xFFFBEA));
        card.setPreferredSize(new Dimension(198, 180));
        File file = new File(project.getDir(), image.getFile());
        JLabel preview = new JLabel();
        preview.setHorizontalAlignment(JLabel.CENTER);
        preview.setPreferredSize(new Dimension(184, 132));
        if (file.isFile()) {
            ImageIcon source = new ImageIcon(file.getAbsolutePath());
            double scale = Math.min(180.0 / source.getIconWidth(), 128.0 / source.getIconHeight());
            int width = Math.max(1, (int) (source.getIconWidth() * scale));
            int height = Math.max(1, (int) (source.getIconHeight() * scale));
            preview.setIcon(new ImageIcon(source.getImage().getScaledInstance(
                    width, height, java.awt.Image.SCALE_SMOOTH)));
        } else {
            preview.setText("Image unavailable");
        }
        preview.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && file.isFile()) showFullImage(file, image.getCaption());
            }
        });
        JLabel caption = new JLabel("<html><div style='width:180px'>"
                + escapeHtml(image.getCaption().isEmpty() ? file.getName() : image.getCaption())
                + "</div></html>");
        caption.setVerticalAlignment(JLabel.TOP);
        JButton remove = new JButton(Icons.delete());
        remove.setToolTipText("Remove this image");
        remove.setFocusable(false);
        remove.setMargin(new java.awt.Insets(3, 5, 3, 5));
        remove.addActionListener(event -> removeImage(image));
        JPanel media = new JPanel(new BorderLayout());
        media.setOpaque(false);
        media.add(preview, BorderLayout.CENTER);
        JPanel removeBar = new JPanel(new BorderLayout());
        removeBar.setOpaque(false);
        removeBar.add(remove, BorderLayout.EAST);
        media.add(removeBar, BorderLayout.NORTH);
        if (item.isPrimaryImage(image)) {
            JLabel primary = new JLabel(Icons.primary());
            primary.setToolTipText("Primary image");
            JPanel primaryBar = new JPanel(new BorderLayout());
            primaryBar.setOpaque(false);
            primaryBar.add(primary, BorderLayout.WEST);
            media.add(primaryBar, BorderLayout.SOUTH);
        }
        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.add(caption, BorderLayout.CENTER);
        card.add(media, BorderLayout.CENTER);
        card.add(footer, BorderLayout.SOUTH);
        imageGallery.add(card);
    }

    private void removeImage(ResearchImage image) {
        if (item == null) return;
        int answer = JOptionPane.showConfirmDialog(this, "Remove this image from the card?",
                "Remove image", JOptionPane.YES_NO_OPTION);
        if (answer != JOptionPane.YES_OPTION) return;

        List<ResearchImage> remaining = item.getImages();
        remaining.remove(image);
        item.setImages(remaining);
        if (remaining.isEmpty()) item.setPrimaryImageFile("");
        project.deleteResearchImageIfUnused(image.getFile());
        project.markDirty();
        refreshLists();
    }

    private void choosePrimaryImage() {
        if (item == null || item.getResearchType() != Item.ResearchType.CHARACTER
                || item.getImages().isEmpty()) return;
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Images");
        for (ResearchImage image : item.getImages()) {
            root.add(new DefaultMutableTreeNode(new PrimaryImageNode(image)));
        }
        JTree tree = new JTree(root);
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setSelectionRow(0);
        tree.setCellRenderer(new javax.swing.tree.DefaultTreeCellRenderer() {
            @Override public Component getTreeCellRendererComponent(JTree valueTree, Object value,
                    boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
                Component component = super.getTreeCellRendererComponent(valueTree, value,
                        selected, expanded, leaf, row, hasFocus);
                if (value instanceof DefaultMutableTreeNode
                        && ((DefaultMutableTreeNode) value).getUserObject() instanceof PrimaryImageNode) {
                    PrimaryImageNode node = (PrimaryImageNode) ((DefaultMutableTreeNode) value).getUserObject();
                    setText(node.label);
                    setIcon(node.icon);
                }
                return component;
            }
        });
        for (int row = 0; row < tree.getRowCount(); row++) tree.expandRow(row);
        for (int row = 1; row < tree.getRowCount(); row++) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) tree.getPathForRow(row).getLastPathComponent();
            if (node.getUserObject() instanceof PrimaryImageNode
                    && item.isPrimaryImage(((PrimaryImageNode) node.getUserObject()).image)) {
                tree.setSelectionRow(row);
                break;
            }
        }
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this),
                "Choose primary image", java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        JPanel body = new JPanel(new BorderLayout(0, 10));
        body.setBorder(BorderFactory.createEmptyBorder(14, 16, 8, 16));
        body.add(new JLabel("Select the image used for this character in Relationships."), BorderLayout.NORTH);
        JScrollPane treeScroll = new JScrollPane(tree);
        treeScroll.setPreferredSize(new Dimension(520, 300));
        body.add(treeScroll, BorderLayout.CENTER);
        JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 8));
        JButton cancel = new JButton("Cancel");
        JButton apply = new JButton("Set primary");
        cancel.addActionListener(event -> dialog.dispose());
        apply.addActionListener(event -> {
            Object value = ((DefaultMutableTreeNode) tree.getLastSelectedPathComponent()).getUserObject();
            if (value instanceof PrimaryImageNode) {
                item.setPrimaryImageFile(((PrimaryImageNode) value).image.getFile());
                project.markDirty();
                refreshLists();
            }
            dialog.dispose();
        });
        actions.add(cancel);
        actions.add(apply);
        dialog.add(body, BorderLayout.CENTER);
        dialog.add(actions, BorderLayout.SOUTH);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private void showFullImage(File file, String caption) {
        ImageIcon source = new ImageIcon(file.getAbsolutePath());
        JLabel image = new JLabel();
        image.setHorizontalAlignment(JLabel.CENTER);
        image.setBackground(java.awt.Color.BLACK);
        image.setOpaque(true);
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this));
        dialog.setTitle(caption.isEmpty() ? file.getName() : caption);
        dialog.setResizable(true);
        dialog.setMinimumSize(new Dimension(420, 320));
        JPanel viewer = new JPanel(new BorderLayout());
        viewer.setBackground(java.awt.Color.BLACK);
        viewer.add(image, BorderLayout.CENTER);
        dialog.add(viewer);
        Runnable fitImage = () -> {
            int availableWidth = Math.max(1, image.getWidth() - 24);
            int availableHeight = Math.max(1, image.getHeight() - 24);
            double scale = Math.min((double) availableWidth / source.getIconWidth(),
                    (double) availableHeight / source.getIconHeight());
            scale = Math.min(1.0, scale);
            int width = Math.max(1, (int) (source.getIconWidth() * scale));
            int height = Math.max(1, (int) (source.getIconHeight() * scale));
            image.setIcon(new ImageIcon(source.getImage().getScaledInstance(
                    width, height, java.awt.Image.SCALE_SMOOTH)));
        };
        image.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) { fitImage.run(); }
        });
        image.setToolTipText("Click or press Escape to close");
        image.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) { dialog.dispose(); }
        });
        dialog.getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(javax.swing.KeyStroke.getKeyStroke("ESCAPE"), "close-image");
        dialog.getRootPane().getActionMap().put("close-image", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { dialog.dispose(); }
        });
        dialog.setSize(1000, 760);
        dialog.setLocationRelativeTo(SwingUtilities.getWindowAncestor(this));
        dialog.setVisible(true);
        fitImage.run();
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void refreshLinkedGroups() {
        List<Item> linkedItems = new ArrayList<>();
        for (String id : item.getLinkedCardIds()) {
            Item linked = project.findItem(id);
            if (linked != null) linkedItems.add(linked);
        }
        JTree tree = createLinkedTree(linkedItems);
        linksTree.setModel(tree.getModel());
        linksTree.setRootVisible(false);
        for (int row = 0; row < tree.getRowCount(); row++) linksTree.expandRow(row);
    }

    private JTree createLinkedTree(List<Item> linkedItems) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Linked Cards");
        Map<Item.Special, DefaultMutableTreeNode> zoneRoots = new LinkedHashMap<>();
        for (Item linked : linkedItems) {
            Item.Special zone = linked.zone();
            if (zone != Item.Special.DRAFT && zone != Item.Special.RESEARCH) continue;
            DefaultMutableTreeNode parent = zoneRoots.get(zone);
            if (parent == null) {
                parent = new DefaultMutableTreeNode(zone == Item.Special.DRAFT ? "Draft" : "Research");
                zoneRoots.put(zone, parent);
                root.add(parent);
            }
            List<Item> ancestors = new ArrayList<>();
            Item ancestor = linked.parentItem();
            while (ancestor != null && !ancestor.isSpecial()) {
                ancestors.add(0, ancestor);
                ancestor = ancestor.parentItem();
            }
            for (Item folder : ancestors) {
                DefaultMutableTreeNode next = findChild(parent, folder.getTitle());
                if (next == null) {
                    next = new DefaultMutableTreeNode(folder.getTitle());
                    parent.add(next);
                }
                parent = next;
            }
            parent.add(new LinkedNode(linked));
        }
        JTree tree = new JTree(new DefaultTreeModel(root));
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new javax.swing.tree.DefaultTreeCellRenderer() {
            @Override public Component getTreeCellRendererComponent(JTree tree, Object value,
                    boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
                Component component = super.getTreeCellRendererComponent(tree, value, selected,
                        expanded, leaf, row, hasFocus);
                if (value instanceof DefaultMutableTreeNode) {
                    Object user = ((DefaultMutableTreeNode) value).getUserObject();
                    if (user instanceof LinkedNode) {
                        setText(((LinkedNode) user).label);
                        setIcon(Icons.forItem(((LinkedNode) user).item));
                    }
                }
                return component;
            }
        });
        for (int row = 0; row < Math.min(2, tree.getRowCount()); row++) tree.expandRow(row);
        return tree;
    }

    private DefaultMutableTreeNode findChild(DefaultMutableTreeNode parent, String title) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) parent.getChildAt(i);
            if (title.equals(child.getUserObject().toString())) return child;
        }
        return null;
    }

    private void refreshRelationships() {
        relationshipRows.removeAll();
        for (RelationshipLink link : project.relationships()) {
            if (!item.getId().equals(link.getFromId()) && !item.getId().equals(link.getToId())) continue;
            Item other = project.findItem(item.getId().equals(link.getFromId())
                    ? link.getToId() : link.getFromId());
            if (other == null) continue;
            JPanel row = new JPanel(new BorderLayout(8, 0));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.add(new JLabel(other.getTitle() + "  \u00b7  " + link.getType()), BorderLayout.CENTER);
            row.add(iconButton("Delete relationship", Icons.delete(), () -> deleteRelationship(link)), BorderLayout.WEST);
            relationshipRows.add(row);
        }
        relationshipRows.revalidate();
        relationshipRows.repaint();
    }

    private void deleteRelationship(RelationshipLink link) {
        if (JOptionPane.showConfirmDialog(this, "Delete this relationship?",
                "Delete relationship", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        project.removeRelationship(link.getFromId(), link.getToId());
        refreshRelationships();
    }

    private void refreshFields() {
        fieldRows.removeAll();
        for (Map.Entry<String, String> field : item.getFields().entrySet()) addFieldRow(field.getKey(), field.getValue());
        fieldRows.revalidate();
        fieldRows.repaint();
    }

    private void addFieldRow(String name, String value) {
        JPanel row = new JPanel(new BorderLayout(8, 4));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JTextField fieldName = new JTextField(name);
        JTextArea fieldValue = new JTextArea(value, 4, 40);
        fieldValue.setLineWrap(true);
        fieldValue.setWrapStyleWord(true);
        JButton remove = iconButton("Delete optional field", Icons.delete(), () -> {
            Map<String, String> fields = item.getFields();
            fields.remove(name);
            item.setFields(fields);
            project.markDirty();
            refreshFields();
        });
        fieldName.addActionListener(event -> saveField(name, fieldName, fieldValue));
        fieldValue.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { saveField(name, fieldName, fieldValue); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { saveField(name, fieldName, fieldValue); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { saveField(name, fieldName, fieldValue); }
        });
        JPanel valuePanel = new JPanel(new BorderLayout(4, 2));
        valuePanel.add(fieldName, BorderLayout.NORTH);
        valuePanel.add(new JScrollPane(fieldValue), BorderLayout.CENTER);
        row.add(valuePanel, BorderLayout.CENTER);
        row.add(remove, BorderLayout.EAST);
        fieldRows.add(row);
    }

    private void saveField(String originalName, JTextField fieldName, JTextArea fieldValue) {
        if (updating || item == null) return;
        Map<String, String> fields = item.getFields();
        fields.remove(originalName);
        String name = fieldName.getText().trim();
        if (!name.isEmpty()) fields.put(name, fieldValue.getText());
        item.setFields(fields);
        project.markDirty();
    }

    private static final class CollapsibleSection extends JPanel {
        private final JPanel body = new JPanel();
        private final JButton toggle;
        private boolean expanded = true;

        CollapsibleSection(String title) {
            super(new BorderLayout());
            setAlignmentX(Component.LEFT_ALIGNMENT);
            toggle = new JButton("\u25bc  " + title);
            toggle.setHorizontalAlignment(JButton.LEFT);
            toggle.setBorder(BorderFactory.createEmptyBorder(7, 8, 7, 8));
            toggle.setFocusable(false);
            toggle.addActionListener(e -> {
                expanded = !expanded;
                body.setVisible(expanded);
                toggle.setText((expanded ? "\u25bc  " : "\u25b6  ") + title);
                revalidate();
            });
            body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
            body.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new java.awt.Color(0xD6C9B0)),
                    BorderFactory.createEmptyBorder(8, 8, 8, 8)));
            add(toggle, BorderLayout.NORTH);
            add(body, BorderLayout.CENTER);
        }

        void addContent(Component component) {
            if (component instanceof JComponent) ((JComponent) component).setAlignmentX(Component.LEFT_ALIGNMENT);
            body.add(component);
            body.add(Box.createVerticalStrut(6));
        }
    }

    private void addImage() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Images", "png", "jpg", "jpeg", "gif", "bmp", "webp"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            String caption = JOptionPane.showInputDialog(this, "Image caption:",
                    chooser.getSelectedFile().getName());
            String storedPath = project.importResearchImage(chooser.getSelectedFile());
            item.addImage(new ResearchImage(storedPath, caption));
            project.markDirty();
            refreshLists();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Could not import image:\n" + ex.getMessage(),
                    "Image import failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void linkCards() {
        List<Item> candidates = new ArrayList<>();
        for (Item candidate : project.root().subtree()) {
            if (candidate != project.root() && candidate != item
                    && candidate.getKind() == Item.Kind.TEXT) candidates.add(candidate);
        }
        candidates.sort(Comparator.comparing(this::linkedPath, String.CASE_INSENSITIVE_ORDER));
        JTree tree = createLinkedTree(candidates);
        Set<String> selectedIds = new HashSet<>(item.getLinkedCardIds());
        javax.swing.tree.TreeCellRenderer baseRenderer = tree.getCellRenderer();
        tree.setCellRenderer((valueTree, value, selected, expanded, leaf, row, hasFocus) -> {
            Component component = baseRenderer.getTreeCellRendererComponent(
                    valueTree, value, false, expanded, leaf, row, hasFocus);
            if (value instanceof DefaultMutableTreeNode) {
                Object user = ((DefaultMutableTreeNode) value).getUserObject();
                if (user instanceof LinkedNode) {
                    LinkedNode node = (LinkedNode) user;
                    String mark = selectedIds.contains(node.item.getId()) ? "[x] " : "[ ] ";
                    ((JLabel) component).setText(mark + node.label);
                }
            }
            return component;
        });
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                TreePath path = tree.getPathForLocation(event.getX(), event.getY());
                if (path == null) return;
                Object value = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
                if (!(value instanceof LinkedNode)) return;
                LinkedNode node = (LinkedNode) value;
                if (!selectedIds.add(node.item.getId())) selectedIds.remove(node.item.getId());
                tree.clearSelection();
                tree.repaint();
                event.consume();
            }
        });

        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this),
                "Link cards", java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(16, 18, 8, 18));
        JLabel hint = new JLabel("Click cards to toggle them. Folder nodes are only for navigation.");
        content.add(hint, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(tree);
        scroll.setPreferredSize(new Dimension(560, 420));
        content.add(scroll, BorderLayout.CENTER);
        JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 8));
        JButton cancel = new JButton("Cancel");
        JButton save = new JButton("Link selected");
        cancel.addActionListener(event -> dialog.dispose());
        save.addActionListener(event -> {
            item.setLinkedCardIds(new ArrayList<>(selectedIds));
            project.markDirty();
            refreshLists();
            dialog.dispose();
        });
        actions.add(cancel);
        actions.add(save);
        dialog.add(content, BorderLayout.CENTER);
        dialog.add(actions, BorderLayout.SOUTH);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private JButton iconButton(String tooltip, javax.swing.Icon icon, Runnable action) {
        JButton button = new JButton(icon);
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.setMargin(new java.awt.Insets(3, 7, 3, 7));
        button.addActionListener(event -> action.run());
        return button;
    }

    private String linkedPath(Item linked) {
        Item parent = linked.parentItem();
        return (parent == null ? "Project" : parent.getTitle()) + " / "
                + linked.getTitle() + " \u00b7 " + linked.getResearchType();
    }

    private static final class LinkedNode extends DefaultMutableTreeNode {
        private final Item item;
        private final String label;

        LinkedNode(Item item) {
            super(item);
            this.item = item;
            this.label = item.getTitle() + " \u00b7 " + item.getResearchType();
        }

        @Override public String toString() { return label; }
    }

    private final class PrimaryImageNode {
        private final ResearchImage image;
        private final String label;
        private final ImageIcon icon;

        PrimaryImageNode(ResearchImage image) {
            this.image = image;
            File file = new File(project.getDir(), image.getFile());
            this.label = image.getCaption().isEmpty() ? file.getName() : image.getCaption();
            ImageIcon source = file.isFile() ? new ImageIcon(file.getAbsolutePath()) : null;
            if (source == null || source.getIconWidth() <= 0) {
                this.icon = null;
            } else {
                int size = 34;
                double scale = Math.min((double) size / source.getIconWidth(),
                        (double) size / source.getIconHeight());
                this.icon = new ImageIcon(source.getImage().getScaledInstance(
                        Math.max(1, (int) (source.getIconWidth() * scale)),
                        Math.max(1, (int) (source.getIconHeight() * scale)),
                        java.awt.Image.SCALE_SMOOTH));
            }
        }
    }

    private void addRelationship() {
        List<Item> candidates = new ArrayList<>();
        for (Item candidate : project.root().subtree()) {
            if (candidate != item && candidate.getKind() == Item.Kind.TEXT
                    && candidate.getResearchType() == Item.ResearchType.CHARACTER) candidates.add(candidate);
        }
        JComboBox<Item> target = new JComboBox<>(candidates.toArray(new Item[0]));
        JTextField relationshipType = new JTextField("Connected");
        JPanel panel = new JPanel(new java.awt.GridLayout(0, 1, 4, 4));
        panel.add(new JLabel("Connect to"));
        panel.add(target);
        panel.add(new JLabel("Type"));
        panel.add(relationshipType);
        if (JOptionPane.showConfirmDialog(this, panel, "Add relationship",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        Item other = (Item) target.getSelectedItem();
        if (other == null) return;
        project.addRelationship(new RelationshipLink(item.getId(), other.getId(),
                relationshipType.getText(), "", ""));
        refreshLists();
    }

    private void addField() {
        if (item == null) return;
        Map<String, String> values = item.getFields();
        String name = "New field";
        int suffix = 2;
        while (values.containsKey(name)) name = "New field " + suffix++;
        values.put(name, "");
        item.setFields(values);
        project.markDirty();
        refreshFields();
    }
}

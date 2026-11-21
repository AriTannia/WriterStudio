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
import javax.swing.Scrollable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.Timer;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
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
    private final JPanel form = new ResponsiveForm();
    private final JTextField title = new JTextField();
    private final JComboBox<Item.ResearchType> type =
            new JComboBox<>(Item.ResearchType.documentTypes());
    private final JTextArea content = new JTextArea(8, 60);
    private final CollapsibleSection contentSection = new CollapsibleSection("Content");
    private final CollapsibleSection characterSection = new CollapsibleSection("Character Profile");
    private final CollapsibleSection threeActSection = new CollapsibleSection("Three-Act Structure", false);
    private final CollapsibleSection imagesSection = new CollapsibleSection("Images");
    private final CollapsibleSection linksSection = new CollapsibleSection("Linked Cards");
    private final CollapsibleSection fieldsSection = new CollapsibleSection("Optional Fields");
    private final JComboBox<Item.CharacterClass> characterClass =
            new JComboBox<>(Item.CharacterClass.values());
    private final JTextField roles = new JTextField();
    private final JPanel imageGallery = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 12, 8));
    private final JButton primaryImageButton = button("Set primary image", this::choosePrimaryImage);
    private final JTree linksTree = new JTree(new DefaultMutableTreeNode("Linked Cards"));
    private final JPanel relationshipRows = new JPanel();
    private final JPanel fieldRows = new JPanel();
    private final List<FieldEditorBinding> fieldEditors = new ArrayList<>();
    private final Map<String, Boolean> fieldExpansionStates = new LinkedHashMap<>();
    private final JLabel saveNotice = new JLabel(" ");
    private final Timer saveNoticeTimer = new Timer(2600, event -> saveNotice.setText(" "));
    private Item item;
    private boolean updating;
    private final Consumer<Item> openItem;
    private final Runnable onResearchTypeChanged;

    public ResearchEditorPanel(Project project, Consumer<Item> openItem, Runnable onResearchTypeChanged) {
        super(new BorderLayout());
        this.project = project;
        this.openItem = openItem;
        this.onResearchTypeChanged = onResearchTypeChanged;
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBorder(BorderFactory.createEmptyBorder(18, 22, 30, 22));

        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD, 22f));
        addField("Title", title);
        addField("Research Type", type);
        content.setLineWrap(true);
        content.setWrapStyleWord(true);
        JScrollPane contentScroll = new JScrollPane(content);
        contentScroll.setPreferredSize(new Dimension(700, 180));
        allowWidthResize(contentScroll);
        contentSection.addContent(contentScroll);
        form.add(contentSection);

        addThreeActSection();
        addResearchSection();
        addCharacterSection();

        JScrollPane scroll = new JScrollPane(form);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);
        saveNotice.setHorizontalAlignment(JLabel.RIGHT);
        saveNotice.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 16));
        saveNotice.setFont(saveNotice.getFont().deriveFont(java.awt.Font.BOLD, 12f));
        saveNotice.setForeground(new java.awt.Color(0x197A45));
        saveNoticeTimer.setRepeats(false);
        add(saveNotice, BorderLayout.SOUTH);

        title.addActionListener(e -> saveBasics());
        title.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { saveBasics(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { saveBasics(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { saveBasics(); }
        });
        type.addActionListener(e -> saveBasics());
        content.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { saveContent(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { saveContent(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { saveContent(); }
        });
        characterClass.addActionListener(e -> saveCharacter());
    }

    public void showSaveNotice(String message) {
        saveNotice.setText(message);
        saveNotice.setForeground(message.toLowerCase().contains("failed")
                ? new java.awt.Color(0xB3261E) : new java.awt.Color(0x197A45));
        saveNotice.setEnabled(true);
        if ("Saving…".equals(message)) saveNoticeTimer.stop();
        else saveNoticeTimer.restart();
    }

    void keepSaveNoticeEnabled() {
        saveNotice.setEnabled(true);
        saveNotice.setForeground(new java.awt.Color(0x197A45));
    }

    private void addField(String label, Component component) {
        addField(new JLabel(label), component);
    }

    private void addField(JLabel heading, Component component) {
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(heading);
        if (component instanceof JScrollPane) component.setPreferredSize(new Dimension(700, 180));
        if (component instanceof JComponent) {
            ((JComponent) component).setAlignmentX(Component.LEFT_ALIGNMENT);
            allowWidthResize((JComponent) component);
        }
        form.add(component);
        form.add(Box.createVerticalStrut(10));
    }

    private void allowWidthResize(JComponent component) {
        Dimension preferred = component.getPreferredSize();
        component.setMinimumSize(new Dimension(0, preferred.height));
        component.setMaximumSize(new Dimension(Integer.MAX_VALUE, preferred.height));
    }

    private void addResearchSection() {
        imageGallery.setBackground(new java.awt.Color(0xF5F0E7));
        JScrollPane imageScroll = new JScrollPane(imageGallery);
        imageScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        imageScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        imageScroll.setPreferredSize(new Dimension(700, 205));
        allowWidthResize(imageScroll);
        imagesSection.addContent(imageScroll);
        imagesSection.addContent(primaryImageButton);
        imagesSection.addContent(button("+ Add image", this::addImage));
        linksTree.setRootVisible(false);
        linksTree.setShowsRootHandles(true);
        linksTree.addTreeSelectionListener(event -> {
            Object value = linksTree.getLastSelectedPathComponent();
            if (value instanceof LinkedNode && ((LinkedNode) value).item != null) {
                openItem.accept(((LinkedNode) value).item);
            }
        });
        JScrollPane linksScroll = new JScrollPane(linksTree);
        linksScroll.setPreferredSize(new Dimension(700, 160));
        allowWidthResize(linksScroll);
        linksSection.addContent(linksScroll);
        linksSection.addContent(button("+ Link cards", this::linkCards));
        fieldRows.setLayout(new BoxLayout(fieldRows, BoxLayout.Y_AXIS));
        fieldsSection.addContent(fieldRows);
        fieldsSection.addContent(button("+ Add field", this::addField));
        form.add(imagesSection);
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

    private void addThreeActSection() {
        threeActSection.setVisible(false);
        form.add(threeActSection);
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
            for (String fieldName : item.getFields().keySet()) {
                fieldExpansionStates.put(fieldStateKey(item, fieldName), false);
            }
            this.item = item;
            title.setText(item.getTitle());
            type.setSelectedItem(item.getResearchType());
            content.setText(item.getContent());
            boolean threeAct = item.getResearchType() == Item.ResearchType.THREE_ACT_STRUCTURE;
            contentSection.setVisible(!threeAct);
            characterSection.setVisible(item.getResearchType() == Item.ResearchType.CHARACTER);
            threeActSection.setVisible(threeAct);
            setResearchSectionsVisible(!threeAct);
            primaryImageButton.setVisible(item.getResearchType() == Item.ResearchType.CHARACTER);
            refreshLists();
        } finally {
            updating = false;
        }
        form.revalidate();
        form.repaint();
    }

    /** Commit all currently displayed research controls into their owning item before save or navigation. */
    public void flush() {
        for (FieldEditorBinding binding : new ArrayList<>(fieldEditors)) {
            saveField(binding);
            saveFieldValue(binding);
        }
    }

    private void saveBasics() {
        if (updating || item == null) return;
        Item.ResearchType previousType = item.getResearchType();
        String value = title.getText().trim();
        if (!value.isEmpty() && !value.equals(item.getTitle())) item.setTitle(value);
        item.setResearchType((Item.ResearchType) type.getSelectedItem());
        boolean threeAct = item.getResearchType() == Item.ResearchType.THREE_ACT_STRUCTURE;
        contentSection.setVisible(!threeAct);
        characterSection.setVisible(item.getResearchType() == Item.ResearchType.CHARACTER);
        threeActSection.setVisible(threeAct);
        setResearchSectionsVisible(!threeAct);
        primaryImageButton.setVisible(item.getResearchType() == Item.ResearchType.CHARACTER);
        if (item.getResearchType() == Item.ResearchType.THREE_ACT_STRUCTURE) refreshThreeActFields();
        project.changedQuietly(item);
        if (previousType != item.getResearchType()) onResearchTypeChanged.run();
        form.revalidate();
    }

    private void setResearchSectionsVisible(boolean visible) {
        imagesSection.setVisible(visible);
        linksSection.setVisible(visible);
        fieldsSection.setVisible(visible);
    }

    private void saveContent() {
        if (updating || item == null || item.getResearchType() == Item.ResearchType.THREE_ACT_STRUCTURE) return;
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
        refreshThreeActFields();
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
            JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 3, 0));
            actions.add(iconButton("Edit relationship", Icons.edit(), () -> editRelationship(link)));
            actions.add(iconButton("Delete relationship", Icons.delete(), () -> deleteRelationship(link)));
            row.add(actions, BorderLayout.EAST);
            relationshipRows.add(row);
        }
        relationshipRows.revalidate();
        relationshipRows.repaint();
    }

    private void deleteRelationship(RelationshipLink link) {
        if (JOptionPane.showConfirmDialog(this, "Delete this relationship?",
                "Delete relationship", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        project.removeRelationship(link);
        refreshRelationships();
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
        if (JOptionPane.showConfirmDialog(this, form, "Edit relationship",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        link.setType(name.getText().trim());
        link.setArrowDirection((RelationshipLink.ArrowDirection) direction.getSelectedItem());
        project.markDirty();
        refreshRelationships();
    }

    private void refreshFields() {
        fieldRows.removeAll();
        fieldEditors.clear();
        for (Map.Entry<String, String> field : item.getFields().entrySet()) {
            addFieldRow(item, field.getKey(), field.getValue());
        }
        fieldRows.revalidate();
        fieldRows.repaint();
    }

    static final ThreeActField[] THREE_ACT_FIELDS = {
            new ThreeActField("1. Opening Image", "0-1%",
                    "Image showing the initial state of the character and world."),
            new ThreeActField("2. Theme Stated", "5%",
                    "The main theme is introduced through a character or situation."),
            new ThreeActField("3. Set-Up", "1-10%",
                    "Introduce the characters, world, relationships, circumstances, and problems that must change."),
            new ThreeActField("4. Catalyst", "10%",
                    "An unexpected event breaks the ordinary world and starts the story."),
            new ThreeActField("5. Debate", "10-20%",
                    "The character hesitates, weighs the event, and questions the choice ahead."),
            new ThreeActField("6. Break into Two", "20-25%",
                    "The character chooses and enters the world of Act II."),
            new ThreeActField("7. B Story", "22-30%",
                    "A subplot, often about relationships or theme, helps the character change."),
            new ThreeActField("8. Fun and Games", "30-55%",
                    "Deliver the promise of the premise while exploring the story's world and situations."),
            new ThreeActField("9. Midpoint", "50%",
                    "The middle turning point: often a false victory or false defeat that raises the stakes."),
            new ThreeActField("10. Bad Guys Close In", "50-75%",
                    "External pressure and internal conflict intensify; everything starts working against the character."),
            new ThreeActField("11. All Is Lost", "75%",
                    "The character reaches the lowest point after a major loss or failure."),
            new ThreeActField("12. Dark Night of the Soul", "75-80%",
                    "The character faces failure, reflects, and realizes what they have not understood."),
            new ThreeActField("13. Break into Three", "80%",
                    "The character finds a new solution by combining lessons from the A Story and B Story."),
            new ThreeActField("14. Finale", "80-99%",
                    "The character applies the new understanding, faces the final challenge, and resolves the main conflict."),
            new ThreeActField("15. Final Image", "100%",
                    "An ending image reflecting the Opening Image and showing how the character and world changed.")
    };

    private void refreshThreeActFields() {
        threeActSection.removeAllContent();
        if (item == null || item.getResearchType() != Item.ResearchType.THREE_ACT_STRUCTURE) return;
        Map<String, String> values = item.getThreeActFields();
        boolean changed = false;
        for (ThreeActField beat : THREE_ACT_FIELDS) {
            if (!values.containsKey(beat.name)) {
                values.put(beat.name, "");
                changed = true;
            }
        }
        if (changed) {
            item.setThreeActFields(values);
            project.markDirty();
        }

        addThreeActGroup("ACT I - SETUP", "0-25%", THREE_ACT_FIELDS, 0, 5);
        addThreeActGroup("ACT II - CONFRONTATION", "25-75%", THREE_ACT_FIELDS, 6, 12);
        addThreeActGroup("ACT III - RESOLUTION", "80-100%", THREE_ACT_FIELDS, 13, 14);
        threeActSection.revalidate();
        threeActSection.repaint();
    }

    private void addThreeActGroup(String title, String range, ThreeActField[] fields, int start, int end) {
        CollapsibleSection actSection = new CollapsibleSection(title + "  " + range);
        for (int i = start; i <= end; i++) addThreeActField(actSection, fields[i]);
        threeActSection.addContent(actSection);
    }

    private void addThreeActField(CollapsibleSection parent, ThreeActField beat) {
        JTextArea value = new JTextArea(item.getThreeActFields().get(beat.name), 4, 60);
        value.setLineWrap(true);
        value.setWrapStyleWord(true);
        value.setAlignmentX(Component.LEFT_ALIGNMENT);
        value.setPreferredSize(new Dimension(700, 90));
        value.setMinimumSize(new Dimension(0, 90));
        value.setMaximumSize(new Dimension(Integer.MAX_VALUE, 110));
        JLabel hint = new JLabel("<html><i>" + escapeHtml(beat.description) + "</i></html>");
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        value.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { saveThreeActField(beat.name, value); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { saveThreeActField(beat.name, value); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { saveThreeActField(beat.name, value); }
        });
        CollapsibleSection fieldSection = new CollapsibleSection(
                beat.name + (beat.range.isEmpty() ? "" : "  " + beat.range));
        fieldSection.addContent(hint);
        fieldSection.addContent(value);
        parent.addContent(fieldSection);
    }

    private void saveThreeActField(String name, JTextArea value) {
        if (updating || item == null || item.getResearchType() != Item.ResearchType.THREE_ACT_STRUCTURE) return;
        Map<String, String> fields = item.getThreeActFields();
        fields.put(name, value.getText());
        item.setThreeActFields(fields);
        project.markDirty();
    }

    private void addFieldRow(Item owner, String name, String value) {
        CollapsibleSection fieldSection = new CollapsibleSection(name);
        fieldSection.setBorder(BorderFactory.createLineBorder(new java.awt.Color(0xD6C9B0)));
        String initialStateKey = fieldStateKey(owner, name);
        fieldSection.setExpanded(fieldExpansionStates.getOrDefault(initialStateKey, false));
        JTextField fieldName = new JTextField(name);
        fieldName.setToolTipText("Field name");
        JTextArea fieldValue = new JTextArea(value, 4, 40);
        String[] storedName = {name};
        FieldEditorBinding binding = new FieldEditorBinding(owner, storedName, fieldName, fieldValue, fieldSection);
        fieldEditors.add(binding);
        fieldSection.setExpansionListener(expanded -> fieldExpansionStates.put(
                fieldStateKey(owner, binding.storedName[0]), expanded));
        fieldValue.setLineWrap(true);
        fieldValue.setWrapStyleWord(true);
        fieldValue.setBorder(BorderFactory.createEmptyBorder(5, 7, 5, 7));
        fieldValue.setMinimumSize(new Dimension(0, 76));
        JButton remove = iconButton("Delete optional field", Icons.delete(), () -> {
            Map<String, String> fields = owner.getFields();
            fields.remove(storedName[0]);
            owner.setFields(fields);
            fieldExpansionStates.remove(fieldStateKey(owner, storedName[0]));
            project.markDirty();
            if (item == owner) refreshFields();
        });
        remove.setMargin(new java.awt.Insets(4, 6, 4, 6));
        JPanel nameRow = new JPanel(new BorderLayout(8, 0));
        nameRow.setOpaque(false);
        nameRow.add(new JLabel("Name"), BorderLayout.WEST);
        nameRow.add(fieldName, BorderLayout.CENTER);
        nameRow.add(remove, BorderLayout.EAST);
        JPanel valuePanel = new JPanel(new BorderLayout(0, 4));
        valuePanel.setOpaque(false);
        valuePanel.add(new JLabel("Value"), BorderLayout.NORTH);
        JScrollPane valueScroll = new JScrollPane(fieldValue,
                JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        valueScroll.setBorder(BorderFactory.createLineBorder(new java.awt.Color(0xC8BFAF)));
        valueScroll.setPreferredSize(new Dimension(680, 120));
        valueScroll.setMinimumSize(new Dimension(0, 100));
        valueScroll.setWheelScrollingEnabled(true);
        valueScroll.getVerticalScrollBar().setUnitIncrement(18);
        valuePanel.add(valueScroll, BorderLayout.CENTER);
        JPanel resizeRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0));
        resizeRow.setOpaque(false);
        JLabel resizeGrip = new JLabel("\u25e2");
        resizeGrip.setForeground(new java.awt.Color(0x7D756A));
        resizeGrip.setHorizontalAlignment(JLabel.CENTER);
        resizeGrip.setPreferredSize(new Dimension(20, 16));
        resizeGrip.setCursor(Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR));
        resizeGrip.setToolTipText("Drag to resize this field");
        resizeRow.add(resizeGrip);
        valuePanel.add(resizeRow, BorderLayout.SOUTH);
        final Point[] resizeStartPoint = {null};
        final Dimension[] resizeStartSize = {null};
        updateFieldValueSize(fieldValue, valueScroll);
        valueScroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) {
                updateFieldValueSize(fieldValue, valueScroll);
            }
        });
        resizeGrip.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                resizeStartPoint[0] = event.getLocationOnScreen();
                resizeStartSize[0] = valueScroll.getPreferredSize();
            }

            @Override public void mouseReleased(MouseEvent event) {
                resizeStartPoint[0] = null;
                resizeStartSize[0] = null;
            }
        });
        resizeGrip.addMouseMotionListener(new MouseAdapter() {
            @Override public void mouseDragged(MouseEvent event) {
                if (resizeStartPoint[0] == null || resizeStartSize[0] == null) return;
                Point current = event.getLocationOnScreen();
                int height = Math.max(100, Math.min(520,
                        resizeStartSize[0].height + current.y - resizeStartPoint[0].y));
                valueScroll.setPreferredSize(new Dimension(resizeStartSize[0].width, height));
                valuePanel.revalidate();
                fieldSection.revalidate();
                fieldRows.revalidate();
                form.revalidate();
            }
        });
        fieldSection.addContent(nameRow);
        fieldSection.addContent(valuePanel);

        fieldName.addActionListener(event -> saveField(binding));
        fieldName.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent event) { saveField(binding); }
        });
        fieldName.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void updateTitle() {
                String title = fieldName.getText().trim();
                fieldSection.setTitle(title.isEmpty() ? "Untitled field" : title);
            }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { updateTitle(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { updateTitle(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { updateTitle(); }
        });
        fieldValue.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                updateFieldValueSize(fieldValue, valueScroll);
                saveFieldValue(binding);
            }
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                updateFieldValueSize(fieldValue, valueScroll);
                saveFieldValue(binding);
            }
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                updateFieldValueSize(fieldValue, valueScroll);
                saveFieldValue(binding);
            }
        });
        fieldRows.add(fieldSection);
        fieldRows.add(Box.createVerticalStrut(8));
    }

    private void updateFieldValueSize(JTextArea textArea, JScrollPane scroll) {
        int viewWidth = scroll.getViewport().getWidth();
        if (viewWidth <= 0) viewWidth = Math.max(320,
                scroll.getPreferredSize().width - scroll.getVerticalScrollBar().getPreferredSize().width - 4);
        java.awt.Insets insets = textArea.getInsets();
        int lineWidth = Math.max(80, viewWidth - insets.left - insets.right - 4);
        FontMetrics metrics = textArea.getFontMetrics(textArea.getFont());
        int visualLines = 0;
        for (String line : textArea.getText().split("\\n", -1)) {
            visualLines += wrappedLineCount(line, metrics, lineWidth);
        }
        visualLines = Math.max(4, Math.min(20000, visualLines));
        int height = visualLines * metrics.getHeight() + insets.top + insets.bottom;
        Dimension preferred = new Dimension(viewWidth, height);
        if (!preferred.equals(textArea.getPreferredSize())) {
            textArea.setPreferredSize(preferred);
            textArea.revalidate();
            scroll.revalidate();
        }
    }

    private int wrappedLineCount(String text, FontMetrics metrics, int maxWidth) {
        if (text.isEmpty()) return 1;
        int rows = 1;
        int currentWidth = 0;
        int spaceWidth = metrics.stringWidth(" ");
        for (String word : text.split("\\s+")) {
            int wordWidth = metrics.stringWidth(word);
            if (currentWidth > 0 && currentWidth + spaceWidth + wordWidth <= maxWidth) {
                currentWidth += spaceWidth + wordWidth;
                continue;
            }
            if (currentWidth > 0) {
                rows++;
                currentWidth = 0;
            }
            if (wordWidth <= maxWidth) {
                currentWidth = wordWidth;
            } else {
                rows += wordWidth / maxWidth;
                currentWidth = wordWidth % maxWidth;
                if (currentWidth == 0) {
                    rows--;
                    currentWidth = maxWidth;
                }
            }
        }
        return rows;
    }

    private static final class FieldEditorBinding {
        final Item owner;
        final String[] storedName;
        final JTextField fieldName;
        final JTextArea fieldValue;
        final CollapsibleSection section;

        FieldEditorBinding(Item owner, String[] storedName, JTextField fieldName, JTextArea fieldValue,
                           CollapsibleSection section) {
            this.owner = owner;
            this.storedName = storedName;
            this.fieldName = fieldName;
            this.fieldValue = fieldValue;
            this.section = section;
        }
    }

    static final class ThreeActField {
        final String name;
        final String range;
        final String description;

        ThreeActField(String name, String range, String description) {
            this.name = name;
            this.range = range;
            this.description = description;
        }
    }

    private static final class ResponsiveForm extends JPanel implements Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 20;
        }
        @Override public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(20, visibleRect.height - 20);
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    private void saveField(FieldEditorBinding binding) {
        if (updating || binding.owner == null) return;
        Map<String, String> fields = binding.owner.getFields();
        String name = binding.fieldName.getText().trim();
        if (name.isEmpty()) {
            binding.fieldName.setText(binding.storedName[0]);
            return;
        }
        if (!name.equals(binding.storedName[0]) && fields.containsKey(name)) {
            JOptionPane.showMessageDialog(this, "A field with that name already exists.",
                    "Rename field", JOptionPane.WARNING_MESSAGE);
            binding.fieldName.setText(binding.storedName[0]);
            return;
        }
        if (name.equals(binding.storedName[0])
                && java.util.Objects.equals(fields.get(name), binding.fieldValue.getText())) return;
        String oldName = binding.storedName[0];
        boolean wasExpanded = binding.section.isExpanded();
        fields.remove(oldName);
        fields.put(name, binding.fieldValue.getText());
        fieldExpansionStates.remove(fieldStateKey(binding.owner, oldName));
        binding.storedName[0] = name;
        fieldExpansionStates.put(fieldStateKey(binding.owner, name), wasExpanded);
        binding.owner.setFields(fields);
        project.markDirty();
    }

    private void saveFieldValue(FieldEditorBinding binding) {
        if (updating || binding.owner == null || binding.storedName[0].isEmpty()) return;
        Map<String, String> fields = binding.owner.getFields();
        if (java.util.Objects.equals(fields.get(binding.storedName[0]), binding.fieldValue.getText())) return;
        fields.put(binding.storedName[0], binding.fieldValue.getText());
        binding.owner.setFields(fields);
        project.markDirty();
    }

    private String fieldStateKey(Item owner, String fieldName) {
        return owner.getId() + "\u0000" + fieldName;
    }

    private static final class CollapsibleSection extends JPanel {
        private final JPanel body = new JPanel();
        private final JButton toggle;
        private String title;
        private boolean expanded = true;
        private java.util.function.Consumer<Boolean> expansionListener;

        CollapsibleSection(String title) {
            this(title, true);
        }

        CollapsibleSection(String title, boolean collapsible) {
            super(new BorderLayout());
            this.title = title;
            setAlignmentX(Component.LEFT_ALIGNMENT);
            toggle = new JButton("\u25bc  " + title);
            toggle.setHorizontalAlignment(JButton.LEFT);
            toggle.setBorder(BorderFactory.createEmptyBorder(7, 8, 7, 8));
            toggle.setFocusable(false);
            toggle.addActionListener(e -> {
                setExpanded(!expanded);
                if (expansionListener != null) expansionListener.accept(expanded);
            });
            body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
            body.setBorder(collapsible
                    ? BorderFactory.createCompoundBorder(
                            BorderFactory.createLineBorder(new java.awt.Color(0xD6C9B0)),
                            BorderFactory.createEmptyBorder(8, 8, 8, 8))
                    : BorderFactory.createEmptyBorder(8, 0, 8, 0));
            if (collapsible) add(toggle, BorderLayout.NORTH);
            add(body, BorderLayout.CENTER);
        }

        void setTitle(String value) {
            title = value;
            toggle.setText((expanded ? "\u25bc  " : "\u25b6  ") + title);
        }

        void setExpanded(boolean value) {
            expanded = value;
            body.setVisible(expanded);
            toggle.setText((expanded ? "\u25bc  " : "\u25b6  ") + title);
            revalidate();
        }

        boolean isExpanded() { return expanded; }

        void setExpansionListener(java.util.function.Consumer<Boolean> listener) {
            expansionListener = listener;
        }

        void addContent(Component component) {
            if (component instanceof JComponent) ((JComponent) component).setAlignmentX(Component.LEFT_ALIGNMENT);
            body.add(component);
            body.add(Box.createVerticalStrut(6));
        }

        void removeAllContent() {
            body.removeAll();
        }
    }

    private void addImage() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Images", "png", "jpg", "jpeg", "gif", "bmp", "webp"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            java.awt.image.BufferedImage cropped = ImageCropDialog.show(this, chooser.getSelectedFile());
            if (cropped == null) return;
            String caption = JOptionPane.showInputDialog(this, "Image caption:",
                    chooser.getSelectedFile().getName());
            String storedPath = project.importResearchImage(cropped);
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
        Item chartFolder = item.getParent() instanceof Item
                && ((Item) item.getParent()).isRelationshipFolder() ? (Item) item.getParent() : null;
        for (Item candidate : project.root().subtree()) {
            if (candidate != item && candidate.getKind() == Item.Kind.TEXT
                    && candidate.getResearchType() == Item.ResearchType.CHARACTER
                    && (chartFolder == null || candidate.getParent() == chartFolder)) candidates.add(candidate);
        }
        JComboBox<Item> target = new JComboBox<>(candidates.toArray(new Item[0]));
        JTextField relationshipName = new JTextField("Connected");
        JComboBox<RelationshipLink.ArrowDirection> direction =
                new JComboBox<>(RelationshipLink.ArrowDirection.values());
        JPanel panel = new JPanel(new java.awt.GridLayout(0, 1, 4, 4));
        panel.add(new JLabel("Connect to"));
        panel.add(target);
        panel.add(new JLabel("Relationship name"));
        panel.add(relationshipName);
        panel.add(new JLabel("Type (arrow direction)"));
        panel.add(direction);
        if (JOptionPane.showConfirmDialog(this, panel, "Add relationship",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        Item other = (Item) target.getSelectedItem();
        if (other == null) return;
        RelationshipLink link = new RelationshipLink(item.getId(), other.getId(),
                relationshipName.getText(), "", "");
        link.setArrowDirection((RelationshipLink.ArrowDirection) direction.getSelectedItem());
        project.addRelationship(link);
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
        fieldExpansionStates.put(fieldStateKey(item, name), true);
        project.markDirty();
        refreshFields();
    }
}

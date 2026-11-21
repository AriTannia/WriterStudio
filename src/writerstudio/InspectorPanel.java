package writerstudio;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JList;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Image;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Right-hand panel with the metadata of the selected item. */
public class InspectorPanel extends JPanel {
    private final Project project;
    private final JLabel title = new JLabel(" ");
    private final JTextArea synopsis = new JTextArea(7, 18);
    private final JLabel synopsisLabel = new JLabel("Synopsis");
    private final JScrollPane synopsisScroll = new JScrollPane(synopsis);
    private final JComboBox<String> label = new JComboBox<>(Project.LABELS);
    private final JComboBox<String> status = new JComboBox<>(Project.STATUSES);
    private final JLabel labelTitle = new JLabel("Label");
    private final JComboBox<Item.ResearchType> researchType =
            new JComboBox<>(Item.ResearchType.documentTypes());
    private final JLabel researchTypeLabel = new JLabel("Research Type");
    private final JCheckBox include = new JCheckBox("Include in Compile");
    private final JLabel stats = new JLabel(" ");
    private final JPanel characterDetails = new JPanel();
    private final JComboBox<Item.CharacterClass> characterClass =
            new JComboBox<>(Item.CharacterClass.values());
    private final JTextField roles = new JTextField();
    private final JLabel portrait = new JLabel("No portrait");
    private final DefaultListModel<String> linkedModel = new DefaultListModel<>();
    private final JList<String> linkedCards = new JList<>(linkedModel);
    private final DefaultListModel<String> imageModel = new DefaultListModel<>();
    private final JList<String> images = new JList<>(imageModel);
    private final DefaultListModel<String> relationshipModel = new DefaultListModel<>();
    private final JList<String> relationships = new JList<>(relationshipModel);
    private final DefaultListModel<String> fieldModel = new DefaultListModel<>();
    private final JList<String> optionalFields = new JList<>(fieldModel);
    private final JPanel researchDetails = new JPanel();

    private Item item;
    private boolean updating;

    public InspectorPanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        setPreferredSize(new Dimension(270, 400));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));
        synopsis.setLineWrap(true);
        synopsis.setWrapStyleWord(true);

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        add(form, BorderLayout.NORTH);

        form.add(left(title));
        form.add(Box.createVerticalStrut(10));
        form.add(left(researchTypeLabel));
        researchType.setAlignmentX(Component.LEFT_ALIGNMENT);
        researchType.setMaximumSize(new Dimension(Integer.MAX_VALUE, researchType.getPreferredSize().height));
        form.add(researchType);
        form.add(Box.createVerticalStrut(8));
        form.add(left(synopsisLabel));
        synopsisScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(synopsisScroll);
        form.add(Box.createVerticalStrut(10));
        form.add(left(labelTitle));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setMaximumSize(new Dimension(Integer.MAX_VALUE, label.getPreferredSize().height));
        form.add(label);
        form.add(Box.createVerticalStrut(8));
        form.add(left(new JLabel("Status")));
        status.setAlignmentX(Component.LEFT_ALIGNMENT);
        status.setMaximumSize(new Dimension(Integer.MAX_VALUE, status.getPreferredSize().height));
        form.add(status);
        form.add(Box.createVerticalStrut(8));
        form.add(left(include));
        form.add(Box.createVerticalStrut(14));
        form.add(left(stats));

        researchDetails.setLayout(new BoxLayout(researchDetails, BoxLayout.Y_AXIS));
        researchDetails.setBorder(BorderFactory.createTitledBorder("Research card"));
        researchDetails.setAlignmentX(Component.LEFT_ALIGNMENT);
        researchDetails.add(left(new JLabel("Images and captions")));
        researchDetails.add(new JScrollPane(images));
        JButton addResearchImage = new JButton("+ Add image");
        addResearchImage.setAlignmentX(Component.LEFT_ALIGNMENT);
        addResearchImage.setFocusable(false);
        addResearchImage.addActionListener(e -> addImage());
        researchDetails.add(addResearchImage);
        researchDetails.add(left(new JLabel("Linked Cards")));
        researchDetails.add(new JScrollPane(linkedCards));
        JButton linkResearchCard = new JButton("+ Link cards");
        linkResearchCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        linkResearchCard.setFocusable(false);
        linkResearchCard.addActionListener(e -> linkCards());
        researchDetails.add(linkResearchCard);
        researchDetails.add(left(new JLabel("Optional fields")));
        researchDetails.add(new JScrollPane(optionalFields));
        JButton addResearchField = new JButton("+ Add field");
        addResearchField.setAlignmentX(Component.LEFT_ALIGNMENT);
        addResearchField.setFocusable(false);
        addResearchField.addActionListener(e -> addOptionalField());
        researchDetails.add(addResearchField);
        form.add(researchDetails);

        characterDetails.setLayout(new BoxLayout(characterDetails, BoxLayout.Y_AXIS));
        characterDetails.setBorder(BorderFactory.createTitledBorder("Character profile"));
        characterDetails.setAlignmentX(Component.LEFT_ALIGNMENT);
        portrait.setAlignmentX(Component.LEFT_ALIGNMENT);
        portrait.setPreferredSize(new Dimension(120, 120));
        characterDetails.add(portrait);
        JButton addPortrait = new JButton("Add portrait");
        addPortrait.setAlignmentX(Component.LEFT_ALIGNMENT);
        addPortrait.setFocusable(false);
        addPortrait.addActionListener(e -> addImage());
        characterDetails.add(addPortrait);
        characterDetails.add(Box.createVerticalStrut(6));
        characterDetails.add(left(new JLabel("Character Class")));
        characterClass.setAlignmentX(Component.LEFT_ALIGNMENT);
        characterDetails.add(characterClass);
        characterDetails.add(left(new JLabel("Roles (comma separated)")));
        roles.setAlignmentX(Component.LEFT_ALIGNMENT);
        characterDetails.add(roles);
        JButton saveRoles = new JButton("Save roles");
        saveRoles.setAlignmentX(Component.LEFT_ALIGNMENT);
        saveRoles.setFocusable(false);
        saveRoles.addActionListener(e -> saveCharacterBasics());
        characterDetails.add(saveRoles);
        characterDetails.add(left(new JLabel("Relationships")));
        characterDetails.add(new JScrollPane(relationships));
        JButton addRelationship = new JButton("+ Add relationship");
        addRelationship.setAlignmentX(Component.LEFT_ALIGNMENT);
        addRelationship.setFocusable(false);
        addRelationship.addActionListener(e -> addRelationship());
        characterDetails.add(addRelationship);
        form.add(characterDetails);

        synopsis.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { store(); }
            @Override public void removeUpdate(DocumentEvent e) { store(); }
            @Override public void changedUpdate(DocumentEvent e) { store(); }
            private void store() {
                if (updating || item == null) return;
                item.setSynopsis(synopsis.getText());
                InspectorPanel.this.project.markDirty();
            }
        });
        label.addActionListener(e -> {
            if (updating || item == null) return;
            item.setLabel((String) label.getSelectedItem());
            this.project.changedQuietly(item);
        });
        status.addActionListener(e -> {
            if (updating || item == null) return;
            item.setStatus((String) status.getSelectedItem());
            this.project.changedQuietly(item);
        });
        researchType.addActionListener(e -> {
            if (updating || item == null || item.zone() != Item.Special.RESEARCH) return;
            item.setResearchType((Item.ResearchType) researchType.getSelectedItem());
            this.project.changedQuietly(item);
        });
        characterClass.addActionListener(e -> saveCharacterBasics());
        include.addActionListener(e -> {
            if (updating || item == null) return;
            item.setInclude(include.isSelected());
            this.project.changedQuietly(item);
        });
        setItem(null);
    }

    private static Component left(JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }

    public void setItem(Item it) {
        updating = true;
        try {
            this.item = it;
            boolean on = it != null;
            synopsis.setEnabled(on);
            boolean research = on && !it.isSpecial()
                    && it.getKind() == Item.Kind.TEXT
                    && it.zone() == Item.Special.RESEARCH;
            researchTypeLabel.setVisible(research);
            researchType.setVisible(research);
            researchType.setEnabled(research);
            boolean showLabel = on && !it.isSpecial() && it.zone() != Item.Special.RESEARCH;
            labelTitle.setVisible(showLabel);
            label.setVisible(showLabel);
            label.setEnabled(showLabel);
            boolean character = research && it.getResearchType() == Item.ResearchType.CHARACTER;
            researchDetails.setVisible(false);
            characterDetails.setVisible(false);
            synopsisLabel.setVisible(!research);
            synopsisScroll.setVisible(!research);
            researchTypeLabel.setVisible(false);
            researchType.setVisible(false);
            status.setEnabled(on && !it.isSpecial());
            include.setEnabled(on && !it.isSpecial());
            if (!on) {
                title.setText("Details");
                synopsis.setText("");
                stats.setText("Select one item to see its details.");
            } else {
                title.setText(it.getTitle());
                researchType.setSelectedItem(it.getResearchType());
                synopsis.setText(it.getSynopsis());
                label.setSelectedItem(it.getLabel());
                status.setSelectedItem(it.getStatus());
                include.setSelected(it.isInclude());
                refreshStats();
                if (research) refreshResearchDetails(it);
                if (character) refreshCharacterDetails(it);
            }
        } finally {
            updating = false;
            revalidate();
        }
    }

    private void refreshCharacterDetails(Item character) {
        characterClass.setSelectedItem(character.getCharacterClass());
        roles.setText(String.join(", ", character.getRoles()));
        linkedModel.clear();
        for (String id : character.getLinkedCardIds()) {
            Item linked = project.findItem(id);
            if (linked != null) linkedModel.addElement(linked.getTitle() + " \u00b7 " + linked.getResearchType());
        }
        relationshipModel.clear();
        for (RelationshipLink link : project.relationships()) {
            if (!character.getId().equals(link.getFromId()) && !character.getId().equals(link.getToId())) continue;
            Item other = project.findItem(character.getId().equals(link.getFromId())
                    ? link.getToId() : link.getFromId());
            if (other != null) relationshipModel.addElement(other.getTitle() + " \u00b7 " + link.getType());
        }

        fieldModel.clear();
        for (java.util.Map.Entry<String, String> field : character.getFields().entrySet()) {
            fieldModel.addElement(field.getKey() + ": " + field.getValue());
        }
        if (character.getImages().isEmpty()) {
            portrait.setIcon(null);
            portrait.setText("No portrait");
        } else {
            ResearchImage image = character.getImages().get(0);
            File file = new File(project.getDir(), image.getFile());
            portrait.setText(image.getCaption());
            portrait.setIcon(file.exists()
                    ? new ImageIcon(new ImageIcon(file.getAbsolutePath()).getImage()
                    .getScaledInstance(110, 110, Image.SCALE_SMOOTH)) : null);
        }
    }

    private void refreshResearchDetails(Item researchItem) {
        imageModel.clear();
        for (ResearchImage image : researchItem.getImages()) {
            imageModel.addElement(image.getFile() + (image.getCaption().isEmpty()
                    ? "" : " \u2014 " + image.getCaption()));
        }
        linkedModel.clear();
        for (String id : researchItem.getLinkedCardIds()) {
            Item linked = project.findItem(id);
            if (linked != null) linkedModel.addElement(linked.getTitle() + " \u00b7 " + linked.getResearchType());
        }
        fieldModel.clear();
        for (java.util.Map.Entry<String, String> field : researchItem.getFields().entrySet()) {
            fieldModel.addElement(field.getKey() + ": " + field.getValue());
        }
    }

    private void saveCharacterBasics() {
        if (item == null || item.getResearchType() != Item.ResearchType.CHARACTER) return;
        item.setCharacterClass((Item.CharacterClass) characterClass.getSelectedItem());
        List<String> values = new ArrayList<>();
        for (String role : roles.getText().split(",")) if (!role.trim().isEmpty()) values.add(role.trim());
        item.setRoles(values);
        project.changedQuietly(item);
    }

    private void addImage() {
        if (item == null) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Images", "png", "jpg", "jpeg", "gif", "bmp", "webp"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            java.awt.image.BufferedImage cropped = ImageCropDialog.show(this, chooser.getSelectedFile());
            if (cropped == null) return;
            String caption = JOptionPane.showInputDialog(this, "Image caption:", chooser.getSelectedFile().getName());
            String storedPath = project.importResearchImage(cropped);
            item.addImage(new ResearchImage(storedPath, caption));
            project.markDirty();
            refreshResearchDetails(item);
            refreshCharacterDetails(item);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Could not import image:\n" + ex.getMessage(),
                    "Image import failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void linkCards() {
        if (item == null) return;
        List<Item> candidates = new ArrayList<>();
        for (Item candidate : project.root().subtree()) {
            if (candidate != project.root() && candidate != item
                    && candidate.getKind() == Item.Kind.TEXT) candidates.add(candidate);
        }
        JList<Item> list = new JList<>(candidates.toArray(new Item[0]));
        list.setSelectionMode(javax.swing.ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        if (JOptionPane.showConfirmDialog(this, new JScrollPane(list), "Link cards",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        List<String> ids = new ArrayList<>();
        for (Item selected : list.getSelectedValuesList()) ids.add(selected.getId());
        item.setLinkedCardIds(ids);
        project.markDirty();
        refreshCharacterDetails(item);
    }

    private void addRelationship() {
        if (item == null) return;
        List<Item> candidates = new ArrayList<>();
        Item chartFolder = item.getParent() instanceof Item
                && ((Item) item.getParent()).isRelationshipFolder() ? (Item) item.getParent() : null;
        for (Item candidate : project.root().subtree()) {
            if (candidate != item && candidate.getKind() == Item.Kind.TEXT
                    && candidate.getResearchType() == Item.ResearchType.CHARACTER
                    && (chartFolder == null || candidate.getParent() == chartFolder)) candidates.add(candidate);
        }
        JComboBox<Item> target = new JComboBox<>(candidates.toArray(new Item[0]));
        JTextField name = new JTextField("Connected");
        JComboBox<RelationshipLink.ArrowDirection> direction =
                new JComboBox<>(RelationshipLink.ArrowDirection.values());
        JPanel form = new JPanel(new java.awt.GridLayout(0, 1, 4, 4));
        form.add(new JLabel("Connect to"));
        form.add(target);
        form.add(new JLabel("Relationship name"));
        form.add(name);
        form.add(new JLabel("Type (arrow direction)"));
        form.add(direction);
        if (JOptionPane.showConfirmDialog(this, form, "Add relationship",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        Item other = (Item) target.getSelectedItem();
        if (other == null) return;
        RelationshipLink link = new RelationshipLink(item.getId(), other.getId(), name.getText(), "", "");
        link.setArrowDirection((RelationshipLink.ArrowDirection) direction.getSelectedItem());
        project.addRelationship(link);
        refreshCharacterDetails(item);
    }

    private void addOptionalField() {
        if (item == null) return;
        JTextField name = new JTextField();
        JTextField value = new JTextField();
        JPanel form = new JPanel(new java.awt.GridLayout(0, 1, 4, 4));
        form.add(new JLabel("Field name"));
        form.add(name);
        form.add(new JLabel("Value"));
        form.add(value);
        if (JOptionPane.showConfirmDialog(this, form, "Add optional field",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        if (name.getText().trim().isEmpty()) return;
        java.util.Map<String, String> fields = item.getFields();
        fields.put(name.getText().trim(), value.getText());
        item.setFields(fields);
        project.markDirty();
        refreshCharacterDetails(item);
    }

    public void refreshStats() {
        if (item == null) return;
        int own = item.words();
        int total = item.totalWords();
        stats.setText("<html>Words: " + own + (total != own ? "<br>With sub-documents: " + total : "") + "</html>");
    }
}

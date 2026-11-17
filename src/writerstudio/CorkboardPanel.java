package writerstudio;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.border.Border;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The corkboard: every item is an index card showing its title and synopsis.
 * Cards are selected with a click and re-ordered with the fixed toolbar controls.
 */
public class CorkboardPanel extends JPanel {
    private static final Color CORK = new Color(0xD9C29C);
    private static final Color PAPER = new Color(0xFFFBEA);
    private static final Color INK = new Color(0x2B2B2B);

    private final Project project;
    private final Consumer<Item> openItem;
    private final JLabel heading = new JLabel(" ");
    private final JButton addButton = new JButton("New Card");
    private final JButton upButton = new JButton("\u25b2");
    private final JButton downButton = new JButton("\u25bc");
    private final JButton firstButton = new JButton("Top");
    private final JButton lastButton = new JButton("Bottom");
    private final JButton zoomOutButton = new JButton("-");
    private final JButton zoomLabel = new JButton("100%");
    private final JButton zoomInButton = new JButton("+");
    private final Board board = new Board();
    private final JScrollPane scroll;
    private final List<Card> cardList = new ArrayList<>();
    private List<Item> displayedItems = new ArrayList<>();
    private Item displayedContainer;
    private boolean displayedCanAdd;
    private double zoom = 1.0;
    private Card selectedCard;

    public CorkboardPanel(Project project, Consumer<Item> openItem, Runnable newCard) {
        super(new BorderLayout());
        this.project = project;
        this.openItem = openItem;

        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        heading.setFont(heading.getFont().deriveFont(Font.BOLD));
        top.add(heading, BorderLayout.CENTER);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        addMoveButton(controls, firstButton, "Move selected card to the top", () -> moveSelectedTo(0));
        addMoveButton(controls, upButton, "Move selected card up", () -> moveSelectedBy(-1));
        addMoveButton(controls, downButton, "Move selected card down", () -> moveSelectedBy(1));
        addMoveButton(controls, lastButton, "Move selected card to the bottom", () -> moveSelectedTo(cardList.size() - 1));
        addZoomButton(controls, zoomOutButton, "Zoom out", () -> setZoom(zoom - 0.1));
        addZoomButton(controls, zoomLabel, "Reset zoom", () -> setZoom(1.0));
        addZoomButton(controls, zoomInButton, "Zoom in", () -> setZoom(zoom + 0.1));
        addButton.setFocusable(false);
        addButton.addActionListener(e -> newCard.run());
        controls.add(addButton);
        top.add(controls, BorderLayout.EAST);

        scroll = new JScrollPane(board);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(CORK);
        add(top, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        updateMoveButtons();
    }

    /**
     * @param container the folder whose children are shown (null when several items are selected)
     * @param items     the cards to show
     * @param canAdd    whether "New Card" makes sense for the current selection
     */
    public void show(Item container, List<Item> items, boolean canAdd) {
        commitEdits();
        displayedContainer = container;
        displayedItems = new ArrayList<>(items);
        displayedCanAdd = canAdd;
        renderCards();
    }

    private void renderCards() {
        String selectedId = selectedCard == null ? null : selectedCard.item.getId();
        selectedCard = null;
        board.removeAll();
        cardList.clear();
        for (Item it : displayedItems) {
            Card c = new Card(it);
            cardList.add(c);
            board.add(c);
            if (it.getId().equals(selectedId)) selectedCard = c;
        }
        if (selectedCard != null) selectedCard.setSelected(true);
        if (displayedItems.isEmpty()) {
            JLabel none = new JLabel("No cards here. Select a folder that contains documents, or press \u201cNew Card\u201d.");
            none.setForeground(INK);
            board.add(none);
        }
        heading.setText(displayedContainer != null
                ? "Corkboard \u2014 " + displayedContainer.getTitle()
                : "Corkboard \u2014 " + displayedItems.size() + " selected item(s)");
        addButton.setEnabled(displayedCanAdd);
        updateMoveButtons();
        board.revalidate();
        board.repaint();
    }

    private void addMoveButton(JPanel parent, JButton button, String tooltip, Runnable action) {
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.setEnabled(false);
        button.setMargin(new java.awt.Insets(2, 8, 2, 8));
        button.addActionListener(e -> action.run());
        parent.add(button);
    }

    private void addZoomButton(JPanel parent, JButton button, String tooltip, Runnable action) {
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.setMargin(new java.awt.Insets(2, 7, 2, 7));
        button.addActionListener(e -> action.run());
        parent.add(button);
    }

    private void setZoom(double value) {
        zoom = Math.max(0.7, Math.min(1.6, Math.round(value * 10.0) / 10.0));
        zoomLabel.setText((int) Math.round(zoom * 100) + "%");
        zoomOutButton.setEnabled(zoom > 0.7);
        zoomInButton.setEnabled(zoom < 1.6);
        if (!displayedItems.isEmpty()) {
            commitEdits();
            renderCards();
        }
    }

    private void selectCard(Card card) {
        if (selectedCard != null) selectedCard.setSelected(false);
        selectedCard = card;
        if (selectedCard != null) selectedCard.setSelected(true);
        updateMoveButtons();
    }

    private void updateMoveButtons() {
        boolean enabled = selectedCard != null && canReorderDisplayedCards();
        int index = enabled ? cardList.indexOf(selectedCard) : -1;
        upButton.setEnabled(enabled && index > 0);
        downButton.setEnabled(enabled && index >= 0 && index < cardList.size() - 1);
        firstButton.setEnabled(enabled && index > 0);
        lastButton.setEnabled(enabled && index >= 0 && index < cardList.size() - 1);
    }

    private boolean canReorderDisplayedCards() {
        if (selectedCard == null || selectedCard.item.parentItem() == null) return false;
        Item parent = selectedCard.item.parentItem();
        for (Card card : cardList) {
            if (card.item.parentItem() != parent) return false;
        }
        return true;
    }

    private void moveSelectedBy(int amount) {
        if (selectedCard == null) return;
        moveSelectedTo(cardList.indexOf(selectedCard) + amount);
    }

    private void moveSelectedTo(int targetIndex) {
        if (selectedCard == null) return;
        Item parent = selectedCard.item.parentItem();
        if (parent == null || !canReorderDisplayedCards()) return;
        int from = cardList.indexOf(selectedCard);
        int bounded = Math.max(0, Math.min(targetIndex, cardList.size() - 1));
        if (from == bounded || bounded < 0) return;

        commitEdits();
        int sourceIndex = parent.getIndex(selectedCard.item);
        Item target = cardList.get(bounded).item;
        int targetIndexInParent = parent.getIndex(target);
        project.model().removeNodeFromParent(selectedCard.item);
        int insertionIndex = targetIndexInParent;
        if (sourceIndex < targetIndexInParent) insertionIndex--;
        if (bounded > from) insertionIndex++;
        project.model().insertNodeInto(selectedCard.item, parent, Math.max(0, insertionIndex));
        project.markDirty();

        Card moved = cardList.remove(from);
        cardList.add(bounded, moved);
        board.removeAll();
        for (Card card : cardList) board.add(card);
        board.revalidate();
        board.repaint();
        updateMoveButtons();
    }

    /** Makes sure a title that is still being typed is stored before the cards are rebuilt. */
    private void commitEdits() {
        for (Card c : cardList) c.commitTitle();
    }

    // ---- board (scrollable, wrapping) -----------------------------------------------------------------

    private static class Board extends JPanel implements Scrollable {
        Board() {
            super(new WrapLayout(FlowLayout.LEFT, 14, 14));
            setBackground(CORK);
        }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int orientation, int direction) { return 24; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int orientation, int direction) { return 160; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    // ---- a single index card ------------------------------------------------------------------------------

    private class Card extends JPanel {
        private final Item item;
        private final JTextField title = new JTextField();
        private final JTextArea synopsis = new JTextArea();
        private final Border normal = BorderFactory.createLineBorder(new Color(0xB89B6A));
        private final Border selected = BorderFactory.createLineBorder(new Color(0x2F80ED), 3);

        Card(Item item) {
            super(new BorderLayout(0, 3));
            this.item = item;
            setPreferredSize(new Dimension(scaled(230), scaled(180)));
            setBackground(PAPER);
            setBorder(normal);

            Color labelColor = Project.labelColor(item.getLabel());
            JLabel handle = new JLabel("\u2261  Click to select", Icons.forItem(item), SwingConstants.LEFT);
            handle.setOpaque(true);
            handle.setBackground(labelColor != null ? labelColor : new Color(0xE3D5B5));
            handle.setForeground(labelColor != null ? Color.WHITE : INK);
            handle.setFont(handle.getFont().deriveFont(scaledFont(10f)));
            handle.setBorder(BorderFactory.createEmptyBorder(scaled(2), scaled(6), scaled(2), scaled(6)));
            handle.setToolTipText("Bấm để chọn thẻ, sau đó dùng các nút trên toolbar");

            title.setText(item.getTitle());
            title.setFont(title.getFont().deriveFont(Font.BOLD, scaledFont(14f)));
            title.setBackground(PAPER);
            title.setForeground(INK);
            title.setCaretColor(INK);
            title.setBorder(BorderFactory.createEmptyBorder(scaled(2), scaled(6), scaled(2), scaled(6)));
            title.addActionListener(e -> commitTitle());
            title.addFocusListener(new FocusAdapter() {
                @Override public void focusLost(FocusEvent e) { commitTitle(); }
            });

            synopsis.setText(item.getSynopsis());
            synopsis.setLineWrap(true);
            synopsis.setWrapStyleWord(true);
            synopsis.setBackground(PAPER);
            synopsis.setForeground(INK);
            synopsis.setCaretColor(INK);
            synopsis.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
            synopsis.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { store(); }
                @Override public void removeUpdate(DocumentEvent e) { store(); }
                @Override public void changedUpdate(DocumentEvent e) { store(); }
                private void store() {
                    item.setSynopsis(synopsis.getText());
                    project.markDirty();
                }
            });
            JScrollPane synopsisScroll = new JScrollPane(synopsis);
            synopsisScroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0xE3D5B5)));
            synopsisScroll.getViewport().setBackground(PAPER);

            JPanel north = new JPanel(new BorderLayout());
            north.setOpaque(false);
            north.add(handle, BorderLayout.NORTH);
            north.add(title, BorderLayout.CENTER);

            String statusText = Project.NONE.equals(item.getStatus()) ? "" : "  \u00b7  " + item.getStatus();
            JLabel footer = new JLabel(item.totalWords() + " words" + statusText);
            footer.setForeground(new Color(0x777777));
            footer.setFont(footer.getFont().deriveFont(scaledFont(11f)));
            JButton open = new JButton("Open");
            open.setFocusable(false);
            open.setMargin(new java.awt.Insets(0, scaled(6), 0, scaled(6)));
            open.addActionListener(e -> openItem.accept(item));
            JPanel south = new JPanel(new BorderLayout());
            south.setOpaque(false);
            south.setBorder(BorderFactory.createEmptyBorder(0, scaled(6), scaled(4), scaled(6)));
            south.add(footer, BorderLayout.CENTER);
            south.add(open, BorderLayout.EAST);

            add(north, BorderLayout.NORTH);
            add(synopsisScroll, BorderLayout.CENTER);
            add(south, BorderLayout.SOUTH);
            MouseAdapter dbl = new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) openItem.accept(item);
                }
            };
            addMouseListener(dbl);
            footer.addMouseListener(dbl);
            handle.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { selectCard(Card.this); }
            });
            title.addFocusListener(new FocusAdapter() {
                @Override public void focusGained(FocusEvent e) { selectCard(Card.this); }
            });
            synopsis.addFocusListener(new FocusAdapter() {
                @Override public void focusGained(FocusEvent e) { selectCard(Card.this); }
            });
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 1) selectCard(Card.this);
                }
            });
        }

        void commitTitle() {
            String t = title.getText().trim();
            if (t.isEmpty()) {
                title.setText(item.getTitle());
            } else if (!t.equals(item.getTitle())) {
                item.setTitle(t);
                project.changedQuietly(item);
            }

        }

        void setSelected(boolean on) {
            setBorder(on ? selected : normal);
            setBackground(on ? new Color(0xFFF2C7) : PAPER);
        }

        private int scaled(int value) { return Math.max(1, (int) Math.round(value * zoom)); }
        private float scaledFont(float value) { return Math.max(8f, (float) (value * zoom)); }
    }

}

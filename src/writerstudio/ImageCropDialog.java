package writerstudio;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/** Modal square avatar cropper with zoom and drag-to-position controls. */
final class ImageCropDialog {
    private static final int OUTPUT_SIZE = 1024;

    private ImageCropDialog() { }

    static BufferedImage show(Component parent, File file) throws IOException {
        BufferedImage source = ImageIO.read(file);
        if (source == null) throw new IOException("This image format could not be opened.");

        Window owner = javax.swing.SwingUtilities.getWindowAncestor(parent);
        JDialog dialog = new JDialog(owner, "Adjust image", Dialog.ModalityType.APPLICATION_MODAL);
        CropView cropView = new CropView(source);
        JSlider zoom = new JSlider(SwingConstants.HORIZONTAL, 100, 400, 100);
        zoom.setMajorTickSpacing(100);
        zoom.setMinorTickSpacing(25);
        zoom.setPaintTicks(true);
        zoom.addChangeListener(event -> cropView.setZoom(zoom.getValue() / 100.0));

        JPanel controls = new JPanel(new BorderLayout(10, 0));
        controls.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        controls.add(new JLabel("Zoom"), BorderLayout.WEST);
        controls.add(zoom, BorderLayout.CENTER);

        BufferedImage[] result = new BufferedImage[1];
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(event -> dialog.dispose());
        JButton apply = new JButton("Use image");
        apply.addActionListener(event -> {
            result[0] = cropView.createCrop();
            dialog.dispose();
        });
        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT));
        buttons.add(cancel);
        buttons.add(apply);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(14, 14, 10, 14));
        content.add(new JLabel("Drag to reposition the image, then adjust the zoom."), BorderLayout.NORTH);
        content.add(cropView, BorderLayout.CENTER);
        content.add(controls, BorderLayout.SOUTH);
        dialog.add(content, BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.pack();
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
        return result[0];
    }

    private static final class CropView extends JPanel {
        private final BufferedImage source;
        private double zoom = 1.0;
        private double offsetX;
        private double offsetY;
        private int dragX;
        private int dragY;

        CropView(BufferedImage source) {
            this.source = source;
            setPreferredSize(new Dimension(360, 360));
            setBackground(new Color(0x222222));
            setBorder(BorderFactory.createLineBorder(new Color(0x777777)));
            MouseAdapter drag = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent event) {
                    dragX = event.getX();
                    dragY = event.getY();
                    setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.MOVE_CURSOR));
                }

                @Override public void mouseReleased(MouseEvent event) {
                    setCursor(java.awt.Cursor.getDefaultCursor());
                }

                @Override public void mouseDragged(MouseEvent event) {
                    offsetX += event.getX() - dragX;
                    offsetY += event.getY() - dragY;
                    dragX = event.getX();
                    dragY = event.getY();
                    clampOffsets();
                    repaint();
                }
            };
            addMouseListener(drag);
            addMouseMotionListener(drag);
        }

        void setZoom(double value) {
            zoom = value;
            clampOffsets();
            repaint();
        }

        private double scale() {
            return Math.max(getWidth() / (double) source.getWidth(),
                    getHeight() / (double) source.getHeight()) * zoom;
        }

        private double imageX(double scale) {
            return (getWidth() - source.getWidth() * scale) / 2.0 + offsetX;
        }

        private double imageY(double scale) {
            return (getHeight() - source.getHeight() * scale) / 2.0 + offsetY;
        }

        private void clampOffsets() {
            double scale = scale();
            double maxX = Math.max(0, (source.getWidth() * scale - getWidth()) / 2.0);
            double maxY = Math.max(0, (source.getHeight() * scale - getHeight()) / 2.0);
            offsetX = Math.max(-maxX, Math.min(maxX, offsetX));
            offsetY = Math.max(-maxY, Math.min(maxY, offsetY));
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            double scale = scale();
            int x = (int) Math.round(imageX(scale));
            int y = (int) Math.round(imageY(scale));
            int width = (int) Math.ceil(source.getWidth() * scale);
            int height = (int) Math.ceil(source.getHeight() * scale);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(source, x, y, width, height, null);

            g.setColor(Color.WHITE);
            g.drawRect(1, 1, getWidth() - 3, getHeight() - 3);
            g.dispose();
        }

        BufferedImage createCrop() {
            double scale = scale();
            double cropX = -imageX(scale) / scale;
            double cropY = -imageY(scale) / scale;
            double cropSize = getWidth() / scale;
            int sx = Math.max(0, (int) Math.floor(cropX));
            int sy = Math.max(0, (int) Math.floor(cropY));
            int sw = Math.min(source.getWidth() - sx, (int) Math.ceil(cropSize));
            int sh = Math.min(source.getHeight() - sy, (int) Math.ceil(cropSize));

            BufferedImage result = new BufferedImage(OUTPUT_SIZE, OUTPUT_SIZE, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = result.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, OUTPUT_SIZE, OUTPUT_SIZE, sx, sy, sx + sw, sy + sh, null);
            g.dispose();
            return result;
        }
    }
}

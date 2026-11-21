package writerstudio;

import javax.swing.Icon;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/** Small vector icons so the app needs no image resources. */
final class Icons {
    private Icons() {}

    private static final Icon DRAFT = folder(new Color(0x3B7DD8));
    private static final Icon RESEARCH = folder(new Color(0x8E5BD0));
    private static final Icon FOLDER = folder(new Color(0xE0B040));
    private static final Icon TEXT = sheet(Color.WHITE, false);
    private static final Icon FILE = sheet(new Color(0xCFE4F7), true);
    private static final Icon TRASH = trash();

    static Icon forItem(Item it) {
        switch (it.getSpecial()) {
            case DRAFT: return DRAFT;
            case RESEARCH: return RESEARCH;
            case TRASH: return TRASH;
            default: break;
        }
        switch (it.getKind()) {
            case FOLDER: return FOLDER;
            case FILE: return FILE;
            default: return TEXT;
        }
    }

    /** A small coloured square used to show a label colour. */
    static Icon chip(Color c) {
        return new Icon16(12, 12) {
            @Override void draw(Graphics2D g) {
                g.setColor(c);
                g.fillRoundRect(1, 1, 10, 10, 4, 4);
                g.setColor(c.darker());
                g.drawRoundRect(1, 1, 10, 10, 4, 4);
            }

        };
    }

    static Icon delete() {
        return new Icon16(16, 16) {
            @Override void draw(Graphics2D g) {
                g.setColor(new Color(0xB83232));
                g.fillRoundRect(3, 4, 10, 11, 2, 2);
                g.fillRect(2, 2, 12, 2);
                g.fillRect(6, 0, 4, 2);
                g.setColor(Color.WHITE);
                g.drawLine(6, 7, 6, 13);
                g.drawLine(8, 7, 8, 13);
                g.drawLine(10, 7, 10, 13);
            }

        };
    }

    static Icon edit() {
        return new Icon16(16, 16) {
            @Override void draw(Graphics2D g) {
                g.setColor(new Color(0x3B6EA8));
                java.awt.Polygon pencil = new java.awt.Polygon(
                        new int[] {3, 11, 14, 6}, new int[] {12, 4, 7, 15}, 4);
                g.fillPolygon(pencil);
                g.setColor(new Color(0x24466F));
                g.drawPolygon(pencil);
                g.setColor(new Color(0xD8C39A));
                g.fillPolygon(new java.awt.Polygon(
                        new int[] {3, 6, 2}, new int[] {12, 15, 15}, 3));
                g.setColor(new Color(0x6E6258));
                g.drawLine(2, 15, 3, 12);
                g.setColor(new Color(0x8A6A3D));
                g.drawLine(10, 5, 13, 8);
            }
        };
    }

    static Icon primary() {
        return new Icon16(16, 16) {
            @Override void draw(Graphics2D g) {
                g.setColor(new Color(0xD29B22));
                java.awt.Polygon star = new java.awt.Polygon();
                for (int i = 0; i < 10; i++) {
                    double angle = -Math.PI / 2 + i * Math.PI / 5;
                    double radius = i % 2 == 0 ? 7 : 3;
                    star.addPoint(8 + (int) (Math.cos(angle) * radius),
                            8 + (int) (Math.sin(angle) * radius));
                }
                g.fillPolygon(star);
                g.setColor(new Color(0x9B6D10));
                g.drawPolygon(star);
            }
        };
    }

    private static Icon folder(Color c) {
        return new Icon16(16, 16) {
            @Override void draw(Graphics2D g) {
                g.setColor(c.darker());
                g.fillRoundRect(1, 3, 7, 4, 2, 2);
                g.setColor(c);
                g.fillRoundRect(1, 5, 14, 9, 3, 3);
                g.setColor(c.darker());
                g.drawRoundRect(1, 5, 13, 8, 3, 3);
            }
        };
    }

    private static Icon sheet(Color fill, boolean picture) {
        return new Icon16(16, 16) {
            @Override void draw(Graphics2D g) {
                g.setColor(fill);
                g.fillRect(3, 1, 10, 14);
                g.setColor(Color.GRAY);
                g.drawRect(3, 1, 9, 13);
                if (picture) {
                    g.setColor(new Color(0x4A90D9));
                    g.fillRect(5, 8, 6, 4);
                    g.fillOval(5, 3, 3, 3);
                } else {
                    g.setColor(Color.LIGHT_GRAY);
                    for (int y = 4; y <= 11; y += 3) g.drawLine(5, y, 11, y);
                }
            }
        };
    }

    private static Icon trash() {
        return new Icon16(16, 16) {
            @Override void draw(Graphics2D g) {
                g.setColor(new Color(0x777777));
                g.fillRect(6, 1, 4, 2);
                g.fillRect(2, 3, 12, 2);
                g.setColor(new Color(0x999999));
                g.fillRect(3, 6, 10, 9);
                g.setColor(Color.WHITE);
                g.drawLine(6, 8, 6, 13);
                g.drawLine(8, 8, 8, 13);
                g.drawLine(10, 8, 10, 13);
            }
        };
    }

    private abstract static class Icon16 implements Icon {
        private final int w, h;
        Icon16(int w, int h) { this.w = w; this.h = h; }
        abstract void draw(Graphics2D g);
        @Override public int getIconWidth() { return w; }
        @Override public int getIconHeight() { return h; }
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.translate(x, y);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            draw(g2);
            g2.dispose();
        }
    }
}

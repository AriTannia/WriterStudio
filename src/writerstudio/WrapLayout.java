package writerstudio;

import javax.swing.JViewport;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;

/**
 * A FlowLayout that reports the correct preferred height once components wrap
 * onto several rows. Used by the corkboard inside a scroll pane.
 */
final class WrapLayout extends FlowLayout {
    WrapLayout(int align, int hgap, int vgap) {
        super(align, hgap, vgap);
    }

    @Override public Dimension preferredLayoutSize(Container target) {
        return measure(target);
    }

    @Override public Dimension minimumLayoutSize(Container target) {
        Dimension d = measure(target);
        d.width = 10;
        return d;
    }

    private Dimension measure(Container target) {
        synchronized (target.getTreeLock()) {
            int width = target.getWidth();
            if (target.getParent() instanceof JViewport) width = target.getParent().getWidth();
            if (width <= 0) width = 100000;

            Insets in = target.getInsets();
            int hgap = getHgap(), vgap = getVgap();
            int maxRow = width - in.left - in.right - hgap * 2;

            int x = 0, rowH = 0, height = 0, widest = 0, count = 0;
            for (Component c : target.getComponents()) {
                if (!c.isVisible()) continue;
                Dimension d = c.getPreferredSize();
                if (count > 0 && x + hgap + d.width > maxRow) {
                    height += rowH + vgap;
                    widest = Math.max(widest, x);
                    x = 0;
                    rowH = 0;
                    count = 0;
                }
                if (count > 0) x += hgap;
                x += d.width;
                rowH = Math.max(rowH, d.height);
                count++;
            }
            widest = Math.max(widest, x);
            height += rowH;
            return new Dimension(widest + in.left + in.right + hgap * 2,
                    height + in.top + in.bottom + vgap * 2);
        }
    }
}

package writerstudio;

/** Small static helpers shared by the whole application. */
final class Util {
    private Util() {}

    /** Counts words (runs of non-whitespace characters). */
    static int words(String s) {
        if (s == null) return 0;
        int n = 0;
        boolean inWord = false;
        for (int i = 0; i < s.length(); i++) {
            boolean ws = Character.isWhitespace(s.charAt(i));
            if (!ws && !inWord) n++;
            inWord = !ws;
        }
        return n;
    }

    /** Removes characters that are illegal in XML 1.0. */
    static String xmlSafe(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\t' || c == '\n' || (c >= 0x20 && c <= 0xD7FF)
                    || (c >= 0xE000 && c <= 0xFFFD) || Character.isSurrogate(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Ctrl on Windows/Linux, Cmd on macOS (falls back to Ctrl when no display is available). */
    static int menuMask() {
        try {
            return java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        } catch (java.awt.HeadlessException e) {
            return java.awt.event.InputEvent.CTRL_DOWN_MASK;
        }
    }

    static String baseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}

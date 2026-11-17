package writerstudio;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Component;
import java.io.File;
import java.util.prefs.Preferences;

/** Application entry point. Usage: {@code java -jar WriterStudio.jar [project-folder]} */
public final class Main {
    private static final Preferences PREFS = Preferences.userNodeForPackage(Main.class);

    private Main() {}

    public static void main(String[] args) {
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        SwingUtilities.invokeLater(() -> {
            installLookAndFeel();
            Project p = null;
            try {
                if (args.length > 0) {
                    p = Project.load(new File(args[0]));
                } else {
                    String last = PREFS.get("lastProject", "");
                    if (!last.isEmpty() && new File(last, "project.xml").exists()) p = Project.load(new File(last));
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(null, "Could not open the project:\n" + ex.getMessage(),
                        "Writer's Studio", JOptionPane.ERROR_MESSAGE);
            }
            if (p == null) p = welcome();
            if (p == null) {
                System.exit(0);
                return;
            }
            show(p);
        });
    }

    static void show(Project p) {
        PREFS.put("lastProject", p.getDir().getAbsolutePath());
        new MainFrame(p).setVisible(true);
    }

    private static Project welcome() {
        Object[] options = {"New Project\u2026", "Open Project\u2026", "Quit"};
        while (true) {
            int c = JOptionPane.showOptionDialog(null,
                    "Welcome to Writer's Studio.\nCreate a new project or open an existing project folder.",
                    "Writer's Studio", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                    null, options, options[0]);
            Project p;
            if (c == 0) p = promptNewProject(null);
            else if (c == 1) p = promptOpenProject(null);
            else return null;
            if (p != null) return p;
        }
    }

    static Project promptNewProject(Component parent) {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("New project \u2014 choose or create a folder");
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return null;
        File dir = fc.getSelectedFile();
        try {
            if (new File(dir, "project.xml").exists()) return Project.load(dir);   // already a project
            String[] existing = dir.list();
            if (existing != null && existing.length > 0) {
                int r = JOptionPane.showConfirmDialog(parent,
                        "The folder is not empty. Create the project here anyway?",
                        "New Project", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
                if (r != JOptionPane.OK_OPTION) return null;
            }
            return Project.createNew(dir, dir.getName(), true);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(parent, "Could not create the project:\n" + ex.getMessage(),
                    "New Project", JOptionPane.ERROR_MESSAGE);
            return null;
        }
    }

    static Project promptOpenProject(Component parent) {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Open project \u2014 choose the project folder");
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return null;
        File dir = fc.getSelectedFile();
        if (!new File(dir, "project.xml").exists()) {
            JOptionPane.showMessageDialog(parent, "That folder does not contain a Writer's Studio project (project.xml).",
                    "Open Project", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        try {
            return Project.load(dir);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(parent, "Could not open the project:\n" + ex.getMessage(),
                    "Open Project", JOptionPane.ERROR_MESSAGE);
            return null;
        }
    }

    private static void installLookAndFeel() {
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win") || os.contains("mac")) {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } else {
                for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                    if ("Nimbus".equals(info.getName())) {
                        UIManager.setLookAndFeel(info.getClassName());
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
            // fall back to the default look and feel
        }
    }
}

package writerstudio;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.util.List;
import java.util.concurrent.ExecutionException;

/** "Paste your API key and it just works": one field, automatic detection and test. */
final class AiKeyDialog extends JDialog {
    private final AiService service;
    private final JPasswordField keyField = new JPasswordField(38);
    private final JLabel detected = new JLabel(" ");
    private final JLabel status = new JLabel(" ");
    private final JTextArea log = new JTextArea(9, 60);
    private final JScrollPane logScroll = new JScrollPane(log);
    private final JPanel advanced = new JPanel(new GridBagLayout());
    private final JComboBox<String> providerBox = new JComboBox<>(new String[] {
            "Tự nhận diện", "Gemini", "Groq", "OpenRouter", "Custom (OpenAI-compatible, ví dụ Ollama)"});
    private final JTextField baseUrlField = new JTextField(34);
    private final JTextField modelField = new JTextField(34);
    private final JButton connectBtn = new JButton("Kết nối");
    private final JButton advancedBtn = new JButton("Nâng cao \u25b8");
    private final JButton clearBtn = new JButton("Xóa key");
    private final JButton closeBtn = new JButton("Đóng");

    private AiClient.Call running;
    private boolean connected;

    static void show(Component parent, AiService service) {
        new AiKeyDialog(parent == null ? null : SwingUtilities.getWindowAncestor(parent), service).setVisible(true);
    }

    private AiKeyDialog(java.awt.Window owner, AiService service) {
        super(owner, "Kết nối AI", ModalityType.APPLICATION_MODAL);
        this.service = service;
        AiSettings s = service.settings();

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBorder(BorderFactory.createEmptyBorder(14, 16, 6, 16));
        JLabel help = new JLabel("<html>Dán <b>API key</b> của bạn (Gemini, Groq hoặc OpenRouter). "
                + "App tự nhận ra nhà cung cấp, tự chọn model và kiểm tra kết nối.<br>"
                + "Chưa có key? Với Gemini: vào Google AI Studio \u2192 \u201cGet API key\u201d (miễn phí).</html>");
        help.setAlignmentX(LEFT_ALIGNMENT);
        top.add(help);
        top.add(Box.createVerticalStrut(10));

        JPanel keyRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        keyRow.setAlignmentX(LEFT_ALIGNMENT);
        keyField.setText(s.apiKey);
        keyRow.add(keyField);
        JButton paste = new JButton("Dán");
        paste.setFocusable(false);
        paste.addActionListener(e -> pasteFromClipboard());
        keyRow.add(paste);
        JCheckBox showKey = new JCheckBox("Hiện");
        char echo = keyField.getEchoChar();
        showKey.addActionListener(e -> keyField.setEchoChar(showKey.isSelected() ? (char) 0 : echo));
        keyRow.add(showKey);
        top.add(keyRow);
        top.add(Box.createVerticalStrut(4));
        detected.setAlignmentX(LEFT_ALIGNMENT);
        top.add(detected);
        keyField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { updateDetected(); }
            @Override public void removeUpdate(DocumentEvent e) { updateDetected(); }
            @Override public void changedUpdate(DocumentEvent e) { updateDetected(); }
        });

        buildAdvanced(s);
        advanced.setAlignmentX(LEFT_ALIGNMENT);
        advanced.setVisible(false);
        top.add(advanced);

        status.setAlignmentX(LEFT_ALIGNMENT);
        status.setBorder(BorderFactory.createEmptyBorder(8, 0, 4, 0));
        top.add(status);

        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logScroll.setVisible(false);
        logScroll.setAlignmentX(LEFT_ALIGNMENT);
        top.add(logScroll);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        advancedBtn.addActionListener(e -> {
            advanced.setVisible(!advanced.isVisible());
            advancedBtn.setText(advanced.isVisible() ? "Nâng cao \u25be" : "Nâng cao \u25b8");
            pack();
        });
        clearBtn.addActionListener(e -> {
            service.settings().clearKey();
            keyField.setText("");
            connected = false;
            connectBtn.setText("Kết nối");
            status.setForeground(Color.DARK_GRAY);
            status.setText("Đã xóa key khỏi máy này.");
        });
        connectBtn.addActionListener(e -> {
            if (connected) dispose();
            else connect();
        });
        closeBtn.addActionListener(e -> {
            if (running != null) running.cancel();
            dispose();
        });
        buttons.add(advancedBtn);
        buttons.add(clearBtn);
        buttons.add(connectBtn);
        buttons.add(closeBtn);

        getContentPane().add(top, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(connectBtn);
        updateDetected();
        pack();
        setLocationRelativeTo(owner);
    }

    private void buildAdvanced(AiSettings s) {
        advanced.setBorder(BorderFactory.createTitledBorder("Nâng cao (thường không cần)"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 6, 3, 6);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0; c.gridy = 0;
        advanced.add(new JLabel("Nhà cung cấp"), c);
        c.gridx = 1;
        advanced.add(providerBox, c);
        c.gridx = 0; c.gridy = 1;
        advanced.add(new JLabel("Địa chỉ API"), c);
        c.gridx = 1;
        baseUrlField.setToolTipText("Để trống để dùng địa chỉ mặc định của nhà cung cấp");
        baseUrlField.setText(s.baseUrl);
        advanced.add(baseUrlField, c);
        c.gridx = 0; c.gridy = 2;
        advanced.add(new JLabel("Model"), c);
        c.gridx = 1;
        modelField.setToolTipText("Để trống để app tự chọn model phù hợp");
        modelField.setText(s.modelOverride);
        advanced.add(modelField, c);
        if (s.provider != null) providerBox.setSelectedIndex(s.provider.ordinal() + 1);
    }

    private void pasteFromClipboard() {
        try {
            Object o = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
            keyField.setText(String.valueOf(o).trim());
        } catch (Exception ex) {
            status.setForeground(Color.RED.darker());
            status.setText("Không đọc được clipboard. Hãy dán bằng Ctrl+V vào ô key.");
        }
    }

    private void updateDetected() {
        String key = new String(keyField.getPassword()).trim();
        if (key.isEmpty()) {
            detected.setText(" ");
            return;
        }
        AiSettings.Provider p = AiSettings.detect(key);
        detected.setForeground(p != null ? new Color(0x2E7D32) : new Color(0xB26A00));
        detected.setText(p != null ? "Nhận diện: " + p.label
                : "Chưa nhận ra dạng key này. Mở \u201cNâng cao\u201d để chọn nhà cung cấp.");
    }

    private void connect() {
        AiSettings s = service.settings();
        String key = new String(keyField.getPassword()).trim();
        int idx = providerBox.getSelectedIndex();
        AiSettings.Provider chosen = idx == 0 ? AiSettings.detect(key) : AiSettings.Provider.values()[idx - 1];
        if (chosen == null && !key.isEmpty()) {
            advanced.setVisible(true);
            advancedBtn.setText("Nâng cao \u25be");
            status.setForeground(new Color(0xB26A00));
            status.setText("Không nhận ra dạng key. Hãy chọn nhà cung cấp trong mục Nâng cao rồi bấm Kết nối lại.");
            pack();
            return;
        }
        s.apiKey = key;
        s.provider = chosen;
        s.baseUrl = baseUrlField.getText().trim();
        s.modelOverride = modelField.getText().trim();
        s.autoModel = "";
        s.save();

        log.setText("");
        logScroll.setVisible(true);
        logScroll.setPreferredSize(new Dimension(560, 150));
        status.setForeground(Color.DARK_GRAY);
        status.setText("Đang kết nối\u2026");
        connectBtn.setEnabled(false);
        running = new AiClient.Call();
        final AiClient.Call call = running;
        pack();

        new SwingWorker<AiService.Diag, String>() {
            @Override protected AiService.Diag doInBackground() {
                return service.diagnose(this::publish, call, false, null);
            }

            @Override protected void process(List<String> lines) {
                for (String l : lines) log.append(l + "\n");
            }

            @Override protected void done() {
                connectBtn.setEnabled(true);
                running = null;
                try {
                    AiService.Diag d = get();
                    if (d.ok) {
                        connected = true;
                        status.setForeground(new Color(0x2E7D32));
                        status.setText(d.summary);
                        connectBtn.setText("Xong");
                        closeBtn.setText("Đóng");
                    } else if (d.error == null || d.error.kind != AiException.Kind.CANCELLED) {
                        status.setForeground(Color.RED.darker());
                        status.setText("<html>" + d.summary.replace("<", "&lt;") + "</html>");
                    }
                } catch (InterruptedException | ExecutionException e) {
                    status.setForeground(Color.RED.darker());
                    status.setText("Lỗi không mong đợi: " + e.getMessage());
                }
            }
        }.execute();
    }
}

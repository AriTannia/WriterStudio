package writerstudio;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

/**
 * "Phong cách viết": the writer describes the style in plain words; the AI can compose
 * the system prompt and parameters, and a sample run shows the result immediately.
 */
final class AiStyleDialog extends JDialog {
    private static final String SAMPLE_TEXT =
            "Mai ngồi sát cửa sổ toa tàu. Ngoài kia mưa rơi từng hạt nặng, những ánh đèn thành phố lùi dần phía sau.";

    private final AiService service;
    private final File projectDir;
    private final AiStyle style;
    private final JTextArea description = new JTextArea(3, 50);
    private final Map<String, JCheckBox> optionBoxes = new LinkedHashMap<>();
    private final JSlider creativity = new JSlider(0, 100, 60);
    private final Map<AiStyle.Length, JRadioButton> lengthButtons = new EnumMap<>(AiStyle.Length.class);
    private final JTextArea prompt = new JTextArea(8, 50);
    private final JTextArea notes = new JTextArea(3, 50);
    private final JTextArea sampleIn = new JTextArea(3, 50);
    private final JTextArea sampleOut = new JTextArea(5, 50);
    private final JLabel status = new JLabel(" ");
    private final JButton suggestBtn = new JButton("Để AI soạn cấu hình giúp");
    private final JButton testBtn = new JButton("Thử với đoạn mẫu");
    private final JButton saveBtn = new JButton("Lưu");
    private final JButton cancelBtn = new JButton("Hủy");

    private AiClient.Call running;
    private boolean saved;

    /** @return true if the style was saved */
    static boolean show(Component parent, AiService service, File projectDir, String selectedText) {
        AiStyleDialog d = new AiStyleDialog(parent == null ? null : SwingUtilities.getWindowAncestor(parent),
                service, projectDir, selectedText);
        d.setVisible(true);
        return d.saved;
    }

    private AiStyleDialog(java.awt.Window owner, AiService service, File projectDir, String selectedText) {
        super(owner, "Phong cách viết", ModalityType.APPLICATION_MODAL);
        this.service = service;
        this.projectDir = projectDir;
        this.style = AiStyle.load(projectDir);

        JPanel form = new JPanel();
        form.setLayout(new javax.swing.BoxLayout(form, javax.swing.BoxLayout.Y_AXIS));
        form.setBorder(BorderFactory.createEmptyBorder(12, 14, 8, 14));

        form.add(label("Mô tả giọng văn bạn muốn:"));
        description.setLineWrap(true);
        description.setWrapStyleWord(true);
        description.setText(style.description);
        form.add(scroll(description));

        form.add(space());
        form.add(label("Chọn nhanh:"));
        JPanel boxes = new JPanel(new java.awt.GridLayout(0, 3, 8, 2));
        boxes.setAlignmentX(LEFT_ALIGNMENT);
        for (String[] o : AiStyle.OPTIONS) {
            JCheckBox cb = new JCheckBox(o[1], style.options.contains(o[0]));
            optionBoxes.put(o[0], cb);
            boxes.add(cb);
        }
        form.add(boxes);

        form.add(space());
        JPanel sliders = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        sliders.setAlignmentX(LEFT_ALIGNMENT);
        sliders.add(new JLabel("Sáng tạo:  Chặt chẽ"));
        creativity.setValue(style.creativity);
        creativity.setPreferredSize(new Dimension(180, 24));
        sliders.add(creativity);
        sliders.add(new JLabel("Bay bổng"));
        form.add(sliders);
        JPanel lengths = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        lengths.setAlignmentX(LEFT_ALIGNMENT);
        lengths.add(new JLabel("Độ dài mỗi lần:"));
        ButtonGroup group = new ButtonGroup();
        for (AiStyle.Length len : AiStyle.Length.values()) {
            JRadioButton rb = new JRadioButton(len.label + " (~" + len.words + " chữ)", style.length == len);
            group.add(rb);
            lengthButtons.put(len, rb);
            lengths.add(rb);
        }
        form.add(lengths);

        form.add(space());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.setAlignmentX(LEFT_ALIGNMENT);
        suggestBtn.addActionListener(e -> suggest());
        actions.add(suggestBtn);
        form.add(actions);

        form.add(space());
        form.add(label("Lời nhắc hệ thống (để trống = app tự ghép từ các lựa chọn ở trên; sửa được):"));
        prompt.setLineWrap(true);
        prompt.setWrapStyleWord(true);
        prompt.setText(style.systemPrompt);
        form.add(scroll(prompt));
        form.add(label("Giải thích của AI:"));
        notes.setLineWrap(true);
        notes.setWrapStyleWord(true);
        notes.setEditable(false);
        notes.setBackground(new Color(0xF4F4F4));
        form.add(scroll(notes));

        form.add(space());
        form.add(label("Thử với đoạn mẫu (sửa tùy ý):"));
        sampleIn.setLineWrap(true);
        sampleIn.setWrapStyleWord(true);
        sampleIn.setText(selectedText != null && !selectedText.isBlank() ? selectedText.trim() : SAMPLE_TEXT);
        form.add(scroll(sampleIn));
        JPanel testRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        testRow.setAlignmentX(LEFT_ALIGNMENT);
        testBtn.addActionListener(e -> runSample());
        testRow.add(testBtn);
        form.add(testRow);
        sampleOut.setLineWrap(true);
        sampleOut.setWrapStyleWord(true);
        sampleOut.setEditable(false);
        sampleOut.setBackground(new Color(0xF1F8E9));
        form.add(scroll(sampleOut));

        status.setAlignmentX(LEFT_ALIGNMENT);
        status.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        form.add(status);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        saveBtn.addActionListener(e -> save());
        cancelBtn.addActionListener(e -> {
            if (running != null) running.cancel();
            dispose();
        });
        buttons.add(saveBtn);
        buttons.add(cancelBtn);

        getContentPane().add(new JScrollPane(form), BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        setPreferredSize(new Dimension(680, 760));
        pack();
        setLocationRelativeTo(owner);
    }

    private static JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setAlignmentX(LEFT_ALIGNMENT);
        return l;
    }

    private static Component space() {
        return javax.swing.Box.createVerticalStrut(8);
    }

    private static JScrollPane scroll(JTextArea area) {
        JScrollPane sp = new JScrollPane(area);
        sp.setAlignmentX(LEFT_ALIGNMENT);
        return sp;
    }

    // ---- reading the form --------------------------------------------------------------------------

    /** A style object reflecting the current state of the form. */
    private AiStyle currentStyle() {
        AiStyle s = style.copy();
        s.description = description.getText().trim();
        s.options.clear();
        for (Map.Entry<String, JCheckBox> e : optionBoxes.entrySet()) if (e.getValue().isSelected()) s.options.add(e.getKey());
        s.creativity = creativity.getValue();
        for (Map.Entry<AiStyle.Length, JRadioButton> e : lengthButtons.entrySet()) if (e.getValue().isSelected()) s.length = e.getKey();
        s.systemPrompt = prompt.getText().trim();
        return s;
    }

    private List<String> tickedLabels() {
        List<String> out = new ArrayList<>();
        for (String[] o : AiStyle.OPTIONS) if (optionBoxes.get(o[0]).isSelected()) out.add(o[1]);
        return out;
    }

    // ---- actions ---------------------------------------------------------------------------------------

    private void save() {
        AiStyle s = currentStyle();
        try {
            s.save(projectDir);
            saved = true;
            dispose();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Không lưu được phong cách:\n" + ex.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void setBusy(boolean busy, String message) {
        suggestBtn.setEnabled(!busy);
        testBtn.setEnabled(!busy);
        saveBtn.setEnabled(!busy);
        status.setForeground(Color.DARK_GRAY);
        status.setText(message);
    }

    private <T> void runInBackground(Function<AiClient.Call, T> work, java.util.function.Consumer<T> onDone, String busyMessage) {
        if (!service.ensureConfigured(this)) {
            status.setForeground(Color.RED.darker());
            status.setText("Cần API key trước (menu AI \u2192 Dán API key\u2026).");
            return;
        }
        final AiClient.Call call = new AiClient.Call();
        running = call;
        setBusy(true, busyMessage);
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() {
                return work.apply(call);
            }

            @Override protected void done() {
                running = null;
                setBusy(false, " ");
                try {
                    onDone.accept(get());
                } catch (InterruptedException | ExecutionException e) {
                    status.setForeground(Color.RED.darker());
                    status.setText("Lỗi không mong đợi: " + e.getMessage());
                }
            }
        }.execute();
    }

    /** Result of a background call: either text or an error. */
    private static final class Out {
        final String text;
        final AiException error;
        Out(String text, AiException error) {
            this.text = text;
            this.error = error;
        }
    }

    private void showError(AiException e) {
        if (e.kind == AiException.Kind.CANCELLED) return;
        status.setForeground(Color.RED.darker());
        status.setText("<html>" + e.friendly().replace("<", "&lt;") + "</html>");
    }

    private void suggest() {
        String desc = description.getText().trim();
        if (desc.isEmpty()) {
            status.setForeground(Color.RED.darker());
            status.setText("Hãy mô tả giọng văn bạn muốn trước.");
            return;
        }
        final List<AiClient.Msg> msgs = AiPrompts.suggestStyle(desc, tickedLabels());
        runInBackground(call -> {
            try {
                return new Out(service.client().chat(msgs, 0.4, 2048, call).text, null);
            } catch (AiException e) {
                return new Out(null, e);
            }
        }, out -> {
            if (out.error != null) {
                showError(out.error);
                return;
            }
            applySuggestion(out.text);
        }, "AI đang soạn cấu hình\u2026");
    }

    /** Reads the JSON answer of the style-suggestion prompt and fills the form. */
    private void applySuggestion(String raw) {
        try {
            int a = raw.indexOf('{'), b = raw.lastIndexOf('}');
            JsonObject o = JsonParser.parseString(raw.substring(a, b + 1)).getAsJsonObject();
            if (o.has("system_prompt")) prompt.setText(o.get("system_prompt").getAsString().trim());
            if (o.has("temperature")) creativity.setValue(AiStyle.creativityFromTemperature(o.get("temperature").getAsDouble()));
            if (o.has("length")) {
                String l = o.get("length").getAsString().trim().toUpperCase();
                try {
                    lengthButtons.get(AiStyle.Length.valueOf(l)).setSelected(true);
                } catch (IllegalArgumentException ignored) {
                    // keep the current choice
                }
            }
            notes.setText(o.has("notes") ? o.get("notes").getAsString().trim() : "");
            prompt.setCaretPosition(0);
            status.setForeground(new Color(0x2E7D32));
            status.setText("Đã soạn xong. Hãy xem, sửa nếu muốn, thử với đoạn mẫu rồi bấm Lưu.");
        } catch (RuntimeException e) {
            notes.setText("Không đọc được câu trả lời của AI. Nội dung nhận được:\n" + raw);
            status.setForeground(Color.RED.darker());
            status.setText("AI trả về định dạng không như mong đợi. Bạn có thể bấm lại hoặc tự sửa lời nhắc.");
        }
    }

    private void runSample() {
        String sample = sampleIn.getText().trim();
        if (sample.isEmpty()) return;
        final AiStyle s = currentStyle();
        final List<AiClient.Msg> msgs = AiPrompts.assist(s, "viết tiếp", sample, "", "", "");
        sampleOut.setText("");
        runInBackground(call -> {
            try {
                return new Out(service.client().chat(msgs, s.temperature(), s.maxTokens(), call).text, null);
            } catch (AiException e) {
                return new Out(null, e);
            }
        }, out -> {
            if (out.error != null) {
                showError(out.error);
                return;
            }
            sampleOut.setText(out.text);
            sampleOut.setCaretPosition(0);
            status.setForeground(new Color(0x2E7D32));
            status.setText("Đây là kết quả với phong cách hiện tại. Chưa ưng thì chỉnh rồi thử lại.");
        }, "AI đang viết thử\u2026");
    }

}

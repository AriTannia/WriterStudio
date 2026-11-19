package writerstudio;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * How the AI should write for this project. The writer describes the style in plain
 * words and ticks a few boxes; the system prompt and numeric parameters are derived
 * from that (or suggested by the AI itself in the style dialog).
 */
final class AiStyle {
    enum Length {
        SHORT("Ngắn", 60), MEDIUM("Vừa", 130), LONG("Dài", 250);

        final String label;
        final int words;

        Length(String label, int words) {
            this.label = label;
            this.words = words;
        }
    }

    /** id, label shown to the writer, instruction fragment added to the system prompt. */
    static final String[][] OPTIONS = {
            {"spelling", "Chuẩn chính tả",
                    "Viết tiếng Việt chuẩn: đúng chính tả, ngữ pháp, dấu câu và cách viết hoa."},
            {"concise", "Không lan man",
                    "Súc tích, không lan man: mỗi câu phải có mục đích, không lặp ý, không rào đón hay giải thích thêm."},
            {"vocab", "Từ ngữ phong phú",
                    "Dùng vốn từ phong phú; tránh lặp từ trong cùng một đoạn; ưu tiên từ cụ thể, giàu hình ảnh."},
            {"space", "Tả không gian chi tiết",
                    "Khi tả cảnh, miêu tả không gian xung quanh cụ thể: ánh sáng, âm thanh, mùi, vật thể và vị trí "
                            + "tương đối của chúng, đi từ bao quát đến chi tiết, gắn với cảm nhận của nhân vật."},
            {"dialogue", "Đối thoại tự nhiên",
                    "Đối thoại tự nhiên, đúng khẩu khí từng nhân vật; xen hành động và cảm xúc thay vì chỉ thoại."},
            {"pov", "Giữ ngôi kể",
                    "Giữ nguyên ngôi kể, thì và điểm nhìn của đoạn hiện có."},
    };

    static final String DEFAULT_DESCRIPTION =
            "giọng văn chuẩn, đúng chính tả, không lan man, từ ngữ phong phú, tả không gian xung quanh chi tiết";

    String description = DEFAULT_DESCRIPTION;
    final Set<String> options = new LinkedHashSet<>(List.of("spelling", "concise", "vocab", "space"));
    int creativity = 60;                    // 0 (chặt chẽ) .. 100 (bay bổng)
    Length length = Length.MEDIUM;
    String systemPrompt = "";               // set when the AI composed one or the writer edited it

    AiStyle copy() {
        AiStyle c = new AiStyle();
        c.description = description;
        c.options.clear();
        c.options.addAll(options);
        c.creativity = creativity;
        c.length = length;
        c.systemPrompt = systemPrompt;
        return c;
    }

    /** Prompt built from the ticked boxes and the description, without calling any AI. */
    String composeLocal() {
        StringBuilder sb = new StringBuilder("Bạn là cộng sự viết tiểu thuyết.");
        for (String[] o : OPTIONS) {
            if (options.contains(o[0])) sb.append(' ').append(o[2]);
        }
        sb.append(" Không thêm nhân vật hoặc tình tiết lớn ngoài yêu cầu.");
        if (!description.isBlank()) sb.append("\nMô tả phong cách của tác giả: ").append(description.trim());
        return sb.toString();
    }

    /** The prompt actually sent: the edited/AI-written one if there is one, else the local one. */
    String effectivePrompt() {
        return systemPrompt.isBlank() ? composeLocal() : systemPrompt.trim();
    }

    double temperature() {
        return Math.round((0.3 + 0.7 * creativity / 100.0) * 100.0) / 100.0;
    }

    int words() { return length.words; }

    int maxTokens() { return Math.max(2048, length.words * 8); }

    static int creativityFromTemperature(double t) {
        return (int) Math.round(Math.max(0, Math.min(100, (t - 0.3) / 0.7 * 100)));
    }

    // ---- persistence (in the project folder; the style is not secret) ----------------------------

    static File file(File projectDir) {
        return new File(projectDir, "ai-style.properties");
    }

    static AiStyle load(File projectDir) {
        AiStyle s = new AiStyle();
        File f = file(projectDir);
        if (!f.exists()) return s;
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            return s;
        }
        s.description = p.getProperty("description", s.description);
        String opts = p.getProperty("options");
        if (opts != null) {
            s.options.clear();
            for (String id : opts.split(",")) if (!id.isBlank()) s.options.add(id.trim());
        }
        try {
            s.creativity = Math.max(0, Math.min(100, Integer.parseInt(p.getProperty("creativity", "60").trim())));
        } catch (NumberFormatException ignored) {
            s.creativity = 60;
        }
        try {
            s.length = Length.valueOf(p.getProperty("length", "MEDIUM").trim());
        } catch (IllegalArgumentException ignored) {
            s.length = Length.MEDIUM;
        }
        s.systemPrompt = p.getProperty("systemPrompt", "");
        return s;
    }

    void save(File projectDir) throws IOException {
        Properties p = new Properties();
        p.setProperty("description", description);
        p.setProperty("options", String.join(",", new ArrayList<>(options)));
        p.setProperty("creativity", Integer.toString(creativity));
        p.setProperty("length", length.name());
        p.setProperty("systemPrompt", systemPrompt);
        try (Writer w = Files.newBufferedWriter(file(projectDir).toPath(), StandardCharsets.UTF_8)) {
            p.store(w, "Writer's Studio - AI writing style");
        }
    }
}

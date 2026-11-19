package writerstudio;

import java.util.List;

/** All prompt text lives here so it is easy to read, test and tweak. */
final class AiPrompts {
    private AiPrompts() {}

    static final int MAX_BEFORE_CHARS = 3000;
    static final int MAX_AFTER_CHARS = 500;

    private static final String ASSIST_RULES =
            "QUY TẮC CỐ ĐỊNH CỦA ỨNG DỤNG:\n"
                    + "- Bạn đang chèn văn bản vào một bản thảo, ngay tại vị trí con trỏ.\n"
                    + "- Chỉ trả về đúng phần văn bản sẽ chèn. Không lời dẫn, không giải thích, không tiêu đề, "
                    + "không đặt trong dấu ngoặc kép, không dùng Markdown.\n"
                    + "- Viết bằng đúng ngôn ngữ của văn bản đang có; nếu chưa có văn bản thì dùng ngôn ngữ của yêu cầu.\n"
                    + "- Nối tiếp tự nhiên từ văn bản trước, không lặp lại câu đã có.\n"
                    + "- Nếu có văn bản sau vị trí con trỏ, phần chèn phải liền mạch với nó.\n"
                    + "- Các đoạn cách nhau bằng một dòng trống.";

    private static final String CHAT_RULES =
            "Bạn là trợ lý cho một tác giả đang viết truyện. Trả lời bằng ngôn ngữ của câu hỏi, rõ ràng, "
                    + "có cấu trúc, không dài dòng. Dùng thông tin trong bản thảo khi liên quan. "
                    + "Không dùng Markdown; khi liệt kê hãy dùng \"- \" ở đầu dòng. Không viết lại toàn bộ bản thảo.";

    /** Messages for {@code /assist:}. */
    static List<AiClient.Msg> assist(AiStyle style, String instruction, String before, String after,
                                     String synopsis, String title) {
        String system = ASSIST_RULES + "\n\nPHONG CÁCH VIẾT:\n" + style.effectivePrompt();
        StringBuilder u = new StringBuilder();
        if (title != null && !title.isBlank()) u.append("[TÀI LIỆU] ").append(title.trim()).append("\n");
        if (synopsis != null && !synopsis.isBlank()) u.append("[TÓM TẮT] ").append(synopsis.trim()).append("\n");
        u.append("\n[VĂN BẢN TRƯỚC VỊ TRÍ CON TRỎ]\n<<<\n").append(before == null ? "" : before.stripTrailing()).append("\n>>>\n");
        if (after != null && !after.isBlank()) {
            u.append("\n[VĂN BẢN SAU VỊ TRÍ CON TRỎ]\n<<<\n").append(after.strip()).append("\n>>>\n");
        }
        u.append("\n[YÊU CẦU]\n");
        if (instruction == null || instruction.isBlank()) {
            u.append("Viết tiếp một cách tự nhiên.");
        } else {
            u.append(instruction.trim());
        }
        u.append("\nĐộ dài mặc định nếu yêu cầu không nói rõ: khoảng ").append(style.words()).append(" chữ.");
        return List.of(AiClient.Msg.system(system), AiClient.Msg.user(u.toString()));
    }

    /** Messages for {@code /chat:}. */
    static List<AiClient.Msg> chat(String question, String before, String synopsis, String title) {
        StringBuilder u = new StringBuilder();
        if (title != null && !title.isBlank()) u.append("[TÀI LIỆU] ").append(title.trim()).append("\n");
        if (synopsis != null && !synopsis.isBlank()) u.append("[TÓM TẮT] ").append(synopsis.trim()).append("\n");
        if (before != null && !before.isBlank()) {
            u.append("\n[ĐOẠN BẢN THẢO GẦN NHẤT]\n<<<\n").append(before.strip()).append("\n>>>\n");
        }
        u.append("\n[CÂU HỎI]\n").append(question == null || question.isBlank() ? "Gợi ý hướng đi tiếp theo cho đoạn này." : question.trim());
        return List.of(AiClient.Msg.system(CHAT_RULES), AiClient.Msg.user(u.toString()));
    }

    /** Asks the AI to turn a plain-language style description into a system prompt + parameters (as JSON). */
    static List<AiClient.Msg> suggestStyle(String description, List<String> tickedLabels) {
        String system = "Bạn là chuyên gia viết prompt cho một trợ lý AI hỗ trợ viết tiểu thuyết. "
                + "Hãy chuyển mô tả phong cách của tác giả thành cấu hình. "
                + "CHỈ trả về MỘT đối tượng JSON hợp lệ, không có văn bản nào khác, không dùng khối mã, với các khóa: "
                + "\"system_prompt\" (chuỗi: lời nhắc hệ thống 120-220 chữ, viết cùng ngôn ngữ với mô tả, ra lệnh rõ ràng, "
                + "nêu cả những điều cần tránh), "
                + "\"temperature\" (số từ 0.2 đến 1.0: thấp = chặt chẽ, cao = bay bổng), "
                + "\"length\" (\"short\", \"medium\" hoặc \"long\": độ dài mặc định của đoạn chèn, khoảng 60, 130 hoặc 250 chữ), "
                + "\"notes\" (chuỗi 1-3 câu giải thích ngắn gọn các lựa chọn và chỉ ra nếu có yêu cầu mâu thuẫn nhau).";
        StringBuilder u = new StringBuilder("Mô tả của tác giả:\n").append(description == null ? "" : description.trim());
        if (tickedLabels != null && !tickedLabels.isEmpty()) {
            u.append("\n\nTác giả cũng đã chọn: ").append(String.join(", ", tickedLabels)).append(".");
        }
        return List.of(AiClient.Msg.system(system), AiClient.Msg.user(u.toString()));
    }
}

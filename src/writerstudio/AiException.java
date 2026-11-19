package writerstudio;

/** An error from the AI service, with a friendly message for the writer and a technical one for logs. */
final class AiException extends Exception {
    enum Kind { NO_KEY, UNAUTHORIZED, RATE_LIMIT, MODEL_NOT_FOUND, NETWORK, TIMEOUT, SERVER, BAD_RESPONSE, CANCELLED, OTHER }

    final Kind kind;
    final int status;          // HTTP status, 0 if none
    final String detail;       // short, already redacted

    AiException(Kind kind, int status, String detail) {
        super(kind + (status > 0 ? " (HTTP " + status + ")" : "") + (detail == null || detail.isEmpty() ? "" : ": " + detail));
        this.kind = kind;
        this.status = status;
        this.detail = detail == null ? "" : detail;
    }

    /** Message in Vietnamese for the dialog / status bar. */
    String friendly() {
        switch (kind) {
            case NO_KEY:
                return "Chưa có API key. Vào menu AI \u2192 Dán API key\u2026";
            case UNAUTHORIZED:
                return "API key không hợp lệ hoặc đã bị thu hồi (HTTP " + status + "). Hãy kiểm tra lại key.";
            case RATE_LIMIT:
                return "Đã chạm giới hạn miễn phí (HTTP 429). Hãy chờ ít phút rồi thử lại, hoặc đổi nhà cung cấp.";
            case MODEL_NOT_FOUND:
                return "Model không dùng được (" + detail + "). Mở AI \u2192 Dán API key\u2026 \u2192 Nâng cao để chọn lại model.";
            case NETWORK:
                return "Không kết nối được tới máy chủ AI. Hãy kiểm tra mạng.";
            case TIMEOUT:
                return "Máy chủ AI phản hồi quá chậm. Hãy thử lại.";
            case SERVER:
                return "Máy chủ AI đang gặp lỗi (HTTP " + status + "). Hãy thử lại sau.";
            case BAD_RESPONSE:
                return "AI trả về dữ liệu không đọc được hoặc trống. Hãy thử lại. " + detail;
            case CANCELLED:
                return "Đã hủy.";
            default:
                return "Lỗi AI" + (status > 0 ? " (HTTP " + status + ")" : "") + ": " + detail;
        }
    }
}

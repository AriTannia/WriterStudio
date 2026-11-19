package writerstudio;

import java.awt.Component;
import java.util.List;
import java.util.function.Consumer;

/** Holds the AI settings and client for the running app. */
final class AiService {
    /** Result of a connection test. */
    static final class Diag {
        boolean ok;
        String provider = "";
        String model = "";
        long millis;
        AiException error;      // null when ok
        String summary = "";    // Vietnamese one-liner for the dialog
    }

    private final AiSettings settings;
    private final AiClient client;

    AiService() {
        this(AiSettings.load());
    }

    AiService(AiSettings settings) {
        this.settings = settings;
        this.client = new AiClient(settings);
    }

    AiSettings settings() { return settings; }

    AiClient client() { return client; }

    boolean isConfigured() { return settings.isConfigured(); }

    /** Makes sure a key exists, opening the key dialog if not. */
    boolean ensureConfigured(Component parent) {
        if (isConfigured()) return true;
        AiKeyDialog.show(parent, this);
        return isConfigured();
    }

    /**
     * Tests the connection step by step, writing each step to {@code log}.
     * Technical lines are in English so they read the same in the dialog and in a console.
     *
     * @param showRequest if true, also prints the exact JSON of a sample request
     */
    Diag diagnose(Consumer<String> log, AiClient.Call call, boolean showRequest, AiStyle style) {
        Diag d = new Diag();
        log.accept("[1/5] API key: " + settings.maskedKey());
        if (settings.provider == null) settings.provider = AiSettings.detect(settings.apiKey);
        if (!settings.isConfigured() || settings.provider == null) {
            d.error = settings.hasKey()
                    ? new AiException(AiException.Kind.OTHER, 0, "unrecognised key format; choose the provider under Advanced")
                    : new AiException(AiException.Kind.NO_KEY, 0, "");
            log.accept("      FAILED: " + d.error.getMessage());
            d.summary = settings.hasKey()
                    ? "Không nhận ra dạng key này. Mở mục Nâng cao để chọn nhà cung cấp."
                    : d.error.friendly();
            return d;
        }
        d.provider = settings.provider.label;
        log.accept("[2/5] Provider: " + settings.provider.label + "  (" + settings.effectiveBaseUrl() + ")");

        try {
            String model = settings.modelOverride.trim();
            if (model.isEmpty()) {
                log.accept("[3/5] Asking the provider which models are available...");
                List<String> ids = client.listModels(call);
                log.accept("      " + ids.size() + " models listed.");
                model = AiModelPicker.pick(settings.provider, ids);
                if (model == null) {
                    throw new AiException(AiException.Kind.MODEL_NOT_FOUND, 0, "no suitable model in the list");
                }
                settings.autoModel = model;
            } else {
                log.accept("[3/5] Skipped model list: using your model override.");
            }
            log.accept("[4/5] Model: " + AiModelPicker.normalize(model));

            if (showRequest) {
                AiStyle st = style == null ? new AiStyle() : style;
                List<AiClient.Msg> sample = AiPrompts.assist(st, "viết tiếp 3 câu",
                        "Mai ngồi sát cửa sổ toa tàu. Mưa rơi.", "", "", "Cảnh 1");
                log.accept("      --- sample request that /assist would send ---");
                log.accept(client.body(AiModelPicker.normalize(model), sample, st.temperature(), st.maxTokens(), 0));
                log.accept("      -----------------------------------------------");
            }

            log.accept("[5/5] Sending a short test message...");
            AiClient.Reply r = client.ping(call);
            d.model = r.model;
            d.millis = r.millis;
            String snippet = r.text.replaceAll("\\s+", " ");
            if (snippet.length() > 60) snippet = snippet.substring(0, 60) + "...";
            log.accept("      Reply in " + r.millis + " ms: \"" + snippet + "\"");
            d.ok = true;
            d.summary = "Đã kết nối " + d.provider + " \u2713  (model " + d.model + ", " + r.millis + " ms)";
            log.accept("OK. Saved.");
            settings.save();
        } catch (AiException e) {
            d.error = e;
            log.accept("      FAILED: " + e.getMessage());
            d.summary = e.friendly();
        }
        return d;
    }
}

package writerstudio;

import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * Connection settings for the AI assistant. The API key lives in the user's
 * Java preferences (never in the project folder, so it is not committed to Git
 * or sent along when a project is zipped).
 */
final class AiSettings {
    enum Provider {
        GEMINI("Gemini", "https://generativelanguage.googleapis.com/v1beta/openai/"),
        GROQ("Groq", "https://api.groq.com/openai/v1"),
        OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1"),
        CUSTOM("Custom (OpenAI-compatible)", "");

        final String label;
        final String baseUrl;

        Provider(String label, String baseUrl) {
            this.label = label;
            this.baseUrl = baseUrl;
        }

        @Override public String toString() { return label; }
    }

    private final Preferences prefs;      // null = in memory only (tests)

    String apiKey = "";
    Provider provider = null;             // null = not detected yet
    String baseUrl = "";                  // empty = use the provider's default
    String modelOverride = "";            // empty = pick a model automatically
    String autoModel = "";                // last model chosen automatically

    /** In-memory settings (used by tests and by the command-line test). */
    AiSettings() {
        this.prefs = null;
    }

    private AiSettings(Preferences prefs) {
        this.prefs = prefs;
    }

    static AiSettings load() {
        Preferences p = Preferences.userNodeForPackage(AiSettings.class).node("ai");
        AiSettings s = new AiSettings(p);
        s.apiKey = p.get("apiKey", "");
        String prov = p.get("provider", "");
        if (!prov.isEmpty()) {
            try {
                s.provider = Provider.valueOf(prov);
            } catch (IllegalArgumentException ignored) {
                s.provider = null;
            }
        }
        s.baseUrl = p.get("baseUrl", "");
        s.modelOverride = p.get("modelOverride", "");
        s.autoModel = p.get("autoModel", "");
        return s;
    }

    void save() {
        if (prefs == null) return;
        prefs.put("apiKey", apiKey);
        prefs.put("provider", provider == null ? "" : provider.name());
        prefs.put("baseUrl", baseUrl);
        prefs.put("modelOverride", modelOverride);
        prefs.put("autoModel", autoModel);
        try {
            prefs.flush();
        } catch (BackingStoreException ignored) {
            // preferences could not be written; the in-memory values still work this session
        }
    }

    /** Removes the key (and the cached model) but keeps the other options. */
    void clearKey() {
        apiKey = "";
        autoModel = "";
        save();
    }

    /** Guesses the provider from the shape of the key; null if unknown. */
    static Provider detect(String key) {
        if (key == null) return null;
        String k = key.trim();
        if (k.startsWith("AIza")) return Provider.GEMINI;
        if (k.startsWith("gsk_")) return Provider.GROQ;
        if (k.startsWith("sk-or-")) return Provider.OPENROUTER;
        return null;
    }

    boolean hasKey() {
        return !apiKey.trim().isEmpty();
    }

    /** True when the app can try to call the API (a custom/local server may need no key). */
    boolean isConfigured() {
        if (provider == Provider.CUSTOM) return !effectiveBaseUrl().isEmpty();
        return hasKey();
    }

    String effectiveBaseUrl() {
        if (!baseUrl.trim().isEmpty()) return baseUrl.trim();
        return provider == null ? "" : provider.baseUrl;
    }

    String effectiveModel() {
        return !modelOverride.trim().isEmpty() ? modelOverride.trim() : autoModel;
    }

    /** First characters of the key for display, e.g. "AIza****". */
    String maskedKey() {
        String k = apiKey.trim();
        if (k.isEmpty()) return "(none)";
        return k.substring(0, Math.min(4, k.length())) + "****(" + k.length() + " chars)";
    }

    /** Removes the API key from a piece of text before it is shown or logged. */
    String redact(String text) {
        if (text == null) return "";
        String k = apiKey.trim();
        return k.length() >= 8 ? text.replace(k, "[key hidden]") : text;
    }
}

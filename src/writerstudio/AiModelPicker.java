package writerstudio;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chooses a text-generation model from the list a provider returns, so the user
 * never has to type a model name. Model names change often, hence the heuristics.
 */
final class AiModelPicker {
    private AiModelPicker() {}

    private static final Pattern EXCLUDED = Pattern.compile(
            "(?i)(embed|whisper|tts|audio|image|imagen|veo|guard|moderation|vision|live|aqa|rerank|"
                    + "transcribe|speech|dall|stable|robotics|computer-use|safeguard|compound|orpheus|playai)");
    private static final Pattern GEMINI_FLASH = Pattern.compile("(?i)gemini-(\\d+(?:\\.\\d+)?)-flash");
    private static final Pattern SIZE_B = Pattern.compile("(?i)(?<![\\d.])(\\d{1,3})b(?![a-z])");

    /** Name without a leading "models/" (Gemini lists models that way). */
    static String normalize(String id) {
        return id != null && id.startsWith("models/") ? id.substring("models/".length()) : id;
    }

    /** @return the chosen model id, or null when nothing suitable is in the list */
    static String pick(AiSettings.Provider provider, List<String> ids) {
        List<String> all = new ArrayList<>();
        for (String id : ids) {
            String n = normalize(id);
            if (n != null && !n.isBlank() && !EXCLUDED.matcher(n).find()) all.add(n);
        }
        if (all.isEmpty()) return null;
        AiSettings.Provider p = provider == null ? AiSettings.Provider.CUSTOM : provider;
        switch (p) {
            case GEMINI:
                return pickGemini(all);
            case GROQ:
                return pickBySize(all, false);
            case OPENROUTER:
                return pickBySize(all, true);
            default:
                return all.get(0);
        }
    }

    private static String pickGemini(List<String> all) {
        List<String> gemini = new ArrayList<>();
        for (String n : all) if (n.toLowerCase().startsWith("gemini")) gemini.add(n);
        if (gemini.isEmpty()) return all.get(0);

        for (String n : gemini) if (n.equalsIgnoreCase("gemini-flash-latest")) return n;

        String best = null;
        double bestVersion = -1;
        for (int pass = 0; pass < 2 && best == null; pass++) {
            for (String n : gemini) {
                String l = n.toLowerCase();
                Matcher m = GEMINI_FLASH.matcher(l);
                if (!m.find() || l.contains("lite")) continue;
                boolean unstable = l.contains("preview") || l.contains("exp") || l.contains("thinking");
                if (pass == 0 && unstable) continue;
                double v = Double.parseDouble(m.group(1));
                if (v > bestVersion) {
                    bestVersion = v;
                    best = n;
                }
            }
        }
        if (best != null) return best;
        for (String n : gemini) if (n.toLowerCase().contains("flash")) return n;
        for (String n : gemini) if (n.toLowerCase().contains("pro")) return n;
        return gemini.get(0);
    }

    /** Prefers the largest model (by the "NNb" in its name); for OpenRouter only ":free" ones. */
    private static String pickBySize(List<String> all, boolean freeOnly) {
        String best = null;
        double bestScore = -1e9;
        for (String n : all) {
            String l = n.toLowerCase();
            if (freeOnly && !l.endsWith(":free")) continue;
            double score = 0;
            Matcher m = SIZE_B.matcher(l);
            while (m.find()) score = Math.max(score, Double.parseDouble(m.group(1)));
            if (score > 250) score = 0;                    // implausible: probably not a size
            if (l.contains("preview")) score -= 5;
            if (l.contains("versatile") || l.contains("instruct")) score += 0.5;
            if (score > bestScore) {
                bestScore = score;
                best = n;
            }
        }
        return best;
    }
}

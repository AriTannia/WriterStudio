package writerstudio;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Minimal client for the "OpenAI-compatible" chat API that Gemini, Groq, OpenRouter,
 * Ollama and many others speak: {@code GET {base}/models} and {@code POST {base}/chat/completions}.
 * Blocking; call it from a background thread.
 */
final class AiClient {
    record Msg(String role, String content) {
        static Msg system(String c) { return new Msg("system", c); }
        static Msg user(String c) { return new Msg("user", c); }
    }

    static final class Reply {
        final String text;
        final String model;
        final long millis;
        final String finishReason;

        Reply(String text, String model, long millis, String finishReason) {
            this.text = text;
            this.model = model;
            this.millis = millis;
            this.finishReason = finishReason;
        }
    }

    /** Handle that lets another thread cancel a running request. */
    static final class Call {
        private volatile boolean cancelled;
        private volatile CompletableFuture<?> inflight;

        void cancel() {
            cancelled = true;
            CompletableFuture<?> f = inflight;
            if (f != null) f.cancel(true);
        }

        boolean isCancelled() { return cancelled; }
    }

    private record Http(int status, String body, String retryAfter) {}

    private static final Gson GSON = new Gson();

    private final AiSettings s;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    int requestTimeoutSeconds = 60;

    AiClient(AiSettings settings) {
        this.s = settings;
    }

    // ---- models ----------------------------------------------------------------------------

    List<String> listModels(Call call) throws AiException {
        Http r = send("GET", "models", null, call);
        if (r.status < 200 || r.status > 299) throw httpError(r, "");
        try {
            JsonObject root = JsonParser.parseString(r.body).getAsJsonObject();
            List<String> ids = new ArrayList<>();
            JsonArray data = root.has("data") && root.get("data").isJsonArray() ? root.getAsJsonArray("data") : null;
            if (data != null) {
                for (JsonElement e : data) {
                    if (e.isJsonObject() && e.getAsJsonObject().has("id")) ids.add(e.getAsJsonObject().get("id").getAsString());
                }
            } else if (root.has("models") && root.get("models").isJsonArray()) {   // Gemini-native shape
                for (JsonElement e : root.getAsJsonArray("models")) {
                    if (e.isJsonObject() && e.getAsJsonObject().has("name")) ids.add(e.getAsJsonObject().get("name").getAsString());
                }
            }
            return ids;
        } catch (RuntimeException e) {
            throw new AiException(AiException.Kind.BAD_RESPONSE, r.status, "model list is not valid JSON");
        }
    }

    /** The model to use: the user's override, else a cached automatic choice, else pick one now. */
    String ensureModel(Call call) throws AiException {
        String m = s.effectiveModel();
        if (!m.isEmpty()) return AiModelPicker.normalize(m);
        List<String> ids = listModels(call);
        String pick = AiModelPicker.pick(s.provider, ids);
        if (pick == null) {
            throw new AiException(AiException.Kind.MODEL_NOT_FOUND, 0, "no suitable model among " + ids.size() + " listed");
        }
        s.autoModel = pick;
        s.save();
        return pick;
    }

    // ---- chat ------------------------------------------------------------------------------------

    Reply ping(Call call) throws AiException {
        List<Msg> msgs = List.of(Msg.user("Reply with exactly one word: OK"));
        return chat(msgs, 0.0, 256, call);
    }

    Reply chat(List<Msg> msgs, double temperature, int maxTokens, Call call) throws AiException {
        if (!s.isConfigured()) throw new AiException(AiException.Kind.NO_KEY, 0, "");
        String model = ensureModel(call);
        int tokens = maxTokens;
        int relax = 0;                      // 0 = everything, 1 = without reasoning_effort, 2 = without max_tokens
        boolean refreshedModel = false, grew = false;
        int rateRetries = 0, serverRetries = 0;

        for (int attempt = 0; attempt < 8; attempt++) {
            long t0 = System.currentTimeMillis();
            Http r = send("POST", "chat/completions", body(model, msgs, temperature, tokens, relax), call);
            if (r.status >= 200 && r.status <= 299) {
                Reply reply = parseReply(r, model, System.currentTimeMillis() - t0);
                if (reply.text.isBlank()) {
                    if ("length".equals(reply.finishReason) && !grew) {
                        grew = true;
                        tokens = Math.min(tokens * 2, 8192);
                        continue;
                    }
                    throw new AiException(AiException.Kind.BAD_RESPONSE, r.status, "empty answer (finish_reason="
                            + reply.finishReason + ")");
                }
                return reply;
            }
            if (r.status == 429) {
                long waitMs = retryWaitMillis(r);
                if (rateRetries++ < 1 && waitMs <= 20_000) {
                    sleep(waitMs, call);
                    continue;
                }
                throw httpError(r, model);
            }
            if (r.status == 401 || r.status == 403) throw httpError(r, model);
            if (r.status == 400) {
                if (looksLikeModelProblem(r.body) && !refreshedModel && s.modelOverride.trim().isEmpty()) {
                    refreshedModel = true;
                    s.autoModel = "";
                    model = ensureModel(call);
                    continue;
                }
                int next = relax + 1;
                if (next == 1 && s.provider != AiSettings.Provider.GEMINI) next = 2;
                if (next <= 2) {
                    relax = next;
                    continue;
                }
                throw httpError(r, model);
            }
            if (r.status == 404) {
                if (!refreshedModel && s.modelOverride.trim().isEmpty()) {
                    refreshedModel = true;
                    s.autoModel = "";
                    model = ensureModel(call);
                    continue;
                }
                throw httpError(r, model);
            }
            if (r.status >= 500) {
                if (serverRetries++ < 1) {
                    sleep(2000, call);
                    continue;
                }
                throw httpError(r, model);
            }
            throw httpError(r, model);
        }
        throw new AiException(AiException.Kind.OTHER, 0, "too many retries");
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** The exact JSON that is sent (also used by the "show prompt" diagnostics). */
    String body(String model, List<Msg> msgs, double temperature, int maxTokens, int relax) {
        JsonObject o = new JsonObject();
        o.addProperty("model", model);
        JsonArray arr = new JsonArray();
        for (Msg m : msgs) {
            JsonObject j = new JsonObject();
            j.addProperty("role", m.role());
            j.addProperty("content", m.content());
            arr.add(j);
        }
        o.add("messages", arr);
        o.addProperty("temperature", temperature);
        if (relax < 2) o.addProperty("max_tokens", maxTokens);
        if (relax < 1 && s.provider == AiSettings.Provider.GEMINI) o.addProperty("reasoning_effort", "low");
        return GSON.toJson(o);
    }

    private Reply parseReply(Http r, String model, long millis) throws AiException {
        try {
            JsonObject root = JsonParser.parseString(r.body).getAsJsonObject();
            JsonObject first = root.getAsJsonArray("choices").get(0).getAsJsonObject();
            String finish = first.has("finish_reason") && !first.get("finish_reason").isJsonNull()
                    ? first.get("finish_reason").getAsString() : "";
            String text = "";
            if (first.has("message") && first.get("message").isJsonObject()) {
                JsonElement c = first.getAsJsonObject("message").get("content");
                if (c != null && c.isJsonPrimitive()) {
                    text = c.getAsString();
                } else if (c != null && c.isJsonArray()) {
                    StringBuilder sb = new StringBuilder();
                    for (JsonElement part : c.getAsJsonArray()) {
                        if (part.isJsonObject() && part.getAsJsonObject().has("text")) {
                            sb.append(part.getAsJsonObject().get("text").getAsString());
                        }
                    }
                    text = sb.toString();
                }
            }
            text = text.replaceAll("(?s)<think>.*?</think>", "").strip();
            return new Reply(text, model, millis, finish);
        } catch (RuntimeException e) {
            throw new AiException(AiException.Kind.BAD_RESPONSE, r.status, "unexpected response shape: " + shorten(r.body));
        }
    }

    private Http send(String method, String path, String json, Call call) throws AiException {
        if (call.isCancelled()) throw new AiException(AiException.Kind.CANCELLED, 0, "");
        String base = s.effectiveBaseUrl();
        if (base.isEmpty()) throw new AiException(AiException.Kind.OTHER, 0, "no API address configured");
        if (!base.endsWith("/")) base += "/";
        HttpRequest.Builder b;
        try {
            b = HttpRequest.newBuilder(URI.create(base + path));
        } catch (IllegalArgumentException e) {
            throw new AiException(AiException.Kind.OTHER, 0, "invalid API address: " + base);
        }
        b.timeout(Duration.ofSeconds(requestTimeoutSeconds)).header("Accept", "application/json");
        if (s.hasKey()) b.header("Authorization", "Bearer " + s.apiKey.trim());
        if (json != null) {
            b.header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        } else {
            b.GET();
        }
        CompletableFuture<HttpResponse<String>> f = http.sendAsync(b.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        call.inflight = f;
        try {
            HttpResponse<String> r = f.get(requestTimeoutSeconds + 10L, TimeUnit.SECONDS);
            return new Http(r.statusCode(), r.body(), r.headers().firstValue("retry-after").orElse(""));
        } catch (CancellationException e) {
            throw new AiException(AiException.Kind.CANCELLED, 0, "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException(AiException.Kind.CANCELLED, 0, "");
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new AiException(AiException.Kind.TIMEOUT, 0, "");
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (call.isCancelled() || c instanceof CancellationException) {
                throw new AiException(AiException.Kind.CANCELLED, 0, "");
            }
            if (c instanceof HttpTimeoutException) throw new AiException(AiException.Kind.TIMEOUT, 0, "");
            if (c instanceof IOException) {
                throw new AiException(AiException.Kind.NETWORK, 0, s.redact(c.getClass().getSimpleName() + (c.getMessage() == null ? "" : ": " + c.getMessage())));
            }
            throw new AiException(AiException.Kind.OTHER, 0, s.redact(String.valueOf(c)));
        }
    }

    private AiException httpError(Http r, String model) {
        String detail = shorten(r.body);
        switch (r.status) {
            case 401:
            case 403:
                return new AiException(AiException.Kind.UNAUTHORIZED, r.status, detail);
            case 429:
                return new AiException(AiException.Kind.RATE_LIMIT, r.status, detail);
            case 404:
                return new AiException(AiException.Kind.MODEL_NOT_FOUND, r.status, model.isEmpty() ? detail : model);
            default:
                if (r.status == 400 && looksLikeModelProblem(r.body)) {
                    return new AiException(AiException.Kind.MODEL_NOT_FOUND, r.status, model.isEmpty() ? detail : model);
                }
                if (r.status >= 500) return new AiException(AiException.Kind.SERVER, r.status, detail);
                return new AiException(AiException.Kind.OTHER, r.status, detail);
        }
    }

    private static boolean looksLikeModelProblem(String body) {
        String b = body == null ? "" : body.toLowerCase();
        return b.contains("model") && (b.contains("not found") || b.contains("does not exist")
                || b.contains("not supported") || b.contains("decommission") || b.contains("invalid model")
                || b.contains("no longer"));
    }

    private static long retryWaitMillis(Http r) {
        try {
            return (long) (Double.parseDouble(r.retryAfter.trim()) * 1000);
        } catch (NumberFormatException e) {
            return 4000;
        }
    }

    private String shorten(String body) {
        String t = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        if (t.length() > 220) t = t.substring(0, 220) + "…";
        return s.redact(t);
    }

    private static void sleep(long ms, Call call) throws AiException {
        long end = System.currentTimeMillis() + ms;
        try {
            while (System.currentTimeMillis() < end) {
                if (call.isCancelled()) throw new AiException(AiException.Kind.CANCELLED, 0, "");
                Thread.sleep(Math.min(100, Math.max(1, end - System.currentTimeMillis())));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException(AiException.Kind.CANCELLED, 0, "");
        }
    }
}

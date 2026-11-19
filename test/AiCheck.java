package writerstudio;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Logic tests for the AI layer: provider detection, model picking, style, prompts, HTTP client. */
public class AiCheck {
    static int failures = 0;

    static void ok(boolean cond, String msg) {
        System.out.println((cond ? "PASS  " : "FAIL  ") + msg);
        if (!cond) failures++;
    }

    static AiSettings settings(FakeAi fake, AiSettings.Provider p, String key) {
        AiSettings s = new AiSettings();
        s.apiKey = key;
        s.provider = p;
        s.baseUrl = fake.baseUrl();
        return s;
    }

    static AiException expectError(AiClient c, AiClient.Call call) {
        try {
            c.chat(List.of(AiClient.Msg.user("hi")), 0.5, 100, call);
            return null;
        } catch (AiException e) {
            return e;
        }
    }

    public static void main(String[] a) throws Exception {
        // ---------- provider detection ----------
        ok(AiSettings.detect("AIzaSyABC123") == AiSettings.Provider.GEMINI, "detect Gemini key");
        ok(AiSettings.detect("gsk_abc123") == AiSettings.Provider.GROQ, "detect Groq key");
        ok(AiSettings.detect("sk-or-v1-abc") == AiSettings.Provider.OPENROUTER, "detect OpenRouter key");
        ok(AiSettings.detect("whatever") == null && AiSettings.detect("") == null, "unknown key -> null");

        // ---------- model picking ----------
        ok("gemini-flash-latest".equals(AiModelPicker.pick(AiSettings.Provider.GEMINI, List.of(
                "models/gemini-2.5-pro", "models/gemini-flash-latest", "models/gemini-2.5-flash", "models/text-embedding-004"))),
                "gemini: prefers the flash-latest alias");
        ok("gemini-3.5-flash".equals(AiModelPicker.pick(AiSettings.Provider.GEMINI, List.of(
                "models/gemini-2.5-flash", "models/gemini-3.5-flash", "models/gemini-3.5-flash-lite",
                "models/gemini-4-flash-preview", "models/gemini-2.5-flash-preview-tts", "models/gemini-2.5-pro"))),
                "gemini: highest stable non-lite flash, ignores preview/tts/lite");
        ok("gemini-2.5-flash-lite".equals(AiModelPicker.pick(AiSettings.Provider.GEMINI, List.of(
                "models/gemini-2.5-flash-lite", "models/gemini-2.5-pro"))), "gemini: flash-lite is preferred over pro when it is the only flash (bigger free quota)");
        ok("openai/gpt-oss-120b".equals(AiModelPicker.pick(AiSettings.Provider.GROQ, List.of(
                "llama-3.1-8b-instant", "llama-3.3-70b-versatile", "openai/gpt-oss-120b", "whisper-large-v3",
                "meta-llama/llama-guard-4-12b", "groq/compound", "playai-tts"))), "groq: largest chat model, skips whisper/guard/tts/compound");
        ok("qwen/qwen3-72b:free".equals(AiModelPicker.pick(AiSettings.Provider.OPENROUTER, List.of(
                "openai/gpt-4o", "meta-llama/llama-3.3-70b-instruct", "qwen/qwen3-72b:free", "mistralai/mistral-7b:free"))),
                "openrouter: only :free models, largest");
        ok(AiModelPicker.pick(AiSettings.Provider.OPENROUTER, List.of("openai/gpt-4o")) == null, "openrouter: nothing free -> null");
        ok("llama3".equals(AiModelPicker.pick(AiSettings.Provider.CUSTOM, List.of("llama3", "nomic-embed-text"))), "custom: first chat model");

        // ---------- style ----------
        AiStyle st = new AiStyle();
        ok(st.composeLocal().contains("chính tả") && st.composeLocal().contains("không gian"), "default style prompt mentions spelling + space");
        ok(Math.abs(st.temperature() - 0.72) < 1e-9, "creativity 60 -> temperature 0.72, got " + st.temperature());
        st.creativity = 0;
        ok(Math.abs(st.temperature() - 0.3) < 1e-9, "creativity 0 -> 0.3");
        st.creativity = 100;
        ok(Math.abs(st.temperature() - 1.0) < 1e-9, "creativity 100 -> 1.0");
        ok(AiStyle.creativityFromTemperature(0.65) == 50, "temperature 0.65 -> creativity 50");
        File dir = Files.createTempDirectory("ai-style").toFile();
        AiStyle s2 = new AiStyle();
        s2.description = "văn phong trầm, \"có dấu nháy\" và\nxuống dòng";
        s2.options.clear();
        s2.options.add("dialogue");
        s2.length = AiStyle.Length.LONG;
        s2.creativity = 33;
        s2.systemPrompt = "Dòng 1\nDòng 2 — có ký tự đặc biệt: ặằẵ";
        s2.save(dir);
        AiStyle s3 = AiStyle.load(dir);
        ok(s3.description.equals(s2.description) && s3.systemPrompt.equals(s2.systemPrompt), "style round-trips Vietnamese + newlines");
        ok(s3.options.equals(s2.options) && s3.length == AiStyle.Length.LONG && s3.creativity == 33, "style options/length/creativity round-trip");
        ok(AiStyle.load(new File(dir, "nope")).options.size() == 4, "missing style file -> defaults");

        // ---------- prompts ----------
        List<AiClient.Msg> m = AiPrompts.assist(new AiStyle(), "viết tiếp 3 câu", "TRƯỚC", "SAU", "TÓM TẮT X", "Cảnh 1");
        ok(m.get(0).content().contains("QUY TẮC CỐ ĐỊNH") && m.get(0).content().contains("chính tả"), "assist system = fixed rules + style");
        String u = m.get(1).content();
        ok(u.contains("TRƯỚC") && u.contains("SAU") && u.contains("TÓM TẮT X") && u.contains("viết tiếp 3 câu") && u.contains("130 chữ"), "assist user message has context + instruction + length");
        ok(AiPrompts.assist(new AiStyle(), "", "x", "", "", "").get(1).content().contains("Viết tiếp một cách tự nhiên"), "empty instruction = continue naturally");
        ok(!AiPrompts.assist(new AiStyle(), "a", "x", "", "", "").get(1).content().contains("SAU VỊ TRÍ"), "no 'after' block when there is no text after");

        // ---------- text helpers ----------
        ok(AiCommandController.normalize("```\nxin chào\n```").equals("xin chào"), "normalize strips code fence");
        ok(AiCommandController.normalize("a\r\n\r\n\r\n\r\nb").equals("a\n\nb"), "normalize collapses blank lines + CRLF");
        String long1 = "para1\n" + "x".repeat(50) + "\npara3";
        ok(AiCommandController.tailAtParagraph(long1, 20).equals("para3"), "tail cuts at a paragraph boundary");
        ok(AiCommandController.headAtParagraph(long1, 20).equals("para1"), "head cuts at a paragraph boundary");
        ok(AiCommandController.shorten("một hai ba bốn năm sáu bảy tám chín mười", 15).endsWith("…"), "shorten adds ellipsis");

        // ---------- client against a fake server ----------
        FakeAi fake = new FakeAi();
        try {
            clientTests(fake);
        } finally {
            fake.stop();
        }

        System.out.println(failures == 0 ? "\nALL OK" : "\nFAILURES: " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    static void clientTests(FakeAi fake) throws Exception {
        String key = "AIzaTESTKEY-0123456789";

        // normal flow: auto model + unicode + auth + params
        fake.handler = r -> {
            if (r.path.endsWith("/models")) return FakeAi.Resp.json(200, FakeAi.models("models/gemini-2.5-pro", "models/gemini-2.5-flash", "models/text-embedding-004"));
            return FakeAi.Resp.json(200, FakeAi.completion("Xin chào \"thế giới\"\nDòng hai — ặằẵ"));
        };
        AiSettings s = settings(fake, AiSettings.Provider.GEMINI, key);
        AiClient c = new AiClient(s);
        AiClient.Reply r = c.chat(List.of(AiClient.Msg.system("SYS đ"), AiClient.Msg.user("Hỏi \"gì\"?\nDòng 2")), 0.72, 1234, new AiClient.Call());
        ok(r.text.equals("Xin chào \"thế giới\"\nDòng hai — ặằẵ"), "reply text round-trips (Vietnamese, quotes, newline)");
        ok("gemini-2.5-flash".equals(s.autoModel) && "gemini-2.5-flash".equals(r.model), "model auto-picked and cached");
        FakeAi.Req chatReq = fake.requests.get(fake.requests.size() - 1);
        JsonObject body = JsonParser.parseString(chatReq.body).getAsJsonObject();
        ok(("Bearer " + key).equals(chatReq.auth), "Authorization: Bearer <key> sent");
        ok(body.get("model").getAsString().equals("gemini-2.5-flash") && body.get("max_tokens").getAsInt() == 1234, "body has model + max_tokens");
        ok(Math.abs(body.get("temperature").getAsDouble() - 0.72) < 1e-9 && body.has("reasoning_effort"), "body has temperature (+ reasoning_effort for Gemini)");
        ok(body.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString().equals("Hỏi \"gì\"?\nDòng 2"), "message content escaped/unescaped correctly");

        // second call must not list models again
        int before = fake.requests.size();
        c.chat(List.of(AiClient.Msg.user("again")), 0.5, 50, new AiClient.Call());
        ok(fake.requests.size() == before + 1, "cached model: only one request for the second call");

        // 401
        fake.handler = req -> FakeAi.Resp.json(401, "{\"error\":\"bad key " + key + "\"}");
        s = settings(fake, AiSettings.Provider.GEMINI, key); s.modelOverride = "m";
        AiException e = expectError(new AiClient(s), new AiClient.Call());
        ok(e != null && e.kind == AiException.Kind.UNAUTHORIZED, "401 -> UNAUTHORIZED");
        ok(e != null && !e.getMessage().contains(key) && !e.friendly().contains(key), "API key is redacted from error text");

        // 429 then success
        final int[] n = {0};
        fake.handler = req -> {
            if (n[0]++ == 0) { FakeAi.Resp x = FakeAi.Resp.json(429, "{}"); x.retryAfter = "0"; return x; }
            return FakeAi.Resp.json(200, FakeAi.completion("ok sau khi chờ"));
        };
        s = settings(fake, AiSettings.Provider.CUSTOM, "k"); s.modelOverride = "m";
        ok(new AiClient(s).chat(List.of(AiClient.Msg.user("x")), 0.5, 50, new AiClient.Call()).text.equals("ok sau khi chờ"), "429 once then success -> retried automatically");
        fake.handler = req -> { FakeAi.Resp x = FakeAi.Resp.json(429, "{}"); x.retryAfter = "0"; return x; };
        e = expectError(new AiClient(s), new AiClient.Call());
        ok(e != null && e.kind == AiException.Kind.RATE_LIMIT, "429 twice -> RATE_LIMIT");
        fake.handler = req -> { FakeAi.Resp x = FakeAi.Resp.json(429, "{}"); x.retryAfter = "3600"; return x; };
        long t0 = System.currentTimeMillis();
        e = expectError(new AiClient(s), new AiClient.Call());
        ok(e != null && e.kind == AiException.Kind.RATE_LIMIT && System.currentTimeMillis() - t0 < 3000, "429 with huge Retry-After fails fast");

        // model not found -> refresh list -> success
        fake.handler = req -> {
            if (req.path.endsWith("/models")) return FakeAi.Resp.json(200, FakeAi.models("llama-3.3-70b-versatile"));
            JsonObject b = JsonParser.parseString(req.body).getAsJsonObject();
            if (b.get("model").getAsString().equals("retired-model")) return FakeAi.Resp.json(404, "{\"error\":{\"message\":\"The model `retired-model` does not exist\"}}");
            return FakeAi.Resp.json(200, FakeAi.completion("dùng model mới"));
        };
        s = settings(fake, AiSettings.Provider.GROQ, "gsk_testkey12345678"); s.autoModel = "retired-model";
        r = new AiClient(s).chat(List.of(AiClient.Msg.user("x")), 0.5, 50, new AiClient.Call());
        ok(r.text.equals("dùng model mới") && "llama-3.3-70b-versatile".equals(s.autoModel), "404 on cached model -> list refreshed, new model used");
        s = settings(fake, AiSettings.Provider.GROQ, "gsk_testkey12345678"); s.modelOverride = "retired-model";
        e = expectError(new AiClient(s), new AiClient.Call());
        ok(e != null && e.kind == AiException.Kind.MODEL_NOT_FOUND, "404 on user-chosen model -> MODEL_NOT_FOUND (no silent switch)");

        // 400 on reasoning_effort -> retried without
        fake.handler = req -> {
            if (req.body.contains("reasoning_effort")) return FakeAi.Resp.json(400, "{\"error\":\"Unknown field reasoning_effort\"}");
            return FakeAi.Resp.json(200, FakeAi.completion("không cần reasoning"));
        };
        s = settings(fake, AiSettings.Provider.GEMINI, key); s.modelOverride = "gemini-x";
        ok(new AiClient(s).chat(List.of(AiClient.Msg.user("x")), 0.5, 50, new AiClient.Call()).text.equals("không cần reasoning"), "400 on optional param -> retried without it");

        // empty + length -> more tokens
        final int[] seen = {0};
        fake.handler = req -> {
            JsonObject b = JsonParser.parseString(req.body).getAsJsonObject();
            seen[0] = b.get("max_tokens").getAsInt();
            if (seen[0] < 400) return FakeAi.Resp.json(200, "{\"choices\":[{\"message\":{\"content\":\"\"},\"finish_reason\":\"length\"}]}");
            return FakeAi.Resp.json(200, FakeAi.completion("đủ chỗ rồi"));
        };
        s = settings(fake, AiSettings.Provider.CUSTOM, "k"); s.modelOverride = "m";
        ok(new AiClient(s).chat(List.of(AiClient.Msg.user("x")), 0.5, 256, new AiClient.Call()).text.equals("đủ chỗ rồi") && seen[0] == 512, "empty answer cut by length -> retried with doubled max_tokens");

        // content as array + <think>
        fake.handler = req -> FakeAi.Resp.json(200, "{\"choices\":[{\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"<think>nghĩ...</think>Kết quả\"},{\"type\":\"text\",\"text\":\" cuối\"}]},\"finish_reason\":\"stop\"}]}");
        ok(new AiClient(s).chat(List.of(AiClient.Msg.user("x")), 0.5, 50, new AiClient.Call()).text.equals("Kết quả cuối"), "array content joined, <think> removed");

        // garbage
        fake.handler = req -> FakeAi.Resp.json(200, "<html>not json</html>");
        e = expectError(new AiClient(s), new AiClient.Call());
        ok(e != null && e.kind == AiException.Kind.BAD_RESPONSE, "non-JSON 200 -> BAD_RESPONSE");

        // 500
        fake.handler = req -> FakeAi.Resp.json(503, "down");
        e = expectError(new AiClient(s), new AiClient.Call());
        ok(e != null && e.kind == AiException.Kind.SERVER, "503 twice -> SERVER");

        // timeout
        fake.handler = req -> { FakeAi.Resp x = FakeAi.Resp.json(200, FakeAi.completion("late")); x.delayMs = 3000; return x; };
        AiClient slow = new AiClient(s);
        slow.requestTimeoutSeconds = 1;
        e = expectError(slow, new AiClient.Call());
        ok(e != null && e.kind == AiException.Kind.TIMEOUT, "slow server -> TIMEOUT");

        // cancel
        final AiClient.Call call = new AiClient.Call();
        new Thread(() -> { try { Thread.sleep(300); } catch (InterruptedException ignored) { } call.cancel(); }).start();
        t0 = System.currentTimeMillis();
        e = expectError(new AiClient(s), call);
        ok(e != null && e.kind == AiException.Kind.CANCELLED && System.currentTimeMillis() - t0 < 2000, "cancel aborts the request quickly");

        // network down
        s = new AiSettings(); s.apiKey = "k"; s.provider = AiSettings.Provider.CUSTOM; s.baseUrl = "http://127.0.0.1:9/v1"; s.modelOverride = "m";
        e = expectError(new AiClient(s), new AiClient.Call());
        ok(e != null && e.kind == AiException.Kind.NETWORK, "connection refused -> NETWORK");

        // no key
        s = new AiSettings();
        e = expectError(new AiClient(s), new AiClient.Call());
        ok(e != null && e.kind == AiException.Kind.NO_KEY, "no key -> NO_KEY");

        // diagnose
        fake.handler = req -> {
            if (req.path.endsWith("/models")) return FakeAi.Resp.json(200, FakeAi.models("models/gemini-2.5-flash"));
            return FakeAi.Resp.json(200, FakeAi.completion("OK"));
        };
        s = settings(fake, null, key);
        s.provider = null;                       // must be auto-detected from the key
        List<String> log = new ArrayList<>();
        AiService svc = new AiService(s);
        AiService.Diag d = svc.diagnose(log::add, new AiClient.Call(), true, null);
        ok(d.ok && d.provider.equals("Gemini") && d.model.equals("gemini-2.5-flash"), "diagnose: detects provider, picks model, pings -> ok");
        ok(log.stream().anyMatch(x -> x.contains("sample request")) && log.stream().anyMatch(x -> x.contains("RESULT") || x.startsWith("OK")), "diagnose logs steps and the sample request");
        ok(d.summary.contains("Đã kết nối"), "diagnose summary in Vietnamese");
        ok(log.stream().noneMatch(x -> x.contains(key)), "diagnose log never contains the full key");
        fake.handler = req -> FakeAi.Resp.json(401, "nope");
        s = settings(fake, null, key); s.provider = null;
        d = new AiService(s).diagnose(x -> { }, new AiClient.Call(), false, null);
        ok(!d.ok && d.error != null && d.error.kind == AiException.Kind.UNAUTHORIZED && d.summary.contains("không hợp lệ"), "diagnose with bad key -> UNAUTHORIZED with friendly message");
        s = new AiSettings(); s.apiKey = "randomkeyformat";
        d = new AiService(s).diagnose(x -> { }, new AiClient.Call(), false, null);
        ok(!d.ok && d.summary.contains("Nâng cao"), "unrecognised key format -> asks for provider under Advanced");
    }
}

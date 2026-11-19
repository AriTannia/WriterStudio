package writerstudio;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/** Tiny local server that imitates an OpenAI-compatible API, for tests. */
public class FakeAi {
    public static class Req {
        public String method, path, body, auth;
    }

    public static class Resp {
        public int status = 200;
        public String body = "";
        public String retryAfter;
        public long delayMs;

        public static Resp json(int status, String body) {
            Resp r = new Resp();
            r.status = status;
            r.body = body;
            return r;
        }
    }

    public final HttpServer server;
    public final List<Req> requests = Collections.synchronizedList(new ArrayList<>());
    public volatile Function<Req, Resp> handler;

    public FakeAi() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    public void stop() { server.stop(0); }

    public static String completion(String text) {
        com.google.gson.JsonObject msg = new com.google.gson.JsonObject();
        msg.addProperty("role", "assistant");
        msg.addProperty("content", text);
        com.google.gson.JsonObject choice = new com.google.gson.JsonObject();
        choice.add("message", msg);
        choice.addProperty("finish_reason", "stop");
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        arr.add(choice);
        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        root.add("choices", arr);
        return root.toString();
    }

    public static String models(String... ids) {
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (String id : ids) {
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("id", id);
            arr.add(o);
        }
        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        root.addProperty("object", "list");
        root.add("data", arr);
        return root.toString();
    }

    private void handle(HttpExchange ex) throws IOException {
        Req r = new Req();
        r.method = ex.getRequestMethod();
        r.path = ex.getRequestURI().getPath();
        r.auth = ex.getRequestHeaders().getFirst("Authorization");
        r.body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(r);
        Resp resp;
        try {
            resp = handler.apply(r);
        } catch (RuntimeException e) {
            resp = Resp.json(500, "handler error " + e);
        }
        if (resp.delayMs > 0) {
            try {
                Thread.sleep(resp.delayMs);
            } catch (InterruptedException ignored) {
                // test shutting down
            }
        }
        byte[] bytes = resp.body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        if (resp.retryAfter != null) ex.getResponseHeaders().add("Retry-After", resp.retryAfter);
        try {
            ex.sendResponseHeaders(resp.status, bytes.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(bytes);
            }
        } catch (IOException ignored) {
            // client went away (cancelled request)
        }
    }
}

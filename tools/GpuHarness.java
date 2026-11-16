import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

class GpuHarness {
    public static void main(String[] args) throws Exception {
        var root = Path.of("").toAbsolutePath();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8765), 0);
        server.createContext("/", exchange -> {
            String body;
            String type;
            if (exchange.getRequestURI().getPath().equals("/shaders")) {
                var source = Files.readString(root.resolve("app/src/main/java/dev/uninvg/render/NightRenderer.kt"));
                var matcher = Pattern.compile("private const val (\\w+) = \"\"\"(.*?)\"\"\"", Pattern.DOTALL).matcher(source);
                var json = new StringBuilder("{");
                while (matcher.find()) {
                    if (json.length() > 1) json.append(',');
                    json.append('"').append(matcher.group(1)).append("\":\"")
                        .append(matcher.group(2).replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "\\n"))
                        .append('"');
                }
                body = json.append('}').toString();
                type = "application/json";
            } else {
                body = Files.readString(root.resolve("tools/gpu-harness.html"));
                type = "text/html";
            }
            var data = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, data.length);
            try (var output = exchange.getResponseBody()) { output.write(data); }
        });
        server.start();
        System.out.println("UniNVG GPU harness listening on http://127.0.0.1:8765");
    }
}

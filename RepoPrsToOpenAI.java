import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.Base64;

public class RepoPrsToOpenAI {

    // ----------------------------
    // Entry
    // ----------------------------
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: java RepoPrsToOpenAI <owner> <repo> [--state=open|closed|all] [--maxPrs=10] [--maxCommentsPerPr=20] [--context=3] [--model=gpt-4o-mini] [--githubToken=...] [--openaiKey=...]");
            System.out.println("\nExample:");
            System.out.println("  java RepoPrsToOpenAI octocat Hello-World --state=open --maxPrs=5 --model=gpt-5.2");
            System.exit(1);
        }

        String owner = args[0];
        String repo = args[1];

        Map<String, String> flags = parseFlags(args);

        String state = flags.getOrDefault("state", "open"); // open|closed|all
        int maxPrs = parseInt(flags.getOrDefault("maxPrs", "10"), 10);
        int maxCommentsPerPr = parseInt(flags.getOrDefault("maxCommentsPerPr", "20"), 20);
        int context = parseInt(flags.getOrDefault("context", "3"), 3);
        String model = flags.getOrDefault("model", "gpt-4o-mini"); // you can set gpt-5.2 etc. :contentReference[oaicite:2]{index=2}

        String githubToken = flags.get("githubToken"); // optional, helps rate limits
        String openaiKey = flags.getOrDefault("openaiKey", System.getenv("OPENAI_API_KEY"));

        if (openaiKey == null || openaiKey.isBlank()) {
            throw new RuntimeException("Missing OpenAI key. Set OPENAI_API_KEY or pass --openaiKey=...");
        }

        GitHub gh = new GitHub(githubToken);
        OpenAI oa = new OpenAI(openaiKey);

        // 1) list PRs
        List<Map<String, Object>> prList = gh.listPullRequests(owner, repo, state, maxPrs);

        // 2) build big payload
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("repo", owner + "/" + repo);
        payload.put("state_filter", state);
        payload.put("generated_at", Instant.now().toString());

        List<Object> prsPayload = new ArrayList<>();

        for (Map<String, Object> prItem : prList) {
            int prNumber = ((Number) prItem.get("number")).intValue();

            // detail
            Map<String, Object> prDetail = gh.getPullRequest(owner, repo, prNumber);
            // reviews
            List<Map<String, Object>> reviews = gh.fetchAllPullReviews(owner, repo, prNumber);
            ReviewSummary summary = ReviewSummary.fromReviews(reviews);

            // inline comments
            List<Map<String, Object>> inlineComments = gh.fetchAllInlineReviewComments(owner, repo, prNumber);
            if (inlineComments.size() > maxCommentsPerPr) {
                inlineComments = inlineComments.subList(0, maxCommentsPerPr);
            }

            List<Object> commentsPayload = new ArrayList<>();
            for (Map<String, Object> c : inlineComments) {
                Long id = asLongObj(c.get("id"));
                String path = asString(c.get("path"));
                Integer line = asIntObj(c.get("line"));
                Integer startLine = asIntObj(c.get("start_line"));
                String body = asString(c.get("body"));
                String commitId = asString(c.get("commit_id"));
                String htmlUrl = asString(c.get("html_url"));

                Map<String, Object> user = asMap(c.get("user"));
                String login = user != null ? asString(user.get("login")) : null;

                Map<String, Object> one = new LinkedHashMap<>();
                one.put("id", id);
                one.put("user", login);
                one.put("url", htmlUrl);
                one.put("path", path);
                one.put("start_line", startLine);
                one.put("line", line);
                one.put("body", body);

                // add snippet
                if (path != null && commitId != null && line != null) {
                    int from = (startLine != null ? startLine : line);
                    int to = line;
                    try {
                        String fileText = gh.fetchFileAtCommit(owner, repo, path, commitId);
                        String snippet = buildHighlightedSnippet(fileText, from, to, context);
                        one.put("snippet", snippet);
                    } catch (Exception e) {
                        one.put("snippet_error", e.getMessage());
                    }
                } else {
                    one.put("snippet_error", "Missing path/commit_id/line in comment");
                }

                commentsPayload.add(one);
            }

            // PR core info
            Map<String, Object> prPayload = new LinkedHashMap<>();
            prPayload.put("number", prNumber);
            prPayload.put("title", asString(prDetail.get("title")));
            prPayload.put("url", asString(prDetail.get("html_url")));

            Map<String, Object> prUser = asMap(prDetail.get("user"));
            prPayload.put("author", prUser != null ? asString(prUser.get("login")) : null);

            prPayload.put("state", asString(prDetail.get("state"))); // open/closed
            prPayload.put("merged", prDetail.get("merged_at") != null);

            prPayload.put("created_at", asString(prDetail.get("created_at")));
            prPayload.put("updated_at", asString(prDetail.get("updated_at")));

            Map<String, Object> head = asMap(prDetail.get("head"));
            Map<String, Object> base = asMap(prDetail.get("base"));
            prPayload.put("head_ref", head != null ? asString(head.get("ref")) : null);
            prPayload.put("base_ref", base != null ? asString(base.get("ref")) : null);

            prPayload.put("additions", prDetail.get("additions"));
            prPayload.put("deletions", prDetail.get("deletions"));
            prPayload.put("changed_files", prDetail.get("changed_files"));

            // review summary
            prPayload.put("review_overall", summary.overall);
            prPayload.put("review_by_user", summary.byUser);

            // inline comments payload
            prPayload.put("inline_comments", commentsPayload);

            prsPayload.add(prPayload);
            System.out.println("Collected PR #" + prNumber + " (" + prPayload.get("review_overall") + "), comments=" + commentsPayload.size());
        }

        payload.put("pull_requests", prsPayload);

        // 3) send to OpenAI
        String payloadJson = JsonWriter.toJson(payload);

        String instructions =
                "You are a code review assistant. Analyze the repository PR data I provide.\n" +
                        "For each PR: summarize key issues, suggest concrete fixes, and flag risky changes.\n" +
                        "Output:\n" +
                        "1) Overall repo summary\n" +
                        "2) PR-by-PR bullet list (include PR number, review_overall, and top actions)\n" +
                        "3) If any inline comment snippets show a bug, propose a patch at a high level.\n";

        // Keep input as a single text string (simple)
        String input =
                "Here is JSON data about pull requests, reviews, inline comments, and code snippets:\n\n" +
                        payloadJson;

        String llmText = oa.createResponse(model, instructions, input);

        System.out.println("\n================ OPENAI RESPONSE ================\n");
        System.out.println(llmText);
    }

    // ----------------------------
    // Flags
    // ----------------------------
    static Map<String, String> parseFlags(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (String a : args) {
            if (!a.startsWith("--")) continue;
            int eq = a.indexOf('=');
            if (eq > 2) {
                String k = a.substring(2, eq).trim();
                String v = a.substring(eq + 1).trim();
                m.put(k, v);
            } else {
                m.put(a.substring(2).trim(), "true");
            }
        }
        return m;
    }

    static int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }

    // ----------------------------
    // Highlight snippet builder
    // ----------------------------
    static String buildHighlightedSnippet(String fileText, int fromLine1, int toLine1, int context) {
        String[] lines = fileText.split("\n", -1);
        int n = lines.length;

        int from = Math.max(1, Math.min(fromLine1, toLine1));
        int to = Math.max(fromLine1, toLine1);

        int start = Math.max(1, from - context);
        int end = Math.min(n, to + context);

        StringBuilder sb = new StringBuilder();
        for (int i = start; i <= end; i++) {
            boolean inRange = (i >= from && i <= to);
            String prefix = inRange ? ">> " : "   ";
            sb.append(String.format("%s%5d | %s%n", prefix, i, lines[i - 1]));
        }
        return sb.toString();
    }

    // ----------------------------
    // Review summary
    // ----------------------------
    static class ReviewSummary {
        final String overall; // APPROVED / CHANGES_REQUESTED / COMMENTED / NO_REVIEWS
        final List<Object> byUser; // list of {user,state,submitted_at,review_id}

        ReviewSummary(String overall, List<Object> byUser) {
            this.overall = overall;
            this.byUser = byUser;
        }

        static ReviewSummary fromReviews(List<Map<String, Object>> reviews) {
            if (reviews == null || reviews.isEmpty()) {
                return new ReviewSummary("NO_REVIEWS", List.of());
            }

            // latest review per user
            Map<String, Map<String, Object>> latestByUser = new LinkedHashMap<>();
            for (Map<String, Object> r : reviews) {
                Map<String, Object> user = asMap(r.get("user"));
                String login = user != null ? asString(user.get("login")) : null;
                if (login == null) continue;

                Map<String, Object> prev = latestByUser.get(login);
                if (prev == null || isAfter(r, prev)) {
                    latestByUser.put(login, r);
                }
            }

            boolean anyApproved = false;
            boolean anyCommented = false;

            List<Object> perUser = new ArrayList<>();
            for (Map.Entry<String, Map<String, Object>> e : latestByUser.entrySet()) {
                String login = e.getKey();
                Map<String, Object> r = e.getValue();
                String state = asString(r.get("state")); // APPROVED / CHANGES_REQUESTED / COMMENTED / DISMISSED
                String submittedAt = asString(r.get("submitted_at"));
                long id = asLong(r.get("id"));

                Map<String, Object> one = new LinkedHashMap<>();
                one.put("user", login);
                one.put("state", state);
                one.put("submitted_at", submittedAt);
                one.put("review_id", id);
                perUser.add(one);

                if (state == null) continue;
                if ("DISMISSED".equalsIgnoreCase(state)) continue;
                if ("CHANGES_REQUESTED".equalsIgnoreCase(state)) {
                    return new ReviewSummary("CHANGES_REQUESTED", perUser);
                }
                if ("APPROVED".equalsIgnoreCase(state)) anyApproved = true;
                if ("COMMENTED".equalsIgnoreCase(state)) anyCommented = true;
            }

            String overall = anyApproved ? "APPROVED" : (anyCommented ? "COMMENTED" : "NO_REVIEWS");
            return new ReviewSummary(overall, perUser);
        }

        static boolean isAfter(Map<String, Object> a, Map<String, Object> b) {
            String aTime = asString(a.get("submitted_at"));
            String bTime = asString(b.get("submitted_at"));
            if (aTime != null && bTime != null) return aTime.compareTo(bTime) > 0;
            if (aTime != null) return true;
            if (bTime != null) return false;
            return asLong(a.get("id")) > asLong(b.get("id"));
        }
    }

    // ----------------------------
    // GitHub client
    // ----------------------------
    static class GitHub {
        private final HttpClient http;
        private final String token;

        GitHub(String token) {
            this.token = token;
            this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        }

        List<Map<String, Object>> listPullRequests(String owner, String repo, String state, int maxPrs)
                throws IOException, InterruptedException {

            List<Map<String, Object>> out = new ArrayList<>();
            int page = 1;
            int perPage = 100;

            while (out.size() < maxPrs) {
                String url = "https://api.github.com/repos/" + enc(owner) + "/" + enc(repo)
                        + "/pulls?state=" + enc(state) + "&per_page=" + perPage + "&page=" + page;

                Object parsed = Json.parse(get(url, false));
                if (!(parsed instanceof List)) throw new RuntimeException("Unexpected JSON response for PR list.");
                @SuppressWarnings("unchecked")
                List<Object> arr = (List<Object>) parsed;

                if (arr.isEmpty()) break;

                for (Object o : arr) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> pr = (Map<String, Object>) o;
                    out.add(pr);
                    if (out.size() >= maxPrs) break;
                }

                if (arr.size() < perPage) break;
                page++;
            }
            return out;
        }

        Map<String, Object> getPullRequest(String owner, String repo, int prNumber)
                throws IOException, InterruptedException {

            String url = "https://api.github.com/repos/" + enc(owner) + "/" + enc(repo)
                    + "/pulls/" + prNumber;

            Object parsed = Json.parse(get(url, false));
            if (!(parsed instanceof Map)) throw new RuntimeException("Unexpected JSON response for PR detail.");
            @SuppressWarnings("unchecked")
            Map<String, Object> obj = (Map<String, Object>) parsed;
            return obj;
        }

        List<Map<String, Object>> fetchAllInlineReviewComments(String owner, String repo, int pr)
                throws IOException, InterruptedException {

            List<Map<String, Object>> all = new ArrayList<>();
            int page = 1;
            int perPage = 100;

            while (true) {
                String url = "https://api.github.com/repos/" + enc(owner) + "/" + enc(repo)
                        + "/pulls/" + pr + "/comments?per_page=" + perPage + "&page=" + page;

                Object parsed = Json.parse(get(url, false));
                if (!(parsed instanceof List)) throw new RuntimeException("Unexpected JSON response for PR comments.");
                @SuppressWarnings("unchecked")
                List<Object> arr = (List<Object>) parsed;

                if (arr.isEmpty()) break;

                for (Object o : arr) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) o;
                    all.add(m);
                }

                if (arr.size() < perPage) break;
                page++;
            }

            return all;
        }

        List<Map<String, Object>> fetchAllPullReviews(String owner, String repo, int pr)
                throws IOException, InterruptedException {

            List<Map<String, Object>> all = new ArrayList<>();
            int page = 1;
            int perPage = 100;

            while (true) {
                String url = "https://api.github.com/repos/" + enc(owner) + "/" + enc(repo)
                        + "/pulls/" + pr + "/reviews?per_page=" + perPage + "&page=" + page;

                Object parsed = Json.parse(get(url, false));
                if (!(parsed instanceof List)) throw new RuntimeException("Unexpected JSON response for PR reviews.");
                @SuppressWarnings("unchecked")
                List<Object> arr = (List<Object>) parsed;

                if (arr.isEmpty()) break;

                for (Object o : arr) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) o;
                    all.add(m);
                }

                if (arr.size() < perPage) break;
                page++;
            }

            return all;
        }

        String fetchFileAtCommit(String owner, String repo, String path, String commitSha)
                throws IOException, InterruptedException {

            // try Contents API (base64)
            String contentsUrl = "https://api.github.com/repos/" + enc(owner) + "/" + enc(repo)
                    + "/contents/" + pathEncode(path) + "?ref=" + enc(commitSha);

            try {
                Object parsed = Json.parse(get(contentsUrl, false));
                if (parsed instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> obj = (Map<String, Object>) parsed;
                    String encoding = asString(obj.get("encoding"));
                    String content = asString(obj.get("content"));
                    if ("base64".equalsIgnoreCase(encoding) && content != null) {
                        String cleaned = content.replace("\n", "").replace("\r", "");
                        return new String(Base64.getDecoder().decode(cleaned), StandardCharsets.UTF_8);
                    }
                }
            } catch (Exception ignore) {}

            // fallback raw
            String rawUrl = "https://raw.githubusercontent.com/" + enc(owner) + "/" + enc(repo)
                    + "/" + enc(commitSha) + "/" + pathEncode(path);
            return get(rawUrl, true);
        }

        private String get(String url, boolean raw) throws IOException, InterruptedException {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(40))
                    .GET();

            if (!raw) {
                b.header("Accept", "application/vnd.github+json");
            } else {
                b.header("Accept", "*/*");
            }

            if (token != null && !token.isBlank()) {
                b.header("Authorization", "Bearer " + token.trim());
            }

            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                throw new RuntimeException("GitHub HTTP " + res.statusCode() + " for " + url + " :: " + res.body());
            }
            return res.body();
        }

        private static String enc(String s) {
            return URLEncoder.encode(s, StandardCharsets.UTF_8);
        }

        private static String pathEncode(String path) {
            String[] segs = path.split("/");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < segs.length; i++) {
                if (i > 0) sb.append("/");
                sb.append(URLEncoder.encode(segs[i], StandardCharsets.UTF_8));
            }
            return sb.toString();
        }
    }

    // ----------------------------
    // OpenAI client (Responses API)
    // ----------------------------
    static class OpenAI {
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        private final String apiKey;

        OpenAI(String apiKey) {
            this.apiKey = apiKey;
        }

        /**
         * Calls POST https://api.openai.com/v1/responses with model + instructions + input. :contentReference[oaicite:3]{index=3}
         */
        String createResponse(String model, String instructions, String input) throws IOException, InterruptedException {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("instructions", instructions);
            body.put("input", input);

            String jsonBody = JsonWriter.toJson(body);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.openai.com/v1/responses"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey) // :contentReference[oaicite:4]{index=4}
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                throw new RuntimeException("OpenAI HTTP " + res.statusCode() + " :: " + res.body());
            }

            Object parsed = Json.parse(res.body());
            if (!(parsed instanceof Map)) return res.body();
            @SuppressWarnings("unchecked")
            Map<String, Object> obj = (Map<String, Object>) parsed;

            return extractOutputText(obj);
        }

        /**
         * Robust-ish extraction:
         * 1) if server includes "output_text" (some SDKs expose it), return it
         * 2) else traverse output[] message content[] looking for {type:"output_text", text:"..."}
         */
        @SuppressWarnings("unchecked")
        static String extractOutputText(Map<String, Object> root) {
            Object direct = root.get("output_text");
            if (direct instanceof String) return (String) direct;

            Object output = root.get("output");
            if (!(output instanceof List)) return JsonWriter.toJson(root);

            StringBuilder sb = new StringBuilder();
            for (Object item : (List<Object>) output) {
                if (!(item instanceof Map)) continue;
                Map<String, Object> it = (Map<String, Object>) item;

                String type = asString(it.get("type"));
                if (!"message".equals(type)) continue;

                Object content = it.get("content");
                if (!(content instanceof List)) continue;

                for (Object c : (List<Object>) content) {
                    if (!(c instanceof Map)) continue;
                    Map<String, Object> cm = (Map<String, Object>) c;
                    String cType = asString(cm.get("type"));
                    if ("output_text".equals(cType)) {
                        String text = asString(cm.get("text"));
                        if (text != null) {
                            if (sb.length() > 0) sb.append("\n");
                            sb.append(text);
                        }
                    }
                }
            }

            return sb.length() == 0 ? JsonWriter.toJson(root) : sb.toString();
        }
    }

    // ----------------------------
    // JSON writer (Map/List/String/etc -> JSON)
    // ----------------------------
    static class JsonWriter {
        static String toJson(Object o) {
            StringBuilder sb = new StringBuilder();
            writeValue(sb, o);
            return sb.toString();
        }

        @SuppressWarnings("unchecked")
        static void writeValue(StringBuilder sb, Object o) {
            if (o == null) { sb.append("null"); return; }
            if (o instanceof String) { sb.append('"').append(escape((String) o)).append('"'); return; }
            if (o instanceof Number || o instanceof Boolean) { sb.append(o.toString()); return; }
            if (o instanceof Map) {
                sb.append("{");
                boolean first = true;
                for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
                    if (!first) sb.append(",");
                    first = false;
                    sb.append('"').append(escape(e.getKey())).append('"').append(":");
                    writeValue(sb, e.getValue());
                }
                sb.append("}");
                return;
            }
            if (o instanceof List) {
                sb.append("[");
                boolean first = true;
                for (Object x : (List<Object>) o) {
                    if (!first) sb.append(",");
                    first = false;
                    writeValue(sb, x);
                }
                sb.append("]");
                return;
            }
            // fallback
            sb.append('"').append(escape(String.valueOf(o))).append('"');
        }

        static String escape(String s) {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '\\': out.append("\\\\"); break;
                    case '"': out.append("\\\""); break;
                    case '\n': out.append("\\n"); break;
                    case '\r': out.append("\\r"); break;
                    case '\t': out.append("\\t"); break;
                    default:
                        if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                        else out.append(c);
                }
            }
            return out.toString();
        }
    }

    // ----------------------------
    // Minimal JSON parser (no external libs)
    // ----------------------------
    static class Json {
        static Object parse(String s) { return new Parser(s).parseValue(); }

        static class Parser {
            private final String s;
            private int i = 0;
            Parser(String s) { this.s = s; }

            Object parseValue() {
                skipWs();
                if (i >= s.length()) throw err("Unexpected end");
                char c = s.charAt(i);
                if (c == '{') return parseObject();
                if (c == '[') return parseArray();
                if (c == '"') return parseString();
                if (c == 't' || c == 'f') return parseBoolean();
                if (c == 'n') return parseNull();
                if (c == '-' || isDigit(c)) return parseNumber();
                throw err("Unexpected char: " + c);
            }

            Map<String, Object> parseObject() {
                expect('{'); skipWs();
                Map<String, Object> obj = new LinkedHashMap<>();
                if (peek('}')) { i++; return obj; }
                while (true) {
                    skipWs();
                    String key = parseString();
                    skipWs(); expect(':');
                    Object val = parseValue();
                    obj.put(key, val);
                    skipWs();
                    if (peek('}')) { i++; break; }
                    expect(',');
                }
                return obj;
            }

            List<Object> parseArray() {
                expect('['); skipWs();
                List<Object> arr = new ArrayList<>();
                if (peek(']')) { i++; return arr; }
                while (true) {
                    arr.add(parseValue());
                    skipWs();
                    if (peek(']')) { i++; break; }
                    expect(',');
                }
                return arr;
            }

            String parseString() {
                expect('"');
                StringBuilder sb = new StringBuilder();
                while (i < s.length()) {
                    char c = s.charAt(i++);
                    if (c == '"') break;
                    if (c == '\\') {
                        if (i >= s.length()) throw err("Bad escape");
                        char e = s.charAt(i++);
                        switch (e) {
                            case '"': sb.append('"'); break;
                            case '\\': sb.append('\\'); break;
                            case '/': sb.append('/'); break;
                            case 'b': sb.append('\b'); break;
                            case 'f': sb.append('\f'); break;
                            case 'n': sb.append('\n'); break;
                            case 'r': sb.append('\r'); break;
                            case 't': sb.append('\t'); break;
                            case 'u':
                                if (i + 4 > s.length()) throw err("Bad unicode escape");
                                String hex = s.substring(i, i + 4);
                                i += 4;
                                sb.append((char) Integer.parseInt(hex, 16));
                                break;
                            default: throw err("Unknown escape: \\" + e);
                        }
                    } else sb.append(c);
                }
                return sb.toString();
            }

            Object parseNumber() {
                int start = i;
                if (s.charAt(i) == '-') i++;
                while (i < s.length() && isDigit(s.charAt(i))) i++;
                boolean isFloat = false;
                if (i < s.length() && s.charAt(i) == '.') {
                    isFloat = true; i++;
                    while (i < s.length() && isDigit(s.charAt(i))) i++;
                }
                if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                    isFloat = true; i++;
                    if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                    while (i < s.length() && isDigit(s.charAt(i))) i++;
                }
                String num = s.substring(start, i);
                if (isFloat) return Double.parseDouble(num);
                return Long.parseLong(num);
            }

            Boolean parseBoolean() {
                if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
                if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
                throw err("Bad boolean");
            }

            Object parseNull() {
                if (!s.startsWith("null", i)) throw err("Bad null");
                i += 4; return null;
            }

            void skipWs() {
                while (i < s.length()) {
                    char c = s.charAt(i);
                    if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++;
                    else break;
                }
            }

            boolean peek(char c) { return i < s.length() && s.charAt(i) == c; }
            void expect(char c) { if (i >= s.length() || s.charAt(i) != c) throw err("Expected '" + c + "'"); i++; }
            boolean isDigit(char c) { return c >= '0' && c <= '9'; }
            RuntimeException err(String msg) { return new RuntimeException(msg + " at pos " + i); }
        }
    }

    // ----------------------------
    // Helpers
    // ----------------------------
    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) { return (o instanceof Map) ? (Map<String, Object>) o : null; }

    static String asString(Object o) { return (o == null) ? null : String.valueOf(o); }

    static long asLong(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        return Long.parseLong(String.valueOf(o));
    }

    static Long asLongObj(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(String.valueOf(o)); } catch (Exception e) { return null; }
    }

    static Integer asIntObj(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return null; }
    }
}
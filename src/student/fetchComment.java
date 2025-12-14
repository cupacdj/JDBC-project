import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.Base64;

/**
 * Fetches GitHub Pull Request inline review comments (Files changed comments)
 * and prints the referenced code lines (highlighted) from the comment's commit_id.
 *
 * PUBLIC repo usage (no token):
 *   java FetchPrInlineComments owner repo 123
 *
 * Optional token (helps avoid rate limit):
 *   java FetchPrInlineComments owner repo 123 ghp_xxx
 *
 * Notes:
 * - This fetches "pull request review comments" (inline code comments), not PR conversation comments.
 * - Conversation comments are "issue comments" and do not contain file/line anchors.
 */
public class FetchPrInlineComments {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Usage: java FetchPrInlineComments <owner> <repo> <prNumber> [token]");
            System.exit(1);
        }

        String owner = args[0];
        String repo = args[1];
        int pr = Integer.parseInt(args[2]);
        String token = (args.length >= 4) ? args[3] : null;

        GitHub gh = new GitHub(token);

        List<Map<String, Object>> comments = gh.fetchAllInlineReviewComments(owner, repo, pr);

        System.out.println("Inline review comments found: " + comments.size());
        System.out.println("------------------------------------------------------------");

        for (Map<String, Object> c : comments) {
            long id = asLong(c.get("id"));
            String path = asString(c.get("path"));
            Integer line = asIntObj(c.get("line"));
            Integer startLine = asIntObj(c.get("start_line"));
            String body = asString(c.get("body"));
            String diffHunk = asString(c.get("diff_hunk"));
            String commitId = asString(c.get("commit_id"));
            String htmlUrl = asString(c.get("html_url"));

            Map<String, Object> user = asMap(c.get("user"));
            String login = user != null ? asString(user.get("login")) : "?";

            System.out.println("Comment ID: " + id);
            System.out.println("Author   : " + login);
            System.out.println("File     : " + path);
            System.out.println("Commit   : " + commitId);
            System.out.println("Line(s)  : " + (startLine != null ? startLine : line) + " -> " + line);
            System.out.println("URL      : " + htmlUrl);
            System.out.println("Body     : " + oneLine(body));
            System.out.println();

            // Print highlighted code from that commit, if line info is present.
            if (path != null && commitId != null && line != null) {
                int from = (startLine != null ? startLine : line);
                int to = line;

                try {
                    String fileText = gh.fetchFileAtCommit(owner, repo, path, commitId);
                    printHighlightedLines(fileText, from, to, 3); // context = 3 lines around
                } catch (Exception e) {
                    System.out.println("!! Failed to fetch file content at commit. Reason: " + e.getMessage());
                    System.out.println("   Fallback diff_hunk:");
                    if (diffHunk != null && !diffHunk.isBlank()) {
                        System.out.println(diffHunk);
                    } else {
                        System.out.println("(no diff_hunk)");
                    }
                }
            } else {
                System.out.println("(No file/line anchor in this comment; printing diff_hunk if present)");
                if (diffHunk != null && !diffHunk.isBlank()) {
                    System.out.println(diffHunk);
                }
            }

            System.out.println("------------------------------------------------------------");
        }
    }

    // ------------------------
    // GitHub client
    // ------------------------
    static class GitHub {
        private final HttpClient http;
        private final String token;

        GitHub(String token) {
            this.token = token;
            this.http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();
        }

        List<Map<String, Object>> fetchAllInlineReviewComments(String owner, String repo, int pr) throws IOException, InterruptedException {
            List<Map<String, Object>> all = new ArrayList<>();
            int page = 1;
            int perPage = 100;

            while (true) {
                String url = "https://api.github.com/repos/" + enc(owner) + "/" + enc(repo)
                        + "/pulls/" + pr + "/comments?per_page=" + perPage + "&page=" + page;

                String json = get(url);
                Object parsed = Json.parse(json);

                if (!(parsed instanceof List)) {
                    throw new RuntimeException("Unexpected JSON response for comments.");
                }
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

        /**
         * Fetch file content at a given commit.
         * First tries GitHub Contents API (base64 JSON). If that fails, falls back to raw.githubusercontent.com.
         */
        String fetchFileAtCommit(String owner, String repo, String path, String commitSha) throws IOException, InterruptedException {
            // Try Contents API (nice, but fails for very large files)
            String contentsUrl = "https://api.github.com/repos/" + enc(owner) + "/" + enc(repo)
                    + "/contents/" + pathEncode(path) + "?ref=" + enc(commitSha);

            try {
                String json = get(contentsUrl);
                Object parsed = Json.parse(json);
                if (parsed instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> obj = (Map<String, Object>) parsed;
                    String encoding = asString(obj.get("encoding"));
                    String content = asString(obj.get("content"));

                    if ("base64".equalsIgnoreCase(encoding) && content != null) {
                        // GitHub may include line breaks in base64
                        String cleaned = content.replace("\n", "").replace("\r", "");
                        byte[] decoded = Base64.getDecoder().decode(cleaned);
                        return new String(decoded, StandardCharsets.UTF_8);
                    }
                }
            } catch (Exception ignore) {
                // fall back below
            }

            // Fallback: raw URL
            String rawUrl = "https://raw.githubusercontent.com/" + enc(owner) + "/" + enc(repo)
                    + "/" + enc(commitSha) + "/" + pathEncode(path);

            return get(rawUrl, /*raw*/ true);
        }

        private String get(String url) throws IOException, InterruptedException {
            return get(url, false);
        }

        private String get(String url, boolean raw) throws IOException, InterruptedException {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET();

            // GitHub API headers
            if (!raw) {
                b.header("Accept", "application/vnd.github+json");
                b.header("X-GitHub-Api-Version", "2022-11-28");
            } else {
                b.header("Accept", "*/*");
            }

            if (token != null && !token.isBlank()) {
                b.header("Authorization", "Bearer " + token.trim());
            }

            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                throw new RuntimeException("HTTP " + res.statusCode() + " for " + url + " :: " + res.body());
            }
            return res.body();
        }

        private static String enc(String s) {
            return URLEncoder.encode(s, StandardCharsets.UTF_8);
        }

        private static String pathEncode(String path) {
            // Keep slashes, encode segments
            String[] segs = path.split("/");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < segs.length; i++) {
                if (i > 0) sb.append("/");
                sb.append(URLEncoder.encode(segs[i], StandardCharsets.UTF_8));
            }
            return sb.toString();
        }
    }

    // ------------------------
    // Highlight printer
    // ------------------------
    static void printHighlightedLines(String fileText, int fromLine1, int toLine1, int context) {
        String[] lines = fileText.split("\n", -1);
        int n = lines.length;

        int from = Math.max(1, Math.min(fromLine1, toLine1));
        int to = Math.max(fromLine1, toLine1);

        int start = Math.max(1, from - context);
        int end = Math.min(n, to + context);

        System.out.println("Highlighted code (with context):");
        for (int i = start; i <= end; i++) {
            boolean inRange = (i >= from && i <= to);
            String prefix = inRange ? ">> " : "   ";
            System.out.printf("%s%5d | %s%n", prefix, i, lines[i - 1]);
        }
        System.out.println();
    }

    static String oneLine(String s) {
        if (s == null) return "";
        String t = s.replace("\r", "").replace("\n", " ").trim();
        return t.length() > 180 ? t.substring(0, 180) + "..." : t;
    }

    // ------------------------
    // Small helpers
    // ------------------------
    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) {
        if (o instanceof Map) return (Map<String, Object>) o;
        return null;
    }

    static String asString(Object o) {
        return (o == null) ? null : String.valueOf(o);
    }

    static long asLong(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        if (o == null) return 0L;
        return Long.parseLong(o.toString());
    }

    static Integer asIntObj(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).intValue();
        try {
            return Integer.parseInt(o.toString());
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------
    // Minimal JSON parser (no external libs)
    // Parses JSON into Map<String,Object>, List<Object>, String, Double/Long, Boolean, null.
    // ------------------------
    static class Json {
        static Object parse(String s) {
            return new Parser(s).parseValue();
        }

        static class Parser {
            private final String s;
            private int i = 0;

            Parser(String s) {
                this.s = s;
            }

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
                expect('{');
                skipWs();
                Map<String, Object> obj = new LinkedHashMap<>();
                if (peek('}')) {
                    i++;
                    return obj;
                }
                while (true) {
                    skipWs();
                    String key = parseString();
                    skipWs();
                    expect(':');
                    Object val = parseValue();
                    obj.put(key, val);
                    skipWs();
                    if (peek('}')) {
                        i++;
                        break;
                    }
                    expect(',');
                }
                return obj;
            }

            List<Object> parseArray() {
                expect('[');
                skipWs();
                List<Object> arr = new ArrayList<>();
                if (peek(']')) {
                    i++;
                    return arr;
                }
                while (true) {
                    Object val = parseValue();
                    arr.add(val);
                    skipWs();
                    if (peek(']')) {
                        i++;
                        break;
                    }
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
                            default:
                                throw err("Unknown escape: \\" + e);
                        }
                    } else {
                        sb.append(c);
                    }
                }
                return sb.toString();
            }

            Object parseNumber() {
                int start = i;
                if (s.charAt(i) == '-') i++;
                while (i < s.length() && isDigit(s.charAt(i))) i++;
                boolean isFloat = false;
                if (i < s.length() && s.charAt(i) == '.') {
                    isFloat = true;
                    i++;
                    while (i < s.length() && isDigit(s.charAt(i))) i++;
                }
                if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                    isFloat = true;
                    i++;
                    if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                    while (i < s.length() && isDigit(s.charAt(i))) i++;
                }
                String num = s.substring(start, i);
                if (isFloat) return Double.parseDouble(num);
                // fit into long if possible
                long v = Long.parseLong(num);
                return v;
            }

            Boolean parseBoolean() {
                if (s.startsWith("true", i)) {
                    i += 4;
                    return Boolean.TRUE;
                }
                if (s.startsWith("false", i)) {
                    i += 5;
                    return Boolean.FALSE;
                }
                throw err("Bad boolean");
            }

            Object parseNull() {
                if (!s.startsWith("null", i)) throw err("Bad null");
                i += 4;
                return null;
            }

            void skipWs() {
                while (i < s.length()) {
                    char c = s.charAt(i);
                    if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++;
                    else break;
                }
            }

            boolean peek(char c) {
                return i < s.length() && s.charAt(i) == c;
            }

            void expect(char c) {
                if (i >= s.length() || s.charAt(i) != c) {
                    throw err("Expected '" + c + "'");
                }
                i++;
            }

            boolean isDigit(char c) {
                return c >= '0' && c <= '9';
            }

            RuntimeException err(String msg) {
                return new RuntimeException(msg + " at pos " + i);
            }
        }
    }
}
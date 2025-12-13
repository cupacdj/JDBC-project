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
 * Also fetches PR review "status" (APPROVED / CHANGES_REQUESTED / COMMENTED)
 * by calling: GET /repos/{owner}/{repo}/pulls/{pull_number}/reviews
 *
 * PUBLIC repo usage (no token):
 *   java FetchPrInlineComments owner repo 123
 *   java FetchPrInlineComments owner repo feature/my-change
 *
 * Optional token (helps avoid rate limit):
 *   java FetchPrInlineComments owner repo 123 ghp_xxx
 *   java FetchPrInlineComments owner repo feature/my-change ghp_xxx
 */
public class FetchPrInlineComments {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Usage:");
            System.out.println("  java FetchPrInlineComments <owner> <repo> <PR_NUMBER|BRANCH_NAME> [token]");
            System.out.println();
            System.out.println("Examples:");
            System.out.println("  java FetchPrInlineComments octocat Hello-World 123");
            System.out.println("  java FetchPrInlineComments octocat Hello-World feature/my-change");
            System.exit(1);
        }

        String owner = args[0];
        String repo = args[1];
        String prOrBranch = args[2];
        String token = (args.length >= 4) ? args[3] : null;

        GitHub gh = new GitHub(token);

        int pr = resolvePrNumber(owner, repo, prOrBranch, gh);
        System.out.println("Using PR #" + pr);
        System.out.println();

        // 1) Fetch + print PR review status summary (Approved / Request changes / Commented)
        List<Map<String, Object>> reviews = gh.fetchAllPullReviews(owner, repo, pr);
        printReviewSummary(reviews);

        // 2) Fetch inline review comments + highlight code
        List<Map<String, Object>> comments = gh.fetchAllInlineReviewComments(owner, repo, pr);

        System.out.println();
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

            if (path != null && commitId != null && line != null) {
                int from = (startLine != null ? startLine : line);
                int to = line;

                try {
                    String fileText = gh.fetchFileAtCommit(owner, repo, path, commitId);
                    printHighlightedLines(fileText, from, to, 3);
                } catch (Exception e) {
                    System.out.println("!! Failed to fetch file content at commit. Reason: " + e.getMessage());
                    System.out.println("   Fallback diff_hunk:");
                    if (diffHunk != null && !diffHunk.isBlank()) System.out.println(diffHunk);
                    else System.out.println("(no diff_hunk)");
                }
            } else {
                System.out.println("(No file/line anchor in this comment; printing diff_hunk if present)");
                if (diffHunk != null && !diffHunk.isBlank()) System.out.println(diffHunk);
            }

            System.out.println("------------------------------------------------------------");
        }
    }

    // ------------------------
    // Review status summary
    // ------------------------

    /**
     * GitHub returns many reviews, possibly multiple from the same reviewer.
     * We compute overall status from each reviewer's LATEST review:
     * - If any latest == CHANGES_REQUESTED => overall CHANGES_REQUESTED
     * - Else if any latest == APPROVED => overall APPROVED
     * - Else if any latest == COMMENTED => overall COMMENTED
     * - Else => NO_REVIEWS
     */
    static void printReviewSummary(List<Map<String, Object>> reviews) {
        System.out.println("PR Reviews (summary):");

        if (reviews == null || reviews.isEmpty()) {
            System.out.println("Overall review status: NO_REVIEWS");
            return;
        }

        // Latest review per user (by submitted_at; fallback by id)
        Map<String, Map<String, Object>> latestByUser = new LinkedHashMap<>();

        for (Map<String, Object> r : reviews) {
            Map<String, Object> user = asMap(r.get("user"));
            String login = user != null ? asString(user.get("login")) : null;
            if (login == null) continue;

            Map<String, Object> prev = latestByUser.get(login);
            if (prev == null) {
                latestByUser.put(login, r);
            } else {
                if (isAfter(r, prev)) {
                    latestByUser.put(login, r);
                }
            }
        }

        // Print per-reviewer status
        for (Map.Entry<String, Map<String, Object>> e : latestByUser.entrySet()) {
            String login = e.getKey();
            Map<String, Object> r = e.getValue();

            String state = asString(r.get("state")); // APPROVED / CHANGES_REQUESTED / COMMENTED / DISMISSED
            String submittedAt = asString(r.get("submitted_at"));
            long id = asLong(r.get("id"));

            System.out.printf("- %s: %s (submitted_at=%s, review_id=%d)%n",
                    login,
                    state != null ? state : "?",
                    submittedAt != null ? submittedAt : "null",
                    id
            );
        }

        String overall = computeOverallReviewState(latestByUser.values());
        System.out.println("Overall review status: " + overall);
    }

    static boolean isAfter(Map<String, Object> a, Map<String, Object> b) {
        // Prefer submitted_at ISO-8601 if present (lexicographic compare works for ISO timestamps)
        String aTime = asString(a.get("submitted_at"));
        String bTime = asString(b.get("submitted_at"));

        if (aTime != null && bTime != null) {
            return aTime.compareTo(bTime) > 0;
        }
        if (aTime != null && bTime == null) return true;
        if (aTime == null && bTime != null) return false;

        // fallback: compare ids
        return asLong(a.get("id")) > asLong(b.get("id"));
    }

    static String computeOverallReviewState(Collection<Map<String, Object>> latestReviews) {
        boolean anyApproved = false;
        boolean anyCommented = false;

        for (Map<String, Object> r : latestReviews) {
            String state = asString(r.get("state"));
            if (state == null) continue;

            // ignore DISMISSED (treat like "no longer counts")
            if ("DISMISSED".equalsIgnoreCase(state)) continue;

            if ("CHANGES_REQUESTED".equalsIgnoreCase(state)) return "CHANGES_REQUESTED";
            if ("APPROVED".equalsIgnoreCase(state)) anyApproved = true;
            if ("COMMENTED".equalsIgnoreCase(state)) anyCommented = true;
        }

        if (anyApproved) return "APPROVED";
        if (anyCommented) return "COMMENTED";
        return "NO_REVIEWS";
    }

    // ------------------------
    // PR number auto-resolve
    // ------------------------

    static int resolvePrNumber(String owner, String repo, String prOrBranch, GitHub gh)
            throws IOException, InterruptedException {

        if (isAllDigits(prOrBranch)) return Integer.parseInt(prOrBranch);

        Integer found = findOpenPrNumberByHeadBranch(owner, repo, prOrBranch, gh);
        if (found == null) {
            throw new RuntimeException("Could not find an OPEN PR for head branch: " + prOrBranch
                    + "\nTry passing PR number directly, or check if PR is closed / branch name differs.");
        }
        return found;
    }

    static boolean isAllDigits(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
        return true;
    }

    static Integer findOpenPrNumberByHeadBranch(String owner, String repo, String headBranch, GitHub gh)
            throws IOException, InterruptedException {

        String q = "repo:" + owner + "/" + repo + " is:pr is:open head:" + headBranch;
        String url = "https://api.github.com/search/issues?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8);

        String json = gh.getPublic(url, false);

        Object parsed = Json.parse(json);
        if (!(parsed instanceof Map)) return null;

        @SuppressWarnings("unchecked")
        Map<String, Object> obj = (Map<String, Object>) parsed;

        Object itemsObj = obj.get("items");
        if (!(itemsObj instanceof List)) return null;

        @SuppressWarnings("unchecked")
        List<Object> items = (List<Object>) itemsObj;

        if (items.isEmpty()) return null;

        Object first = items.get(0);
        if (!(first instanceof Map)) return null;

        @SuppressWarnings("unchecked")
        Map<String, Object> firstMap = (Map<String, Object>) first;

        Object numberObj = firstMap.get("number");
        if (numberObj instanceof Number) return ((Number) numberObj).intValue();

        try {
            return Integer.parseInt(String.valueOf(numberObj));
        } catch (Exception e) {
            return null;
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

        List<Map<String, Object>> fetchAllInlineReviewComments(String owner, String repo, int pr)
                throws IOException, InterruptedException {

            List<Map<String, Object>> all = new ArrayList<>();
            int page = 1;
            int perPage = 100;

            while (true) {
                String url = "https://api.github.com/repos/" + enc(owner) + "/" + enc(repo)
                        + "/pulls/" + pr + "/comments?per_page=" + perPage + "&page=" + page;

                String json = getPublic(url, false);
                Object parsed = Json.parse(json);

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

                String json = getPublic(url, false);
                Object parsed = Json.parse(json);

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

            String contentsUrl = "https://api.github.com/repos/" + enc(owner) + "/" + enc(repo)
                    + "/contents/" + pathEncode(path) + "?ref=" + enc(commitSha);

            try {
                String json = getPublic(contentsUrl, false);
                Object parsed = Json.parse(json);
                if (parsed instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> obj = (Map<String, Object>) parsed;
                    String encoding = asString(obj.get("encoding"));
                    String content = asString(obj.get("content"));

                    if ("base64".equalsIgnoreCase(encoding) && content != null) {
                        String cleaned = content.replace("\n", "").replace("\r", "");
                        byte[] decoded = Base64.getDecoder().decode(cleaned);
                        return new String(decoded, StandardCharsets.UTF_8);
                    }
                }
            } catch (Exception ignore) {
                // fall back below
            }

            String rawUrl = "https://raw.githubusercontent.com/" + enc(owner) + "/" + enc(repo)
                    + "/" + enc(commitSha) + "/" + pathEncode(path);

            return getPublic(rawUrl, true);
        }

        public String getPublic(String url, boolean raw) throws IOException, InterruptedException {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET();

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
    // ------------------------

    static class Json {
        static Object parse(String s) {
            return new Parser(s).parseValue();
        }

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
                expect('{');
                skipWs();
                Map<String, Object> obj = new LinkedHashMap<>();
                if (peek('}')) { i++; return obj; }
                while (true) {
                    skipWs();
                    String key = parseString();
                    skipWs();
                    expect(':');
                    Object val = parseValue();
                    obj.put(key, val);
                    skipWs();
                    if (peek('}')) { i++; break; }
                    expect(',');
                }
                return obj;
            }

            List<Object> parseArray() {
                expect('[');
                skipWs();
                List<Object> arr = new ArrayList<>();
                if (peek(']')) { i++; return arr; }
                while (true) {
                    Object val = parseValue();
                    arr.add(val);
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

            void expect(char c) {
                if (i >= s.length() || s.charAt(i) != c) throw err("Expected '" + c + "'");
                i++;
            }

            boolean isDigit(char c) { return c >= '0' && c <= '9'; }

            RuntimeException err(String msg) { return new RuntimeException(msg + " at pos " + i); }
        }
    }
}

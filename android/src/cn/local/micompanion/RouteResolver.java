package cn.local.micompanion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline AstrBot routing. Textual JSON member order is significant; JSONObject.keys() is not.
 * Limits (UTF-16 characters): envelope 1 Mi, 1024 routes, pattern/UMO 512, config id 256;
 * JSON nesting 32, total values 20000, and matching work 8 million pattern/target cells.
 * Supports case-sensitive *, ?, [abc], [!abc], and ascending code-point ranges. Unclosed/empty
 * classes, reversed ranges, ambiguous interior hyphens, and control characters are rejected.
 * Invalid input never silently becomes default. Config-id existence is the caller's concern.
 */
public final class RouteResolver {
    private RouteResolver() { }

    /** Strict syntax boundary for other official JSON objects, without routing-specific shape checks. */
    public static void validateJsonObject(String raw) throws Exception {
        jsonObject(raw);
    }

    private static Map<?, ?> jsonObject(String raw) {
        if (raw == null || raw.length() > 1024 * 1024) throw invalid();
        Object root = new Json(raw).read();
        if (!(root instanceof Map)) throw invalid();
        return (Map<?, ?>)root;
    }

    public static String resolve(String rawEnvelope, String umo) throws Exception {
        String[] target = target(umo);
        return first(routes(rawEnvelope), target);
    }

    /** Simulate POST /chat's exact-key put/delete before trusting its eventual first-match config. */
    public static void verifyChatRoute(String rawEnvelope, String webchatUmo, String desiredConfigId) throws Exception {
        String[] target = target(webchatUmo);
        if (!"webchat".equals(target[0]) || webchatUmo.indexOf('*') >= 0
                || webchatUmo.indexOf('?') >= 0 || webchatUmo.indexOf('[') >= 0) throw invalid();
        configId(desiredConfigId);
        LinkedHashMap<String, String> routing = routes(rawEnvelope);
        if ("default".equals(desiredConfigId)) routing.remove(webchatUmo);
        else routing.put(webchatUmo, desiredConfigId); // LinkedHashMap replacement preserves insertion order.
        if (!desiredConfigId.equals(first(routing, target)))
            throw new IllegalArgumentException("先行 WebChat 通配路由覆盖目标，无法确保聊天配置与目标一致");
    }

    private static String first(LinkedHashMap<String, String> routes, String[] target) throws IllegalArgumentException {
        long work = 0;
        for (Map.Entry<String, String> entry : routes.entrySet()) {
            String[] parts = entry.getKey().split(":", 3);
            boolean matches = true;
            for (int i = 0; i < 3 && matches; i++) {
                if (parts[i].isEmpty() || "*".equals(parts[i])) continue;
                work += (long)parts[i].length() * target[i].length();
                if (work > 8000000) throw invalid();
                matches = glob(parts[i], target[i]);
            }
            if (matches) return entry.getValue();
        }
        return "default";
    }

    // Dynamic programming, not regex backtracking. '?' and classes consume Unicode code points.
    private static boolean glob(String pattern, String value) throws IllegalArgumentException {
        int[] chars = value.codePoints().toArray();
        boolean[] previous = new boolean[chars.length + 1];
        previous[0] = true;
        for (Token token : tokens(pattern)) {
            boolean[] next = new boolean[chars.length + 1];
            next[0] = token.kind == '*' && previous[0];
            for (int j = 1; j <= chars.length; j++) {
                next[j] = token.kind == '*' ? previous[j] || next[j - 1]
                        : previous[j - 1] && token.accepts(chars[j - 1]);
            }
            previous = next;
        }
        return previous[chars.length];
    }

    private static List<Token> tokens(String pattern) throws IllegalArgumentException {
        int[] chars = pattern.codePoints().toArray();
        List<Token> result = new ArrayList<>();
        for (int i = 0; i < chars.length; i++) {
            int ch = chars[i];
            Token token = new Token(ch == '*' || ch == '?' ? ch : 'l', ch);
            if (ch == '[') {
                token = new Token('[', 0);
                int start = ++i;
                if (i < chars.length && chars[i] == '!') { token.negated = true; start = ++i; }
                if (i < chars.length && chars[i] == ']') i++;
                while (i < chars.length && chars[i] != ']') i++;
                if (i == chars.length || start == i) throw invalid();
                for (int j = start; j < i; j++) {
                    int from = chars[j], to = from;
                    if (j + 2 < i && chars[j + 1] == '-') {
                        to = chars[j + 2]; j += 2;
                        if (from > to) throw invalid();
                    } else if (from == '-' && j != start && j != i - 1) {
                        // ponytail: ambiguous multi-hyphen classes are rejected, not approximated.
                        throw invalid();
                    }
                    token.ranges.add(new int[]{from, to});
                }
            }
            result.add(token);
        }
        return result;
    }

    private static final class Token {
        final int kind, literal;
        final List<int[]> ranges = new ArrayList<>();
        boolean negated;
        Token(int kind, int literal) { this.kind = kind; this.literal = literal; }
        boolean accepts(int ch) {
            if (kind == '?') return true;
            if (kind != '[') return ch == literal;
            boolean found = false;
            for (int[] range : ranges) if (ch >= range[0] && ch <= range[1]) found = true;
            return found != negated;
        }
    }

    private static String[] target(String umo) throws IllegalArgumentException {
        if (umo == null || umo.length() > 512 || umo.codePoints().anyMatch(c ->
                Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isISOControl(c)
                        || c >= 0xD800 && c <= 0xDFFF)) throw invalid();
        String[] parts = umo.split(":", 3);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) throw invalid();
        return parts;
    }

    private static LinkedHashMap<String, String> routes(String raw) throws IllegalArgumentException {
        Map<?, ?> envelope = jsonObject(raw);
        if (!"ok".equals(envelope.get("status")) || !(envelope.get("data") instanceof Map)) throw invalid();
        Object routing = ((Map<?, ?>)envelope.get("data")).get("routing");
        if (!(routing instanceof Map) || ((Map<?, ?>)routing).size() > 1024) throw invalid();
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>)routing).entrySet()) {
            String pattern = (String)entry.getKey();
            if (pattern.length() > 512 || pattern.split(":", 3).length != 3
                    || pattern.codePoints().anyMatch(Character::isISOControl)) throw invalid();
            // Validate every segment before considering any match, including unreachable later routes.
            for (String part : pattern.split(":", 3)) tokens(part);
            Object id = entry.getValue();
            if (!(id instanceof String)) throw invalid();
            configId((String)id);
            result.put(pattern, (String)id);
        }
        return result;
    }

    private static void configId(String text) {
        if (text == null || text.length() > 256
                || text.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))
                || text.codePoints().anyMatch(c -> Character.isISOControl(c) || c >= 0xD800 && c <= 0xDFFF)) throw invalid();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("AstrBot 路由响应或目标无效，无法安全确定配置"); }

    /** Strict, bounded JSON reader. All objects retain wire order and reject decoded duplicate keys. */
    private static final class Json {
        final String text;
        int at, values;
        Json(String text) { this.text = text; }
        Object read() throws IllegalArgumentException {
            Object value = value(0);
            space();
            if (at != text.length()) throw invalid();
            return value;
        }
        void space() {
            while (at < text.length() && " \t\r\n".indexOf(text.charAt(at)) >= 0) at++;
        }
        boolean take(char ch) {
            space();
            if (at < text.length() && text.charAt(at) == ch) { at++; return true; }
            return false;
        }
        Object value(int depth) throws IllegalArgumentException {
            if (depth > 32 || ++values > 20000) throw invalid();
            space();
            if (at == text.length()) throw invalid();
            char ch = text.charAt(at);
            if (ch == '"') return string();
            if (take('{')) {
                LinkedHashMap<String, Object> object = new LinkedHashMap<>();
                if (take('}')) return object;
                do {
                    space();
                    String key = string();
                    if (object.containsKey(key) || !take(':')) throw invalid();
                    object.put(key, value(depth + 1));
                    if (take('}')) return object;
                } while (take(','));
                throw invalid();
            }
            if (take('[')) {
                List<Object> array = new ArrayList<>();
                if (take(']')) return array;
                do {
                    array.add(value(depth + 1));
                    if (take(']')) return array;
                } while (take(','));
                throw invalid();
            }
            int start = at;
            while (at < text.length() && ",]} \t\r\n".indexOf(text.charAt(at)) < 0) at++;
            String token = text.substring(start, at);
            if ("null".equals(token)) return null;
            if ("true".equals(token)) return Boolean.TRUE;
            if ("false".equals(token)) return Boolean.FALSE;
            if (token.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) {
                // Numbers only occur in ignored metadata; preserve their non-string, non-object type.
                return Integer.valueOf(0);
            }
            throw invalid();
        }
        String string() throws IllegalArgumentException {
            if (at == text.length() || text.charAt(at++) != '"') throw invalid();
            StringBuilder out = new StringBuilder();
            while (at < text.length()) {
                char ch = text.charAt(at++);
                if (ch == '"') {
                    String result = out.toString();
                    for (int i = 0; i < result.length(); i++) {
                        char c = result.charAt(i);
                        if (Character.isHighSurrogate(c)) {
                            if (++i == result.length() || !Character.isLowSurrogate(result.charAt(i))) throw invalid();
                        } else if (Character.isLowSurrogate(c)) throw invalid();
                    }
                    return result;
                }
                if (ch < 0x20) throw invalid();
                if (ch == '\\') {
                    if (at == text.length()) throw invalid();
                    ch = text.charAt(at++);
                    int escape = "\"\\/bfnrt".indexOf(ch);
                    if (escape >= 0) ch = "\"\\/\b\f\n\r\t".charAt(escape);
                    else if (ch == 'u') {
                        if (at + 4 > text.length()) throw invalid();
                        int code = 0;
                        for (int i = 0; i < 4; i++) {
                            char hex = text.charAt(at++);
                            int digit = "0123456789abcdef".indexOf(Character.toLowerCase(hex));
                            if (digit < 0) throw invalid();
                            code = code * 16 + digit;
                        }
                        ch = (char)code;
                    } else throw invalid();
                }
                out.append(ch);
            }
            throw invalid();
        }
    }
}

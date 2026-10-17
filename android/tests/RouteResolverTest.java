package cn.local.micompanion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONObject;

/** Offline, hand-written wire fixtures, exercised only through the public resolver seam. */
public final class RouteResolverTest {
    private interface Check { void run() throws Exception; }
    private static int passed;
    private static final List<String> failures = new ArrayList<>();
    private static final String UMO = "bot:FriendMessage:123456";
    private static void test(String name, Check check) {
        try { check.run(); passed++; }
        catch (Throwable failure) { failures.add(name + ": " + failure); }
    }
    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    private static void rejected(Check check) throws Exception {
        try { check.run(); }
        catch (Exception expected) {
            if (!(expected instanceof IllegalArgumentException) || expected.getMessage() == null
                    || expected.getMessage().contains("secret-marker") || expected.getCause() != null)
                throw new AssertionError("Rejection must be a safe IllegalArgumentException without untrusted input/cause");
            return;
        }
        throw new AssertionError("Unsafe input was accepted");
    }
    private static String envelope(String members) {
        return "{\"status\":\"ok\",\"data\":{\"routing\":{" + members + "}}}";
    }
    private static String single(String pattern) {
        // quote strings only: never serialize an object whose keys a JVM JSONObject may reorder.
        return envelope(JSONObject.quote(pattern) + ":\"chosen\"");
    }
    private static String repeated(char ch, int count) {
        char[] chars = new char[count]; Arrays.fill(chars, ch); return new String(chars);
    }
    public static void main(String[] args) throws Exception {
        // Catches absent routing and the tempting unconditional default fallback.
        test("target bot profile", () -> equal("fdcc7c02-0fbb-42d3-8585-0feb75c0fa48", RouteResolver.resolve(
                envelope("\"default_1905318781:*:*\":\"fdcc7c02-0fbb-42d3-8585-0feb75c0fa48\""),
                "default_1905318781:FriendMessage:123456")));
        // Java HashMap buckets: bot:*:* = 13, *:*:* = 0, bot:FriendMessage:123456 = 12.
        // Lexical sorting or HashMap iteration puts a different matching route first.
        test("wire order is neither lexical order nor HashMap bucket order", () -> {
            equal("first", RouteResolver.resolve(envelope("\"bot:*:*\":\"first\",\"*:*:*\":\"second\",\"bot:FriendMessage:123456\":\"third\""), UMO));
            equal("broad", RouteResolver.resolve(envelope("\"*:*:*\":\"broad\",\"bot:FriendMessage:123456\":\"narrow\""), UMO));
            equal("narrow", RouteResolver.resolve(envelope("\"bot:FriendMessage:123456\":\"narrow\",\"*:*:*\":\"broad\""), UMO));
        });
        test("no match and empty routing use default", () -> {
            equal("default", RouteResolver.resolve(envelope(""), UMO));
            equal("default", RouteResolver.resolve(single("other:*:*"), UMO));
            equal("default", RouteResolver.resolve(single("bot:GroupMessage:*"), UMO));
            equal("default", RouteResolver.resolve(single("bot:FriendMessage:other"), UMO));
        });
        test("empty route parts match all and third segment preserves remaining colons", () -> {
            equal("chosen", RouteResolver.resolve(single("::"), UMO));
            equal("chosen", RouteResolver.resolve(single(":FriendMessage:"), UMO));
            equal("chosen", RouteResolver.resolve(single("bot:FriendMessage:room:thread:123"), "bot:FriendMessage:room:thread:123"));
            equal("default", RouteResolver.resolve(single("bot:FriendMessage:room:thread:123"), "bot:FriendMessage:room:thread:124"));
        });
        test("hand-checked Python fnmatchcase fixtures", () -> {
            // Literal expected results independently checked with Python fnmatch.fnmatchcase(value, pattern).
            String[][] fixtures = {
                {"a", "[!b]", "chosen"}, {"b", "[!b]", "default"},
                {"c", "[a-c]", "chosen"}, {"Z", "[a-z]", "default"},
                {"]", "[]]", "chosen"}, {"[", "[[]", "chosen"},
                {"-", "[-a]", "chosen"}, {"-", "[a-]", "chosen"},
                {"^", "[^a]", "chosen"}, {"b", "[^a]", "default"},
                {"ab", "a?", "chosen"}, {"a", "a?", "default"},
                {"abc", "a*c", "chosen"}, {"ac", "a*c", "chosen"},
                {"abxyc", "a*b?c", "default"}, {"abxc", "a*b?c", "chosen"}, {"ABC", "a*", "default"},
                {"a:b", "*:*", "chosen"}, {"😀", "?", "chosen"},
                {"😀", "[😀]", "chosen"}, {"a.b", "a.b", "chosen"},
                {"axb", "a.b", "default"}, {"a/b", "a*b", "chosen"},
                {"a\\b", "a\\b", "chosen"}, {"a+b", "a+b", "chosen"},
                {"a", "[!a-c]", "default"}, {"d", "[!a-c]", "chosen"}
            };
            for (String[] f : fixtures) {
                String actual = RouteResolver.resolve(single("bot:FriendMessage:" + f[1]), "bot:FriendMessage:" + f[0]);
                if (!f[2].equals(actual)) throw new AssertionError("Fixture " + Arrays.toString(f) + ": got " + actual);
            }
            equal("chosen", RouteResolver.resolve(single("b?t:[FG]riendMessage:12*"), UMO));
        });
        test("invalid envelopes never silently become default", () -> {
            for (String raw : new String[]{null, "", "{}", "[]", "null", "secret-marker",
                    "{\"status\":\"error\",\"data\":{\"routing\":{}}}",
                    "{\"status\":true,\"data\":{\"routing\":{}}}",
                    "{\"status\":\"ok\",\"data\":{}}",
                    "{\"status\":\"ok\",\"data\":[]}",
                    "{\"status\":\"ok\",\"data\":{\"routing\":[]}}",
                    "{\"status\":\"ok\",\"data\":{\"routing\":null}}",
                    "{\"status\":\"ok\",\"data\":{\"routing\":\"{}\"}}",
                    envelope("") + "secret-marker", envelope("") + "{}",
                    "{'status':'ok','data':{'routing':{}}}",
                    "{status:\"ok\",\"data\":{\"routing\":{}}}",
                    envelope("\"bot:*:*\":\"x\","),
                    "{\"status\":\"ok\",\"data\":{\"routing\":{},\"extra\":01}}",
                    "{\"status\":\"ok\",\"data\":{\"routing\":{},\"extra\":NaN}}"}) {
                rejected(() -> RouteResolver.resolve(raw, UMO));
            }
        });
        test("decoded duplicate keys are rejected in every JSON object", () -> {
            for (String raw : new String[]{
                    envelope("\"bot:*:*\":\"x\",\"bot:*:*\":\"y\""),
                    envelope("\"bot:*:*\":\"x\",\"b\\u006ft:*:*\":\"y\""),
                    "{\"status\":\"error\",\"status\":\"ok\",\"data\":{\"routing\":{}}}",
                    "{\"status\":\"ok\",\"data\":{\"routing\":{},\"routing\":{}}}",
                    "{\"status\":\"ok\",\"data\":{\"routing\":{}},\"extra\":[{\"a\":null,\"a\":1}]}"}) {
                rejected(() -> RouteResolver.resolve(raw, UMO));
            }
        });
        test("all routes validated even after an earlier matching route", () -> {
            for (String member : new String[]{"\"bad\":\"x\"", "\"bot:*\":\"x\"", "\"bot:*:*\":null",
                    "\"bot:*:*\":true", "\"bot:*:*\":123", "\"bot:*:*\":[]", "\"bot:*:*\":{}",
                    "\"bot:*:*\":\"\"", "\"bot:*:*\":\" \\t\\n　\"",
                    "\"bot:*:*\":\"" + repeated('x', 257) + "\"",
                    JSONObject.quote("bot:*:" + repeated('x', 513)) + ":\"x\""}) {
                rejected(() -> RouteResolver.resolve(envelope("\"*:*:*\":\"first\"," + member), UMO));
            }
        });
        test("unsupported classes reject before matching instead of silently selecting another route", () -> {
            for (String pattern : new String[]{"bot:*:[", "bot:*:[]", "bot:*:[!]", "bot:*:[z-a]", "bot:*:[a-b-c]", "bot:*:\n"}) {
                rejected(() -> RouteResolver.resolve(envelope("\"*:*:*\":\"first\"," + JSONObject.quote(pattern) + ":\"secret-marker\""), UMO));
                rejected(() -> RouteResolver.resolve(single(pattern), "other:FriendMessage:123"));
            }
        });
        test("invalid full UMO is rejected even with empty routing", () -> {
            for (String umo : new String[]{null, "", "bot", "bot:FriendMessage", "bot::1", ":FriendMessage:1", "bot:FriendMessage:",
                    "bot:FriendMessage:12 3", " bot:FriendMessage:1", "bot:FriendMessage:\n", "bot:FriendMessage:\u0000",
                    "bot:FriendMessage:\ud800", "bot:FriendMessage:" + repeated('x', 513)}) {
                rejected(() -> RouteResolver.resolve(envelope(""), umo));
            }
        });
        test("bounded resources reject oversized routing and nested metadata", () -> {
            StringBuilder many = new StringBuilder();
            for (int i = 0; i < 1025; i++) {
                if (i != 0) many.append(',');
                many.append(JSONObject.quote("bot:*:" + i)).append(":\"x\"");
            }
            rejected(() -> RouteResolver.resolve(envelope(many.toString()), UMO));
            rejected(() -> RouteResolver.resolve(envelope("") + repeated(' ', 1024 * 1024), UMO));
            String nested = "{\"status\":\"ok\",\"data\":{\"routing\":{}},\"extra\":" + repeated('[', 40) + "0" + repeated(']', 40) + "}";
            rejected(() -> RouteResolver.resolve(nested, UMO));
        });
        test("ordered JSON reader accepts standard escapes metadata and Unicode", () -> {
            equal("chosen", RouteResolver.resolve(" {\"extra\":[true,false,null,-12.5e+2,{\"quote\":\"\\\"\\\\\\/\\b\\f\\n\\r\\t\"}],\"data\":{\"routing\":{\"b\\u006ft:*:*\":\"chosen\"}},\"status\":\"ok\"} \n", UMO));
            equal("chosen", RouteResolver.resolve(single("bot:*:你好😀"), "bot:FriendMessage:你好😀"));
            rejected(() -> RouteResolver.resolve(envelope("\"bot:*:*\":\"\\uD800\""), UMO));
        });
        test("chat append cannot override an earlier broad wildcard with another profile", () -> {
            rejected(() -> RouteResolver.verifyChatRoute(envelope("\"webchat:*:*\":\"other\""), "webchat:FriendMessage:mi-companion-event", "chosen"));
            rejected(() -> RouteResolver.verifyChatRoute(envelope("\"*:*:*\":\"other\""), "webchat:FriendMessage:mi-companion-event", "chosen"));
            RouteResolver.verifyChatRoute(envelope("\"webchat:*:*\":\"chosen\""), "webchat:FriendMessage:mi-companion-event", "chosen");
        });
        test("chat exact replacement retains its position, append works when no earlier route shadows it", () -> {
            String chat = "webchat:FriendMessage:mi-companion-event";
            RouteResolver.verifyChatRoute(envelope(JSONObject.quote(chat) + ":\"old\",\"webchat:*:*\":\"other\""), chat, "chosen");
            RouteResolver.verifyChatRoute(envelope(""), chat, "chosen");
            RouteResolver.verifyChatRoute(envelope("\"bot:*:*\":\"other\""), chat, "chosen");
            rejected(() -> RouteResolver.verifyChatRoute(envelope("\"webchat:*:*\":\"other\"," + JSONObject.quote(chat) + ":\"old\""), chat, "chosen"));
        });
        test("default chat removes only the exact key, never forces past a broad route", () -> {
            String chat = "webchat:FriendMessage:mi-companion-event";
            RouteResolver.verifyChatRoute(envelope(JSONObject.quote(chat) + ":\"old\""), chat, "default");
            RouteResolver.verifyChatRoute(envelope(""), chat, "default");
            RouteResolver.verifyChatRoute(envelope("\"webchat:*:*\":\"default\"," + JSONObject.quote(chat) + ":\"old\""), chat, "default");
            rejected(() -> RouteResolver.verifyChatRoute(envelope(JSONObject.quote(chat) + ":\"old\",\"webchat:*:*\":\"other\""), chat, "default"));
            rejected(() -> RouteResolver.verifyChatRoute(envelope("\"*:*:*\":\"other\""), chat, "default"));
        });
        test("chat verification validates its entire input before simulation", () -> {
            for (String desired : new String[]{null, "", " \t　", repeated('x', 257), "secret-marker\n"}) {
                rejected(() -> RouteResolver.verifyChatRoute(envelope(""), "webchat:FriendMessage:event", desired));
            }
            rejected(() -> RouteResolver.verifyChatRoute("{}", "webchat:FriendMessage:event", "chosen"));
            rejected(() -> RouteResolver.verifyChatRoute(envelope("\"bad\":\"secret-marker\""), "webchat:FriendMessage:event", "chosen"));
            rejected(() -> RouteResolver.verifyChatRoute(envelope(""), "webchat:FriendMessage:", "chosen"));
            rejected(() -> RouteResolver.verifyChatRoute(envelope(""), "bot:FriendMessage:event", "chosen"));
        });
        test("shared JSON boundary rejects permissive JSONObject syntax and decoded duplicates", () -> {
            for (String raw : new String[]{null, "", "[]", "null", "42", "true", "\"object\"",
                    "{\"a\":1,\"a\":2}", "{\"a\":1,\"\\u0061\":2}",
                    "{'a':1}", "{a:1}", "{\"a\":1,}", "{\"a\":[1,]}", "{\"a\":/*x*/1}", "{\"a\":1}//x",
                    "{\"nested\":[{\"a\":null,\"a\":1}]}", "{}{}", "{}secret-marker",
                    "{}" + repeated(' ', 1024 * 1024),
                    "{\"a\":" + repeated('[', 40) + "0" + repeated(']', 40) + "}"}) {
                rejected(() -> RouteResolver.validateJsonObject(raw));
            }
        });
        test("shared JSON boundary allows general official response objects without routing fields", () -> {
            RouteResolver.validateJsonObject("{}");
            RouteResolver.validateJsonObject("{\"type\":\"plain\",\"data\":\"你好😀\",\"streaming\":true}");
            RouteResolver.validateJsonObject(" {\"items\":[null,false,1.2e-3,{\"key\":\"value\"}]} \n");
        });
        for (String failure : failures) System.err.println("FAIL " + failure);
        if (!failures.isEmpty()) throw new AssertionError("RouteResolverTest: " + failures.size() + " failed, " + passed + " passed");
        System.out.println("RouteResolverTest: PASS (" + passed + " behavior groups; offline)");
    }
}

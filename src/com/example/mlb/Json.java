package com.example.mlb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON parser.
 *
 * <p>Deliberately hand-rolled so the add-in bundles no third-party jars (the
 * balldontlie endpoints return small, well-formed JSON documents). Parses
 * into plain JDK types:
 * <ul>
 *   <li>object  -&gt; {@code Map<String,Object>} (insertion-ordered)</li>
 *   <li>array   -&gt; {@code List<Object>}</li>
 *   <li>string  -&gt; {@code String}</li>
 *   <li>number  -&gt; {@code Double}</li>
 *   <li>boolean -&gt; {@code Boolean}</li>
 *   <li>null    -&gt; {@code null}</li>
 * </ul>
 * This is a parser only; it does not serialize.
 */
final class Json {

    private final String s;
    private int i;

    private Json(String text) {
        this.s = text;
    }

    /** Parse a complete JSON document, throwing on trailing garbage. */
    static Object parse(String text) {
        Json p = new Json(text);
        p.skipWs();
        Object v = p.readValue();
        p.skipWs();
        if (p.i < p.s.length()) {
            throw new IllegalArgumentException("Trailing characters at offset " + p.i);
        }
        return v;
    }

    private Object readValue() {
        if (i >= s.length()) {
            throw new IllegalArgumentException("Unexpected end of input");
        }
        char c = s.charAt(i);
        switch (c) {
            case '{': return readObject();
            case '[': return readArray();
            case '"': return readString();
            case 't': case 'f': return readBoolean();
            case 'n': return readNull();
            default:  return readNumber();
        }
    }

    private Map<String, Object> readObject() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        expect('{');
        skipWs();
        if (peek() == '}') { i++; return m; }
        while (true) {
            skipWs();
            String key = readString();
            skipWs();
            expect(':');
            skipWs();
            m.put(key, readValue());
            skipWs();
            char c = next();
            if (c == '}') break;
            if (c != ',') throw err("',' or '}'", c);
        }
        return m;
    }

    private List<Object> readArray() {
        List<Object> a = new ArrayList<Object>();
        expect('[');
        skipWs();
        if (peek() == ']') { i++; return a; }
        while (true) {
            skipWs();
            a.add(readValue());
            skipWs();
            char c = next();
            if (c == ']') break;
            if (c != ',') throw err("',' or ']'", c);
        }
        return a;
    }

    private String readString() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw new IllegalArgumentException("Unterminated string");
            char c = s.charAt(i++);
            if (c == '"') break;
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case '"':  b.append('"');  break;
                    case '\\': b.append('\\'); break;
                    case '/':  b.append('/');  break;
                    case 'b':  b.append('\b'); break;
                    case 'f':  b.append('\f'); break;
                    case 'n':  b.append('\n'); break;
                    case 'r':  b.append('\r'); break;
                    case 't':  b.append('\t'); break;
                    case 'u':
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: throw new IllegalArgumentException("Bad escape \\" + e);
                }
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    private Double readNumber() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        if (i == start) throw err("value", peek());
        return Double.valueOf(s.substring(start, i));
    }

    private Boolean readBoolean() {
        if (s.startsWith("true", i))  { i += 4; return Boolean.TRUE;  }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        throw err("boolean", peek());
    }

    private Object readNull() {
        if (s.startsWith("null", i)) { i += 4; return null; }
        throw err("null", peek());
    }

    // ---- helpers ----

    private void skipWs() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private char peek() {
        return i < s.length() ? s.charAt(i) : '\0';
    }

    private char next() {
        if (i >= s.length()) throw new IllegalArgumentException("Unexpected end of input");
        return s.charAt(i++);
    }

    private void expect(char c) {
        char got = next();
        if (got != c) throw err("'" + c + "'", got);
    }

    private IllegalArgumentException err(String expected, char got) {
        return new IllegalArgumentException(
                "Expected " + expected + " but found '" + got + "' at offset " + i);
    }
}

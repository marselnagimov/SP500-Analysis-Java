import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON parser so the project has zero external dependencies.
 * Objects become Map<String,Object>, arrays List<Object>, numbers Double,
 * plus String, Boolean and null.
 */
final class Json {
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw p.error("trailing characters");
        return v;
    }

    private Object value() {
        ws();
        if (i >= s.length()) throw error("unexpected end");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': expect("true");  return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null");  return null;
            default:  return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; ws();
        if (peek('}')) { i++; return m; }
        while (true) {
            ws();
            String key = string();
            ws(); expect(":");
            m.put(key, value());
            ws();
            if (peek(',')) { i++; continue; }
            expect("}");
            return m;
        }
    }

    private List<Object> array() {
        List<Object> a = new ArrayList<>();
        i++; ws();
        if (peek(']')) { i++; return a; }
        while (true) {
            a.add(value());
            ws();
            if (peek(',')) { i++; continue; }
            expect("]");
            return a;
        }
    }

    private String string() {
        expect("\"");
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            char e = s.charAt(i++);
            switch (e) {
                case 'n': b.append('\n'); break;
                case 't': b.append('\t'); break;
                case 'r': b.append('\r'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                default:  b.append(e);
            }
        }
    }

    private Double number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (start == i) throw error("unexpected character '" + s.charAt(i) + "'");
        return Double.parseDouble(s.substring(start, i));
    }

    private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
    private boolean peek(char c) { return i < s.length() && s.charAt(i) == c; }

    private void expect(String t) {
        if (!s.startsWith(t, i)) throw error("expected '" + t + "'");
        i += t.length();
    }

    private IllegalArgumentException error(String msg) {
        return new IllegalArgumentException("JSON " + msg + " at position " + i);
    }
}

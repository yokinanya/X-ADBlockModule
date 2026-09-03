package com.xadblock.module;

import com.xadblock.module.data.Contract;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parses one keyword-file line into rule items, compatible with the x-comment-blocker
 * community keyword format:
 *   - "#" lines and category headers are ignored;
 *   - "/pattern/flags" is a regex rule (flags: i/m/s/u, "u" always implied);
 *   - any other non-empty line is a literal keyword;
 *   - a line containing the record separator char is an ALL_OF multi-part rule.
 */
final class RuleParserLine {
    private static final Pattern REGEX_LINE = Pattern.compile("^/(.+)/([a-zA-Z]*)$");

    private RuleParserLine() {}

    static List<String[]> parse(String rawLine) {
        if (rawLine == null) return null;
        String line = stripInvisible(rawLine).trim();
        if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
            return null;
        }
        List<String[]> result = new ArrayList<>();
        if (line.indexOf(Contract.ALL_OF_SEPARATOR) >= 0) {
            result.add(new String[]{Contract.KIND_ALL_OF, line});
            return result;
        }
        if (line.length() >= 3 && line.startsWith("/")) {
            var matcher = REGEX_LINE.matcher(line);
            if (matcher.matches()) {
                String pattern = matcher.group(1);
                String flags = matcher.group(2);
                StringBuilder inline = new StringBuilder();
                if (flags.contains("i")) inline.append('i');
                if (flags.contains("m")) inline.append('m');
                if (flags.contains("s")) inline.append('s');
                String finalPattern = inline.length() > 0 ? "(?" + inline + ")" + pattern : pattern;
                result.add(new String[]{Contract.KIND_REGEX, finalPattern});
                return result;
            }
        }
        result.add(new String[]{Contract.KIND_LITERAL, line});
        return result;
    }

    private static String stripInvisible(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            int type = Character.getType(codePoint);
            if (type == Character.FORMAT || type == Character.CONTROL) {
                continue;
            }
            sb.appendCodePoint(codePoint);
        }
        return sb.toString();
    }
}

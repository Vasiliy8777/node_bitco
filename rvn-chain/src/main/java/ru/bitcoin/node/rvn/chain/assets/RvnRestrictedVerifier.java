package ru.bitcoin.node.rvn.chain.assets;

import java.util.*;
import java.util.function.Predicate;

/** Bounded verifier-expression implementation: root qualifiers, !, &, | and
 * parentheses. This parser has not yet been certified against LibBoolEE's
 * historical malformed-expression quirks; it must not unlock historical replay. */
public final class RvnRestrictedVerifier {
    private interface Node { boolean evaluate(Predicate<String> qualifiers); }
    private final Node root;
    private final Set<String> qualifiers;
    private RvnRestrictedVerifier(Node root, Set<String> qualifiers) { this.root = root; this.qualifiers = Set.copyOf(qualifiers); }
    public Set<String> qualifiers() { return qualifiers; }
    public boolean evaluate(Predicate<String> assigned) { return root.evaluate(Objects.requireNonNull(assigned)); }

    public static RvnRestrictedVerifier fromOutput(String expression) {
        Objects.requireNonNull(expression);
        if (expression.equals("true")) return new RvnRestrictedVerifier(q -> true, Set.of());
        // CheckVerifierAssetTxOut forbids space and # explicitly; other ASCII
        // whitespace is removed by LibBoolEE when resolving expressions.
        if (expression.indexOf(' ') >= 0 || expression.indexOf('#') >= 0)
            throw new SecurityException("RVN verifier output contains space or #");
        var normalized = new StringBuilder();
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c > 127) throw new SecurityException("RVN verifier encoding");
            if (c < 9 || c > 13) normalized.append(c);
        }
        if (normalized.isEmpty() || normalized.length() > 80) throw new SecurityException("RVN verifier length");
        var parser = new Parser(normalized.toString());
        Node node = parser.or();
        if (parser.position != parser.source.length()) throw new SecurityException("RVN verifier trailing expression");
        return new RvnRestrictedVerifier(node, parser.qualifiers);
    }

    private static final class Parser {
        final String source;
        final Set<String> qualifiers = new HashSet<>();
        int position;
        Parser(String source) { this.source = source; }
        boolean consume(char c) {
            if (position < source.length() && source.charAt(position) == c) { position++; return true; }
            return false;
        }
        Node or() {
            Node node = and();
            while (consume('|')) { Node left = node, right = and(); node = q -> left.evaluate(q) | right.evaluate(q); }
            return node;
        }
        Node and() {
            Node node = unary();
            while (consume('&')) { Node left = node, right = unary(); node = q -> left.evaluate(q) & right.evaluate(q); }
            return node;
        }
        Node unary() {
            if (consume('!')) { Node child = unary(); return q -> !child.evaluate(q); }
            if (consume('(')) {
                Node child = or();
                if (!consume(')')) throw new SecurityException("RVN verifier unmatched parenthesis");
                return child;
            }
            int start = position;
            while (position < source.length() && (source.charAt(position) >= 'A' && source.charAt(position) <= 'Z'
                    || source.charAt(position) >= '0' && source.charAt(position) <= '9'
                    || source.charAt(position) == '_' || source.charAt(position) == '.')) position++;
            String name = source.substring(start, position);
            if (name.isEmpty() || RvnAssetNames.classify("#" + name).orElse(null) != RvnAssetNames.Type.QUALIFIER)
                throw new SecurityException("RVN verifier invalid qualifier");
            String qualifier = "#" + name; qualifiers.add(qualifier);
            return q -> q.test(qualifier);
        }
    }
}

package ru.bitcoin.node.rvn.chain.assets;

import java.util.*;
import java.util.function.Predicate;

/** Verifier-output checks with LibBoolEE-compatible expression evaluation.
 * Qualifier existence/assignment must come from the parent snapshot. */
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
        var names = new HashSet<String>();
        var matcher = java.util.regex.Pattern.compile("[A-Z0-9_.]+").matcher(normalized);
        while (matcher.find()) {
            String name = "#" + matcher.group();
            if (RvnAssetNames.classify(name).orElse(null) != RvnAssetNames.Type.QUALIFIER)
                throw new SecurityException("RVN verifier invalid qualifier");
            names.add(name);
        }
        String source = normalized.toString();
        RvnLibBoolEvaluator.resolve(source, names, q -> true);
        return new RvnRestrictedVerifier(q -> RvnLibBoolEvaluator.resolve(source, names, q), names);
    }
}

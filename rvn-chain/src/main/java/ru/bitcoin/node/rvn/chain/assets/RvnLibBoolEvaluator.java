/*
 * Adapted from LibBoolEE, Copyright (c) 2016 Adam Streck.
 * Source: Ravencoin Core 6d48ae0175b10283248146ae3080e2ba70966739, src/LibBoolEE.cpp.
 * GNU LGPL version 3 or (at your option) any later version. WITHOUT ANY WARRANTY.
 * See META-INF/licenses/LibBoolEE-LGPL-3.0.txt and META-INF/licenses/GPL-3.0.txt.
 */
package ru.bitcoin.node.rvn.chain.assets;

import java.util.*;
import java.util.function.Predicate;

/** Compatibility evaluation including Core's permissive leading characters. */
final class RvnLibBoolEvaluator {
    private RvnLibBoolEvaluator() {}
    static boolean resolve(String expression, Set<String> qualifiers, Predicate<String> assigned) {
        if (expression.isEmpty()) throw new SecurityException("RVN verifier empty subexpression");
        var pieces = split(expression, '|'); boolean disjunction = true;
        if (pieces.size() == 1) { pieces = split(expression, '&'); disjunction = false; }
        if (pieces.isEmpty()) throw new SecurityException("RVN verifier has no formula");
        if (pieces.size() == 1) {
            if (expression.charAt(0) == '!') return !resolve(expression.substring(1), qualifiers, assigned);
            if (expression.charAt(0) == '(') {
                if (expression.length() < 2) throw new SecurityException("RVN verifier parentheses");
                return resolve(expression.substring(1, expression.length()-1), qualifiers, assigned);
            }
            if (expression.equals("1")) return true;
            if (expression.equals("0")) return false;
            String qualifier = "#" + expression;
            if (!qualifiers.contains(qualifier)) throw new SecurityException("RVN verifier unknown variable");
            return assigned.test(qualifier);
        }
        boolean result = !disjunction;
        for (String piece : pieces) {
            boolean value = resolve(piece, qualifiers, assigned);
            result = disjunction ? result | value : result & value;
        }
        return result;
    }
    private static List<String> split(String expression, char operator) {
        var result = new ArrayList<String>(); int start = -1, depth = 0;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == ')') depth--;
            else if (c == '(') { depth++; if (start < 0) start = i; }
            else if (depth == 0) {
                if (start < 0) { if (nameCharacter(c) || c == '!') start = i; }
                else if (!nameCharacter(c) && c != '!') {
                    if (c == operator) { result.add(expression.substring(start, i)); start = i + 1; }
                    else if (c != '&' && c != '|') throw new SecurityException("RVN verifier unknown operator");
                }
            }
        }
        if (start >= 0) result.add(expression.substring(start));
        if (depth != 0) throw new SecurityException("RVN verifier parenthesis parity");
        return result;
    }
    private static boolean nameCharacter(char c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '#' || c == '.';
    }
}

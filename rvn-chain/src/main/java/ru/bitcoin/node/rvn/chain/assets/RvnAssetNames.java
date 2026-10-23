package ru.bitcoin.node.rvn.chain.assets;

import java.util.*;

/** Asset name grammar from pinned Core assets.cpp. Classification does not
 * establish existence, activation, ownership or permission to issue/reissue. */
public final class RvnAssetNames {
    private RvnAssetNames() {}
    public enum Type { ROOT, SUB, UNIQUE, CHANNEL, OWNER, VOTE, QUALIFIER, SUB_QUALIFIER, RESTRICTED }
    private static final Set<String> RESERVED = Set.of("RVN", "RAVEN", "RAVENCOIN", "#RVN", "#RAVEN", "#RAVENCOIN");

    public static Optional<Type> classify(String name) {
        if (name == null || name.isEmpty() || name.length() > 40) return Optional.empty();
        Type type;
        boolean valid;
        if (name.matches("[^\\^~#!]+#[^~#!/]+")) {
            type = Type.UNIQUE;
            int split = name.indexOf('#');
            String tag = name.substring(split + 1);
            valid = base(name.substring(0, split)) && tag.chars().allMatch(c ->
                    "-ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789@$%&*()[]{}_.?:".indexOf(c) >= 0);
        } else if (name.matches("[^\\^~#!]+~[^~#!/]+")) {
            type = Type.CHANNEL;
            int split = name.indexOf('~'); String tag = name.substring(split + 1);
            valid = base(name.substring(0, split)) && tag.length() <= 12
                    && tag.matches("[A-Za-z0-9_]+") && punctuation(tag, true);
        } else if (name.matches("[^\\^~#!]+!")) {
            type = Type.OWNER; valid = base(name.substring(0, name.length() - 1));
        } else if (name.matches("[^\\^~#!]+\\^[^~#!/]+")) {
            type = Type.VOTE; int split = name.indexOf('^');
            valid = base(name.substring(0, split)) && name.substring(split + 1).matches("[A-Z0-9._]+");
        } else if (name.matches("#[A-Z0-9._]{3,}")) {
            type = Type.QUALIFIER; valid = qualifierRoot(name);
        } else if (name.matches("#[A-Z0-9._]+/#[A-Z0-9._]+")) {
            type = Type.SUB_QUALIFIER; var parts = name.split("/", -1);
            valid = qualifierRoot(parts[0]) && punctuation(parts[1], true);
        } else if (name.matches("\\$[A-Z0-9._]{3,}")) {
            // Preserve Core's check on the whole '$'-prefixed string (including
            // its historical distinction from qualifier leading punctuation).
            type = Type.RESTRICTED; valid = punctuation(name, true) && !RESERVED.contains(name);
        } else {
            type = name.contains("/") ? Type.SUB : Type.ROOT; valid = base(name);
        }
        int maximum = type == Type.ROOT || type == Type.SUB ? 30 : 31;
        return valid && name.length() <= maximum ? Optional.of(type) : Optional.empty();
    }

    public static Type requireValid(String name) {
        return classify(name).orElseThrow(() -> new IllegalArgumentException("Invalid RVN asset name"));
    }

    private static boolean base(String name) {
        var parts = name.split("/", -1);
        if (!parts[0].matches("[A-Z0-9._]{3,}") || !punctuation(parts[0], true) || RESERVED.contains(parts[0])) return false;
        for (int i = 1; i < parts.length; i++)
            if (!parts[i].matches("[A-Z0-9._]+") || !punctuation(parts[i], true)) return false;
        return true;
    }
    private static boolean qualifierRoot(String name) {
        return name.matches("#[A-Z0-9._]{3,}") && !RESERVED.contains(name)
                && punctuation(name, true) && name.charAt(1) != '.' && name.charAt(1) != '_';
    }
    private static boolean punctuation(String value, boolean leading) {
        return !value.isEmpty() && (!leading || (value.charAt(0) != '.' && value.charAt(0) != '_'))
                && !value.endsWith(".") && !value.endsWith("_") && !value.matches(".*[._]{2}.*");
    }
}

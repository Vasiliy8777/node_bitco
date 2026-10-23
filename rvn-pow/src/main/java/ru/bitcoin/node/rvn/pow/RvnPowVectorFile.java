package ru.bitcoin.node.rvn.pow;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Tab-separated independent vectors: name, algorithm, rawHeaderHex,
 * expectedBlockIdWireHex, expectedPowDigestWireHex.
 * Strictly rejects duplicates, comments-only files, malformed input.
 */
public final class RvnPowVectorFile {
    private RvnPowVectorFile() {
    }

    public static List<RvnPowVector> read(Path path) throws IOException {
        Objects.requireNonNull(path);
        var out = new ArrayList<RvnPowVector>();
        var names = new HashSet<String>();
        int line = 0;
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String row;
            while ((row = reader.readLine()) != null) {
                line++;
                if (row.isBlank() || row.startsWith("#")) continue;
                String[] columns = row.split("\\t", -1);
                if (columns.length != 5) throw new IOException("Invalid vector columns at line " + line);
                try {
                    if (columns[0].isBlank() || !names.add(columns[0]))
                        throw new IllegalArgumentException("Duplicate or blank vector name");
                    var algorithm = RvnPowAlgorithm.valueOf(columns[1]);
                    out.add(new RvnPowVector(columns[0], algorithm,
                            RvnPowHex.parse(columns[2]), RvnPowHex.parse(columns[3]),
                            RvnPowHex.parse(columns[4])));
                } catch (IllegalArgumentException e) {
                    throw new IOException("Invalid vector at line " + line, e);
                }
            }
        }
        if (out.isEmpty()) throw new IOException("No reference vectors provided");
        return List.copyOf(out);
    }
}

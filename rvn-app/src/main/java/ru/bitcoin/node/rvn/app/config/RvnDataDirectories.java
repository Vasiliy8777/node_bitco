package ru.bitcoin.node.rvn.app.config;

import java.nio.file.Path;
import java.util.Objects;

/** RVN runtime layout, independent of Bitcoin and the process working directory.
 * Header RocksDB currently also owns downloaded block bodies. No directories
 * are created by resolving this layout; each subsystem opens its own storage. */
public record RvnDataDirectories(Path root) {
    public static final String DEFAULT_ROOT = "K:/RVNJavaNjde";

    public RvnDataDirectories {
        root = Objects.requireNonNull(root).toAbsolutePath().normalize();
    }

    public Path headers() { return root.resolve("headers"); }
    public Path coins() { return root.resolve("coins"); }
    public Path dag() { return root.resolve("dag"); }
    public Path logs() { return root.resolve("logs"); }
    public Path temporary() { return root.resolve("tmp"); }
}

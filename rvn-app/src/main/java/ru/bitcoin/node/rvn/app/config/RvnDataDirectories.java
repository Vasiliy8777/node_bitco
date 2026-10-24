package ru.bitcoin.node.rvn.app.config;

import java.nio.file.Path;
import java.util.Objects;

/** NVMe hot indexes/state and sequential HDD payloads. Resolving paths does not
 * create or migrate data; each store owns its directory identity and lock. */
public record RvnDataDirectories(Path root, Path hotRoot) {
    public static final String DEFAULT_ROOT = "K:/RVNJavaNjde";
    public static final String DEFAULT_HOT_ROOT = "C:/RVNJavaNjde";

    public RvnDataDirectories {
        root = Objects.requireNonNull(root).toAbsolutePath().normalize();
        hotRoot = Objects.requireNonNull(hotRoot).toAbsolutePath().normalize();
    }
    public RvnDataDirectories(Path root) { this(root, root); }

    public Path headers() { return hotRoot.resolve("headers"); }
    public Path coins() { return hotRoot.resolve("coins"); }
    public Path blocks() { return root.resolve("blocks"); }
    public Path undo() { return root.resolve("undo"); }
    public Path archives() { return root.resolve("archives"); }
    public Path dag() { return root.resolve("dag"); }
    public Path logs() { return root.resolve("logs"); }
    public Path temporary() { return hotRoot.resolve("tmp"); }
}

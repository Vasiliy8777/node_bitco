package ru.bitcoin.node.p2p;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Atomic JSON persistence for manual bans.
 */
final class PeerBanStore {
    private final Path file;
    private final JsonMapper mapper = JsonMapper.builder().build();

    PeerBanStore(Path dataDirectory) {
        this.file = dataDirectory.resolve("banlist.json");
    }

    Map<String, PeerBanManager.BanEntry> load() throws IOException {
        if (!Files.exists(file)) return new LinkedHashMap<>();
        Map<?, ?> root = mapper.readValue(Files.readAllBytes(file), Map.class);
        Object raw = root.get("banned");
        if (!(raw instanceof List<?> list)) return new LinkedHashMap<>();
        Map<String, PeerBanManager.BanEntry> result = new LinkedHashMap<>();
        for (Object value : list) {
            if (!(value instanceof Map<?, ?> m)) throw new IOException("Invalid banlist entry");
            String subnet = Objects.toString(m.get("subnet"), "");
            long created = ((Number) m.get("ban_created")).longValue();
            long until = ((Number) m.get("banned_until")).longValue();
            String canonical = PeerBanManager.Cidr.parse(subnet).canonical();
            result.put(canonical, new PeerBanManager.BanEntry(canonical, created, until));
        }
        return result;
    }

    void save(Map<String, PeerBanManager.BanEntry> bans) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".new");
        List<Map<String, Object>> entries = bans.values().stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("subnet", e.subnet());
            m.put("ban_created", e.banCreated());
            m.put("banned_until", e.bannedUntil());
            return m;
        }).toList();
        Files.write(temp, mapper.writeValueAsBytes(Map.of("version", 1, "banned", entries)),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

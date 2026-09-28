package ru.bitcoin.node.p2p.transport;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class Bip324StageContractTest {
    @Test void stageGateContainsOfficialVectorsAndBidirectionalCoreInterop() throws Exception {
        Path root = Path.of("..").toAbsolutePath().normalize();
        String fetch = Files.readString(root.resolve("tools/fetch-bip324-vectors.ps1"));
        String live = Files.readString(root.resolve("tools/bip324-core31-certify.ps1"));
        String gate = Files.readString(root.resolve("tools/bip324-stage-gate.ps1"));
        assertAll(
                () -> assertTrue(fetch.contains("ellswift_decode_test_vectors.csv")),
                () -> assertTrue(fetch.contains("xswiftec_inv_test_vectors.csv")),
                () -> assertTrue(fetch.contains("packet_encoding_test_vectors.csv")),
                () -> assertTrue(live.contains("addnode")),
                () -> assertTrue(live.contains("onetry")),
                () -> assertTrue(live.contains("session_id")),
                () -> assertTrue(live.contains("transport_protocol_type")),
                () -> assertTrue(gate.contains("clean test")),
                () -> assertTrue(gate.contains("Bip324OfficialVectorsTest")),
                () -> assertTrue(gate.contains("bip324-core31-certify.ps1"))
        );
    }
}

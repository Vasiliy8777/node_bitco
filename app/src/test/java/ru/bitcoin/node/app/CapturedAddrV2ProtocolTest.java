package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.address.PeerAddressManager;
import ru.bitcoin.node.p2p.address.PeerAddressProtocol;
import ru.bitcoin.node.p2p.message.BitcoinMessage;
import java.net.InetSocketAddress;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named="bitcoin.pre-mainnet.test",matches="true")
class CapturedAddrV2ProtocolTest {
    @Test void actualAddressHandlerAcceptsFreshCapturedMainnetMessages() throws Exception {
        Path root=Path.of(System.getProperty("maven.multiModuleProjectDirectory",".."));
        Path captures=root.resolve("target/addrv2-probe-20261008");
        assumeTrue(Files.isDirectory(captures),"Run tools/addrv2-probe.py first");
        int checked=0;
        try(var files=Files.list(captures)) {
            for(var file:files.filter(p -> p.toString().endsWith(".bin")).toList()) {
                var peer=mock(Peer.class);
                when(peer.remoteAddress()).thenReturn(new InetSocketAddress("8.8.4.4",8333));
                var manager=new PeerAddressManager();
                var handler=new PeerAddressProtocol(manager);
                assertDoesNotThrow(() -> handler.onMessage(peer,new BitcoinMessage("addrv2",Files.readAllBytes(file))),file.toString());
                checked++;
            }
        }
        assertTrue(checked>0);
        System.out.println("CAPTURED_ADDRV2_HANDLER messages="+checked+" accepted without protocol exception");
    }
}

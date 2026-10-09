package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import ru.bitcoin.node.app.config.*;
import ru.bitcoin.node.app.service.*;
import ru.bitcoin.node.app.rpc.NodeRpcServer;
import ru.bitcoin.node.consensus.time.AdjustedTime;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.mempool.FeeRate;
import ru.bitcoin.node.stratum.StratumServer;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in live certificates and bounded CUDA soak; all databases/ports are isolated. */
@EnabledIfSystemProperty(named="bitcoin.pre-mainnet.test", matches="true")
class BitcoinCorePreMainnetTest {
    @TempDir Path directory;
    Path binary = Path.of(System.getProperty("bitcoin.core.binary", "C:/Program Files/Bitcoin/daemon/bitcoind.exe"));
    Path cli = binary.resolveSibling("bitcoin-cli.exe");
    Path root = Path.of(System.getProperty("maven.multiModuleProjectDirectory", ".." )).toAbsolutePath();
    Path logs;
    int coreRpc;

    @Test void comparesLiveStateRejectsMutationsRestoresReorgAndMeasuresGpuMining() throws Exception {
        logs = Files.createDirectories(root.resolve("target/pre-mainnet-20261008"));
        coreRpc = port(); int coreP2p = port(); int javaP2p = port();
        Files.createDirectory(directory.resolve("core"));
        var core = new ProcessBuilder(binary.toString(), "-regtest", "-datadir="+directory.resolve("core"),
                "-server", "-rpcuser=test", "-rpcpassword=test-password", "-rpcport="+coreRpc,
                "-port="+coreP2p, "-bind=127.0.0.1:"+coreP2p, "-connect=0", "-dnsseed=0", "-discover=0",
                "-listenonion=0", "-natpmp=0", "-whitelist=noban@127.0.0.1")
                .redirectErrorStream(true).redirectOutput(logs.resolve("core.log").toFile()).start();
        try {
            await(() -> { try { core("getblockcount"); return true; } catch (Exception e) { return false; } }, 20);
            core("createwallet", "check");
            core("generatetoaddress", "150", core("getnewaddress").strip());
            try (var context = new AnnotationConfigApplicationContext()) {
                var properties = new HashMap<String,Object>();
                properties.put("bitcoin.network", "regtest");
                properties.put("bitcoin.data-directory", directory.resolve("java").toString());
                properties.put("bitcoin.p2p.peers", "127.0.0.1:"+coreP2p);
                properties.put("bitcoin.p2p.port", javaP2p);
                properties.put("bitcoin.p2p.target-outbound-peers", 1);
                properties.put("bitcoin.p2p.target-block-relay-peers", 0);
                properties.put("bitcoin.rpc.enabled", "true"); properties.put("bitcoin.rpc.port", 0);
                properties.put("bitcoin.rpc.user", "test"); properties.put("bitcoin.rpc.password", "test-password");
                properties.put("bitcoin.mining.payout-address", ""); properties.put("bitcoin.mining.payout-script", "51");
                properties.put("bitcoin.assume-valid", "0");
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated-certificate", properties));
                context.register(NetworkConfiguration.class, NodeConfiguration.class, MiningConfiguration.class);
                context.refresh();
                var lifecycle = context.getBean(NodeLifecycleService.class);
                var validation = context.getBean(NodeValidationService.class);
                try (var runner = new NodeLifecycleRunner(lifecycle)) {
                    runner.start();
                    await(() -> lifecycle.isMiningReady() && validation.activeTip().height()==150, 45);
                    int javaRpc = context.getBean(NodeRpcServer.class).port();
                    certificate("bip324-core31-certify.ps1", javaRpc, "-JavaP2P", "127.0.0.1:"+javaP2p);
                    certificate("consensus-certify-core31.ps1", javaRpc, "-MutationCount", "64", "-ReorgDepth", "3");
                    await(lifecycle::isMiningReady, 15);
                    var backend = new StratumMiningBackend(validation, context.getBean(NodeRelayService.class),
                            NetworkParametersRegistry.regtest(), context.getBean(AdjustedTime.class), lifecycle::isMiningReady,
                            new byte[]{0x51}, 3_996_000, new FeeRate(0));
                    int blockCount = Integer.getInteger("bitcoin.pre-mainnet.blocks", 120);
                    var propagation = new ArrayList<Double>();
                    var notification = new ArrayList<Double>();
                    try (var server = new StratumServer(new InetSocketAddress("127.0.0.1",0),backend,"miner","test-password",java.math.BigDecimal.ONE,4);
                         var observer = new StratumWireMiner(server.port())) {
                        observer.subscribe(); observer.call("mining.authorize",List.of("miner.test","test-password")); observer.job();
                        var builder = new ProcessBuilder("python",root.resolve("tools/gpu-miner/miner.py").toString(),"--port",Integer.toString(server.port()),
                                "--block-only","--batch-size","64","--max-blocks",Integer.toString(blockCount))
                                .redirectErrorStream(true).redirectOutput(logs.resolve("gpu-soak.log").toFile());
                        builder.environment().put("BITCOIN_STRATUM_PASSWORD","test-password");
                        var gpu = builder.start();
                        try {
                            long deadline = System.nanoTime()+Duration.ofSeconds(blockCount*3L+30).toNanos();
                            long previous=150;
                            while(previous<150+blockCount && System.nanoTime()<deadline) {
                                long current=validation.activeTip().height();
                                if(current>previous) {
                                    long observed=System.nanoTime();
                                    String hash=validation.activeTip().hash().toDisplayHex();
                                    await(() -> { try { return core("getbestblockhash").strip().equals(hash); } catch(Exception e) { return false; } },10);
                                    propagation.add((System.nanoTime()-observed)/1e6);
                                    observer.job();
                                    notification.add((System.nanoTime()-observed)/1e6);
                                    previous=current;
                                    // Passive observers must send a request before the server's
                                    // 180-second inbound idle timeout, just like active miners.
                                    if ((previous-150)%60==0)
                                        observer.call("mining.authorize",List.of("miner.test","test-password"));
                                } else Thread.sleep(5);
                            }
                            assertEquals(150+blockCount,validation.activeTip().height());
                            assertTrue(gpu.waitFor(10,TimeUnit.SECONDS));
                            assertEquals(0,gpu.exitValue(),Files.readString(logs.resolve("gpu-soak.log")));
                            assertEquals(blockCount,server.statistics().submittedBlocks());
                            assertEquals(validation.activeTip().hash().toDisplayHex(),core("getbestblockhash").strip());
                            String metrics="blocks="+blockCount+" observations="+propagation.size()+" propagation "+percentiles(propagation)+" notifyAfterPropagation "+percentiles(notification);
                            Files.writeString(logs.resolve("latency.txt"),metrics); System.out.println("GPU_SOAK "+metrics);
                        } finally { if(gpu.isAlive()) {gpu.destroyForcibly();gpu.waitFor(5,TimeUnit.SECONDS);} }
                    }
                    certificate("consensus-certify-core31.ps1", javaRpc,"-MutationCount","64","-ReorgDepth","3");
                }
            }
        } finally {
            try { core("stop"); } catch(Exception ignored) { }
            if(!core.waitFor(10,TimeUnit.SECONDS)) {core.destroyForcibly();core.waitFor(5,TimeUnit.SECONDS);}
        }
    }
    String core(String...args) throws Exception {
        var command=new ArrayList<>(List.of(cli.toString(),"-regtest","-datadir="+directory.resolve("core"),"-rpcuser=test","-rpcpassword=test-password","-rpcport="+coreRpc));
        command.addAll(List.of(args));
        var process=new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] output=process.getInputStream().readAllBytes();
        assertTrue(process.waitFor(20,TimeUnit.SECONDS));
        if(process.exitValue()!=0) throw new IllegalStateException(new String(output));
        return new String(output);
    }
    void certificate(String script,int javaRpc,String...extra) throws Exception {
        var command=new ArrayList<>(List.of("C:/Program Files/PowerShell/7/pwsh.exe","-NoProfile","-ExecutionPolicy","Bypass","-File",root.resolve("tools/"+script).toString(),
                "-JavaRpc","http://127.0.0.1:"+javaRpc,"-CoreRpc","http://127.0.0.1:"+coreRpc,
                "-JavaUser","test","-JavaPassword","test-password","-CoreUser","test","-CorePassword","test-password"));
        command.addAll(List.of(extra));
        Path output=logs.resolve(script+"-"+System.nanoTime()+".log");
        var process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try { assertTrue(process.waitFor(90,TimeUnit.SECONDS)); assertEquals(0,process.exitValue(),new String(Files.readAllBytes(output),java.nio.charset.StandardCharsets.UTF_8)); }
        finally { if(process.isAlive()) {process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);} }
    }
    static String percentiles(List<Double> values) {
        Collections.sort(values); if(values.isEmpty()) return "no samples";
        return String.format(Locale.ROOT,"ms p50=%.2f p95=%.2f p99=%.2f max=%.2f",values.get(values.size()/2),values.get(Math.min(values.size()-1,(int)Math.ceil(values.size()*.95)-1)),values.get(Math.min(values.size()-1,(int)Math.ceil(values.size()*.99)-1)),values.getLast());
    }
    static int port() throws Exception {try(var socket=new ServerSocket(0)){return socket.getLocalPort();}}
    static void await(java.util.function.BooleanSupplier condition,int seconds) throws Exception {
        long end=System.nanoTime()+Duration.ofSeconds(seconds).toNanos();
        while(!condition.getAsBoolean()&&System.nanoTime()<end) Thread.sleep(20);
        assertTrue(condition.getAsBoolean(),"Condition timed out after "+seconds+"s");
    }
}

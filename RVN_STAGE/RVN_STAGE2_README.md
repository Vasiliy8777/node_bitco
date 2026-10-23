# RVN stage 2 — isolated P2P handshake probe

Bitcoin sources and existing run configs are unchanged. New modules: rvn-protocol, rvn-p2p; rvn-app now depends on rvn-p2p. Only existing file outside rvn modules modified: root pom.xml.

Reference: RavenProject/Ravencoin src/chainparams.cpp: mainnet magic 52 41 56 4e and port 8767; testnet magic 52 56 4e 54 and port 18770; regtest magic 43 52 4f 57 and port 18444.

Build without clean (avoids deleting root target, needed by Bitcoin GPU JVM GC logs):

    mvn -pl rvn-app -am test
    mvn -pl rvn-app -am package -DskipTests

Run offline/safe default:

    java -jar rvn-app/target/rvn-app-0.0.1-SNAPSHOT.jar

Optional local Ravencoin Core peer (requires running RVN Core, NOT Bitcoin Core):

    java -jar rvn-app/target/rvn-app-0.0.1-SNAPSHOT.jar --ravencoin.network=mainnet --ravencoin.p2p.probe-enabled=true --ravencoin.p2p.host=127.0.0.1 --ravencoin.p2p.port=8767

Probe makes one outbound connection and closes after version/verack. It does not sync headers, validate consensus, store blocks, or mine. No auto-discovery or inbound server in this stage. Connection failure does not indicate Bitcoin is broken.

IMPORTANT: This is a transport-level implementation, not production-ready RVN P2P. Protocol-version 70028 is a starting handshake value and must be validated against an independently running Ravencoin Core node. Before mainnet operations, compare actual wire traces and implement complete network protocol and consensus tests.

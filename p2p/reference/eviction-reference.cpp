// Test-only adapter around the unchanged Bitcoin Core v32.1 node/eviction.cpp.
#include <node/eviction.h>
#include "eviction.cpp"
#include <iostream>
#include <string>

int main() {
    int mode, count;
    long long case_id;
    while (std::cin >> case_id >> mode >> count) {
        std::vector<NodeEvictionCandidate> candidates;
        for (int i = 0; i < count; ++i) {
            NodeEvictionCandidate c{};
            long long connected, ping, block, tx;
            int relevant, relay, bloom, prefer, local, network, noban, inbound;
            if (!(std::cin >> c.id >> connected >> ping >> block >> tx >> relevant >> relay >> bloom
                    >> c.nKeyedNetGroup >> prefer >> local >> network >> noban >> inbound)) return 2;
            c.m_connected = NodeClock::time_point{std::chrono::seconds{connected}};
            c.m_min_ping_time = std::chrono::duration_cast<NodeClock::duration>(std::chrono::nanoseconds{ping});
            c.m_last_block_time = std::chrono::seconds{block}; c.m_last_tx_time = std::chrono::seconds{tx};
            c.fRelevantServices = relevant; c.m_relay_txs = relay; c.fBloomFilter = bloom;
            c.prefer_evict = prefer; c.m_is_local = local; c.m_network = static_cast<Network>(network);
            c.m_noban = noban; c.m_conn_type = inbound ? ConnectionType::INBOUND : ConnectionType::FULL_RELAY;
            candidates.push_back(c);
        }
        std::cout << case_id << '\t';
        if (mode == 0) {
            auto chosen = SelectNodeToEvict(std::move(candidates));
            if (chosen) std::cout << *chosen; else std::cout << '-';
        } else {
            ProtectEvictionCandidatesByRatio(candidates);
            std::sort(candidates.begin(), candidates.end(), [](const auto& a, const auto& b) { return a.id < b.id; });
            if (candidates.empty()) std::cout << '-';
            for (size_t i = 0; i < candidates.size(); ++i) {
                if (i) std::cout << ',';
                std::cout << candidates[i].id;
            }
        }
        std::cout << '\n';
    }
}

#include <cassert>
#include <compare>
#include <iostream>
#include <tuple>
#include <util/bitset.h>
#include <cluster_linearize.h>

int main() {
    unsigned case_id, count;
    while (std::cin >> case_id >> count) {
        cluster_linearize::DepGraph<BitSet<64>> graph;
        std::vector<BitSet<64>> parents(count);
        for (unsigned i = 0; i < count; ++i) {
            int64_t fee; int32_t weight; unsigned nparents;
            std::cin >> fee >> weight >> nparents;
            graph.AddTransaction({fee, weight});
            for (unsigned j = 0; j < nparents; ++j) {
                unsigned parent; std::cin >> parent; parents[i].Set(parent);
            }
        }
        for (unsigned i = 0; i < count; ++i) graph.AddDependencies(parents[i], i);
        auto [order, optimal, cost] = cluster_linearize::Linearize(graph, 1000000000, uint64_t{320100} + case_id,
                [](uint32_t a, uint32_t b) { return a <=> b; });
        if (!optimal) return 2; // Never publish a budget-limited approximation as the oracle.
        std::cout << case_id << '\t';
        for (unsigned i = 0; i < order.size(); ++i) std::cout << (i ? "," : "") << order[i];
        std::cout << '\t';
        auto chunks = cluster_linearize::ChunkLinearizationInfo(graph, order);
        for (unsigned i = 0; i < chunks.size(); ++i) {
            if (i) std::cout << ';';
            std::cout << chunks[i].feerate.fee << ',' << chunks[i].feerate.size << ':';
            bool first = true;
            for (auto index : chunks[i].transactions) { std::cout << (first ? "" : ",") << index; first = false; }
        }
        std::cout << '\n';
    }
}

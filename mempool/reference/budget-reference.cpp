// Bitcoin Core v32.1 algorithm oracle. MIT license; see CORE-LICENSE.txt.
#include <cassert>
#include <compare>
#include <iostream>
#include <tuple>
#include <util/bitset.h>
#include <cluster_linearize.h>

int main() {
    unsigned id, count, mode;
    uint64_t budget, seed;
    while (std::cin >> id >> count >> budget >> seed >> mode) {
        cluster_linearize::DepGraph<BitSet<64>> graph;
        std::vector<BitSet<64>> parents(count);
        for (unsigned i = 0; i < count; ++i) {
            int64_t fee; int32_t weight; unsigned nparents;
            std::cin >> fee >> weight >> nparents;
            graph.AddTransaction({fee, weight});
            for (unsigned j = 0; j < nparents; ++j) { unsigned parent; std::cin >> parent; parents[i].Set(parent); }
        }
        for (unsigned i = 0; i < count; ++i) graph.AddDependencies(parents[i], i);
        std::vector<uint32_t> old;
        if (mode == 1 || mode == 2) {
            for (unsigned i = 0; i < count; ++i) old.push_back(mode == 2 ? count - 1 - i : i);
        }
        if (mode == 4) {
            auto [previous, previous_optimal, previous_cost] = cluster_linearize::Linearize(graph, 75000, seed ^ 0x1234,
                    [](uint32_t a, uint32_t b) { return a <=> b; });
            cluster_linearize::PostLinearize(graph, previous);
            old = std::move(previous);
        }
        auto [order, optimal, cost] = cluster_linearize::Linearize(graph, budget, seed,
                [](uint32_t a, uint32_t b) { return a <=> b; }, old, mode != 2);
        if (mode != 3) cluster_linearize::PostLinearize(graph, order);
        std::cout << id << '\t' << budget << '\t' << seed << '\t' << mode << '\t' << cost << '\t' << optimal << '\t';
        for (unsigned i = 0; i < order.size(); ++i) std::cout << (i ? "," : "") << order[i];
        std::cout << '\t';
        for (unsigned i = 0; i < old.size(); ++i) std::cout << (i ? "," : "") << old[i];
        std::cout << '\n';
    }
}

// Unchanged Bitcoin Core v32.1 TxGraph oracle; see CORE-LICENSE.txt.
#include <span>
#include <limits>
#include <iostream>
#include <sstream>
#include <algorithm>
#include <map>
#include "txgraph.cpp"

struct Reference : TxGraph::Ref { unsigned id; explicit Reference(unsigned i) : id(i) {} };
static unsigned id(const TxGraph::Ref* ref) { return static_cast<const Reference*>(ref)->id; }
static std::string ids(const std::vector<TxGraph::Ref*>& refs) {
    std::vector<unsigned> values; for (auto ref : refs) values.push_back(id(ref)); std::ranges::sort(values);
    std::ostringstream out; for (unsigned i=0;i<values.size();i++) out << (i ? "," : "") << values[i]; return out.str();
}
static void chunks(TxGraph& graph) {
    auto builder = graph.GetBlockBuilder(); bool first = true;
    while (auto current = builder->GetCurrentChunk()) {
        if (!first) std::cout << ';'; first = false;
        std::cout << current->second.fee << ',' << current->second.size << ':';
        for (unsigned i=0;i<current->first.size();i++) std::cout << (i ? "," : "") << id(current->first[i]);
        builder->Include();
    }
}
int main() {
    std::unique_ptr<TxGraph> graph;
    std::map<unsigned,std::unique_ptr<Reference>> refs;
    std::string line;
    while (std::getline(std::cin,line)) {
        std::istringstream in(line); std::string op; in >> op;
        if (op == "new") {
            graph.reset(); refs.clear();
            unsigned max_count=64; uint64_t max_weight=404000;
            if (in >> max_count) in >> max_weight;
            graph = MakeTxGraph(max_count, max_weight, 75000, [](const auto& a,const auto& b){ return id(&a) <=> id(&b); });
        } else if (op == "add") {
            unsigned index,count; int64_t fee; int32_t weight; in >> index >> fee >> weight >> count;
            auto ref = std::make_unique<Reference>(index); auto ptr = ref.get(); refs[index] = std::move(ref);
            graph->AddTransaction(*ptr, FeePerWeight{fee,weight});
            for (unsigned i=0;i<count;i++) { unsigned parent; in >> parent; graph->AddDependency(*refs.at(parent),*ptr); }
        } else if (op == "remove") { unsigned index; in >> index; graph->RemoveTransaction(*refs.at(index)); }
        else if (op == "dep") { unsigned parent,child; in >> parent >> child; graph->AddDependency(*refs.at(parent),*refs.at(child)); }
        else if (op == "trim") { unsigned sequence; in >> sequence; auto removed=graph->Trim(); std::cout << sequence << '\t' << ids(removed) << '\n'; }
        else if (op == "fee") { unsigned index; int64_t fee; in >> index >> fee; graph->SetTransactionFee(*refs.at(index),fee); }
        else if (op == "start") graph->StartStaging();
        else if (op == "abort") graph->AbortStaging();
        else if (op == "commit") graph->CommitStaging();
        else if (op == "work") { uint64_t cost; in >> cost; graph->DoWork(cost); }
        else if (op == "check") { graph->DoWork(1000000000); graph->SanityCheck(); }
        else if (op == "diagram") {
            unsigned sequence; in >> sequence;
            auto diagrams=graph->GetMainStagingDiagrams();
            std::cout << sequence;
            for (const auto& curve : {diagrams.first,diagrams.second}) {
                std::cout << '\t'; bool first=true;
                for (const auto& chunk : curve) {if(!first) std::cout << ';';first=false;std::cout << chunk.fee << ',' << chunk.size;}
            }
            std::cout << '\n';
        }
        else if (op == "query") {
            unsigned sequence; in >> sequence;
            graph->DoWork(1000000000); graph->SanityCheck();
            std::cout << sequence << '\t' << graph->DoWork(0) << '\t'; chunks(*graph); std::cout << '\t';
            for (auto level : {TxGraph::Level::MAIN,TxGraph::Level::TOP}) {
                bool first=true;
                for (const auto& [index,ref] : refs) {
                    if (!graph->Exists(*ref,level)) continue;
                    if (!first) std::cout << ';'; first=false;
                    std::cout << index << ':' << ids(graph->GetAncestors(*ref,level)) << '/'
                              << ids(graph->GetDescendants(*ref,level)) << '/' << ids(graph->GetCluster(*ref,level));
                }
                std::cout << '\t';
            }
            auto worst=graph->GetWorstMainChunk();
            std::cout << worst.second.fee << ',' << worst.second.size << ':';
            for (unsigned i=0;i<worst.first.size();i++) std::cout << (i ? "," : "") << id(worst.first[i]);
            std::cout << '\n';
        }
    }
    graph.reset();
}

// Test harness for pinned Ravencoin LibBoolEE. Three known-valid qualifier names;
// this is an expression oracle, not a complete CheckTxAssets/asset-name oracle.
#include "LibBoolEE.h"
#include <iostream>
#include <regex>
#include <set>

static std::string decode(const std::string& text) {
    if (text == "-") return "";
    std::string result;
    for (size_t i=0; i<text.size(); i+=2) result.push_back(static_cast<char>(std::stoul(text.substr(i,2), nullptr,16)));
    return result;
}
int main() {
    std::string hex; unsigned mask;
    while (std::cin >> hex >> mask) {
        try {
            std::string expression=decode(hex);
            if (expression == "true") { std::cout << "true true\n"; continue; }
            if (expression.empty() || expression.find(' ') != std::string::npos || expression.find('#') != std::string::npos)
                throw std::runtime_error("invalid output");
            std::string stripped=LibBoolEE::removeWhitespaces(expression);
            if (stripped.size()>80) throw std::runtime_error("length");
            LibBoolEE::Vals syntax, values;
            std::regex names("[A-Z0-9_.]+");
            for (std::sregex_iterator it(stripped.begin(),stripped.end(),names),end;it!=end;++it) {
                std::string name=it->str();
                if (name!="AAA" && name!="BBB" && name!="CCC") throw std::runtime_error("fixture name");
                syntax[name]=true;
                values[name]=(mask & (name=="AAA"?1:name=="BBB"?2:4))!=0;
            }
            LibBoolEE::resolve(expression,syntax);
            bool result=LibBoolEE::resolve(expression,values);
            std::cout << "true " << (result?"true":"false") << '\n';
        } catch (const std::exception&) { std::cout << "false false\n"; }
    }
}

// Deliberately template-heavy translation unit so that IncrediBuild has real compile work to distribute.
#include "NativeCore.h"
#include <array>
#include <numeric>
#include <vector>
#include <algorithm>
#include <utility>

namespace {
    template <unsigned N>
    struct Fib { static constexpr unsigned long long value = Fib<N - 1>::value + Fib<N - 2>::value; };
    template <> struct Fib<0> { static constexpr unsigned long long value = 0; };
    template <> struct Fib<1> { static constexpr unsigned long long value = 1; };

    template <unsigned... Is>
    constexpr std::array<unsigned long long, sizeof...(Is)> table(std::integer_sequence<unsigned, Is...>) {
        return { Fib<Is>::value... };
    }
    constexpr auto kTable = table(std::make_integer_sequence<unsigned, 60>{});
}

unsigned long long work4(unsigned int n) {
    std::vector<unsigned long long> v(kTable.begin(), kTable.end());
    std::sort(v.begin(), v.end(), std::greater<>());
    unsigned long long acc = 0;
    for (unsigned i = 0; i < n; ++i) acc += v[i % v.size()] ^ (4ull * 0x9E3779B97F4A7C15ull);
    return std::accumulate(v.begin(), v.end(), acc);
}

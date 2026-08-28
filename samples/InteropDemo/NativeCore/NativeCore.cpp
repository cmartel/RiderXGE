#include "NativeCore.h"
#include <string>

namespace {
    const std::string kDescription = "NativeCore built with MSVC " + std::to_string(_MSC_VER);
}

unsigned long long work1(unsigned int n);
unsigned long long work2(unsigned int n);
unsigned long long work3(unsigned int n);
unsigned long long work4(unsigned int n);

extern "C" {
    int nc_add(int a, int b) { return a + b; }

    const char* nc_describe() { return kDescription.c_str(); }

    unsigned long long nc_work(int which, unsigned int n) {
        switch (which) {
            case 1: return work1(n);
            case 2: return work2(n);
            case 3: return work3(n);
            default: return work4(n);
        }
    }
}

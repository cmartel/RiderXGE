#pragma once
#include "NativeCore.h"

namespace nc {
    class NATIVECORE_API Counter {
    public:
        explicit Counter(int start);
        void increment();
        int value() const;
    private:
        int value_;
    };
}

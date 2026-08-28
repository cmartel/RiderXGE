#include "Counter.h"

namespace nc {
    Counter::Counter(int start) : value_(start) {}
    void Counter::increment() { ++value_; }
    int Counter::value() const { return value_; }
}

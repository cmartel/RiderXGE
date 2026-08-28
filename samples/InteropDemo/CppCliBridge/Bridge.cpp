#include "Bridge.h"
#include "NativeCore.h"

using namespace System;

namespace CppCliBridge {

    int Bridge::Add(int a, int b) { return nc_add(a, b); }

    String^ Bridge::Describe() { return gcnew String(nc_describe()); }

    UInt64 Bridge::Work(int which, unsigned int n) { return nc_work(which, n); }

    ManagedCounter::ManagedCounter(int start) : native_(new nc::Counter(start)) {}
    ManagedCounter::~ManagedCounter() { this->!ManagedCounter(); }
    ManagedCounter::!ManagedCounter() { delete native_; native_ = nullptr; }
    void ManagedCounter::Increment() { native_->increment(); }
    int ManagedCounter::Value::get() { return native_->value(); }
}

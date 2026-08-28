#pragma once
#include "Counter.h"

namespace CppCliBridge {

    public ref class Bridge abstract sealed {
    public:
        static int Add(int a, int b);
        static System::String^ Describe();
        static System::UInt64 Work(int which, unsigned int n);
    };

    /// Managed wrapper around the native nc::Counter.
    public ref class ManagedCounter {
    public:
        ManagedCounter(int start);
        ~ManagedCounter();
        !ManagedCounter();
        void Increment();
        property int Value { int get(); }
    private:
        nc::Counter* native_;
    };
}

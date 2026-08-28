using System.Runtime.InteropServices;
using CppCliBridge;

namespace App;

internal static class Program
{
    // Direct P/Invoke into the native DLL.
    [DllImport("NativeCore.dll", CallingConvention = CallingConvention.Cdecl)]
    private static extern int nc_add(int a, int b);

    private static int Main()
    {
        Console.WriteLine($"P/Invoke   nc_add(2, 3)      = {nc_add(2, 3)}");
        Console.WriteLine($"C++/CLI    Bridge.Add(40, 2) = {Bridge.Add(40, 2)}");
        Console.WriteLine($"C++/CLI    Bridge.Describe() = {Bridge.Describe()}");
        Console.WriteLine($"C++/CLI    Bridge.Work(1, 5) = {Bridge.Work(1, 5)}");
        using var counter = new ManagedCounter(10);
        counter.Increment();
        counter.Increment();
        Console.WriteLine($"C++/CLI    counter           = {counter.Value}");
        return counter.Value == 12 && Bridge.Add(40, 2) == 42 && nc_add(2, 3) == 5 ? 0 : 1;
    }
}

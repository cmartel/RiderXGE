#pragma once

#ifdef NATIVECORE_EXPORTS
#define NATIVECORE_API __declspec(dllexport)
#else
#define NATIVECORE_API __declspec(dllimport)
#endif

extern "C" {
    NATIVECORE_API int nc_add(int a, int b);
    NATIVECORE_API const char* nc_describe();
    NATIVECORE_API unsigned long long nc_work(int which, unsigned int n);
}

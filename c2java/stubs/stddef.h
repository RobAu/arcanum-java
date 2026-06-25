#ifndef C2J_STDDEF_H_
#define C2J_STDDEF_H_
typedef unsigned long size_t;
typedef long ptrdiff_t;
typedef long ssize_t;
typedef unsigned short wchar_t;
#ifndef NULL
#define NULL ((void*)0)
#endif
#define offsetof(t, m) ((size_t)&(((t*)0)->m))
typedef long double max_align_t;
#endif

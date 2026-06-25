#ifndef C2J_STDARG_H_
#define C2J_STDARG_H_
typedef __builtin_va_list va_list;
#define va_start(ap, last) ((void)0)
#define va_arg(ap, type)   (*(type*)0)
#define va_end(ap)         ((void)0)
#define va_copy(d, s)      ((void)0)
#endif

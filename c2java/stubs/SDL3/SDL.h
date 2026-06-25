/*
 * Minimal SDL3 stub for the c2java transpiler.
 *
 * The real SDL3 sources are git submodules under third_party/sdl3 and are not
 * required to *translate* the Arcanum C code to Java -- we only need enough of
 * the SDL type surface that appears in TIG public headers so that libclang can
 * build a usable AST without fatal "file not found" errors.
 *
 * These are NOT accurate SDL definitions; they exist purely so the parser can
 * resolve identifiers referenced in first_party/tig/include/tig/*.h.
 */
#ifndef C2J_SDL3_STUB_H_
#define C2J_SDL3_STUB_H_

#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdarg.h>

typedef int32_t SDL_Scancode;
typedef int32_t SDL_Keycode;
typedef uint16_t SDL_Keymod;

typedef struct SDL_Window SDL_Window;
typedef struct SDL_Renderer SDL_Renderer;
typedef struct SDL_Surface SDL_Surface;
typedef struct SDL_Texture SDL_Texture;
typedef struct SDL_IOStream SDL_IOStream;

typedef struct SDL_PathInfo {
    int type;
    uint64_t size;
    int64_t create_time;
    int64_t modify_time;
    int64_t access_time;
} SDL_PathInfo;

#define SDL_min(a, b) (((a) < (b)) ? (a) : (b))
#define SDL_max(a, b) (((a) > (b)) ? (a) : (b))
#define SDL_clamp(x, a, b) SDL_min(SDL_max(x, a), b)

#endif /* C2J_SDL3_STUB_H_ */

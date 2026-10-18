/// Bindings for the subset of GLFW 3.4 the desktop viewer uses.
///
/// GLFW is not a system dependency: `scripts/build-native.sh` builds it from pinned sources (X11 backend) into
/// `third_party/build/libcodecity_gui.a`, which the `gui` dub configuration links statically. These declarations must
/// match `GLFW/glfw3.h` of that version by hand; the constants are copied from it. Only the `gui` configuration
/// compiles this module (the `cli` and `unittest` configurations exclude it).
module codecity.gui.glfw;

extern (C) @nogc nothrow:

// Opaque handles: GLFW only ever hands out pointers to these, so D never needs their layout.
struct GLFWwindow;
struct GLFWmonitor;

/// Scroll callback: window, x offset, y offset (in mouse-wheel notches; positive y means scrolling up).
alias GLFWscrollfun = void function(GLFWwindow*, double, double);
/// Key callback: window, key token, platform scancode, action (`GLFW_PRESS`, `GLFW_RELEASE` or 2 = repeat), modifier
/// bits.
alias GLFWkeyfun = void function(GLFWwindow*, int, int, int, int);
/// Mouse button callback: window, button, action, modifier bits.
alias GLFWmousebuttonfun = void function(GLFWwindow*, int, int, int);

enum
{
    GLFW_CONTEXT_VERSION_MAJOR = 0x00022002,
    GLFW_CONTEXT_VERSION_MINOR = 0x00022003,
    GLFW_OPENGL_PROFILE = 0x00022008,
    GLFW_OPENGL_CORE_PROFILE = 0x00032001,
    GLFW_OPENGL_FORWARD_COMPAT = 0x00022006,
    GLFW_SAMPLES = 0x0002100D,
    GLFW_MOUSE_BUTTON_LEFT = 0,
    GLFW_MOUSE_BUTTON_RIGHT = 1,
    GLFW_MOUSE_BUTTON_MIDDLE = 2,
    GLFW_RELEASE = 0,
    GLFW_PRESS = 1,
    GLFW_FALSE = 0,
    GLFW_TRUE = 1,
    /// Window attribute for `glfwGetWindowAttrib`: whether the window has input focus.
    GLFW_FOCUSED = 0x00020001,
    /// Input modes for `glfwSetInputMode`. `GLFW_CURSOR_DISABLED` hides the cursor and locks it to the window,
    /// reporting unbounded virtual positions (what first-person mouse look needs); `GLFW_RAW_MOUSE_MOTION` (only where
    /// `glfwRawMouseMotionSupported`) reports unaccelerated motion while the cursor is disabled.
    GLFW_CURSOR = 0x00033001,
    GLFW_RAW_MOUSE_MOTION = 0x00033005,
    GLFW_CURSOR_NORMAL = 0x00034001,
    GLFW_CURSOR_DISABLED = 0x00034003,
    // Key tokens for `glfwGetKey`. GLFW names them after the US layout but they denote physical key positions.
    GLFW_KEY_SPACE = 32,
    GLFW_KEY_SLASH = 47,
    GLFW_KEY_A = 65,
    GLFW_KEY_D = 68,
    GLFW_KEY_F = 70,
    GLFW_KEY_G = 71,
    GLFW_KEY_N = 78,
    GLFW_KEY_S = 83,
    GLFW_KEY_W = 87,
    GLFW_KEY_ESCAPE = 256,
    GLFW_KEY_RIGHT = 262,
    GLFW_KEY_LEFT = 263,
    GLFW_KEY_DOWN = 264,
    GLFW_KEY_UP = 265,
    GLFW_KEY_LEFT_SHIFT = 340,
    GLFW_KEY_RIGHT_SHIFT = 344,
}

int glfwInit();
void glfwTerminate();
void glfwWindowHint(int hint, int value);
GLFWwindow* glfwCreateWindow(int width, int height, const(char)* title, GLFWmonitor* monitor, GLFWwindow* share);
void glfwDestroyWindow(GLFWwindow*);
void glfwMakeContextCurrent(GLFWwindow*);
void glfwSwapInterval(int);
int glfwWindowShouldClose(GLFWwindow*);
void glfwPollEvents();
void glfwSwapBuffers(GLFWwindow*);
void glfwGetFramebufferSize(GLFWwindow*, int* width, int* height);
void glfwGetWindowSize(GLFWwindow*, int* width, int* height);
void glfwGetCursorPos(GLFWwindow*, double* x, double* y);
int glfwGetMouseButton(GLFWwindow*, int button);
int glfwGetKey(GLFWwindow*, int key);
void glfwSetInputMode(GLFWwindow*, int mode, int value);
int glfwRawMouseMotionSupported();
int glfwGetWindowAttrib(GLFWwindow*, int attrib);
/// Resolves an OpenGL entry point of the current context; `codecity.gui.gl.loadGL` uses it for every GL function.
void* glfwGetProcAddress(const(char)* name);
double glfwGetTime();
/// Returns the previously installed callback; ImGui's GLFW backend relies on this to chain to ours.
GLFWscrollfun glfwSetScrollCallback(GLFWwindow*, GLFWscrollfun);
GLFWkeyfun glfwSetKeyCallback(GLFWwindow*, GLFWkeyfun);
GLFWmousebuttonfun glfwSetMouseButtonCallback(GLFWwindow*, GLFWmousebuttonfun);
void glfwSetWindowTitle(GLFWwindow*, const(char)*);
const(char)* glfwGetVersionString();
int glfwGetError(const(char)** description);

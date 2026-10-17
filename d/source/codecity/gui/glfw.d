/// Bindings for the subset of GLFW 3.4 the desktop viewer uses.
module codecity.gui.glfw;

extern (C) @nogc nothrow:

struct GLFWwindow;
struct GLFWmonitor;

alias GLFWscrollfun = void function(GLFWwindow*, double, double);

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
    GLFW_PRESS = 1,
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
void* glfwGetProcAddress(const(char)* name);
double glfwGetTime();
GLFWscrollfun glfwSetScrollCallback(GLFWwindow*, GLFWscrollfun);
void glfwSetWindowTitle(GLFWwindow*, const(char)*);
const(char)* glfwGetVersionString();
int glfwGetError(const(char)** description);

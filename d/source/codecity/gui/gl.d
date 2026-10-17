/// A tiny OpenGL 3.3 core loader: just the entry points the renderer uses, resolved through GLFW at start-up.
module codecity.gui.gl;

import codecity.gui.glfw : glfwGetProcAddress;

alias GLenum = uint;
alias GLuint = uint;
alias GLint = int;
alias GLsizei = int;
alias GLfloat = float;
alias GLboolean = ubyte;
alias GLbitfield = uint;
alias GLchar = char;
alias GLsizeiptr = ptrdiff_t;
alias GLintptr = ptrdiff_t;

enum : GLenum
{
    GL_DEPTH_BUFFER_BIT = 0x0100,
    GL_COLOR_BUFFER_BIT = 0x4000,
    GL_TRIANGLES = 0x0004,
    GL_DEPTH_TEST = 0x0B71,
    GL_CULL_FACE = 0x0B44,
    GL_BLEND = 0x0BE2,
    GL_BACK = 0x0405,
    GL_FRONT = 0x0404,
    GL_SRC_ALPHA = 0x0302,
    GL_ONE_MINUS_SRC_ALPHA = 0x0303,
    GL_FLOAT = 0x1406,
    GL_UNSIGNED_INT = 0x1405,
    GL_UNSIGNED_BYTE = 0x1401,
    GL_ARRAY_BUFFER = 0x8892,
    GL_ELEMENT_ARRAY_BUFFER = 0x8893,
    GL_STATIC_DRAW = 0x88E4,
    GL_DYNAMIC_DRAW = 0x88E8,
    GL_VERTEX_SHADER = 0x8B31,
    GL_FRAGMENT_SHADER = 0x8B30,
    GL_COMPILE_STATUS = 0x8B81,
    GL_LINK_STATUS = 0x8B82,
    GL_INFO_LOG_LENGTH = 0x8B84,
    GL_FRAMEBUFFER = 0x8D40,
    GL_DEPTH_ATTACHMENT = 0x8D00,
    GL_TEXTURE_2D = 0x0DE1,
    GL_TEXTURE0 = 0x84C0,
    GL_DEPTH_COMPONENT = 0x1902,
    GL_DEPTH_COMPONENT24 = 0x81A6,
    GL_TEXTURE_MIN_FILTER = 0x2801,
    GL_TEXTURE_MAG_FILTER = 0x2800,
    GL_TEXTURE_WRAP_S = 0x2802,
    GL_TEXTURE_WRAP_T = 0x2803,
    GL_TEXTURE_COMPARE_MODE = 0x884C,
    GL_TEXTURE_COMPARE_FUNC = 0x884D,
    GL_COMPARE_REF_TO_TEXTURE = 0x884E,
    GL_LEQUAL = 0x0203,
    GL_LINEAR = 0x2601,
    GL_CLAMP_TO_BORDER = 0x812D,
    GL_TEXTURE_BORDER_COLOR = 0x1004,
    GL_NONE = 0,
    GL_RGBA = 0x1908,
    GL_PACK_ALIGNMENT = 0x0D05,
    GL_POLYGON_OFFSET_FILL = 0x8037,
    GL_MULTISAMPLE = 0x809D,
    GL_FRAMEBUFFER_COMPLETE = 0x8CD5,
}

private enum names = [
    "glClearColor", "glClear", "glViewport", "glEnable", "glDisable", "glBlendFunc", "glDepthMask", "glCullFace",
    "glCreateShader", "glShaderSource", "glCompileShader", "glGetShaderiv", "glGetShaderInfoLog", "glCreateProgram",
    "glAttachShader", "glLinkProgram", "glGetProgramiv", "glGetProgramInfoLog", "glUseProgram", "glGetUniformLocation",
    "glUniform1f", "glUniform1i", "glUniform3f", "glUniform4f", "glUniformMatrix4fv", "glGenVertexArrays",
    "glBindVertexArray", "glGenBuffers", "glBindBuffer", "glBufferData", "glEnableVertexAttribArray",
    "glVertexAttribPointer", "glVertexAttribDivisor", "glDrawArrays", "glDrawArraysInstanced", "glDrawElements",
    "glGenFramebuffers", "glBindFramebuffer", "glFramebufferTexture2D", "glGenTextures", "glBindTexture",
    "glTexImage2D", "glTexParameteri", "glTexParameterfv", "glActiveTexture", "glDrawBuffer", "glReadBuffer",
    "glReadPixels", "glPolygonOffset", "glCheckFramebufferStatus", "glPixelStorei", "glDeleteShader",
];

extern (System) @nogc nothrow __gshared
{
    void function(GLfloat, GLfloat, GLfloat, GLfloat) glClearColor;
    void function(GLbitfield) glClear;
    void function(GLint, GLint, GLsizei, GLsizei) glViewport;
    void function(GLenum) glEnable;
    void function(GLenum) glDisable;
    void function(GLenum, GLenum) glBlendFunc;
    void function(GLboolean) glDepthMask;
    void function(GLenum) glCullFace;
    GLuint function(GLenum) glCreateShader;
    void function(GLuint, GLsizei, const(GLchar*)*, const(GLint)*) glShaderSource;
    void function(GLuint) glCompileShader;
    void function(GLuint, GLenum, GLint*) glGetShaderiv;
    void function(GLuint, GLsizei, GLsizei*, GLchar*) glGetShaderInfoLog;
    GLuint function() glCreateProgram;
    void function(GLuint, GLuint) glAttachShader;
    void function(GLuint) glLinkProgram;
    void function(GLuint, GLenum, GLint*) glGetProgramiv;
    void function(GLuint, GLsizei, GLsizei*, GLchar*) glGetProgramInfoLog;
    void function(GLuint) glUseProgram;
    GLint function(GLuint, const(GLchar)*) glGetUniformLocation;
    void function(GLint, GLfloat) glUniform1f;
    void function(GLint, GLint) glUniform1i;
    void function(GLint, GLfloat, GLfloat, GLfloat) glUniform3f;
    void function(GLint, GLfloat, GLfloat, GLfloat, GLfloat) glUniform4f;
    void function(GLint, GLsizei, GLboolean, const(GLfloat)*) glUniformMatrix4fv;
    void function(GLsizei, GLuint*) glGenVertexArrays;
    void function(GLuint) glBindVertexArray;
    void function(GLsizei, GLuint*) glGenBuffers;
    void function(GLenum, GLuint) glBindBuffer;
    void function(GLenum, GLsizeiptr, const(void)*, GLenum) glBufferData;
    void function(GLuint) glEnableVertexAttribArray;
    void function(GLuint, GLint, GLenum, GLboolean, GLsizei, const(void)*) glVertexAttribPointer;
    void function(GLuint, GLuint) glVertexAttribDivisor;
    void function(GLenum, GLint, GLsizei) glDrawArrays;
    void function(GLenum, GLint, GLsizei, GLsizei) glDrawArraysInstanced;
    void function(GLenum, GLsizei, GLenum, const(void)*) glDrawElements;
    void function(GLsizei, GLuint*) glGenFramebuffers;
    void function(GLenum, GLuint) glBindFramebuffer;
    void function(GLenum, GLenum, GLenum, GLuint, GLint) glFramebufferTexture2D;
    void function(GLsizei, GLuint*) glGenTextures;
    void function(GLenum, GLuint) glBindTexture;
    void function(GLenum, GLint, GLint, GLsizei, GLsizei, GLint, GLenum, GLenum, const(void)*) glTexImage2D;
    void function(GLenum, GLenum, GLint) glTexParameteri;
    void function(GLenum, GLenum, const(GLfloat)*) glTexParameterfv;
    void function(GLenum) glActiveTexture;
    void function(GLenum) glDrawBuffer;
    void function(GLenum) glReadBuffer;
    void function(GLint, GLint, GLsizei, GLsizei, GLenum, GLenum, void*) glReadPixels;
    void function(GLfloat, GLfloat) glPolygonOffset;
    GLenum function(GLenum) glCheckFramebufferStatus;
    void function(GLenum, GLint) glPixelStorei;
    void function(GLuint) glDeleteShader;
}

/// Resolves every entry point; returns the name of the first one missing, or null.
string loadGL()
{
    static foreach (name; names)
    {
        mixin(name ~ " = cast(typeof(" ~ name ~ ")) glfwGetProcAddress(\"" ~ name ~ "\");");
        if (mixin(name) is null) return name;
    }
    return null;
}

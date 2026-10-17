/// Bindings for the subset of Dear ImGui 1.92.9 (through cimgui's C API) and its GLFW/OpenGL3 backends that the
/// desktop viewer uses. Enum values were taken from the compiled headers.
module codecity.gui.imgui;

import codecity.gui.glfw : GLFWwindow;

extern (C) @nogc nothrow:

struct ImVec2
{
    float x = 0, y = 0;
}

struct ImVec4
{
    float x = 0, y = 0, z = 0, w = 0;
}

struct ImGuiContext;
struct ImDrawList;
struct ImDrawData;
struct ImFont;
struct ImFontAtlas;
struct ImFontConfig;
struct ImGuiStyle;
struct ImGuiViewport;

/// Only the leading fields of ImGuiIO (layout verified against the compiled header: IniFilename @32, Fonts @56).
struct ImGuiIO
{
    int ConfigFlags;
    int BackendFlags;
    ImVec2 DisplaySize;
    ImVec2 DisplayFramebufferScale;
    float DeltaTime;
    float IniSavingRate;
    const(char)* IniFilename;
    const(char)* LogFilename;
    void* UserData;
    ImFontAtlas* Fonts;
}

alias ImU32 = uint;

enum ImGuiKey : int
{
    Enter = 525,
    Escape = 526,
    UpArrow = 515,
    DownArrow = 516,
    F = 551,
    N = 559,
    Slash = 600,
}

enum WindowFlags : int
{
    None = 0,
    NoTitleBar = 1,
    NoResize = 2,
    NoMove = 4,
    NoScrollbar = 8,
    NoCollapse = 32,
    AlwaysAutoResize = 64,
    NoSavedSettings = 256,
    HorizontalScrollbar = 2048,
    NoFocusOnAppearing = 4096,
    NoBringToFrontOnFocus = 8192,
    NoNav = 196608,
    NoInputs = 197120,
}

enum Col : int
{
    Text = 0,
    TextDisabled = 1,
    WindowBg = 2,
    ChildBg = 3,
    PopupBg = 4,
    Border = 5,
    FrameBg = 7,
    Button = 22,
    ButtonHovered = 23,
    ButtonActive = 24,
    Header = 25,
    HeaderHovered = 26,
}

enum StyleVar : int
{
    WindowPadding = 2,
    WindowRounding = 3,
    WindowBorderSize = 4,
    FramePadding = 11,
    FrameRounding = 12,
    ItemSpacing = 14,
}

enum Cond : int
{
    Always = 1,
    FirstUseEver = 4,
    Appearing = 8,
}

enum HoveredFlags_AnyWindow = 4;
enum TableFlags_SizingStretchSame = 32768;
enum InputTextFlags_EnterReturnsTrue = 64;

ImGuiContext* igCreateContext(ImFontAtlas* shared_font_atlas);
void igDestroyContext(ImGuiContext*);
ImGuiIO* igGetIO_Nil();
ImGuiStyle* igGetStyle();
void igStyleColorsDark(ImGuiStyle* dst);
void igStyleColorsLight(ImGuiStyle* dst);
void igNewFrame();
void igRender();
ImDrawData* igGetDrawData();

bool igBegin(const(char)* name, bool* p_open, int flags);
void igEnd();
bool igBeginChild_Str(const(char)* str_id, ImVec2 size, int child_flags, int window_flags);
void igEndChild();
void igSetNextWindowPos(ImVec2 pos, int cond, ImVec2 pivot);
void igSetNextWindowSize(ImVec2 size, int cond);
void igSetNextWindowBgAlpha(float alpha);
void igSetNextWindowSizeConstraints(ImVec2 size_min, ImVec2 size_max, void* callback, void* data);
ImVec2 igGetWindowPos();
ImVec2 igGetWindowSize();
float igGetWindowWidth();

void igTextUnformatted(const(char)* text, const(char)* text_end);
void igTextColored(ImVec4 col, const(char)* fmt, ...);
bool igButton(const(char)* label, ImVec2 size);
bool igSmallButton(const(char)* label);
bool igCheckbox(const(char)* label, bool* v);
bool igBeginCombo(const(char)* label, const(char)* preview_value, int flags);
void igEndCombo();
bool igSelectable_Bool(const(char)* label, bool selected, int flags, ImVec2 size);
bool igInputTextWithHint(const(char)* label, const(char)* hint, char* buf, size_t buf_size, int flags, void* callback, void* user_data);
void igBeginDisabled(bool disabled);
void igEndDisabled();
bool igBeginTable(const(char)* str_id, int columns, int flags, ImVec2 outer_size, float inner_width);
void igEndTable();
bool igTableNextColumn();
bool igBeginTooltip();
void igEndTooltip();
void igSetItemTooltip(const(char)* fmt, ...);
void igDummy(ImVec2 size);
void igSameLine(float offset_from_start_x, float spacing);
void igSpacing();
void igBeginGroup();
void igEndGroup();
void igSetNextItemWidth(float width);
void igPushID_Int(int id);
void igPopID();
bool igInvisibleButton(const(char)* str_id, ImVec2 size, int flags);

void igPushStyleColor_Vec4(int idx, ImVec4 col);
void igPopStyleColor(int count);
void igPushStyleVar_Float(int idx, float val);
void igPushStyleVar_Vec2(int idx, ImVec2 val);
void igPopStyleVar(int count);
void igPushFont(ImFont* font, float size);
void igPopFont();

bool igIsWindowHovered(int flags);
bool igIsAnyItemActive();
bool igIsItemHovered(int flags);
bool igIsItemActive();
bool igIsItemDeactivated();
bool igIsKeyPressed_Bool(ImGuiKey key, bool repeat);
void igSetKeyboardFocusHere(int offset);

ImVec2 igGetCursorScreenPos();
ImVec2 igGetContentRegionAvail();
float igGetCursorPosY();
float igGetCursorPosX();
void igSetCursorPosX(float x);
void igSetCursorPosY(float y);
float igGetScrollY();
void igSetScrollY_Float(float y);
float igGetTextLineHeight();
float igGetFrameHeight();
ImVec2 igCalcTextSize(const(char)* text, const(char)* text_end, bool hide_after_hash, float wrap_width);
ImVec2 igGetItemRectMin();
ImVec2 igGetItemRectMax();

ImDrawList* igGetWindowDrawList();
ImDrawList* igGetBackgroundDrawList_Nil();
ImDrawList* igGetForegroundDrawList_Nil();
void ImDrawList_AddRectFilled(ImDrawList* self, ImVec2 p_min, ImVec2 p_max, ImU32 col, float rounding, int flags);
void ImDrawList_AddRect(ImDrawList* self, ImVec2 p_min, ImVec2 p_max, ImU32 col, float rounding, int flags, float thickness);
void ImDrawList_AddText_Vec2(ImDrawList* self, ImVec2 pos, ImU32 col, const(char)* text_begin, const(char)* text_end);

ImFont* ImFontAtlas_AddFontFromMemoryTTF(ImFontAtlas* self, void* data, int size, float size_pixels, const(ImFontConfig)* cfg, const(ushort)* ranges);
ImFontConfig* ImFontConfig_ImFontConfig();

bool ImGui_ImplGlfw_InitForOpenGL(GLFWwindow* window, bool install_callbacks);
void ImGui_ImplGlfw_Shutdown();
void ImGui_ImplGlfw_NewFrame();
bool ImGui_ImplOpenGL3_Init(const(char)* glsl_version);
void ImGui_ImplOpenGL3_Shutdown();
void ImGui_ImplOpenGL3_NewFrame();
void ImGui_ImplOpenGL3_RenderDrawData(ImDrawData* draw_data);

/// 0xAABBGGRR, as ImGui packs colours.
ImU32 rgba(ubyte r, ubyte g, ubyte b, ubyte a = 255) pure
{
    return (cast(uint) a << 24) | (cast(uint) b << 16) | (cast(uint) g << 8) | r;
}

ImVec4 color(ubyte r, ubyte g, ubyte b, ubyte a = 255) pure
{
    return ImVec4(r / 255.0f, g / 255.0f, b / 255.0f, a / 255.0f);
}

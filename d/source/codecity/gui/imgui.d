/// Bindings for the subset of Dear ImGui 1.92.9 (through cimgui's C API) and its GLFW/OpenGL3 backends that the
/// desktop viewer uses. Enum values were taken from the compiled headers.
///
/// cimgui exposes ImGui's C++ API as plain C functions named `ig<Function>` (and `<Type>_<Method>` for methods taking a
/// `self` pointer); overloads get a suffix naming the distinguishing argument (`igPushStyleColor_Vec4`,
/// `igSelectable_Bool`, `igGetIO_Nil` for "no arguments"). `scripts/build-native.sh` compiles Dear ImGui, cimgui and
/// the GLFW / OpenGL3 backends from pinned sources into `libcodecity_gui.a`; the backends are built with
/// `IMGUI_IMPL_API=extern "C"`, which is why `ImGui_ImplGlfw_*` / `ImGui_ImplOpenGL3_*` can be declared `extern (C)`
/// here. Nothing checks these declarations against the headers, so any signature or enum value must be updated by hand
/// when the pinned ImGui version changes.
///
/// ImGui types are opaque (`struct X;`) whenever D only passes pointers to them around. The exceptions are the small
/// by-value types `ImVec2` / `ImVec4` and `ImGuiIO`, whose fields the viewer writes (see there).
module codecity.gui.imgui;

import codecity.gui.glfw : GLFWwindow;

extern (C) @nogc nothrow:

/// Passed and returned by value across the C ABI, so the layout must match exactly (two floats).
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
///
/// The real struct is several kilobytes and changes between ImGui versions. D never allocates or copies one: it only
/// dereferences the pointer `igGetIO_Nil` returns to reach `IniFilename` and `Fonts`. Declaring the prefix up to the
/// last field needed is therefore enough, as long as every field before it is declared with the right size, type and
/// order (offsets: ConfigFlags 0, BackendFlags 4, DisplaySize 8, DisplayFramebufferScale 16, DeltaTime 24,
/// IniSavingRate 28, IniFilename 32, LogFilename 40, UserData 48, Fonts 56 on a 64-bit target). Never use
/// `ImGuiIO.sizeof` or pass an `ImGuiIO` by value.
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

/// The few `ImGuiKey` values the shortcuts need (named keys start at 512 in ImGui's enum).
enum ImGuiKey : int
{
    Enter = 525,
    Escape = 526,
    UpArrow = 515,
    DownArrow = 516,
    Home = 519,
    End = 520,
    F = 551,
    G = 552,
    N = 559,
    P = 561,
    R = 563,
    Comma = 597,
    Period = 599,
    Slash = 600,
}

/// `ImGuiWindowFlags_*` bits. `NoNav` = NoNavInputs | NoNavFocus (1 << 16 | 1 << 17) and `NoInputs` = NoNav |
/// NoMouseInputs (1 << 9), as ImGui defines these combined flags.
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

/// `ImGuiCol_*` indices into the style colour table, for `igPushStyleColor_Vec4` (gaps are colours the viewer never
/// overrides).
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

/// `ImGuiStyleVar_*` indices for `igPushStyleVar_Float` / `_Vec2`; the variant must match the variable's type.
enum StyleVar : int
{
    Alpha = 0,
    WindowPadding = 2,
    WindowRounding = 3,
    WindowBorderSize = 4,
    FramePadding = 11,
    FrameRounding = 12,
    ItemSpacing = 14,
}

/// `ImGuiCond_*`: when an `igSetNextWindow*` call takes effect.
enum Cond : int
{
    Always = 1,
    FirstUseEver = 4,
    Appearing = 8,
}

/// `ImGuiConfigFlags_*` bits for `ImGuiIO.ConfigFlags`: ImGui ignores mouse / keyboard input entirely (walk mode sets
/// both while the cursor is captured, so no window reacts to the hidden cursor or the movement keys).
enum ConfigFlags_NoMouse = 1 << 4;
enum ConfigFlags_NoKeyboard = 1 << 6;
enum HoveredFlags_AnyWindow = 4;
enum TableFlags_SizingStretchSame = 32768;
enum InputTextFlags_EnterReturnsTrue = 64;
enum SliderFlags_NoInput = 1 << 7;

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
bool igButton(const(char)* label, ImVec2 size);
bool igSmallButton(const(char)* label);
bool igCheckbox(const(char)* label, bool* v);
bool igBeginCombo(const(char)* label, const(char)* preview_value, int flags);
void igEndCombo();
bool igSelectable_Bool(const(char)* label, bool selected, int flags, ImVec2 size);
bool igSliderFloat(const(char)* label, float* v, float v_min, float v_max, const(char)* format, int flags);
bool igInputTextWithHint(const(char)* label, const(char)* hint, char* buf, size_t buf_size, int flags, void* callback, void* user_data);
void igBeginDisabled(bool disabled);
void igEndDisabled();
bool igBeginTable(const(char)* str_id, int columns, int flags, ImVec2 outer_size, float inner_width);
void igEndTable();
bool igTableNextColumn();
bool igBeginTooltip();
void igEndTooltip();
/// A printf-style format: call it only through `itemTooltip` in codecity.gui.app, which passes the text as `%s`.
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
void ImDrawList_AddTriangleFilled(ImDrawList* self, ImVec2 p1, ImVec2 p2, ImVec2 p3, ImU32 col);
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

// The two helpers below are D code, but the `extern (C):` label at the top of the module gives them C linkage as
// well; that is harmless because their names do not clash with any C symbol.

/// 0xAABBGGRR, as ImGui packs colours.
ImU32 rgba(ubyte r, ubyte g, ubyte b, ubyte a = 255) pure
{
    return (cast(uint) a << 24) | (cast(uint) b << 16) | (cast(uint) g << 8) | r;
}

/// An 8-bit-per-channel colour as the 0..1 floats `ImVec4` colours use.
ImVec4 color(ubyte r, ubyte g, ubyte b, ubyte a = 255) pure
{
    return ImVec4(r / 255.0f, g / 255.0f, b / 255.0f, a / 255.0f);
}

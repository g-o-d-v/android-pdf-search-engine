#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>

#include <string>
#include <vector>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <mutex>

#define PDFIUM_SEARCH_TAG "PdfiumTextSearch"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, PDFIUM_SEARCH_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, PDFIUM_SEARCH_TAG, __VA_ARGS__)

typedef void* FPDF_DOCUMENT;
typedef void* FPDF_PAGE;
typedef void* FPDF_TEXTPAGE;
typedef int FPDF_BOOL;

static void* g_pdfium_handle = nullptr;
static bool g_pdfium_loaded = false;
static std::mutex g_pdfium_load_mutex;
static std::string g_pdfium_backend_error;
static std::string g_pdfium_library_name;

typedef void (*FN_FPDF_InitLibrary)();
typedef void (*FN_FPDF_DestroyLibrary)();
typedef FPDF_DOCUMENT (*FN_FPDF_LoadDocument)(const char* file_path, const char* password);
typedef void (*FN_FPDF_CloseDocument)(FPDF_DOCUMENT document);
typedef int (*FN_FPDF_GetPageCount)(FPDF_DOCUMENT document);
typedef FPDF_PAGE (*FN_FPDF_LoadPage)(FPDF_DOCUMENT document, int page_index);
typedef void (*FN_FPDF_ClosePage)(FPDF_PAGE page);
typedef FPDF_BOOL (*FN_FPDF_GetPageSizeByIndex)(
        FPDF_DOCUMENT document,
        int page_index,
        double* width,
        double* height
);

typedef FPDF_TEXTPAGE (*FN_FPDFText_LoadPage)(FPDF_PAGE page);
typedef void (*FN_FPDFText_ClosePage)(FPDF_TEXTPAGE text_page);
typedef int (*FN_FPDFText_CountChars)(FPDF_TEXTPAGE text_page);
typedef unsigned int (*FN_FPDFText_GetUnicode)(FPDF_TEXTPAGE text_page, int index);
typedef FPDF_BOOL (*FN_FPDFText_GetCharBox)(
        FPDF_TEXTPAGE text_page,
        int index,
        double* left,
        double* right,
        double* bottom,
        double* top
);

static FN_FPDF_InitLibrary p_FPDF_InitLibrary = nullptr;
static FN_FPDF_DestroyLibrary p_FPDF_DestroyLibrary = nullptr;
static FN_FPDF_LoadDocument p_FPDF_LoadDocument = nullptr;
static FN_FPDF_CloseDocument p_FPDF_CloseDocument = nullptr;
static FN_FPDF_GetPageCount p_FPDF_GetPageCount = nullptr;
static FN_FPDF_LoadPage p_FPDF_LoadPage = nullptr;
static FN_FPDF_ClosePage p_FPDF_ClosePage = nullptr;
static FN_FPDF_GetPageSizeByIndex p_FPDF_GetPageSizeByIndex = nullptr;
static FN_FPDFText_LoadPage p_FPDFText_LoadPage = nullptr;
static FN_FPDFText_ClosePage p_FPDFText_ClosePage = nullptr;
static FN_FPDFText_CountChars p_FPDFText_CountChars = nullptr;
static FN_FPDFText_GetUnicode p_FPDFText_GetUnicode = nullptr;
static FN_FPDFText_GetCharBox p_FPDFText_GetCharBox = nullptr;

static void reset_pdfium_symbols() {
    p_FPDF_InitLibrary = nullptr;
    p_FPDF_DestroyLibrary = nullptr;
    p_FPDF_LoadDocument = nullptr;
    p_FPDF_CloseDocument = nullptr;
    p_FPDF_GetPageCount = nullptr;
    p_FPDF_LoadPage = nullptr;
    p_FPDF_ClosePage = nullptr;
    p_FPDF_GetPageSizeByIndex = nullptr;
    p_FPDFText_LoadPage = nullptr;
    p_FPDFText_ClosePage = nullptr;
    p_FPDFText_CountChars = nullptr;
    p_FPDFText_GetUnicode = nullptr;
    p_FPDFText_GetCharBox = nullptr;
}

static bool open_pdfium_library() {
    if (g_pdfium_handle) return true;

    const char* candidates[] = {
            "libmodpdfium.so",
            "libpdfium.so"
    };
    for (const char* candidate : candidates) {
        dlerror();
        void* handle = dlopen(candidate, RTLD_NOW | RTLD_GLOBAL);
        if (handle) {
            g_pdfium_handle = handle;
            g_pdfium_library_name = candidate;
            LOGI("Opened PDFium backend: %s", candidate);
            return true;
        }
        const char* error = dlerror();
        LOGE("dlopen %s failed: %s", candidate, error ? error : "unknown error");
        if (error) g_pdfium_backend_error = error;
    }
    if (g_pdfium_backend_error.empty()) {
        g_pdfium_backend_error = "PDFium shared library was not found";
    }
    return false;
}

static void* load_symbol(const char* name) {
    // 先查询已经由 PdfiumCore/System.loadLibrary 装入的全局符号。
    dlerror();
    void* symbol = dlsym(RTLD_DEFAULT, name);
    if (symbol) return symbol;

    // Android linker 的局部命名空间下 RTLD_DEFAULT 可能不可见，再用明确句柄查询。
    if (!open_pdfium_library()) return nullptr;
    dlerror();
    symbol = dlsym(g_pdfium_handle, name);
    if (!symbol) {
        const char* error = dlerror();
        g_pdfium_backend_error = std::string("missing PDFium symbol: ") + name;
        if (error) g_pdfium_backend_error += std::string(" (") + error + ")";
        LOGE("%s", g_pdfium_backend_error.c_str());
    }
    return symbol;
}

static bool load_pdfium_symbols() {
    std::lock_guard<std::mutex> lock(g_pdfium_load_mutex);
    if (g_pdfium_loaded) return true;

    g_pdfium_backend_error.clear();
    reset_pdfium_symbols();
    if (!open_pdfium_library()) return false;

    p_FPDF_InitLibrary = reinterpret_cast<FN_FPDF_InitLibrary>(load_symbol("FPDF_InitLibrary"));
    p_FPDF_DestroyLibrary = reinterpret_cast<FN_FPDF_DestroyLibrary>(load_symbol("FPDF_DestroyLibrary"));
    p_FPDF_LoadDocument = reinterpret_cast<FN_FPDF_LoadDocument>(load_symbol("FPDF_LoadDocument"));
    p_FPDF_CloseDocument = reinterpret_cast<FN_FPDF_CloseDocument>(load_symbol("FPDF_CloseDocument"));
    p_FPDF_GetPageCount = reinterpret_cast<FN_FPDF_GetPageCount>(load_symbol("FPDF_GetPageCount"));
    p_FPDF_LoadPage = reinterpret_cast<FN_FPDF_LoadPage>(load_symbol("FPDF_LoadPage"));
    p_FPDF_ClosePage = reinterpret_cast<FN_FPDF_ClosePage>(load_symbol("FPDF_ClosePage"));
    p_FPDF_GetPageSizeByIndex = reinterpret_cast<FN_FPDF_GetPageSizeByIndex>(
            load_symbol("FPDF_GetPageSizeByIndex")
    );
    p_FPDFText_LoadPage = reinterpret_cast<FN_FPDFText_LoadPage>(load_symbol("FPDFText_LoadPage"));
    p_FPDFText_ClosePage = reinterpret_cast<FN_FPDFText_ClosePage>(load_symbol("FPDFText_ClosePage"));
    p_FPDFText_CountChars = reinterpret_cast<FN_FPDFText_CountChars>(load_symbol("FPDFText_CountChars"));
    p_FPDFText_GetUnicode = reinterpret_cast<FN_FPDFText_GetUnicode>(load_symbol("FPDFText_GetUnicode"));
    p_FPDFText_GetCharBox = reinterpret_cast<FN_FPDFText_GetCharBox>(load_symbol("FPDFText_GetCharBox"));

    const bool ok = p_FPDF_InitLibrary
            && p_FPDF_LoadDocument
            && p_FPDF_CloseDocument
            && p_FPDF_GetPageCount
            && p_FPDF_LoadPage
            && p_FPDF_ClosePage
            && p_FPDF_GetPageSizeByIndex
            && p_FPDFText_LoadPage
            && p_FPDFText_ClosePage
            && p_FPDFText_CountChars
            && p_FPDFText_GetUnicode
            && p_FPDFText_GetCharBox;
    if (!ok) {
        if (g_pdfium_backend_error.empty()) {
            g_pdfium_backend_error = "one or more required PDFium text symbols are missing";
        }
        LOGE("PDFium text backend unavailable: %s", g_pdfium_backend_error.c_str());
        reset_pdfium_symbols();
        return false;
    }

    // 本搜索后端维护自己的进程级初始化，且不会主动 Destroy，避免与阅读器引用计数冲突。
    p_FPDF_InitLibrary();
    g_pdfium_loaded = true;
    g_pdfium_backend_error.clear();
    LOGI("PDFium text symbols ready from %s", g_pdfium_library_name.c_str());
    return true;
}

static std::string jstring_to_utf8(JNIEnv* env, jstring jstr) {
    if (!jstr) return "";
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    std::string result = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(jstr, chars);
    return result;
}

static std::u32string jstring_to_u32(JNIEnv* env, jstring jstr) {
    std::u32string out;
    if (!jstr) return out;

    const jsize len = env->GetStringLength(jstr);
    const jchar* chars = env->GetStringChars(jstr, nullptr);
    if (!chars) return out;

    for (jsize i = 0; i < len; i++) {
        uint32_t c = chars[i];
        if (c >= 0xD800 && c <= 0xDBFF && i + 1 < len) {
            const uint32_t low = chars[i + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                out.push_back(0x10000 + ((c - 0xD800) << 10) + (low - 0xDC00));
                i++;
                continue;
            }
        }
        out.push_back(c);
    }
    env->ReleaseStringChars(jstr, chars);
    return out;
}

static uint32_t lower_ascii(uint32_t c) {
    return c >= U'A' && c <= U'Z' ? c + 32 : c;
}

static std::u32string normalize_case(const std::u32string& input, bool ignoreCase) {
    if (!ignoreCase) return input;
    std::u32string out;
    out.reserve(input.size());
    for (uint32_t c : input) out.push_back(lower_ascii(c));
    return out;
}

static bool is_visible_character(uint32_t c) {
    if (c == 0 || c == '\r' || c == '\n' || c == '\t' || c == U' ') return false;
    return !(c < 0x20 || (c >= 0x7F && c <= 0x9F));
}

struct CharInfo {
    uint32_t unicode;
    int charIndex;
};

struct RectD {
    double left = 0;
    double top = 0;
    double right = 0;
    double bottom = 0;
    bool valid = false;
};

struct MatchData {
    int ordinal = 0;
    int matchStart = 0;
    int matchLength = 0;
    std::vector<RectD> rects;
};

struct PageData {
    int pageIndex = -1;
    double pageWidth = 0;
    double pageHeight = 0;
    int status = 0;
    int rawCharCount = 0;
    int visibleCharCount = 0;
    int validBoxCount = 0;
    std::vector<MatchData> matches;
};

struct SearchReportData {
    int pageCount = 0;
    std::vector<PageData> pages;
};

struct IndexCharData {
    uint32_t unicode = 0;
    int sourceCharIndex = -1;
    RectD rect;
};

struct IndexPageData {
    int pageIndex = -1;
    double pageWidth = 0;
    double pageHeight = 0;
    int status = 0;
    int rawCharCount = 0;
    int visibleCharCount = 0;
    int validBoxCount = 0;
    std::vector<IndexCharData> characters;
};

struct IndexReportData {
    int pageCount = 0;
    std::vector<IndexPageData> pages;
};

static RectD get_char_rect(FPDF_TEXTPAGE textPage, int charIndex) {
    RectD r;
    double left = 0;
    double right = 0;
    double bottom = 0;
    double top = 0;
    if (!p_FPDFText_GetCharBox(textPage, charIndex, &left, &right, &bottom, &top)) {
        return r;
    }
    if (right <= left || top <= bottom) return r;
    r.left = left;
    r.right = right;
    r.bottom = bottom;
    r.top = top;
    r.valid = true;
    return r;
}

static void union_rect(RectD& base, const RectD& add) {
    if (!add.valid) return;
    if (!base.valid) {
        base = add;
        return;
    }
    base.left = std::min(base.left, add.left);
    base.right = std::max(base.right, add.right);
    base.bottom = std::min(base.bottom, add.bottom);
    base.top = std::max(base.top, add.top);
}

static void pad_and_clamp_rect(RectD& rect, double pageWidth, double pageHeight) {
    if (!rect.valid) return;
    const double width = std::max(0.0, rect.right - rect.left);
    const double height = std::max(0.0, rect.top - rect.bottom);
    const double padX = std::max(0.25, width * 0.0125);
    const double padY = std::max(0.25, height * 0.06);
    rect.left = std::max(0.0, rect.left - padX);
    rect.right = std::min(pageWidth, rect.right + padX);
    rect.bottom = std::max(0.0, rect.bottom - padY);
    rect.top = std::min(pageHeight, rect.top + padY);
    rect.valid = rect.right > rect.left && rect.top > rect.bottom;
}

static std::vector<RectD> collect_match_rects(
        FPDF_TEXTPAGE textPage,
        const std::vector<CharInfo>& chars,
        int matchStart,
        int matchEnd,
        double pageWidth,
        double pageHeight
) {
    std::vector<RectD> rects;
    RectD current;
    double currentCenterY = 0;
    double currentHeight = 0;

    for (int i = matchStart; i < matchEnd && i < static_cast<int>(chars.size()); i++) {
        RectD r = get_char_rect(textPage, chars[i].charIndex);
        if (!r.valid) continue;

        const double centerY = (r.top + r.bottom) / 2.0;
        const double height = std::max(1.0, r.top - r.bottom);
        if (!current.valid) {
            current = r;
            currentCenterY = centerY;
            currentHeight = height;
            continue;
        }

        const double dy = std::fabs(centerY - currentCenterY);
        const double threshold = std::max(currentHeight, height) * 0.85;
        if (dy > threshold) {
            pad_and_clamp_rect(current, pageWidth, pageHeight);
            if (current.valid) rects.push_back(current);
            current = r;
            currentCenterY = centerY;
            currentHeight = height;
        } else {
            union_rect(current, r);
            currentCenterY = (currentCenterY + centerY) / 2.0;
            currentHeight = std::max(currentHeight, height);
        }
    }

    if (current.valid) {
        pad_and_clamp_rect(current, pageWidth, pageHeight);
        if (current.valid) rects.push_back(current);
    }
    return rects;
}

static SearchReportData search_document(
        const std::string& pdfPath,
        const std::u32string& keyword,
        int requestedStartPage,
        int requestedEndPage,
        bool ignoreCase
) {
    SearchReportData report;
    if (pdfPath.empty() || keyword.empty()) return report;

    FPDF_DOCUMENT document = p_FPDF_LoadDocument(pdfPath.c_str(), nullptr);
    if (!document) {
        LOGE("FPDF_LoadDocument failed: %s", pdfPath.c_str());
        return report;
    }

    report.pageCount = p_FPDF_GetPageCount(document);
    if (report.pageCount <= 0) {
        p_FPDF_CloseDocument(document);
        return report;
    }

    int startPage = std::max(0, requestedStartPage);
    int endPage = requestedEndPage;
    if (endPage < 0 || endPage >= report.pageCount) endPage = report.pageCount - 1;
    if (startPage > endPage) {
        p_FPDF_CloseDocument(document);
        return report;
    }

    const std::u32string normalizedKeyword = normalize_case(keyword, ignoreCase);
    report.pages.reserve(endPage - startPage + 1);

    for (int pageIndex = startPage; pageIndex <= endPage; pageIndex++) {
        PageData pageData;
        pageData.pageIndex = pageIndex;

        if (!p_FPDF_GetPageSizeByIndex(
                document,
                pageIndex,
                &pageData.pageWidth,
                &pageData.pageHeight
        )) {
            pageData.status = 1;
            report.pages.push_back(pageData);
            continue;
        }

        FPDF_PAGE page = p_FPDF_LoadPage(document, pageIndex);
        if (!page) {
            pageData.status = 2;
            report.pages.push_back(pageData);
            continue;
        }

        FPDF_TEXTPAGE textPage = p_FPDFText_LoadPage(page);
        if (!textPage) {
            pageData.status = 3;
            p_FPDF_ClosePage(page);
            report.pages.push_back(pageData);
            continue;
        }

        pageData.rawCharCount = std::max(0, p_FPDFText_CountChars(textPage));
        std::vector<CharInfo> chars;
        chars.reserve(pageData.rawCharCount);
        std::u32string pageText;
        pageText.reserve(pageData.rawCharCount);

        for (int i = 0; i < pageData.rawCharCount; i++) {
            const uint32_t unicode = p_FPDFText_GetUnicode(textPage, i);
            if (unicode == 0 || unicode == '\r' || unicode == '\n') continue;

            const uint32_t normalized = ignoreCase ? lower_ascii(unicode) : unicode;
            pageText.push_back(normalized);
            chars.push_back(CharInfo{normalized, i});

            if (is_visible_character(unicode)) {
                pageData.visibleCharCount++;
                if (get_char_rect(textPage, i).valid) pageData.validBoxCount++;
            }
        }

        if (!pageText.empty() && pageText.size() >= normalizedKeyword.size()) {
            size_t pos = 0;
            int ordinal = 0;
            while (true) {
                const size_t found = pageText.find(normalizedKeyword, pos);
                if (found == std::u32string::npos) break;

                MatchData match;
                match.ordinal = ordinal++;
                match.matchStart = static_cast<int>(found);
                match.matchLength = static_cast<int>(normalizedKeyword.size());
                match.rects = collect_match_rects(
                        textPage,
                        chars,
                        match.matchStart,
                        match.matchStart + match.matchLength,
                        pageData.pageWidth,
                        pageData.pageHeight
                );
                if (!match.rects.empty()) pageData.matches.push_back(match);
                pos = found + std::max<size_t>(1, normalizedKeyword.size());
            }
        }

        p_FPDFText_ClosePage(textPage);
        p_FPDF_ClosePage(page);
        report.pages.push_back(pageData);
    }

    p_FPDF_CloseDocument(document);
    return report;
}

static IndexReportData extract_document_index(
        const std::string& pdfPath,
        int requestedStartPage,
        int requestedEndPage
) {
    IndexReportData report;
    if (pdfPath.empty()) return report;

    FPDF_DOCUMENT document = p_FPDF_LoadDocument(pdfPath.c_str(), nullptr);
    if (!document) {
        LOGE("FPDF_LoadDocument failed for index: %s", pdfPath.c_str());
        return report;
    }

    report.pageCount = p_FPDF_GetPageCount(document);
    if (report.pageCount <= 0) {
        p_FPDF_CloseDocument(document);
        return report;
    }

    int startPage = std::max(0, requestedStartPage);
    int endPage = requestedEndPage;
    if (endPage < 0 || endPage >= report.pageCount) endPage = report.pageCount - 1;
    if (startPage > endPage) {
        p_FPDF_CloseDocument(document);
        return report;
    }

    report.pages.reserve(endPage - startPage + 1);
    for (int pageIndex = startPage; pageIndex <= endPage; pageIndex++) {
        IndexPageData pageData;
        pageData.pageIndex = pageIndex;

        if (!p_FPDF_GetPageSizeByIndex(
                document,
                pageIndex,
                &pageData.pageWidth,
                &pageData.pageHeight
        )) {
            pageData.status = 1;
            report.pages.push_back(pageData);
            continue;
        }

        FPDF_PAGE page = p_FPDF_LoadPage(document, pageIndex);
        if (!page) {
            pageData.status = 2;
            report.pages.push_back(pageData);
            continue;
        }

        FPDF_TEXTPAGE textPage = p_FPDFText_LoadPage(page);
        if (!textPage) {
            pageData.status = 3;
            p_FPDF_ClosePage(page);
            report.pages.push_back(pageData);
            continue;
        }

        pageData.rawCharCount = std::max(0, p_FPDFText_CountChars(textPage));
        pageData.characters.reserve(pageData.rawCharCount);
        for (int i = 0; i < pageData.rawCharCount; i++) {
            const uint32_t unicode = p_FPDFText_GetUnicode(textPage, i);
            if (unicode == 0) continue;

            IndexCharData character;
            character.unicode = unicode;
            character.sourceCharIndex = i;
            character.rect = get_char_rect(textPage, i);
            pageData.characters.push_back(character);

            if (is_visible_character(unicode)) {
                pageData.visibleCharCount++;
                if (character.rect.valid) pageData.validBoxCount++;
            }
        }

        p_FPDFText_ClosePage(textPage);
        p_FPDF_ClosePage(page);
        report.pages.push_back(pageData);
    }

    p_FPDF_CloseDocument(document);
    return report;
}

static void append_i32(std::vector<uint8_t>& out, int32_t value) {
    uint8_t bytes[4];
    std::memcpy(bytes, &value, sizeof(value));
    out.insert(out.end(), bytes, bytes + 4);
}

static void append_f32(std::vector<uint8_t>& out, float value) {
    uint8_t bytes[4];
    std::memcpy(bytes, &value, sizeof(value));
    out.insert(out.end(), bytes, bytes + 4);
}

static jbyteArray vector_to_jbyte_array(JNIEnv* env, const std::vector<uint8_t>& values) {
    jbyteArray array = env->NewByteArray(static_cast<jsize>(values.size()));
    if (!values.empty()) {
        env->SetByteArrayRegion(
                array,
                0,
                static_cast<jsize>(values.size()),
                reinterpret_cast<const jbyte*>(values.data())
        );
    }
    return array;
}

static jfloatArray vector_to_jfloat_array(JNIEnv* env, const std::vector<float>& values) {
    jfloatArray arr = env->NewFloatArray(static_cast<jsize>(values.size()));
    if (!values.empty()) {
        env->SetFloatArrayRegion(arr, 0, static_cast<jsize>(values.size()), values.data());
    }
    return arr;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_nless_pdf_1search_1engine_pdfium_PdfiumTextNative_nativeGetBackendStatus(
        JNIEnv* env,
        jobject
) {
    const bool ready = load_pdfium_symbols();
    std::string status;
    if (ready) {
        status = "READY:" + (g_pdfium_library_name.empty()
                ? std::string("process")
                : g_pdfium_library_name);
    } else {
        status = "ERROR:" + (g_pdfium_backend_error.empty()
                ? std::string("unknown PDFium text backend error")
                : g_pdfium_backend_error);
    }
    return env->NewStringUTF(status.c_str());
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_nless_pdf_1search_1engine_pdfium_PdfiumTextNative_nativeExtractIndex(
        JNIEnv* env,
        jobject,
        jstring j_pdf_path,
        jint j_start_page,
        jint j_end_page
) {
    constexpr int32_t kMagic = 0x50495835; // PIX5
    constexpr int32_t kVersion = 1;
    if (!load_pdfium_symbols()) return env->NewByteArray(0);

    const IndexReportData report = extract_document_index(
            jstring_to_utf8(env, j_pdf_path),
            static_cast<int>(j_start_page),
            static_cast<int>(j_end_page)
    );

    std::vector<uint8_t> out;
    size_t characterCount = 0;
    for (const IndexPageData& page : report.pages) {
        characterCount += page.characters.size();
    }
    out.reserve(16 + report.pages.size() * 32 + characterCount * 32);
    append_i32(out, kMagic);
    append_i32(out, kVersion);
    append_i32(out, static_cast<int32_t>(report.pageCount));
    append_i32(out, static_cast<int32_t>(report.pages.size()));

    for (const IndexPageData& page : report.pages) {
        append_i32(out, static_cast<int32_t>(page.pageIndex));
        append_f32(out, static_cast<float>(page.pageWidth));
        append_f32(out, static_cast<float>(page.pageHeight));
        append_i32(out, static_cast<int32_t>(page.status));
        append_i32(out, static_cast<int32_t>(page.rawCharCount));
        append_i32(out, static_cast<int32_t>(page.visibleCharCount));
        append_i32(out, static_cast<int32_t>(page.validBoxCount));
        append_i32(out, static_cast<int32_t>(page.characters.size()));

        for (const IndexCharData& character : page.characters) {
            append_i32(out, static_cast<int32_t>(character.unicode));
            append_i32(out, static_cast<int32_t>(character.sourceCharIndex));
            append_i32(out, character.rect.valid ? 1 : 0);
            append_i32(out, 0);
            append_f32(out, static_cast<float>(character.rect.left));
            append_f32(out, static_cast<float>(character.rect.top));
            append_f32(out, static_cast<float>(character.rect.right));
            append_f32(out, static_cast<float>(character.rect.bottom));
        }
    }
    return vector_to_jbyte_array(env, out);
}

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_nless_pdf_1search_1engine_pdfium_PdfiumTextNative_nativeSearchDetailed(
        JNIEnv* env,
        jobject,
        jstring j_pdf_path,
        jstring j_keyword,
        jint j_start_page,
        jint j_end_page,
        jboolean j_ignore_case
) {
    constexpr float kMagic = -41001.f;
    constexpr float kVersion = 1.f;

    if (!load_pdfium_symbols()) return env->NewFloatArray(0);
    const SearchReportData report = search_document(
            jstring_to_utf8(env, j_pdf_path),
            jstring_to_u32(env, j_keyword),
            static_cast<int>(j_start_page),
            static_cast<int>(j_end_page),
            j_ignore_case == JNI_TRUE
    );

    std::vector<float> out;
    out.reserve(16 + report.pages.size() * 12);
    out.push_back(kMagic);
    out.push_back(kVersion);
    out.push_back(static_cast<float>(report.pageCount));
    out.push_back(static_cast<float>(report.pages.size()));

    for (const PageData& page : report.pages) {
        out.push_back(static_cast<float>(page.pageIndex));
        out.push_back(static_cast<float>(page.pageWidth));
        out.push_back(static_cast<float>(page.pageHeight));
        out.push_back(static_cast<float>(page.status));
        out.push_back(static_cast<float>(page.rawCharCount));
        out.push_back(static_cast<float>(page.visibleCharCount));
        out.push_back(static_cast<float>(page.validBoxCount));
        out.push_back(static_cast<float>(page.matches.size()));

        for (const MatchData& match : page.matches) {
            out.push_back(static_cast<float>(match.ordinal));
            out.push_back(static_cast<float>(match.matchStart));
            out.push_back(static_cast<float>(match.matchLength));
            out.push_back(static_cast<float>(match.rects.size()));
            for (const RectD& rect : match.rects) {
                out.push_back(static_cast<float>(rect.left));
                out.push_back(static_cast<float>(rect.top));
                out.push_back(static_cast<float>(rect.right));
                out.push_back(static_cast<float>(rect.bottom));
            }
        }
    }
    return vector_to_jfloat_array(env, out);
}

/**
 * 兼容旧版扁平协议：每 7 个 float 为一个矩形。
 * 新代码请使用 nativeSearchDetailed，以保留跨行命中的逻辑分组和页面文本层统计。
 */
extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_nless_pdf_1search_1engine_pdfium_PdfiumTextNative_nativeSearch(
        JNIEnv* env,
        jobject,
        jstring j_pdf_path,
        jstring j_keyword,
        jint j_start_page,
        jint j_end_page
) {
    if (!load_pdfium_symbols()) return env->NewFloatArray(0);
    const SearchReportData report = search_document(
            jstring_to_utf8(env, j_pdf_path),
            jstring_to_u32(env, j_keyword),
            static_cast<int>(j_start_page),
            static_cast<int>(j_end_page),
            true
    );

    std::vector<float> out;
    for (const PageData& page : report.pages) {
        for (const MatchData& match : page.matches) {
            for (const RectD& rect : match.rects) {
                out.push_back(static_cast<float>(page.pageIndex));
                out.push_back(static_cast<float>(rect.left));
                out.push_back(static_cast<float>(rect.top));
                out.push_back(static_cast<float>(rect.right));
                out.push_back(static_cast<float>(rect.bottom));
                out.push_back(static_cast<float>(page.pageWidth));
                out.push_back(static_cast<float>(page.pageHeight));
            }
        }
    }
    return vector_to_jfloat_array(env, out);
}

#include "native_enhancer.h"

static std::mutex g_model_mutex;
static jobject g_asset_manager_ref = nullptr;
static AAssetManager* g_asset_manager = nullptr;

#if USE_NCNN
static ncnn::Net* g_sr_net = nullptr;      // realesr-general-x4v3 (4x SR)
static ncnn::Net* g_wdn_net = nullptr;     // realesr-general-wdn-x4v3 (Denoise 4x SR)
static ncnn::Net* g_scrfd_net = nullptr;   // scrfd_500m_kps-opt2 (Face & 5-Point Landmarks)
static ncnn::Net* g_anime_net = nullptr;   // face_paint_512_v2 (AnimeGANv2)
static ncnn::Net* g_matting_net = nullptr; // modnet_matting (Portrait Hair Alpha Matting)
static ncnn::Net* g_depth_net = nullptr;   // dpt256 (Monocular Depth Map)
#endif

// ---------------------------------------------------------------------------
// Exact IEC 61966-2-1 sRGB <-> Linear Light LUTs (Initialized once)
// ---------------------------------------------------------------------------

static float g_srgb_to_lin[256];
static uint8_t g_lin_to_srgb[4096];
static bool g_luts_ready = false;

static void initColorLuts() {
    if (g_luts_ready) return;
    for (int i = 0; i < 256; ++i) {
        float c = i / 255.0f;
        g_srgb_to_lin[i] = (c <= 0.04045f)
            ? (c / 12.92f)
            : std::pow((c + 0.055f) / 1.055f, 2.4f);
    }
    for (int i = 0; i < 4096; ++i) {
        float l = i / 4095.0f;
        float s = (l <= 0.0031308f)
            ? (12.92f * l)
            : (1.055f * std::pow(l, 1.0f / 2.4f) - 0.055f);
        int v = static_cast<int>(std::clamp(s * 255.0f + 0.5f, 0.0f, 255.0f));
        g_lin_to_srgb[i] = static_cast<uint8_t>(v);
    }
    g_luts_ready = true;
}

static inline float srgb8ToLin(uint8_t v) {
    return g_srgb_to_lin[v];
}

static inline uint8_t linToSrgb8(float l) {
    int idx = static_cast<int>(std::clamp(l, 0.0f, 1.0f) * 4095.0f + 0.5f);
    return g_lin_to_srgb[idx];
}

static inline float linToSrgbF(float l) {
    l = std::clamp(l, 0.0f, 1.0f);
    return (l <= 0.0031308f) ? (12.92f * l) : (1.055f * std::pow(l, 1.0f / 2.4f) - 0.055f);
}

static inline float srgbFToLin(float s) {
    s = std::clamp(s, 0.0f, 1.0f);
    return (s <= 0.04045f) ? (s / 12.92f) : std::pow((s + 0.055f) / 1.055f, 2.4f);
}

static inline uint8_t clamp8(float v) {
    if (v <= 0.0f) return 0;
    if (v >= 255.0f) return 255;
    return static_cast<uint8_t>(v + 0.5f);
}

static inline float smoothstep(float edge0, float edge1, float x) {
    float t = std::clamp((x - edge0) / std::max(1e-5f, edge1 - edge0), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

// Smooth filmic highlight shoulder roll-off in linear light (prevents harsh clipping above 0.85)
static inline float softHighlightRolloff(float x) {
    if (x <= 0.85f) return std::max(0.0f, x);
    float excess = x - 0.85f;
    return 0.85f + 0.15f * (1.0f - std::exp(-excess / 0.15f));
}

// RGB (0..1) <-> HSV (h in 0..360, s in 0..1, v in 0..1)
static inline void rgbToHsv(float r, float g, float b, float& h, float& s, float& v) {
    float mx = std::max(r, std::max(g, b));
    float mn = std::min(r, std::min(g, b));
    float d = mx - mn;
    v = mx;
    s = (mx > 1e-5f) ? (d / mx) : 0.0f;
    if (d <= 1e-5f) {
        h = 0.0f;
    } else if (mx == r) {
        h = 60.0f * std::fmod(((g - b) / d + 6.0f), 6.0f);
    } else if (mx == g) {
        h = 60.0f * (((b - r) / d) + 2.0f);
    } else {
        h = 60.0f * (((r - g) / d) + 4.0f);
    }
}

static inline void hsvToRgb(float h, float s, float v, float& r, float& g, float& b) {
    h = std::fmod(h + 360.0f, 360.0f);
    float c = v * s;
    float x = c * (1.0f - std::fabs(std::fmod(h / 60.0f, 2.0f) - 1.0f));
    float m = v - c;
    float r1 = 0.0f, g1 = 0.0f, b1 = 0.0f;
    if (h < 60.0f)       { r1 = c; g1 = x; b1 = 0.0f; }
    else if (h < 120.0f) { r1 = x; g1 = c; b1 = 0.0f; }
    else if (h < 180.0f) { r1 = 0.0f; g1 = c; b1 = x; }
    else if (h < 240.0f) { r1 = 0.0f; g1 = x; b1 = c; }
    else if (h < 300.0f) { r1 = x; g1 = 0.0f; b1 = c; }
    else                 { r1 = c; g1 = 0.0f; b1 = x; }
    r = r1 + m; g = g1 + m; b = b1 + m;
}

// ---------------------------------------------------------------------------
// Lazy NCNN Model Loader (Low-RAM Safe: loads on demand, evicts heavy models)
// ---------------------------------------------------------------------------

#if USE_NCNN
static bool loadNcnnNet(ncnn::Net*& netPtr, const char* paramAsset, const char* binAsset) {
    if (netPtr) return true;
    if (!g_asset_manager) return false;

    AAsset* probe = AAssetManager_open(g_asset_manager, paramAsset, AASSET_MODE_BUFFER);
    if (!probe) {
        LOGW("NCNN model asset missing: %s", paramAsset);
        return false;
    }
    AAsset_close(probe);

    auto* net = new ncnn::Net();
    net->opt.use_vulkan_compute = false; // Universally stable across all Android GPUs
    net->opt.use_fp16_packed = true;
    net->opt.use_fp16_storage = true;
    net->opt.use_fp16_arithmetic = false;
    net->opt.num_threads = std::min(4, std::max(2, ncnn::get_big_cpu_count()));

    int retP = net->load_param(g_asset_manager, paramAsset);
    int retM = net->load_model(g_asset_manager, binAsset);
    if (retP == 0 && retM == 0) {
        netPtr = net;
        LOGI("Lazy-loaded NCNN model: %s", paramAsset);
        return true;
    }
    LOGE("Failed to load NCNN model %s (param=%d, bin=%d)", paramAsset, retP, retM);
    delete net;
    return false;
}

static void evictHeavyModelsExcept(const char* keep) {
    if (std::strcmp(keep, "depth") != 0 && g_depth_net) {
        delete g_depth_net; g_depth_net = nullptr;
    }
    if (std::strcmp(keep, "matting") != 0 && g_matting_net) {
        delete g_matting_net; g_matting_net = nullptr;
    }
    if (std::strcmp(keep, "anime") != 0 && g_anime_net) {
        delete g_anime_net; g_anime_net = nullptr;
    }
}

static ncnn::Net* ensureSrNet() {
    loadNcnnNet(g_sr_net, "models/realesr-general-x4v3.param", "models/realesr-general-x4v3.bin");
    return g_sr_net;
}
static ncnn::Net* ensureWdnNet() {
    loadNcnnNet(g_wdn_net, "models/realesr-general-wdn-x4v3.param", "models/realesr-general-wdn-x4v3.bin");
    return g_wdn_net;
}
static ncnn::Net* ensureScrfdNet() {
    loadNcnnNet(g_scrfd_net, "models/scrfd_500m_kps-opt2.param", "models/scrfd_500m_kps-opt2.bin");
    return g_scrfd_net;
}
static ncnn::Net* ensureAnimeNet() {
    evictHeavyModelsExcept("anime");
    loadNcnnNet(g_anime_net, "models/face_paint_512_v2.param", "models/face_paint_512_v2.bin");
    return g_anime_net;
}
static ncnn::Net* ensureMattingNet() {
    evictHeavyModelsExcept("matting");
    loadNcnnNet(g_matting_net, "models/modnet_matting.param", "models/modnet_matting.bin");
    return g_matting_net;
}
static ncnn::Net* ensureDepthNet() {
    evictHeavyModelsExcept("depth");
    loadNcnnNet(g_depth_net, "models/dpt256.param", "models/dpt256.bin");
    return g_depth_net;
}

static void releaseAllNcnnNetsLocked() {
    delete g_sr_net;      g_sr_net = nullptr;
    delete g_wdn_net;     g_wdn_net = nullptr;
    delete g_scrfd_net;   g_scrfd_net = nullptr;
    delete g_anime_net;   g_anime_net = nullptr;
    delete g_matting_net; g_matting_net = nullptr;
    delete g_depth_net;   g_depth_net = nullptr;
}
#endif

// ---------------------------------------------------------------------------
// O(N) Fast Guided Image Filter (He et al.) & Separable 2D EDT
// ---------------------------------------------------------------------------

static void boxFilterFloat(const float* src, float* dst, int width, int height, int radius) {
    int r = std::clamp(radius, 1, std::max(1, std::min(width, height) / 2));
    std::vector<float> temp(width * height, 0.0f);

    #pragma omp parallel for schedule(static)
    for (int y = 0; y < height; ++y) {
        float sum = 0.0f;
        int count = 0;
        const float* rowIn = src + y * width;
        float* rowOut = temp.data() + y * width;
        for (int x = 0; x <= r && x < width; ++x) {
            sum += rowIn[x];
            count++;
        }
        for (int x = 0; x < width; ++x) {
            rowOut[x] = sum / std::max(1, count);
            int removeIdx = x - r;
            int addIdx = x + r + 1;
            if (removeIdx >= 0) { sum -= rowIn[removeIdx]; count--; }
            if (addIdx < width) { sum += rowIn[addIdx]; count++; }
        }
    }

    #pragma omp parallel for schedule(static)
    for (int x = 0; x < width; ++x) {
        float sum = 0.0f;
        int count = 0;
        for (int y = 0; y <= r && y < height; ++y) {
            sum += temp[y * width + x];
            count++;
        }
        for (int y = 0; y < height; ++y) {
            dst[y * width + x] = sum / std::max(1, count);
            int removeIdx = y - r;
            int addIdx = y + r + 1;
            if (removeIdx >= 0) { sum -= temp[removeIdx * width + x]; count--; }
            if (addIdx < height) { sum += temp[addIdx * width + x]; count++; }
        }
    }
}

static void guidedFilter1Ch(const float* guide, const float* p, float* out, int width, int height, int radius, float eps) {
    int total = width * height;
    std::vector<float> meanI(total), meanP(total), corrI(total), corrIP(total);
    std::vector<float> prodI(total), prodIP(total);

    #pragma omp parallel for schedule(static)
    for (int i = 0; i < total; ++i) {
        prodI[i] = guide[i] * guide[i];
        prodIP[i] = guide[i] * p[i];
    }

    boxFilterFloat(guide, meanI.data(), width, height, radius);
    boxFilterFloat(p, meanP.data(), width, height, radius);
    boxFilterFloat(prodI.data(), corrI.data(), width, height, radius);
    boxFilterFloat(prodIP.data(), corrIP.data(), width, height, radius);

    std::vector<float> a(total), b(total);
    #pragma omp parallel for schedule(static)
    for (int i = 0; i < total; ++i) {
        float varI = std::max(0.0f, corrI[i] - meanI[i] * meanI[i]);
        float covIP = corrIP[i] - meanI[i] * meanP[i];
        a[i] = covIP / (varI + eps);
        b[i] = meanP[i] - a[i] * meanI[i];
    }

    std::vector<float> meanA(total), meanB(total);
    boxFilterFloat(a.data(), meanA.data(), width, height, radius);
    boxFilterFloat(b.data(), meanB.data(), width, height, radius);

    #pragma omp parallel for schedule(static)
    for (int i = 0; i < total; ++i) {
        out[i] = meanA[i] * guide[i] + meanB[i];
    }
}

static void computeSeparableEDT(const uint8_t* binaryMask, float* distOut, int width, int height) {
    const float INF = 1e9f;
    int total = width * height;
    std::vector<float> d1d(total, INF);

    for (int y = 0; y < height; ++y) {
        float lastDist = INF;
        for (int x = 0; x < width; ++x) {
            if (binaryMask[y * width + x]) lastDist = 0.0f;
            else if (lastDist < INF) lastDist += 1.0f;
            d1d[y * width + x] = lastDist * lastDist;
        }
        lastDist = INF;
        for (int x = width - 1; x >= 0; --x) {
            if (binaryMask[y * width + x]) lastDist = 0.0f;
            else if (lastDist < INF) lastDist += 1.0f;
            float dSq = lastDist * lastDist;
            if (dSq < d1d[y * width + x]) d1d[y * width + x] = dSq;
        }
    }

    for (int x = 0; x < width; ++x) {
        std::vector<int> v(height);
        std::vector<float> z(height + 1);
        int k = 0;
        v[0] = 0;
        z[0] = -INF;
        z[1] = +INF;

        for (int q = 1; q < height; ++q) {
            float fq = d1d[q * width + x];
            float s = 0.0f;
            while (k >= 0) {
                int vk = v[k];
                float fvk = d1d[vk * width + x];
                s = ((fq + q * q) - (fvk + vk * vk)) / (2.0f * q - 2.0f * vk);
                if (s > z[k]) break;
                k--;
            }
            k++;
            v[k] = q;
            z[k] = s;
            z[k + 1] = +INF;
        }

        k = 0;
        for (int q = 0; q < height; ++q) {
            while (z[k + 1] < q) k++;
            int vk = v[k];
            float dy = static_cast<float>(q - vk);
            float dSq = dy * dy + d1d[vk * width + x];
            distOut[q * width + x] = std::sqrt(std::max(0.0f, dSq));
        }
    }
}

// ---------------------------------------------------------------------------
// Linear-Light Color, Lighting, HSL, Preset & Film Pipeline (v3.0)
// ---------------------------------------------------------------------------

// Apply curated photographic look in perceptual sRGB [0..1]
static inline void applyPresetLook(int presetId, float& r, float& g, float& b) {
    float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
    switch (presetId) {
        case 1: { // Alami (crisp clean micro-contrast & gentle vibrancy)
            float sc = smoothstep(0.0f, 1.0f, lum);
            float ratio = (lum > 1e-4f) ? ((lum * 0.84f + sc * 0.16f) / lum) : 1.0f;
            r *= ratio; g *= ratio; b *= ratio;
            float l2 = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            r = l2 + (r - l2) * 1.12f;
            g = l2 + (g - l2) * 1.12f;
            b = l2 + (b - l2) * 1.10f;
            break;
        }
        case 2: { // Film Hangat (Kodak Gold-inspired warm highlights, creamy shadows)
            r = std::pow(std::clamp(r, 0.0f, 1.0f), 0.94f) * 1.04f + 0.015f;
            g = std::pow(std::clamp(g, 0.0f, 1.0f), 0.98f) * 1.01f + 0.012f;
            b = std::pow(std::clamp(b, 0.0f, 1.0f), 1.06f) * 0.93f + 0.025f;
            break;
        }
        case 3: { // Sejuk (Fuji-inspired crisp cyan shadows & soft greens)
            r = std::pow(std::clamp(r, 0.0f, 1.0f), 1.03f) * 0.97f + 0.008f;
            g = std::pow(std::clamp(g, 0.0f, 1.0f), 0.97f) * 1.02f + 0.014f;
            b = std::pow(std::clamp(b, 0.0f, 1.0f), 0.95f) * 1.04f + 0.020f;
            break;
        }
        case 4: { // Senja (Golden Hour amber glow)
            r = r * 1.08f + 0.02f * (1.0f - r);
            g = g * 1.01f + 0.01f * (1.0f - g);
            b = b * 0.88f + 0.015f * (1.0f - lum);
            break;
        }
        case 5: { // Potret Lembut (skin-friendly soft contrast, lifted shadows)
            r = 0.03f + r * 0.95f;
            g = 0.025f + g * 0.94f;
            b = 0.028f + b * 0.92f;
            float l2 = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            r = l2 + (r - l2) * 0.94f;
            g = l2 + (g - l2) * 0.94f;
            b = l2 + (b - l2) * 0.92f;
            break;
        }
        case 6: { // Moody (deep shadows, muted highlights, cool-teal tint)
            float sc = smoothstep(0.0f, 1.0f, lum);
            float nl = lum * 0.65f + sc * 0.35f;
            float ratio = (lum > 1e-4f) ? (nl / lum) : 1.0f;
            r = r * ratio * 0.95f;
            g = g * ratio * 0.99f;
            b = b * ratio * 1.03f + 0.02f * (1.0f - lum);
            float l2 = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            r = l2 + (r - l2) * 0.76f;
            g = l2 + (g - l2) * 0.78f;
            b = l2 + (b - l2) * 0.84f;
            break;
        }
        case 7: { // Cerah Pop (vibrant social feed look)
            float sc = smoothstep(0.0f, 1.0f, lum);
            float nl = lum * 0.78f + sc * 0.22f;
            float ratio = (lum > 1e-4f) ? (nl / lum) : 1.0f;
            r *= ratio; g *= ratio; b *= ratio;
            float l2 = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            r = l2 + (r - l2) * 1.26f;
            g = l2 + (g - l2) * 1.24f;
            b = l2 + (b - l2) * 1.28f;
            break;
        }
        case 8: { // Matte (lifted blacks, soft pastel highlights)
            r = 0.065f + r * 0.88f;
            g = 0.062f + g * 0.88f;
            b = 0.072f + b * 0.86f;
            float l2 = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            r = l2 + (r - l2) * 0.88f;
            g = l2 + (g - l2) * 0.88f;
            b = l2 + (b - l2) * 0.86f;
            break;
        }
        case 9: { // Monokrom Klasik (balanced B&W with subtle print curve)
            float bw = 0.28f * r + 0.60f * g + 0.12f * b;
            bw = bw * 0.75f + smoothstep(0.0f, 1.0f, bw) * 0.25f;
            r = bw; g = bw; b = bw;
            break;
        }
        case 10: { // Monokrom Dramatis (high-contrast silver gelatin look)
            float bw = 0.34f * r + 0.56f * g + 0.10f * b;
            float sc = smoothstep(0.04f, 0.96f, bw);
            bw = bw * 0.35f + sc * 0.65f;
            r = bw; g = bw; b = bw;
            break;
        }
        case 11: { // Retro CCD (2000s compact digicam split tone)
            r = 0.045f + r * 0.91f + 0.02f;
            g = 0.040f + g * 0.90f + 0.008f;
            b = 0.060f + b * 0.85f;
            break;
        }
        case 12: { // Sinema (teal shadows, warm orange skin/highlights)
            float hi = smoothstep(0.35f, 0.85f, lum);
            float sh = 1.0f - hi;
            r = r + 0.045f * hi - 0.025f * sh;
            g = g + 0.012f * hi + 0.010f * sh;
            b = b - 0.035f * hi + 0.040f * sh;
            break;
        }
        default:
            break;
    }
}

void applyLinearColorPipeline(const uint32_t* src, uint32_t* dst, int width, int height, const float* params) {
    if (!src || !dst || !params || width <= 0 || height <= 0) return;
    initColorLuts();

    const int total = width * height;
    const float exposure   = std::clamp(params[0], -2.5f, 2.5f);
    const float contrast   = std::clamp(params[1], -1.0f, 1.0f);
    const float highlights = std::clamp(params[2], -1.0f, 1.0f);
    const float shadows    = std::clamp(params[3], -1.0f, 1.0f);
    const float whites     = std::clamp(params[4], -1.0f, 1.0f);
    const float blacks     = std::clamp(params[5], -1.0f, 1.0f);
    const float temp       = std::clamp(params[6], -1.0f, 1.0f);
    const float tint       = std::clamp(params[7], -1.0f, 1.0f);
    const float vibrance   = std::clamp(params[8], -1.0f, 1.0f);
    const float saturation = std::clamp(params[9], -1.0f, 1.0f);
    const float clarity    = std::clamp(params[10], -1.0f, 1.0f);
    const float sharpen    = std::clamp(params[11], 0.0f, 1.0f);
    const float fade       = std::clamp(params[12], 0.0f, 1.0f);
    const float vignette   = std::clamp(params[13], -1.0f, 1.0f);
    const float grain      = std::clamp(params[14], 0.0f, 1.0f);
    const float halation   = std::clamp(params[15], 0.0f, 1.0f);
    const int presetId     = static_cast<int>(params[16] + 0.5f);
    const float presetStr  = std::clamp(params[17], 0.0f, 1.0f);

    bool hasHsl = false;
    for (int i = 18; i < 42; ++i) {
        if (std::fabs(params[i]) > 0.005f) { hasHsl = true; break; }
    }

    // Physical linear exposure multiplier & chromatic white-balance gain
    const float expGain = std::pow(2.0f, exposure);
    float wbR = std::pow(2.0f, temp * 0.35f + tint * 0.14f);
    float wbG = std::pow(2.0f, -tint * 0.22f);
    float wbB = std::pow(2.0f, -temp * 0.38f + tint * 0.14f);
    float wbNorm = 1.0f / (0.2126f * wbR + 0.7152f * wbG + 0.0722f * wbB);
    wbR *= wbNorm; wbG *= wbNorm; wbB *= wbNorm;

    // Optional edge-aware guided base luminance for halo-free Highlights, Shadows, and Clarity
    const bool needLocalTone = (std::fabs(highlights) > 0.005f) ||
                               (std::fabs(shadows) > 0.005f) ||
                               (std::fabs(clarity) > 0.005f);
    std::vector<float> baseLum;
    std::vector<float> origLum;
    if (needLocalTone) {
        origLum.resize(total);
        baseLum.resize(total);
        #pragma omp parallel for schedule(static)
        for (int i = 0; i < total; ++i) {
            uint32_t p = src[i];
            float r = srgb8ToLin((p >> 16) & 0xFF) * expGain * wbR;
            float g = srgb8ToLin((p >> 8) & 0xFF) * expGain * wbG;
            float b = srgb8ToLin(p & 0xFF) * expGain * wbB;
            float y = std::max(0.0f, 0.2126f * r + 0.7152f * g + 0.0722f * b);
            // Perceptual [0..1] tone representation for guided decomposition
            origLum[i] = linToSrgbF(softHighlightRolloff(y));
        }
        int guideR = std::clamp(std::min(width, height) / 36, 4, 24);
        guidedFilter1Ch(origLum.data(), origLum.data(), baseLum.data(), width, height, guideR, 0.015f);
    }

    // Optional Linear-Light Red-Orange Halation Bloom
    std::vector<float> halR, halG;
    if (halation > 0.005f) {
        std::vector<float> rawHR(total, 0.0f), rawHG(total, 0.0f);
        #pragma omp parallel for schedule(static)
        for (int i = 0; i < total; ++i) {
            uint32_t p = src[i];
            float r = srgb8ToLin((p >> 16) & 0xFF) * expGain;
            float g = srgb8ToLin((p >> 8) & 0xFF) * expGain;
            float b = srgb8ToLin(p & 0xFF) * expGain;
            float y = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            if (y > 0.55f) {
                float ex = (y - 0.55f) / 0.45f;
                float glow = ex * ex;
                rawHR[i] = glow * 0.95f;
                rawHG[i] = glow * 0.24f;
            }
        }
        halR.resize(total);
        halG.resize(total);
        int hRad = std::clamp(std::min(width, height) / 60, 4, 18);
        boxFilterFloat(rawHR.data(), halR.data(), width, height, hRad);
        boxFilterFloat(rawHG.data(), halG.data(), width, height, hRad);
        // Second box pass approximates smooth Gaussian
        boxFilterFloat(halR.data(), rawHR.data(), width, height, hRad);
        boxFilterFloat(halG.data(), rawHG.data(), width, height, hRad);
        halR.swap(rawHR);
        halG.swap(rawHG);
    }

    // 8 HSL band centers in degrees: Red, Orange, Yellow, Green, Aqua, Blue, Purple, Magenta
    static const float bandCenters[8] = {0.0f, 30.0f, 60.0f, 120.0f, 180.0f, 225.0f, 275.0f, 320.0f};
    static const float bandWidths[8]  = {30.0f, 30.0f, 45.0f, 60.0f, 50.0f, 48.0f, 48.0f, 42.0f};
    const float PI = 3.14159265358979323846f;

    #pragma omp parallel for schedule(static)
    for (int y = 0; y < height; ++y) {
        float ny = (static_cast<float>(y) / height) - 0.5f;
        for (int x = 0; x < width; ++x) {
            int i = y * width + x;
            uint32_t orig = src[i];
            uint32_t a = (orig >> 24) & 0xFF;

            // 1. Decode sRGB -> Linear Light + Exposure + Chromatic White Balance
            float rL = srgb8ToLin((orig >> 16) & 0xFF) * expGain * wbR;
            float gL = srgb8ToLin((orig >> 8) & 0xFF) * expGain * wbG;
            float bL = srgb8ToLin(orig & 0xFF) * expGain * wbB;

            // 2. Linear-light Halation addition
            if (halation > 0.005f) {
                rL += halR[i] * halation * 0.75f;
                gL += halG[i] * halation * 0.55f;
            }

            // 3. Edge-Aware Local Tone Mapping (Highlights, Shadows, Clarity) in Linear Light
            if (needLocalTone) {
                float bY = std::clamp(baseLum[i], 0.0f, 1.0f);
                float detail = origLum[i] - bY;

                // Smooth highlight & shadow masks driven by edge-aware base luminance
                float shMask = (1.0f - bY) * (1.0f - bY);
                float hiMask = bY * bY;

                float toneStops = shadows * shMask * 1.15f + highlights * hiMask * 0.95f;
                // Midtone clarity boosts local detail without clipping extremes
                float midW = 4.0f * bY * (1.0f - bY);
                toneStops += clarity * detail * midW * 2.4f;

                float localGain = std::pow(2.0f, std::clamp(toneStops, -2.5f, 2.5f));
                rL *= localGain;
                gL *= localGain;
                bL *= localGain;
            }

            // 4. Filmic Highlight Roll-off & Linear -> Perceptual sRGB [0..1]
            float r = linToSrgbF(softHighlightRolloff(rL));
            float g = linToSrgbF(softHighlightRolloff(gL));
            float b = linToSrgbF(softHighlightRolloff(bL));

            // 5. Blacks, Whites & Smooth S-Curve Contrast (luminance-ratio preserving to avoid hue shifts)
            if (std::fabs(blacks) > 0.005f || std::fabs(whites) > 0.005f || std::fabs(contrast) > 0.005f) {
                float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                float newLum = lum;

                // Blacks (toe) & Whites (shoulder)
                float toe = (1.0f - newLum) * (1.0f - newLum) * (1.0f - newLum);
                float shoulder = newLum * newLum * newLum;
                newLum += blacks * 0.16f * toe + whites * 0.16f * shoulder;
                newLum = std::clamp(newLum, 0.0f, 1.0f);

                // Smooth S-curve contrast around mid-grey
                if (std::fabs(contrast) > 0.005f) {
                    float sCurve = newLum * newLum * (3.0f - 2.0f * newLum);
                    if (contrast > 0.0f) {
                        newLum = newLum + (sCurve - newLum) * (contrast * 0.85f);
                    } else {
                        // Reduce contrast smoothly toward 0.5
                        newLum = newLum + (0.5f - newLum) * (-contrast * 0.45f);
                    }
                }

                float scale = (lum > 1e-4f) ? (std::clamp(newLum, 0.0f, 1.0f) / lum) : 1.0f;
                // Blend ratio with additive toe lift for deep blacks
                if (lum <= 1e-4f && newLum > 0.0f) {
                    r = newLum; g = newLum; b = newLum;
                } else {
                    r = std::clamp(r * scale, 0.0f, 1.0f);
                    g = std::clamp(g * scale, 0.0f, 1.0f);
                    b = std::clamp(b * scale, 0.0f, 1.0f);
                }
            }

            // 6. Vibrance (skin-protecting smart saturation) & Saturation
            if (std::fabs(vibrance) > 0.005f || std::fabs(saturation) > 0.005f) {
                float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                float mx = std::max(r, std::max(g, b));
                float mn = std::min(r, std::min(g, b));
                float sat = (mx > 1e-4f) ? ((mx - mn) / mx) : 0.0f;

                // Protect warm skin-tone region (R > G > B) from excessive vibrance boost
                float skinProtect = (r > g && g > b) ? 0.55f : 1.0f;
                float vibBoost = vibrance * (1.0f - sat) * skinProtect * 0.75f;
                float satFactor = std::max(0.0f, 1.0f + saturation + vibBoost);

                r = std::clamp(lum + (r - lum) * satFactor, 0.0f, 1.0f);
                g = std::clamp(lum + (g - lum) * satFactor, 0.0f, 1.0f);
                b = std::clamp(lum + (b - lum) * satFactor, 0.0f, 1.0f);
            }

            // 7. 8-Band HSL Selective Color Mixer
            if (hasHsl) {
                float h, s, v;
                rgbToHsv(r, g, b, h, s, v);
                if (s > 0.02f) {
                    float dH = 0.0f, dS = 0.0f, dL = 0.0f;
                    for (int k = 0; k < 8; ++k) {
                        float diff = std::fabs(h - bandCenters[k]);
                        if (diff > 180.0f) diff = 360.0f - diff;
                        float bw = bandWidths[k];
                        if (diff < bw) {
                            float w = 0.5f * (1.0f + std::cos(PI * diff / bw));
                            dH += params[18 + k] * 28.0f * w;
                            dS += params[26 + k] * w;
                            dL += params[34 + k] * 0.35f * w;
                        }
                    }
                    h = std::fmod(h + dH + 360.0f, 360.0f);
                    s = std::clamp(s * (1.0f + dS), 0.0f, 1.0f);
                    v = std::clamp(v * (1.0f + dL * smoothstep(0.05f, 0.35f, s)), 0.0f, 1.0f);
                    hsvToRgb(h, s, v, r, g, b);
                }
            }

            // 8. Curated Filter Preset Look with Intensity Blend
            if (presetId > 0 && presetStr > 0.005f) {
                float pr = r, pg = g, pb = b;
                applyPresetLook(presetId, pr, pg, pb);
                r = r * (1.0f - presetStr) + pr * presetStr;
                g = g * (1.0f - presetStr) + pg * presetStr;
                b = b * (1.0f - presetStr) + pb * presetStr;
            }

            // 9. Matte Fade (lifted black point)
            if (fade > 0.005f) {
                float lift = fade * 0.14f;
                r = lift + r * (1.0f - lift * 0.85f);
                g = lift + g * (1.0f - lift * 0.85f);
                b = lift + b * (1.0f - lift * 0.85f);
            }

            // 10. Optical cos^4 Radial Vignette
            if (std::fabs(vignette) > 0.005f) {
                float nx = (static_cast<float>(x) / width) - 0.5f;
                float rSq = nx * nx + ny * ny;
                float cosA = 1.0f / std::sqrt(1.0f + rSq * 2.2f);
                float cos4 = cosA * cosA * cosA * cosA;
                if (vignette > 0.0f) {
                    // Darken edges
                    float vig = 1.0f - (1.0f - cos4) * vignette * 1.15f;
                    vig = std::clamp(vig, 0.12f, 1.0f);
                    r *= vig; g *= vig; b *= vig;
                } else {
                    // Brighten edges gently
                    float vig = (1.0f - cos4) * (-vignette) * 0.65f;
                    r = r + (1.0f - r) * vig;
                    g = g + (1.0f - g) * vig;
                    b = b + (1.0f - b) * vig;
                }
            }

            // 11. Midtone-Weighted Organic Film Grain
            if (grain > 0.005f) {
                uint32_t seed = static_cast<uint32_t>(x * 374761393U + y * 668265263U) ^ 0x9E3779B9U;
                seed = (seed ^ (seed >> 13)) * 1274126177U;
                seed ^= (seed >> 16);
                float n1 = (static_cast<float>(seed & 0xFFFF) / 32768.0f) - 1.0f;
                float n2 = (static_cast<float>((seed >> 16) & 0xFFFF) / 32768.0f) - 1.0f;
                float gaussNoise = 0.5f * (n1 + n2); // Triangular approximation of Gaussian grain
                float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                float midWeight = 0.20f + 0.80f * (4.0f * lum * (1.0f - lum));
                float gDelta = gaussNoise * grain * 0.16f * midWeight;
                r += gDelta; g += gDelta; b += gDelta;
            }

            dst[i] = (a << 24) |
                     (static_cast<uint32_t>(clamp8(r * 255.0f)) << 16) |
                     (static_cast<uint32_t>(clamp8(g * 255.0f)) << 8) |
                     static_cast<uint32_t>(clamp8(b * 255.0f));
        }
    }

    // 12. Optional Soft-Coring Unsharp Mask Sharpening
    if (sharpen > 0.005f) {
        std::vector<uint32_t> tmp(dst, dst + total);
        unsharpMask(tmp.data(), dst, width, height, sharpen * 1.15f, 1.15f);
    }
}

void analyzeAutoEnhance(const uint32_t* src, int width, int height, float* outParams) {
    if (!src || !outParams || width <= 0 || height <= 0) return;
    initColorLuts();
    std::memset(outParams, 0, sizeof(float) * COLOR_PARAMS_COUNT);

    int hist[256] = {0};
    double sumR = 0.0, sumG = 0.0, sumB = 0.0;
    int count = 0;
    int step = std::max(1, std::min(width, height) / 256);

    for (int y = 0; y < height; y += step) {
        for (int x = 0; x < width; x += step) {
            uint32_t p = src[y * width + x];
            uint8_t r = (p >> 16) & 0xFF;
            uint8_t g = (p >> 8) & 0xFF;
            uint8_t b = p & 0xFF;
            int l = static_cast<int>(0.2126f * r + 0.7152f * g + 0.0722f * b + 0.5f);
            hist[std::clamp(l, 0, 255)]++;
            sumR += srgb8ToLin(r);
            sumG += srgb8ToLin(g);
            sumB += srgb8ToLin(b);
            count++;
        }
    }
    if (count <= 0) return;

    auto percentile = [&](float pct) -> float {
        int target = static_cast<int>(count * pct);
        int acc = 0;
        for (int i = 0; i < 256; ++i) {
            acc += hist[i];
            if (acc >= target) return i / 255.0f;
        }
        return 1.0f;
    };

    float p02 = percentile(0.02f);
    float p50 = percentile(0.50f);
    float p98 = percentile(0.98f);

    // Gentle exposure toward balanced midtone (0.48), capped safely
    float expEv = std::clamp((0.48f - p50) * 1.4f, -0.65f, 0.75f);
    // Recover clipped highlights & lift crushed shadows
    float hi = (p98 > 0.90f) ? -std::clamp((p98 - 0.88f) * 3.2f, 0.0f, 0.45f) : 0.0f;
    float sh = (p02 < 0.08f && p50 < 0.45f) ? std::clamp((0.45f - p50) * 0.9f, 0.0f, 0.38f) : 0.12f;
    // Dynamic range expansion
    float spread = std::max(0.05f, p98 - p02);
    float contrast = std::clamp((0.82f - spread) * 0.65f + 0.12f, -0.15f, 0.32f);
    float whites = (p98 < 0.88f) ? std::clamp((0.92f - p98) * 1.2f, 0.0f, 0.30f) : 0.0f;
    float blacks = (p02 > 0.08f) ? -std::clamp((p02 - 0.04f) * 1.2f, 0.0f, 0.25f) : 0.0f;

    // Gentle gray-world white balance damping (30% strength to preserve intentional lighting mood)
    double avgR = sumR / count + 1e-5;
    double avgG = sumG / count + 1e-5;
    double avgB = sumB / count + 1e-5;
    float temp = std::clamp(static_cast<float>(std::log2(avgB / avgR) * 0.28), -0.25f, 0.25f);
    float tint = std::clamp(static_cast<float>(std::log2(avgG / std::sqrt(avgR * avgB)) * 0.20), -0.18f, 0.18f);

    outParams[0]  = expEv;
    outParams[1]  = contrast;
    outParams[2]  = hi;
    outParams[3]  = sh;
    outParams[4]  = whites;
    outParams[5]  = blacks;
    outParams[6]  = temp;
    outParams[7]  = tint;
    outParams[8]  = 0.18f; // Gentle vibrance
    outParams[10] = 0.10f; // Subtle clarity
}

// ---------------------------------------------------------------------------
// Core DSP Filters: Unsharp Mask, Bilateral Denoise, Bicubic & Tile Blending
// ---------------------------------------------------------------------------

void unsharpMask(const uint32_t* src, uint32_t* dst, int width, int height, float amount, float radius) {
    if (!src || !dst || width <= 0 || height <= 0) return;

    int r = std::clamp(static_cast<int>(radius + 0.5f), 1, 24);
    std::vector<float> kernel(2 * r + 1);
    float sigma = std::max(0.5f, radius);
    float sum = 0.0f;
    for (int i = -r; i <= r; ++i) {
        float val = std::exp(-(i * i) / (2.0f * sigma * sigma));
        kernel[i + r] = val;
        sum += val;
    }
    for (float& k : kernel) k /= sum;

    std::vector<uint32_t> temp(width * height);

    #pragma omp parallel for schedule(static)
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            float rAcc = 0.0f, gAcc = 0.0f, bAcc = 0.0f;
            for (int k = -r; k <= r; ++k) {
                int nx = std::clamp(x + k, 0, width - 1);
                uint32_t p = src[y * width + nx];
                float w = kernel[k + r];
                rAcc += ((p >> 16) & 0xFF) * w;
                gAcc += ((p >> 8) & 0xFF) * w;
                bAcc += (p & 0xFF) * w;
            }
            uint32_t a = (src[y * width + x] >> 24) & 0xFF;
            temp[y * width + x] = (a << 24) | (clamp8(rAcc) << 16) | (clamp8(gAcc) << 8) | clamp8(bAcc);
        }
    }

    #pragma omp parallel for schedule(static)
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            float rBlur = 0.0f, gBlur = 0.0f, bBlur = 0.0f;
            for (int k = -r; k <= r; ++k) {
                int ny = std::clamp(y + k, 0, height - 1);
                uint32_t p = temp[ny * width + x];
                float w = kernel[k + r];
                rBlur += ((p >> 16) & 0xFF) * w;
                gBlur += ((p >> 8) & 0xFF) * w;
                bBlur += (p & 0xFF) * w;
            }

            uint32_t orig = src[y * width + x];
            uint32_t a = (orig >> 24) & 0xFF;
            float rOrig = (orig >> 16) & 0xFF;
            float gOrig = (orig >> 8) & 0xFF;
            float bOrig = orig & 0xFF;

            float dR = rOrig - rBlur;
            float dG = gOrig - gBlur;
            float dB = bOrig - bBlur;

            float mag = std::abs(dR) + std::abs(dG) + std::abs(dB);
            float coring = smoothstep(2.0f, 10.0f, mag);
            float effAmount = amount * coring;

            dst[y * width + x] = (a << 24) |
                                 (static_cast<uint32_t>(clamp8(rOrig + effAmount * dR)) << 16) |
                                 (static_cast<uint32_t>(clamp8(gOrig + effAmount * dG)) << 8) |
                                 static_cast<uint32_t>(clamp8(bOrig + effAmount * dB));
        }
    }
}

void bilateralDenoise(const uint32_t* src, uint32_t* dst, int width, int height, float spatialSigma, float colorSigma) {
    if (!src || !dst || width <= 0 || height <= 0) return;

    int r = std::clamp(static_cast<int>(spatialSigma * 1.5f), 1, 7);
    float twoSpatialSigmaSq = 2.0f * std::max(0.5f, spatialSigma) * std::max(0.5f, spatialSigma);
    float twoColorSigmaSq = 2.0f * std::max(1.0f, colorSigma) * std::max(1.0f, colorSigma);

    int side = 2 * r + 1;
    std::vector<float> spatialLut(side * side);
    for (int dy = -r; dy <= r; ++dy) {
        for (int dx = -r; dx <= r; ++dx) {
            spatialLut[(dy + r) * side + (dx + r)] = std::exp(-static_cast<float>(dx * dx + dy * dy) / twoSpatialSigmaSq);
        }
    }

    const int maxColorDistSq = 3 * 255 * 255;
    std::vector<float> colorLut(maxColorDistSq + 1);
    for (int d = 0; d <= maxColorDistSq; ++d) {
        colorLut[d] = std::exp(-static_cast<float>(d) / twoColorSigmaSq);
    }

    #pragma omp parallel for schedule(static)
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            uint32_t centerP = src[y * width + x];
            int cR = (centerP >> 16) & 0xFF;
            int cG = (centerP >> 8) & 0xFF;
            int cB = centerP & 0xFF;
            uint32_t a = (centerP >> 24) & 0xFF;

            float sumWeights = 0.0f, accR = 0.0f, accG = 0.0f, accB = 0.0f;

            for (int dy = -r; dy <= r; ++dy) {
                int ny = std::clamp(y + dy, 0, height - 1);
                const uint32_t* rowPtr = src + ny * width;
                for (int dx = -r; dx <= r; ++dx) {
                    int nx = std::clamp(x + dx, 0, width - 1);
                    uint32_t neighborP = rowPtr[nx];
                    int nR = (neighborP >> 16) & 0xFF;
                    int nG = (neighborP >> 8) & 0xFF;
                    int nB = neighborP & 0xFF;

                    int dR = cR - nR, dG = cG - nG, dB = cB - nB;
                    float weight = spatialLut[(dy + r) * side + (dx + r)] * colorLut[dR * dR + dG * dG + dB * dB];
                    accR += nR * weight;
                    accG += nG * weight;
                    accB += nB * weight;
                    sumWeights += weight;
                }
            }

            if (sumWeights > 1e-5f) {
                accR /= sumWeights; accG /= sumWeights; accB /= sumWeights;
            }
            dst[y * width + x] = (a << 24) | (clamp8(accR) << 16) | (clamp8(accG) << 8) | clamp8(accB);
        }
    }
}

static inline float cubicWeight(float x) {
    const float a = -0.5f;
    x = std::fabs(x);
    if (x <= 1.0f) return (a + 2.0f) * x * x * x - (a + 3.0f) * x * x + 1.0f;
    if (x < 2.0f)  return a * x * x * x - 5.0f * a * x * x + 8.0f * a * x - 4.0f * a;
    return 0.0f;
}

static void bicubicResize(const uint32_t* src, uint32_t* dst, int srcW, int srcH, int dstW, int dstH) {
    float scaleX = static_cast<float>(srcW) / dstW;
    float scaleY = static_cast<float>(srcH) / dstH;

    #pragma omp parallel for schedule(static)
    for (int y = 0; y < dstH; ++y) {
        float srcY = (y + 0.5f) * scaleY - 0.5f;
        int yInt = static_cast<int>(std::floor(srcY));
        float yFrac = srcY - yInt;
        float wy[4];
        for (int m = -1; m <= 2; ++m) wy[m + 1] = cubicWeight(m - yFrac);

        for (int x = 0; x < dstW; ++x) {
            float srcX = (x + 0.5f) * scaleX - 0.5f;
            int xInt = static_cast<int>(std::floor(srcX));
            float xFrac = srcX - xInt;
            float wx[4];
            for (int n = -1; n <= 2; ++n) wx[n + 1] = cubicWeight(n - xFrac);

            float rT = 0.0f, gT = 0.0f, bT = 0.0f, aT = 0.0f, wT = 0.0f;
            for (int m = -1; m <= 2; ++m) {
                int sy = std::clamp(yInt + m, 0, srcH - 1);
                const uint32_t* row = src + sy * srcW;
                for (int n = -1; n <= 2; ++n) {
                    int sx = std::clamp(xInt + n, 0, srcW - 1);
                    float w = wy[m + 1] * wx[n + 1];
                    uint32_t p = row[sx];
                    aT += ((p >> 24) & 0xFF) * w;
                    rT += ((p >> 16) & 0xFF) * w;
                    gT += ((p >> 8) & 0xFF) * w;
                    bT += (p & 0xFF) * w;
                    wT += w;
                }
            }
            if (std::fabs(wT) > 1e-4f) {
                aT /= wT; rT /= wT; gT /= wT; bT /= wT;
            }
            dst[y * dstW + x] = (static_cast<uint32_t>(clamp8(aT)) << 24) |
                                (static_cast<uint32_t>(clamp8(rT)) << 16) |
                                (static_cast<uint32_t>(clamp8(gT)) << 8) |
                                static_cast<uint32_t>(clamp8(bT));
        }
    }
}

void superResolutionFallback(const uint32_t* src, uint32_t* dst, int srcW, int srcH, int scale, float sharpenAmount) {
    int dstW = srcW * scale;
    int dstH = srcH * scale;
    std::vector<uint32_t> upscaled(dstW * dstH);
    bicubicResize(src, upscaled.data(), srcW, srcH, dstW, dstH);
    unsharpMask(upscaled.data(), dst, dstW, dstH, sharpenAmount, 1.2f);
}

static inline float raisedCosineEdgeWeight(int pos, int length, int globalPos, int globalMax, int margin) {
    if (margin <= 0) return 1.0f;
    float w = 1.0f;
    const float PI = 3.14159265358979323846f;
    if (globalPos > 0 && pos < margin) {
        float t = (pos + 0.5f) / static_cast<float>(margin);
        w *= 0.5f * (1.0f - std::cos(PI * t));
    }
    if (globalPos + length < globalMax && pos >= length - margin) {
        float t = (length - 1 - pos + 0.5f) / static_cast<float>(margin);
        w *= 0.5f * (1.0f - std::cos(PI * t));
    }
    return std::max(1e-4f, w);
}

void cosineTileBlendWithOverlap(const uint32_t* tile, int tileW, int tileH, int dstX, int dstY,
                                uint32_t* target, float* weightMap, int targetW, int targetH, int overlapPx) {
    int margin = std::clamp(overlapPx, 8, std::max(8, std::min(tileW, tileH) / 2));

    for (int y = 0; y < tileH; ++y) {
        int ty = dstY + y;
        if (ty < 0 || ty >= targetH) continue;
        float wy = raisedCosineEdgeWeight(y, tileH, dstY, targetH, margin);

        for (int x = 0; x < tileW; ++x) {
            int tx = dstX + x;
            if (tx < 0 || tx >= targetW) continue;
            float wx = raisedCosineEdgeWeight(x, tileW, dstX, targetW, margin);
            float weight = wx * wy;

            int targetIdx = ty * targetW + tx;
            uint32_t tp = tile[y * tileW + x];
            uint32_t tA = (tp >> 24) & 0xFF;
            float tR = (tp >> 16) & 0xFF;
            float tG = (tp >> 8) & 0xFF;
            float tB = tp & 0xFF;

            float curWeight = weightMap ? weightMap[targetIdx] : 0.0f;
            uint32_t curP = target[targetIdx];

            if (curWeight <= 1e-5f || ((curP >> 24) & 0xFF) == 0) {
                target[targetIdx] = (std::max(tA, 255u) << 24) |
                                    (clamp8(tR) << 16) | (clamp8(tG) << 8) | clamp8(tB);
                if (weightMap) weightMap[targetIdx] = weight;
            } else {
                float curR = (curP >> 16) & 0xFF;
                float curG = (curP >> 8) & 0xFF;
                float curB = curP & 0xFF;
                float newWeight = curWeight + weight;
                float invW = 1.0f / newWeight;
                target[targetIdx] = 0xFF000000 |
                                    (clamp8((curR * curWeight + tR * weight) * invW) << 16) |
                                    (clamp8((curG * curWeight + tG * weight) * invW) << 8) |
                                    clamp8((curB * curWeight + tB * weight) * invW);
                if (weightMap) weightMap[targetIdx] = newWeight;
            }
        }
    }
}

// ---------------------------------------------------------------------------
// SCRFD Face & 5-Point Landmark Detector
// ---------------------------------------------------------------------------

static inline float faceBoxIntersectionArea(const DetectedFace& a, const DetectedFace& b) {
    float x0 = std::max(a.x, b.x), y0 = std::max(a.y, b.y);
    float x1 = std::min(a.x + a.w, b.x + b.w), y1 = std::min(a.y + a.h, b.y + b.h);
    return (x1 <= x0 || y1 <= y0) ? 0.0f : (x1 - x0) * (y1 - y0);
}

#if USE_NCNN
static void generateScrfdProposals(
    int baseSize, int featStride, const ncnn::Mat& scoreBlob, const ncnn::Mat& bboxBlob,
    const ncnn::Mat& kpsBlob, float probThreshold, std::vector<DetectedFace>& proposals
) {
    int w = scoreBlob.w, h = scoreBlob.h;
    const float scales[2] = {1.0f, 2.0f};
    for (int q = 0; q < 2; ++q) {
        float anchorW = baseSize * scales[q];
        float anchorH = baseSize * scales[q];
        float anchorX0 = -anchorW * 0.5f, anchorY0 = -anchorH * 0.5f;

        const ncnn::Mat score = scoreBlob.channel(q);
        const ncnn::Mat bbox = bboxBlob.channel_range(q * 4, 4);

        float ay = anchorY0;
        for (int i = 0; i < h; ++i) {
            float ax = anchorX0;
            for (int j = 0; j < w; ++j) {
                int idx = i * w + j;
                float prob = score[idx];
                if (prob >= probThreshold) {
                    float dx = bbox.channel(0)[idx] * featStride;
                    float dy = bbox.channel(1)[idx] * featStride;
                    float dw = bbox.channel(2)[idx] * featStride;
                    float dh = bbox.channel(3)[idx] * featStride;
                    float cx = ax + anchorW * 0.5f;
                    float cy = ay + anchorH * 0.5f;

                    DetectedFace face{};
                    face.x = cx - dx;
                    face.y = cy - dy;
                    face.w = (cx + dw) - face.x;
                    face.h = (cy + dh) - face.y;
                    face.score = prob;

                    if (!kpsBlob.empty()) {
                        const ncnn::Mat kps = kpsBlob.channel_range(q * 10, 10);
                        for (int k = 0; k < 5; ++k) {
                            face.landmarks[k * 2 + 0] = cx + kps.channel(k * 2 + 0)[idx] * featStride;
                            face.landmarks[k * 2 + 1] = cy + kps.channel(k * 2 + 1)[idx] * featStride;
                        }
                    } else {
                        face.landmarks[0] = face.x + face.w * 0.32f; face.landmarks[1] = face.y + face.h * 0.38f;
                        face.landmarks[2] = face.x + face.w * 0.68f; face.landmarks[3] = face.y + face.h * 0.38f;
                        face.landmarks[4] = face.x + face.w * 0.50f; face.landmarks[5] = face.y + face.h * 0.56f;
                        face.landmarks[6] = face.x + face.w * 0.36f; face.landmarks[7] = face.y + face.h * 0.76f;
                        face.landmarks[8] = face.x + face.w * 0.64f; face.landmarks[9] = face.y + face.h * 0.76f;
                    }
                    proposals.push_back(face);
                }
                ax += featStride;
            }
            ay += featStride;
        }
    }
}
#endif

std::vector<DetectedFace> detectFacesInternal(const uint32_t* src, int width, int height) {
    std::vector<DetectedFace> results;
    if (!src || width <= 0 || height <= 0) return results;

#if USE_NCNN
    std::lock_guard<std::mutex> lock(g_model_mutex);
    ncnn::Net* scrfd = ensureScrfdNet();
    if (scrfd) {
        std::vector<unsigned char> rgbIn(width * height * 3);
        for (int i = 0; i < width * height; ++i) {
            uint32_t p = src[i];
            rgbIn[i * 3 + 0] = (p >> 16) & 0xFF;
            rgbIn[i * 3 + 1] = (p >> 8) & 0xFF;
            rgbIn[i * 3 + 2] = p & 0xFF;
        }

        const int targetSize = 640;
        int w = width, h = height;
        float scale = 1.0f;
        if (w > h) {
            scale = static_cast<float>(targetSize) / w;
            w = targetSize;
            h = std::max(1, static_cast<int>(h * scale));
        } else {
            scale = static_cast<float>(targetSize) / h;
            h = targetSize;
            w = std::max(1, static_cast<int>(w * scale));
        }

        ncnn::Mat in = ncnn::Mat::from_pixels_resize(rgbIn.data(), ncnn::Mat::PIXEL_RGB, width, height, w, h);
        int wpad = (w + 31) / 32 * 32 - w;
        int hpad = (h + 31) / 32 * 32 - h;
        ncnn::Mat inPad;
        ncnn::copy_make_border(in, inPad, hpad / 2, hpad - hpad / 2, wpad / 2, wpad - wpad / 2, ncnn::BORDER_CONSTANT, 0.0f);

        const float meanVals[3] = {127.5f, 127.5f, 127.5f};
        const float normVals[3] = {1.0f / 128.0f, 1.0f / 128.0f, 1.0f / 128.0f};
        inPad.substract_mean_normalize(meanVals, normVals);

        ncnn::Extractor ex = scrfd->create_extractor();
        ex.input("input.1", inPad);

        std::vector<DetectedFace> proposals;
        const int strides[3] = {8, 16, 32};
        const int baseSizes[3] = {16, 64, 256};
        const char* scoreNames[3] = {"score_8", "score_16", "score_32"};
        const char* bboxNames[3]  = {"bbox_8",  "bbox_16",  "bbox_32"};
        const char* kpsNames[3]   = {"kps_8",   "kps_16",   "kps_32"};

        for (int s = 0; s < 3; ++s) {
            ncnn::Mat scoreBlob, bboxBlob, kpsBlob;
            ex.extract(scoreNames[s], scoreBlob);
            ex.extract(bboxNames[s], bboxBlob);
            ex.extract(kpsNames[s], kpsBlob);
            if (!scoreBlob.empty() && !bboxBlob.empty()) {
                generateScrfdProposals(baseSizes[s], strides[s], scoreBlob, bboxBlob, kpsBlob, 0.45f, proposals);
            }
        }

        std::sort(proposals.begin(), proposals.end(), [](const DetectedFace& a, const DetectedFace& b) {
            return a.score > b.score;
        });

        for (const auto& cand : proposals) {
            bool keep = true;
            float candArea = cand.w * cand.h;
            for (const auto& picked : results) {
                float inter = faceBoxIntersectionArea(cand, picked);
                float unionArea = candArea + (picked.w * picked.h) - inter;
                if (unionArea > 0.0f && (inter / unionArea) > 0.4f) {
                    keep = false;
                    break;
                }
            }
            if (keep) {
                DetectedFace mapped = cand;
                float x0 = std::clamp((cand.x - (wpad / 2.0f)) / scale, 0.0f, static_cast<float>(width - 1));
                float y0 = std::clamp((cand.y - (hpad / 2.0f)) / scale, 0.0f, static_cast<float>(height - 1));
                float x1 = std::clamp((cand.x + cand.w - (wpad / 2.0f)) / scale, 0.0f, static_cast<float>(width - 1));
                float y1 = std::clamp((cand.y + cand.h - (hpad / 2.0f)) / scale, 0.0f, static_cast<float>(height - 1));
                mapped.x = x0; mapped.y = y0;
                mapped.w = std::max(1.0f, x1 - x0);
                mapped.h = std::max(1.0f, y1 - y0);
                for (int k = 0; k < 5; ++k) {
                    mapped.landmarks[k * 2 + 0] = std::clamp((cand.landmarks[k * 2 + 0] - (wpad / 2.0f)) / scale, 0.0f, static_cast<float>(width - 1));
                    mapped.landmarks[k * 2 + 1] = std::clamp((cand.landmarks[k * 2 + 1] - (hpad / 2.0f)) / scale, 0.0f, static_cast<float>(height - 1));
                }
                results.push_back(mapped);
            }
        }
    }
#endif
    return results;
}

// ---------------------------------------------------------------------------
// Natural Portrait & Face Restoration (Works for ALL face sizes, no color bias)
// ---------------------------------------------------------------------------

void restoreFacesNeural(const uint32_t* src, uint32_t* dst, int width, int height, float fidelityStrength) {
    if (!src || !dst || width <= 0 || height <= 0) return;

    float strength = std::clamp(fidelityStrength, 0.1f, 1.2f);

    // Step 1: Gentle global chroma-safe cleanup + mild clarity
    std::vector<uint32_t> baseDenoised(width * height);
    bilateralDenoise(src, baseDenoised.data(), width, height, 1.6f, 8.0f);
    unsharpMask(baseDenoised.data(), dst, width, height, 0.40f * strength, 1.1f);

    // Step 2: Detect actual faces via SCRFD (if none found, don't hallucinate an oval in the middle!)
    std::vector<DetectedFace> faces = detectFacesInternal(src, width, height);
    if (faces.empty()) return;

    for (const auto& face : faces) {
        float padW = face.w * 0.25f;
        float padH = face.h * 0.25f;
        int x0 = std::clamp(static_cast<int>(face.x - padW), 0, width - 1);
        int y0 = std::clamp(static_cast<int>(face.y - padH), 0, height - 1);
        int x1 = std::clamp(static_cast<int>(face.x + face.w + padW), x0 + 1, width);
        int y1 = std::clamp(static_cast<int>(face.y + face.h + padH), y0 + 1, height);
        int cropW = x1 - x0;
        int cropH = y1 - y0;
        if (cropW < 16 || cropH < 16) continue;

        std::vector<uint32_t> faceCrop(cropW * cropH);
        for (int y = 0; y < cropH; ++y) {
            std::memcpy(&faceCrop[y * cropW], &src[(y0 + y) * width + x0], cropW * sizeof(uint32_t));
        }

        // Step 2a: Neural Real-ESRGAN detail reconstruction (adaptively resized if > 256px so selfies also benefit!)
        std::vector<uint32_t> neuralRestored(cropW * cropH);
        bool usedNeural = false;

#if USE_NCNN
        {
            std::lock_guard<std::mutex> lock(g_model_mutex);
            ncnn::Net* srNet = ensureWdnNet();
            if (!srNet) srNet = ensureSrNet();
            if (srNet) {
                int inW = cropW, inH = cropH;
                const int maxNetSide = 224;
                if (std::max(inW, inH) > maxNetSide) {
                    float s = static_cast<float>(maxNetSide) / std::max(inW, inH);
                    inW = std::max(16, static_cast<int>(inW * s));
                    inH = std::max(16, static_cast<int>(inH * s));
                }

                std::vector<unsigned char> rgbFace(cropW * cropH * 3);
                for (int i = 0; i < cropW * cropH; ++i) {
                    uint32_t p = faceCrop[i];
                    rgbFace[i * 3 + 0] = (p >> 16) & 0xFF;
                    rgbFace[i * 3 + 1] = (p >> 8) & 0xFF;
                    rgbFace[i * 3 + 2] = p & 0xFF;
                }

                ncnn::Mat inMat = (inW == cropW && inH == cropH)
                    ? ncnn::Mat::from_pixels(rgbFace.data(), ncnn::Mat::PIXEL_RGB, cropW, cropH)
                    : ncnn::Mat::from_pixels_resize(rgbFace.data(), ncnn::Mat::PIXEL_RGB, cropW, cropH, inW, inH);

                const float meanVals[3] = {0.0f, 0.0f, 0.0f};
                const float normVals[3] = {1.0f / 255.0f, 1.0f / 255.0f, 1.0f / 255.0f};
                inMat.substract_mean_normalize(meanVals, normVals);

                ncnn::Extractor ex = srNet->create_extractor();
                ex.input("data", inMat);
                ncnn::Mat outMat;
                if (ex.extract("output", outMat) == 0 && outMat.w > 0 && outMat.h > 0) {
                    ncnn::Mat resMat;
                    ncnn::resize_bilinear(outMat, resMat, cropW, cropH);
                    const float denormVals[3] = {255.0f, 255.0f, 255.0f};
                    resMat.substract_mean_normalize(meanVals, denormVals);
                    std::vector<unsigned char> rgbOut(cropW * cropH * 3);
                    resMat.to_pixels(rgbOut.data(), ncnn::Mat::PIXEL_RGB);

                    // For already large sharp selfies, blend 55% neural + 45% original to keep true skin identity
                    float neuralMix = (std::max(cropW, cropH) > maxNetSide) ? 0.50f : 0.85f;
                    for (int i = 0; i < cropW * cropH; ++i) {
                        uint32_t origP = faceCrop[i];
                        uint32_t a = (origP >> 24) & 0xFF;
                        float r = ((origP >> 16) & 0xFF) * (1.0f - neuralMix) + rgbOut[i * 3 + 0] * neuralMix;
                        float g = ((origP >> 8) & 0xFF) * (1.0f - neuralMix) + rgbOut[i * 3 + 1] * neuralMix;
                        float b = (origP & 0xFF) * (1.0f - neuralMix) + rgbOut[i * 3 + 2] * neuralMix;
                        neuralRestored[i] = (a << 24) | (clamp8(r) << 16) | (clamp8(g) << 8) | clamp8(b);
                    }
                    usedNeural = true;
                }
            }
        }
#endif
        if (!usedNeural) {
            std::copy(faceCrop.begin(), faceCrop.end(), neuralRestored.begin());
        }

        // Step 2b: Natural Frequency Separation (smooth blotches, preserve natural pores, gentle eye clarity)
        std::vector<uint32_t> lowFreq(cropW * cropH);
        std::vector<uint32_t> fineSharp(cropW * cropH);
        bilateralDenoise(neuralRestored.data(), lowFreq.data(), cropW, cropH, 3.0f, 15.0f);
        unsharpMask(neuralRestored.data(), fineSharp.data(), cropW, cropH, 0.80f * strength, 1.0f);

        float lEyeX = face.landmarks[0] - x0, lEyeY = face.landmarks[1] - y0;
        float rEyeX = face.landmarks[2] - x0, rEyeY = face.landmarks[3] - y0;
        float lMouthX = face.landmarks[6] - x0, lMouthY = face.landmarks[7] - y0;
        float rMouthX = face.landmarks[8] - x0, rMouthY = face.landmarks[9] - y0;
        float mouthCX = 0.5f * (lMouthX + rMouthX), mouthCY = 0.5f * (lMouthY + rMouthY);

        float eyeDist = std::hypot(rEyeX - lEyeX, rEyeY - lEyeY);
        if (eyeDist < cropW * 0.12f) eyeDist = cropW * 0.30f;

        float eyeRadius = eyeDist * 0.34f;
        float mouthRx = std::max(eyeDist * 0.45f, std::hypot(rMouthX - lMouthX, rMouthY - lMouthY) * 0.60f);
        float mouthRy = eyeDist * 0.24f;
        float faceCenterX = (face.x + face.w * 0.5f) - x0;
        float faceCenterY = (face.y + face.h * 0.5f) - y0;
        float faceRx = face.w * 0.60f;
        float faceRy = face.h * 0.64f;

        #pragma omp parallel for schedule(static)
        for (int cy = 0; cy < cropH; ++cy) {
            for (int cx = 0; cx < cropW; ++cx) {
                int cIdx = cy * cropW + cx;
                float normFx = (cx - faceCenterX) / std::max(1.0f, faceRx);
                float normFy = (cy - faceCenterY) / std::max(1.0f, faceRy);
                float fDist = std::sqrt(normFx * normFx + normFy * normFy);
                float pasteMask = (1.0f - smoothstep(0.60f, 1.0f, fDist)) * std::min(1.0f, strength);
                if (pasteMask <= 0.001f) continue;

                float dLeftEye  = std::hypot(cx - lEyeX, cy - lEyeY) / std::max(1.0f, eyeRadius);
                float dRightEye = std::hypot(cx - rEyeX, cy - rEyeY) / std::max(1.0f, eyeRadius);
                float eyeWeight = std::max(1.0f - smoothstep(0.35f, 1.0f, dLeftEye),
                                           1.0f - smoothstep(0.35f, 1.0f, dRightEye));

                float dMouth = std::hypot((cx - mouthCX) / std::max(1.0f, mouthRx),
                                          (cy - mouthCY) / std::max(1.0f, mouthRy));
                float mouthWeight = 1.0f - smoothstep(0.40f, 1.0f, dMouth);
                float featureWeight = std::clamp(eyeWeight + mouthWeight * 0.7f, 0.0f, 1.0f);

                uint32_t origP = neuralRestored[cIdx];
                uint32_t lowP = lowFreq[cIdx];
                uint32_t sharpP = fineSharp[cIdx];

                float oR = (origP >> 16) & 0xFF, oG = (origP >> 8) & 0xFF, oB = origP & 0xFF;
                float lR = (lowP >> 16) & 0xFF,  lG = (lowP >> 8) & 0xFF,  lB = lowP & 0xFF;
                float sR = (sharpP >> 16) & 0xFF, sG = (sharpP >> 8) & 0xFF, sB = sharpP & 0xFF;

                // Skin: smooth blotches while keeping 80% of natural pore texture (no plastic skin)
                float skinR = lR + (oR - lR) * 0.85f;
                float skinG = lG + (oG - lG) * 0.85f;
                float skinB = lB + (oB - lB) * 0.85f;

                // Eyes & Lips: crisp micro-detail without artificial color shift
                float restR = skinR * (1.0f - featureWeight) + sR * featureWeight;
                float restG = skinG * (1.0f - featureWeight) + sG * featureWeight;
                float restB = skinB * (1.0f - featureWeight) + sB * featureWeight;

                int dstIdx = (y0 + cy) * width + (x0 + cx);
                uint32_t bgP = dst[dstIdx];
                uint32_t a = (bgP >> 24) & 0xFF;
                float bgR = (bgP >> 16) & 0xFF, bgG = (bgP >> 8) & 0xFF, bgB = bgP & 0xFF;

                dst[dstIdx] = (a << 24) |
                              (clamp8(bgR * (1.0f - pasteMask) + restR * pasteMask) << 16) |
                              (clamp8(bgG * (1.0f - pasteMask) + restG * pasteMask) << 8) |
                              clamp8(bgB * (1.0f - pasteMask) + restB * pasteMask);
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Alpha Matting: ML Kit Subject Segmentation + Optional MODNet Portrait Hair + Guided Filter
// ---------------------------------------------------------------------------

void extractAlphaMatteInternal(const uint32_t* src, const float* coarseMask, float* outAlpha, int width, int height, bool isPortraitHint) {
    if (!src || !outAlpha || width <= 0 || height <= 0) return;
    int total = width * height;
    std::vector<float> rawAlpha(total, 0.0f);
    bool modnetUsed = false;

    // Only run portrait-only MODNet when photo has a portrait/person OR when no coarseMask was provided!
    // (Prevents portrait-only MODNet from erasing products, food, cars, or pets detected by ML Kit Subject Segmentation)
    bool shouldRunModnet = isPortraitHint || (coarseMask == nullptr);

#if USE_NCNN
    if (shouldRunModnet) {
        std::lock_guard<std::mutex> lock(g_model_mutex);
        ncnn::Net* matting = ensureMattingNet();
        if (matting) {
            std::vector<unsigned char> rgbIn(total * 3);
            for (int i = 0; i < total; ++i) {
                uint32_t p = src[i];
                rgbIn[i * 3 + 0] = (p >> 16) & 0xFF;
                rgbIn[i * 3 + 1] = (p >> 8) & 0xFF;
                rgbIn[i * 3 + 2] = p & 0xFF;
            }

            const int targetSize = 512;
            int w = width, h = height;
            float scale = 1.0f;
            if (w > h) {
                scale = static_cast<float>(targetSize) / w;
                w = targetSize;
                h = std::max(1, static_cast<int>(h * scale));
            } else {
                scale = static_cast<float>(targetSize) / h;
                h = targetSize;
                w = std::max(1, static_cast<int>(w * scale));
            }

            ncnn::Mat inResized = ncnn::Mat::from_pixels_resize(rgbIn.data(), ncnn::Mat::PIXEL_RGB, width, height, w, h);
            int wpad = targetSize - w, hpad = targetSize - h;
            int padTop = hpad / 2, padBottom = hpad - padTop;
            int padLeft = wpad / 2, padRight = wpad - padLeft;

            ncnn::Mat inPad;
            ncnn::copy_make_border(inResized, inPad, padTop, padBottom, padLeft, padRight, ncnn::BORDER_CONSTANT, 127.5f);

            const float meanVals[3] = {127.5f, 127.5f, 127.5f};
            const float normVals[3] = {0.0078431f, 0.0078431f, 0.0078431f};
            inPad.substract_mean_normalize(meanVals, normVals);

            ncnn::Extractor ex = matting->create_extractor();
            ex.input("input", inPad);
            ncnn::Mat outMat;
            if (ex.extract("output", outMat) == 0 && outMat.w >= targetSize && outMat.h >= targetSize) {
                ncnn::Mat cropped(w, h, 1);
                for (int y = 0; y < h; ++y) {
                    const float* srcRow = outMat.row(padTop + y) + padLeft;
                    std::memcpy(cropped.row(y), srcRow, w * sizeof(float));
                }
                ncnn::Mat alphaFull;
                ncnn::resize_bilinear(cropped, alphaFull, width, height);
                const float* ptr = static_cast<const float*>(alphaFull.data);

                for (int i = 0; i < total; ++i) {
                    float mVal = std::clamp(ptr[i], 0.0f, 1.0f);
                    if (!coarseMask) {
                        rawAlpha[i] = mVal;
                    } else {
                        float cVal = std::clamp(coarseMask[i], 0.0f, 1.0f);
                        // Trust ML Kit Subject Segmentation for body/objects, fuse MODNet along hair transition band
                        if (cVal >= 0.70f) {
                            rawAlpha[i] = std::max(cVal, mVal);
                        } else if (cVal >= 0.10f) {
                            rawAlpha[i] = 0.45f * cVal + 0.55f * mVal;
                        } else {
                            rawAlpha[i] = (mVal > 0.55f) ? (mVal * 0.75f) : std::min(cVal, mVal);
                        }
                    }
                }
                modnetUsed = true;
            }
        }
    }
#endif

    if (!modnetUsed) {
        if (coarseMask) {
            std::copy(coarseMask, coarseMask + total, rawAlpha.begin());
        } else {
            std::fill(rawAlpha.begin(), rawAlpha.end(), 1.0f);
        }
    }

    // Edge-aligned Fast Guided Image Filter refinement
    std::vector<float> guideLum(total);
    #pragma omp parallel for schedule(static)
    for (int i = 0; i < total; ++i) {
        uint32_t p = src[i];
        guideLum[i] = (0.2126f * ((p >> 16) & 0xFF) + 0.7152f * ((p >> 8) & 0xFF) + 0.0722f * (p & 0xFF)) / 255.0f;
    }

    guidedFilter1Ch(guideLum.data(), rawAlpha.data(), outAlpha, width, height, 4, 4e-3f);

    #pragma omp parallel for schedule(static)
    for (int i = 0; i < total; ++i) {
        if (rawAlpha[i] > 0.65f) {
            outAlpha[i] = std::max(outAlpha[i], rawAlpha[i] * 0.94f);
        } else if (rawAlpha[i] < 0.08f) {
            outAlpha[i] = std::min(outAlpha[i], rawAlpha[i] * 1.25f);
        }
        outAlpha[i] = std::clamp(outAlpha[i], 0.0f, 1.0f);
    }
}

// ---------------------------------------------------------------------------
// Monocular Depth Estimation (Depth Anything dpt256 NCNN + Guided Refinement)
// ---------------------------------------------------------------------------

void estimateDepthMapInternal(const uint32_t* src, const float* subjectMask, float* outDepth, int width, int height) {
    if (!src || !outDepth || width <= 0 || height <= 0) return;
    int total = width * height;
    bool dptSuccess = false;

#if USE_NCNN
    {
        std::lock_guard<std::mutex> lock(g_model_mutex);
        ncnn::Net* depthNet = ensureDepthNet();
        if (depthNet) {
            std::vector<unsigned char> rgbIn(total * 3);
            for (int i = 0; i < total; ++i) {
                uint32_t p = src[i];
                rgbIn[i * 3 + 0] = (p >> 16) & 0xFF;
                rgbIn[i * 3 + 1] = (p >> 8) & 0xFF;
                rgbIn[i * 3 + 2] = p & 0xFF;
            }

            const int targetSize = 256;
            int w = width, h = height;
            float scale = 1.0f;
            if (w > h) {
                scale = static_cast<float>(targetSize) / w;
                w = targetSize;
                h = std::max(1, static_cast<int>(h * scale));
            } else {
                scale = static_cast<float>(targetSize) / h;
                h = targetSize;
                w = std::max(1, static_cast<int>(w * scale));
            }

            ncnn::Mat in = ncnn::Mat::from_pixels_resize(rgbIn.data(), ncnn::Mat::PIXEL_RGB, width, height, w, h);
            int wpad = targetSize - w, hpad = targetSize - h;
            int padTop = hpad / 2, padBottom = hpad - padTop;
            int padLeft = wpad / 2, padRight = wpad - padLeft;

            ncnn::Mat inPad;
            ncnn::copy_make_border(in, inPad, padTop, padBottom, padLeft, padRight, ncnn::BORDER_CONSTANT, 0.0f);

            const float meanVals[3] = {123.675f, 116.28f, 103.53f};
            const float normVals[3] = {0.01712475f, 0.0175f, 0.01742919f};
            inPad.substract_mean_normalize(meanVals, normVals);

            ncnn::Extractor ex = depthNet->create_extractor();
            ex.input("image", inPad);
            ncnn::Mat depthMat;
            if (ex.extract("depth", depthMat) == 0 && depthMat.w >= targetSize && depthMat.h >= targetSize) {
                ncnn::Mat cropped(w, h, 1);
                for (int y = 0; y < h; ++y) {
                    const float* srcRow = depthMat.row(padTop + y) + padLeft;
                    std::memcpy(cropped.row(y), srcRow, w * sizeof(float));
                }
                ncnn::Mat fullDepth;
                ncnn::resize_bilinear(cropped, fullDepth, width, height);

                const float* dPtr = static_cast<const float*>(fullDepth.data);
                float minD = 1e9f, maxD = -1e9f;
                for (int i = 0; i < total; ++i) {
                    minD = std::min(minD, dPtr[i]);
                    maxD = std::max(maxD, dPtr[i]);
                }
                float range = std::max(1e-5f, maxD - minD);
                for (int i = 0; i < total; ++i) {
                    float normD = (dPtr[i] - minD) / range;
                    outDepth[i] = subjectMask
                        ? std::clamp(0.75f * normD + 0.25f * subjectMask[i], 0.0f, 1.0f)
                        : normD;
                }
                dptSuccess = true;
            }
        }
    }
#endif

    if (!dptSuccess) {
        if (subjectMask) {
            std::copy(subjectMask, subjectMask + total, outDepth);
        } else {
            for (int y = 0; y < height; ++y) {
                float grad = static_cast<float>(y) / height;
                for (int x = 0; x < width; ++x) outDepth[y * width + x] = grad;
            }
        }
    }

    std::vector<float> guideLum(total);
    std::vector<float> tempDepth(outDepth, outDepth + total);
    #pragma omp parallel for schedule(static)
    for (int i = 0; i < total; ++i) {
        uint32_t p = src[i];
        guideLum[i] = (0.2126f * ((p >> 16) & 0xFF) + 0.7152f * ((p >> 8) & 0xFF) + 0.0722f * (p & 0xFF)) / 255.0f;
    }
    guidedFilter1Ch(guideLum.data(), tempDepth.data(), outDepth, width, height, 5, 1e-3f);
    for (int i = 0; i < total; ++i) outDepth[i] = std::clamp(outDepth[i], 0.0f, 1.0f);
}

// ---------------------------------------------------------------------------
// Telea Fast Marching Inpainting + Texture Transfer
// ---------------------------------------------------------------------------

void teleaInpaint(const uint32_t* src, const int32_t* mask, uint32_t* dst, int width, int height, int radius) {
    if (!src || !mask || !dst || width <= 0 || height <= 0) return;

    int totalPixels = width * height;
    std::memcpy(dst, src, totalPixels * sizeof(uint32_t));

    std::vector<uint8_t> flags(totalPixels, 0);
    std::vector<uint8_t> origMask(totalPixels, 0);
    int unknownCount = 0;
    for (int i = 0; i < totalPixels; ++i) {
        uint32_t m = static_cast<uint32_t>(mask[i]);
        uint8_t ma = (m >> 24) & 0xFF;
        uint8_t mr = (m >> 16) & 0xFF;
        uint8_t mg = (m >> 8) & 0xFF;
        uint8_t mb = m & 0xFF;
        if (ma > 48 && (mr > 48 || mg > 48 || mb > 48)) {
            flags[i] = 1;
            origMask[i] = 1;
            unknownCount++;
        }
    }
    if (unknownCount == 0) return;

    std::vector<uint8_t> knownBinary(totalPixels, 0);
    for (int i = 0; i < totalPixels; ++i) knownBinary[i] = (flags[i] == 0) ? 1 : 0;
    std::vector<float> distT(totalPixels, 0.0f);
    computeSeparableEDT(knownBinary.data(), distT.data(), width, height);

    std::vector<int> band;
    const int dx4[4] = {1, -1, 0, 0};
    const int dy4[4] = {0, 0, 1, -1};

    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            int idx = y * width + x;
            if (flags[idx] == 1) {
                for (int k = 0; k < 4; ++k) {
                    int nx = x + dx4[k], ny = y + dy4[k];
                    if (nx >= 0 && nx < width && ny >= 0 && ny < height && flags[ny * width + nx] == 0) {
                        flags[idx] = 2;
                        band.push_back(idx);
                        break;
                    }
                }
            }
        }
    }

    int r = std::clamp(radius, 3, 14);
    int rSq = r * r;

    while (!band.empty()) {
        std::vector<int> nextBand;
        for (int pIdx : band) {
            int px = pIdx % width, py = pIdx / width;

            float tx = 0.5f * (distT[py * width + std::min(width - 1, px + 1)] - distT[py * width + std::max(0, px - 1)]);
            float ty = 0.5f * (distT[std::min(height - 1, py + 1) * width + px] - distT[std::max(0, py - 1) * width + px]);
            float tLen = std::hypot(tx, ty);
            if (tLen > 1e-4f) { tx /= tLen; ty /= tLen; }

            float sumWeights = 0.0f, sumR = 0.0f, sumG = 0.0f, sumB = 0.0f;
            float Tp = distT[pIdx];

            for (int dy = -r; dy <= r; ++dy) {
                int qy = py + dy;
                if (qy < 0 || qy >= height) continue;
                for (int dx = -r; dx <= r; ++dx) {
                    int qx = px + dx;
                    if (qx < 0 || qx >= width) continue;
                    int dSq = dx * dx + dy * dy;
                    if (dSq == 0 || dSq > rSq) continue;

                    int qIdx = qy * width + qx;
                    if (flags[qIdx] == 0) {
                        float dist = std::sqrt(static_cast<float>(dSq));
                        float dirW = std::max(0.05f, std::fabs((-dx / dist) * tx + (-dy / dist) * ty));
                        float dstW = 1.0f / (static_cast<float>(dSq) * dist);
                        float levW = 1.0f / (1.0f + std::fabs(Tp - distT[qIdx]));
                        float w = dirW * dstW * levW;

                        uint32_t col = dst[qIdx];
                        float qR = (col >> 16) & 0xFF, qG = (col >> 8) & 0xFF, qB = col & 0xFF;

                        int qxR = std::min(width - 1, qx + 1), qxL = std::max(0, qx - 1);
                        int qyD = std::min(height - 1, qy + 1), qyU = std::max(0, qy - 1);
                        if (flags[qy * width + qxR] == 0 && flags[qy * width + qxL] == 0 &&
                            flags[qyD * width + qx] == 0 && flags[qyU * width + qx] == 0) {
                            uint32_t pR = dst[qy * width + qxR], pL = dst[qy * width + qxL];
                            uint32_t pD = dst[qyD * width + qx], pU = dst[qyU * width + qx];
                            float gxR = 0.5f * ((int)((pR >> 16) & 0xFF) - (int)((pL >> 16) & 0xFF));
                            float gyR = 0.5f * ((int)((pD >> 16) & 0xFF) - (int)((pU >> 16) & 0xFF));
                            float gxG = 0.5f * ((int)((pR >> 8) & 0xFF) - (int)((pL >> 8) & 0xFF));
                            float gyG = 0.5f * ((int)((pD >> 8) & 0xFF) - (int)((pU >> 8) & 0xFF));
                            float gxB = 0.5f * ((int)(pR & 0xFF) - (int)(pL & 0xFF));
                            float gyB = 0.5f * ((int)(pD & 0xFF) - (int)(pU & 0xFF));
                            qR = std::clamp(qR - (gxR * dx + gyR * dy) * 0.25f, 0.0f, 255.0f);
                            qG = std::clamp(qG - (gxG * dx + gyG * dy) * 0.25f, 0.0f, 255.0f);
                            qB = std::clamp(qB - (gxB * dx + gyB * dy) * 0.25f, 0.0f, 255.0f);
                        }

                        sumR += qR * w; sumG += qG * w; sumB += qB * w; sumWeights += w;
                    }
                }
            }

            if (sumWeights > 1e-6f) {
                uint32_t a = (src[pIdx] >> 24) & 0xFF;
                dst[pIdx] = (a << 24) |
                            (clamp8(sumR / sumWeights) << 16) |
                            (clamp8(sumG / sumWeights) << 8) |
                            clamp8(sumB / sumWeights);
            }
            flags[pIdx] = 0;

            for (int k = 0; k < 4; ++k) {
                int nx = px + dx4[k], ny = py + dy4[k];
                if (nx >= 0 && nx < width && ny >= 0 && ny < height) {
                    int nIdx = ny * width + nx;
                    if (flags[nIdx] == 1) {
                        flags[nIdx] = 2;
                        nextBand.push_back(nIdx);
                    }
                }
            }
        }
        band = std::move(nextBand);
    }

    // Subtle local micro-grain transfer so repaired region blends naturally with surrounding texture
    std::vector<uint32_t> temp(dst, dst + totalPixels);
    #pragma omp parallel for schedule(static)
    for (int y = 2; y < height - 2; ++y) {
        for (int x = 2; x < width - 2; ++x) {
            int idx = y * width + x;
            if (!origMask[idx]) continue;

            uint32_t centerP = temp[idx];
            float cR = (centerP >> 16) & 0xFF, cG = (centerP >> 8) & 0xFF, cB = centerP & 0xFF;
            float bestCost = 1e9f, texR = 0.0f, texG = 0.0f, texB = 0.0f;
            int searchR = std::min(r * 3, 28);
            const int dirX[8] = {1, -1, 0, 0, 1, -1, 1, -1};
            const int dirY[8] = {0, 0, 1, -1, 1, 1, -1, -1};

            for (int d = 0; d < 8; ++d) {
                int sx = std::clamp(x + dirX[d] * searchR, 1, width - 2);
                int sy = std::clamp(y + dirY[d] * searchR, 1, height - 2);
                int sIdx = sy * width + sx;
                if (origMask[sIdx]) continue;
                uint32_t sp = temp[sIdx];
                float sR = (sp >> 16) & 0xFF, sG = (sp >> 8) & 0xFF, sB = sp & 0xFF;
                float cost = std::abs(cR - sR) + std::abs(cG - sG) + std::abs(cB - sB);
                if (cost < bestCost) {
                    bestCost = cost;
                    uint32_t spN = temp[(sy - 1) * width + sx];
                    texR = (sR - ((spN >> 16) & 0xFF)) * 0.30f;
                    texG = (sG - ((spN >> 8) & 0xFF)) * 0.30f;
                    texB = (sB - (spN & 0xFF)) * 0.30f;
                }
            }
            uint32_t a = (dst[idx] >> 24) & 0xFF;
            dst[idx] = (a << 24) | (clamp8(cR + texR) << 16) | (clamp8(cG + texG) << 8) | clamp8(cB + texB);
        }
    }
}

// ---------------------------------------------------------------------------
// Fast Linear-Light Vogel Disc Depth Bokeh (10-40x faster & physically accurate!)
// ---------------------------------------------------------------------------

void depthBokehLinear(const uint32_t* src, const float* depthMask, uint32_t* dst, int width, int height, float maxBlurRadius, float focusDepth) {
    if (!src || !depthMask || !dst || width <= 0 || height <= 0) return;
    initColorLuts();

    const float maxR = std::clamp(maxBlurRadius, 1.0f, 36.0f);
    const int NUM_SAMPLES = 56;
    const float GOLDEN_ANGLE = 2.399963229728653f;

    // Precompute normalized Vogel spiral disc offsets
    float offX[NUM_SAMPLES], offY[NUM_SAMPLES];
    for (int s = 0; s < NUM_SAMPLES; ++s) {
        float r = std::sqrt((s + 0.5f) / NUM_SAMPLES);
        float theta = s * GOLDEN_ANGLE;
        offX[s] = r * std::cos(theta);
        offY[s] = r * std::sin(theta);
    }

    // Pre-decode image into linear light floats for physically accurate bokeh balls
    const int total = width * height;
    std::vector<float> linR(total), linG(total), linB(total);
    #pragma omp parallel for schedule(static)
    for (int i = 0; i < total; ++i) {
        uint32_t p = src[i];
        linR[i] = srgb8ToLin((p >> 16) & 0xFF);
        linG[i] = srgb8ToLin((p >> 8) & 0xFF);
        linB[i] = srgb8ToLin(p & 0xFF);
    }

    #pragma omp parallel for schedule(dynamic, 8)
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            int idx = y * width + x;
            float d = depthMask[idx];
            float coc = std::clamp(std::abs(d - focusDepth) * maxR, 0.0f, maxR);
            if (coc < 0.6f) {
                dst[idx] = src[idx];
                continue;
            }

            float rSum = linR[idx], gSum = linG[idx], bSum = linB[idx], wSum = 1.0f;

            for (int s = 0; s < NUM_SAMPLES; ++s) {
                int nx = std::clamp(static_cast<int>(x + offX[s] * coc + 0.5f), 0, width - 1);
                int ny = std::clamp(static_cast<int>(y + offY[s] * coc + 0.5f), 0, height - 1);
                int nIdx = ny * width + nx;

                float nd = depthMask[nIdx];
                float nCoc = std::clamp(std::abs(nd - focusDepth) * maxR, 0.0f, maxR);
                // Prevent sharp in-focus foreground subject from bleeding into blurred background
                float depthGate = (nd > d + 0.14f && nCoc < coc * 0.5f) ? 0.06f : 1.0f;

                float pr = linR[nIdx], pg = linG[nIdx], pb = linB[nIdx];
                // Natural linear-light highlight bokeh boost
                float lum = 0.2126f * pr + 0.7152f * pg + 0.0722f * pb;
                float bloom = (lum > 0.50f) ? (1.0f + 3.0f * (lum - 0.50f) * (lum - 0.50f)) : 1.0f;

                float w = depthGate * bloom;
                rSum += pr * w;
                gSum += pg * w;
                bSum += pb * w;
                wSum += w;
            }

            uint32_t a = (src[idx] >> 24) & 0xFF;
            float invW = 1.0f / std::max(1e-4f, wSum);
            dst[idx] = (a << 24) |
                       (static_cast<uint32_t>(linToSrgb8(rSum * invW)) << 16) |
                       (static_cast<uint32_t>(linToSrgb8(gSum * invW)) << 8) |
                       static_cast<uint32_t>(linToSrgb8(bSum * invW));
        }
    }
}

// ---------------------------------------------------------------------------
// 3D Studio Relighting (Half-Lambert + Blinn-Phong + Fresnel Rim)
// ---------------------------------------------------------------------------

void relighting3D(const uint32_t* src, const float* depthMask, uint32_t* dst, int width, int height,
                  float lightX, float lightY, float lightZ,
                  float lightR, float lightG, float lightB, float intensity) {
    if (!src || !depthMask || !dst || width <= 0 || height <= 0) return;

    int total = width * height;
    std::vector<float> relief(total, 0.0f);
    boxFilterFloat(depthMask, relief.data(), width, height, std::max(6, std::min(width, height) / 20));

    #pragma omp parallel for schedule(static)
    for (int i = 0; i < total; ++i) {
        uint32_t p = src[i];
        float lum = (0.2126f * ((p >> 16) & 0xFF) + 0.7152f * ((p >> 8) & 0xFF) + 0.0722f * (p & 0xFF)) / 255.0f;
        relief[i] = 0.65f * relief[i] + 0.20f * depthMask[i] + 0.15f * lum * depthMask[i];
    }

    float lZ = std::max(0.12f, lightZ);

    #pragma omp parallel for schedule(static)
    for (int y = 0; y < height; ++y) {
        float v = static_cast<float>(y) / height;
        for (int x = 0; x < width; ++x) {
            float u = static_cast<float>(x) / width;
            int idx = y * width + x;

            int xL = std::max(0, x - 2), xR = std::min(width - 1, x + 2);
            int yU = std::max(0, y - 2), yD = std::min(height - 1, y + 2);

            float gx = (relief[y * width + xR] - relief[y * width + xL]) * 6.0f;
            float gy = (relief[yD * width + x] - relief[yU * width + x]) * 6.0f;
            float nLen = std::sqrt(gx * gx + gy * gy + 1.0f);
            float nx = -gx / nLen, ny = -gy / nLen, nz = 1.0f / nLen;

            float lx = lightX - u, ly = lightY - v, lz = lZ;
            float lDist = std::sqrt(lx * lx + ly * ly + lz * lz);
            if (lDist > 1e-4f) { lx /= lDist; ly /= lDist; lz /= lDist; }

            float nDotL = nx * lx + ny * ly + nz * lz;
            float wrapDiff = std::max(0.0f, (nDotL + 0.35f) / 1.35f);

            float hx = lx, hy = ly, hz = lz + 1.0f;
            float hLen = std::sqrt(hx * hx + hy * hy + hz * hz);
            float spec = 0.0f;
            if (hLen > 1e-4f) {
                float nDotH = std::max(0.0f, (nx * hx + ny * hy + nz * hz) / hLen);
                spec = std::pow(nDotH, 20.0f) * 0.45f;
            }

            float rim = std::pow(1.0f - std::clamp(nz, 0.0f, 1.0f), 1.6f) * std::max(0.0f, nDotL + 0.5f) * 0.55f;
            float atten = 1.0f / (1.0f + 1.5f * lDist * lDist);
            float personWeight = std::clamp(depthMask[idx] * 0.88f + 0.12f, 0.0f, 1.0f);
            float factor = (wrapDiff * 0.72f + spec + rim) * atten * intensity * personWeight;

            uint32_t orig = src[idx];
            uint32_t a = (orig >> 24) & 0xFF;
            float oR = (orig >> 16) & 0xFF, oG = (orig >> 8) & 0xFF, oB = orig & 0xFF;

            float shadowSide = std::clamp(0.84f + 0.16f * std::max(0.0f, nDotL + 0.4f), 0.80f, 1.0f);
            float baseScale = 1.0f - personWeight * 0.22f + personWeight * 0.22f * shadowSide;

            dst[idx] = (a << 24) |
                       (clamp8(oR * baseScale + lightR * factor * 210.0f) << 16) |
                       (clamp8(oG * baseScale + lightG * factor * 210.0f) << 8) |
                       clamp8(oB * baseScale + lightB * factor * 210.0f);
        }
    }
}

// ---------------------------------------------------------------------------
// Clean Anime Stylizer (Aspect-Ratio Letterboxed AnimeGANv2, NO destructive Kuwahara!)
// styleMode: 0 = Anime Halus (pure AnimeGANv2), 1 = Ilustrasi (subtle ink contour), 2 = Sketsa
// ---------------------------------------------------------------------------

void animeStylize(const uint32_t* src, uint32_t* dst, int width, int height, int styleMode, float strength) {
    if (!src || !dst || width <= 0 || height <= 0) return;

    const int total = width * height;
    const float mix = std::clamp(strength, 0.0f, 1.0f);
    std::vector<uint32_t> stylized(total);
    bool usedAnimeGan = false;

    if (styleMode != 2) {
#if USE_NCNN
        std::lock_guard<std::mutex> lock(g_model_mutex);
        ncnn::Net* animeNet = ensureAnimeNet();
        if (animeNet) {
            std::vector<unsigned char> rgbIn(total * 3);
            for (int i = 0; i < total; ++i) {
                uint32_t p = src[i];
                rgbIn[i * 3 + 0] = (p >> 16) & 0xFF;
                rgbIn[i * 3 + 1] = (p >> 8) & 0xFF;
                rgbIn[i * 3 + 2] = p & 0xFF;
            }

            // Preserve exact aspect ratio via letterbox padding to 512x512 (fixes squashed portraits!)
            const int targetSize = 512;
            int w = width, h = height;
            if (w > h) {
                float s = static_cast<float>(targetSize) / w;
                w = targetSize;
                h = std::max(1, static_cast<int>(h * s));
            } else {
                float s = static_cast<float>(targetSize) / h;
                h = targetSize;
                w = std::max(1, static_cast<int>(w * s));
            }

            ncnn::Mat inResized = ncnn::Mat::from_pixels_resize(rgbIn.data(), ncnn::Mat::PIXEL_RGB, width, height, w, h);
            int wpad = targetSize - w, hpad = targetSize - h;
            int padTop = hpad / 2, padBottom = hpad - padTop;
            int padLeft = wpad / 2, padRight = wpad - padLeft;

            ncnn::Mat inPad;
            ncnn::copy_make_border(inResized, inPad, padTop, padBottom, padLeft, padRight, ncnn::BORDER_REPLICATE, 0.0f);

            const float meanVals[3] = {127.5f, 127.5f, 127.5f};
            const float normVals[3] = {1.0f / 127.5f, 1.0f / 127.5f, 1.0f / 127.5f};
            inPad.substract_mean_normalize(meanVals, normVals);

            ncnn::Extractor ex = animeNet->create_extractor();
            ex.input("input", inPad);
            ncnn::Mat outMat;
            if (ex.extract("out", outMat) == 0 && outMat.w >= targetSize && outMat.h >= targetSize && outMat.c >= 3) {
                // Crop out the padded borders before resizing back to original aspect ratio
                ncnn::Mat cropped(w, h, 3);
                for (int c = 0; c < 3; ++c) {
                    const ncnn::Mat srcC = outMat.channel(c);
                    ncnn::Mat dstC = cropped.channel(c);
                    for (int y = 0; y < h; ++y) {
                        const float* sRow = srcC.row(padTop + y) + padLeft;
                        std::memcpy(dstC.row(y), sRow, w * sizeof(float));
                    }
                }

                ncnn::Mat outFull;
                ncnn::resize_bilinear(cropped, outFull, width, height);
                const float* chR = outFull.channel(0);
                const float* chG = outFull.channel(1);
                const float* chB = outFull.channel(2);

                #pragma omp parallel for schedule(static)
                for (int i = 0; i < total; ++i) {
                    uint32_t a = (src[i] >> 24) & 0xFF;
                    float r = (chR[i] + 1.0f) * 127.5f;
                    float g = (chG[i] + 1.0f) * 127.5f;
                    float b = (chB[i] + 1.0f) * 127.5f;
                    stylized[i] = (a << 24) | (clamp8(r) << 16) | (clamp8(g) << 8) | clamp8(b);
                }
                usedAnimeGan = true;
            }
        }
#endif
    }

    if (styleMode == 2) {
        // Mode 2: Clean Pencil / Ink Sketch (color-dodge Difference-of-Gaussians on luminance)
        std::vector<float> lum(total), blurLum(total);
        #pragma omp parallel for schedule(static)
        for (int i = 0; i < total; ++i) {
            uint32_t p = src[i];
            lum[i] = (0.2126f * ((p >> 16) & 0xFF) + 0.7152f * ((p >> 8) & 0xFF) + 0.0722f * (p & 0xFF)) / 255.0f;
        }
        int sRad = std::clamp(std::min(width, height) / 90, 3, 12);
        boxFilterFloat(lum.data(), blurLum.data(), width, height, sRad);
        std::vector<float> blur2(total);
        boxFilterFloat(blurLum.data(), blur2.data(), width, height, sRad);

        #pragma omp parallel for schedule(static)
        for (int i = 0; i < total; ++i) {
            float dodge = std::clamp(lum[i] / std::max(0.05f, blur2[i]), 0.0f, 1.0f);
            float ink = std::pow(dodge, 2.2f) * 255.0f;
            uint32_t orig = src[i];
            uint32_t a = (orig >> 24) & 0xFF;
            float r = ((orig >> 16) & 0xFF) * (1.0f - mix) + ink * mix;
            float g = ((orig >> 8) & 0xFF) * (1.0f - mix) + ink * mix;
            float b = (orig & 0xFF) * (1.0f - mix) + ink * mix;
            dst[i] = (a << 24) | (clamp8(r) << 16) | (clamp8(g) << 8) | clamp8(b);
        }
        return;
    }

    if (!usedAnimeGan) {
        // Clean bilateral fallback when NCNN isn't loaded (e.g. JVM unit tests)
        bilateralDenoise(src, stylized.data(), width, height, 3.5f, 22.0f);
    }

    if (styleMode == 1) {
        // Mode 1: Ilustrasi — AnimeGANv2 + crisp subtle ink lines + gentle vibrancy
        std::vector<float> lum(total);
        #pragma omp parallel for schedule(static)
        for (int i = 0; i < total; ++i) {
            uint32_t p = stylized[i];
            lum[i] = 0.2126f * ((p >> 16) & 0xFF) + 0.7152f * ((p >> 8) & 0xFF) + 0.0722f * (p & 0xFF);
        }

        #pragma omp parallel for schedule(static)
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                int i = y * width + x;
                int xL = std::max(0, x - 1), xR = std::min(width - 1, x + 1);
                int yU = std::max(0, y - 1), yD = std::min(height - 1, y + 1);

                float gx = (lum[yU * width + xR] + 2.0f * lum[y * width + xR] + lum[yD * width + xR]) -
                           (lum[yU * width + xL] + 2.0f * lum[y * width + xL] + lum[yD * width + xL]);
                float gy = (lum[yD * width + xL] + 2.0f * lum[yD * width + x] + lum[yD * width + xR]) -
                           (lum[yU * width + xL] + 2.0f * lum[yU * width + x] + lum[yU * width + xR]);
                float grad = std::sqrt(gx * gx + gy * gy) / 1020.0f;
                float ink = smoothstep(0.18f, 0.42f, grad) * 0.45f;

                uint32_t sp = stylized[i];
                float sr = ((sp >> 16) & 0xFF) * (1.0f - ink) + 28.0f * ink;
                float sg = ((sp >> 8) & 0xFF) * (1.0f - ink) + 22.0f * ink;
                float sb = (sp & 0xFF) * (1.0f - ink) + 32.0f * ink;

                uint32_t orig = src[i];
                uint32_t a = (orig >> 24) & 0xFF;
                float r = ((orig >> 16) & 0xFF) * (1.0f - mix) + sr * mix;
                float g = ((orig >> 8) & 0xFF) * (1.0f - mix) + sg * mix;
                float b = (orig & 0xFF) * (1.0f - mix) + sb * mix;
                dst[i] = (a << 24) | (clamp8(r) << 16) | (clamp8(g) << 8) | clamp8(b);
            }
        }
        return;
    }

    // Mode 0: Pure clean AnimeGANv2 blended by strength (no Kuwahara distortion!)
    #pragma omp parallel for schedule(static)
    for (int i = 0; i < total; ++i) {
        uint32_t orig = src[i];
        uint32_t sp = stylized[i];
        uint32_t a = (orig >> 24) & 0xFF;
        float r = ((orig >> 16) & 0xFF) * (1.0f - mix) + ((sp >> 16) & 0xFF) * mix;
        float g = ((orig >> 8) & 0xFF) * (1.0f - mix) + ((sp >> 8) & 0xFF) * mix;
        float b = (orig & 0xFF) * (1.0f - mix) + (sp & 0xFF) * mix;
        dst[i] = (a << 24) | (clamp8(r) << 16) | (clamp8(g) << 8) | clamp8(b);
    }
}

// ---------------------------------------------------------------------------
// Studio Drop Shadow & Anti-Aliased Sticker Outline (respects shadowAlpha!)
// ---------------------------------------------------------------------------

void studioShadowOutline(const uint32_t* src, const float* mask, uint32_t* dst, int width, int height,
                         int outlineWidth, uint32_t outlineColor,
                         int shadowOffsetX, int shadowOffsetY, float shadowBlur, float shadowAlphaParam, uint32_t bgColor) {
    if (!src || !mask || !dst || width <= 0 || height <= 0) return;

    int totalPixels = width * height;
    float bgR = (bgColor >> 16) & 0xFF, bgG = (bgColor >> 8) & 0xFF, bgB = bgColor & 0xFF;
    float outR = (outlineColor >> 16) & 0xFF, outG = (outlineColor >> 8) & 0xFF, outB = outlineColor & 0xFF;
    float sAlphaScale = std::clamp(shadowAlphaParam, 0.0f, 1.0f);

    std::vector<uint8_t> fgBinary(totalPixels, 0);
    for (int i = 0; i < totalPixels; ++i) fgBinary[i] = (mask[i] > 0.45f) ? 1 : 0;

    std::vector<float> distToFg(totalPixels, 0.0f);
    computeSeparableEDT(fgBinary.data(), distToFg.data(), width, height);

    float outW = static_cast<float>(std::max(0, outlineWidth));
    std::vector<float> outlineAlpha(totalPixels, 0.0f);
    std::vector<float> combinedMask(totalPixels, 0.0f);

    #pragma omp parallel for schedule(static)
    for (int i = 0; i < totalPixels; ++i) {
        float oa = (outW > 0.0f) ? (1.0f - smoothstep(std::max(0.0f, outW - 0.6f), outW + 0.6f, distToFg[i])) : 0.0f;
        outlineAlpha[i] = oa;
        combinedMask[i] = std::max(mask[i], oa);
    }

    std::vector<float> shiftedMask(totalPixels, 0.0f);
    #pragma omp parallel for schedule(static)
    for (int y = 0; y < height; ++y) {
        int sy = y - shadowOffsetY;
        for (int x = 0; x < width; ++x) {
            int sx = x - shadowOffsetX;
            if (sx >= 0 && sx < width && sy >= 0 && sy < height) {
                shiftedMask[y * width + x] = combinedMask[sy * width + sx];
            }
        }
    }

    std::vector<float> shadowAlpha(totalPixels, 0.0f);
    int sRad = std::clamp(static_cast<int>(shadowBlur), 1, 36);
    std::vector<float> tmpShadow(totalPixels, 0.0f);
    boxFilterFloat(shiftedMask.data(), tmpShadow.data(), width, height, sRad);
    boxFilterFloat(tmpShadow.data(), shadowAlpha.data(), width, height, sRad);

    #pragma omp parallel for schedule(static)
    for (int i = 0; i < totalPixels; ++i) {
        float cR = bgR, cG = bgG, cB = bgB;

        float sa = std::clamp(shadowAlpha[i] * sAlphaScale, 0.0f, 1.0f);
        cR *= (1.0f - sa); cG *= (1.0f - sa); cB *= (1.0f - sa);

        float oa = outlineAlpha[i];
        cR = cR * (1.0f - oa) + outR * oa;
        cG = cG * (1.0f - oa) + outG * oa;
        cB = cB * (1.0f - oa) + outB * oa;

        float subjA = std::clamp(mask[i], 0.0f, 1.0f);
        uint32_t orig = src[i];
        cR = cR * (1.0f - subjA) + ((orig >> 16) & 0xFF) * subjA;
        cG = cG * (1.0f - subjA) + ((orig >> 8) & 0xFF) * subjA;
        cB = cB * (1.0f - subjA) + (orig & 0xFF) * subjA;

        dst[i] = (0xFFU << 24) | (clamp8(cR) << 16) | (clamp8(cG) << 8) | clamp8(cB);
    }
}

// ---------------------------------------------------------------------------
// JNI Entrypoints
// ---------------------------------------------------------------------------

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_initAssetManagerNative(
        JNIEnv* env, jobject /*thiz*/, jobject assetManager) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    initColorLuts();
    if (g_asset_manager_ref) {
        env->DeleteGlobalRef(g_asset_manager_ref);
        g_asset_manager_ref = nullptr;
        g_asset_manager = nullptr;
    }
    if (!assetManager) return JNI_FALSE;
    g_asset_manager_ref = env->NewGlobalRef(assetManager);
    g_asset_manager = AAssetManager_fromJava(env, g_asset_manager_ref);
    LOGI("AssetManager registered for lazy NCNN model loading");
    return (g_asset_manager != nullptr) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_releaseModelsNative(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
#if USE_NCNN
    releaseAllNcnnNetsLocked();
#endif
    LOGI("Released all NCNN models from memory");
}

JNIEXPORT jintArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_applyLinearColorNative(
        JNIEnv* env, jobject /*thiz*/, jintArray srcPixels, jint width, jint height, jfloatArray params) {
    if (!srcPixels || !params || width <= 0 || height <= 0) return nullptr;
    if (env->GetArrayLength(params) < COLOR_PARAMS_COUNT) return nullptr;

    jint* src = env->GetIntArrayElements(srcPixels, nullptr);
    jfloat* p = env->GetFloatArrayElements(params, nullptr);
    std::vector<uint32_t> dst(width * height);

    if (src && p) {
        applyLinearColorPipeline(reinterpret_cast<const uint32_t*>(src), dst.data(), width, height, p);
    }

    if (src) env->ReleaseIntArrayElements(srcPixels, src, JNI_ABORT);
    if (p) env->ReleaseFloatArrayElements(params, p, JNI_ABORT);

    jintArray result = env->NewIntArray(width * height);
    if (result) env->SetIntArrayRegion(result, 0, width * height, reinterpret_cast<const jint*>(dst.data()));
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_analyzeAutoEnhanceNative(
        JNIEnv* env, jobject /*thiz*/, jintArray srcPixels, jint width, jint height) {
    if (!srcPixels || width <= 0 || height <= 0) return nullptr;
    jint* src = env->GetIntArrayElements(srcPixels, nullptr);
    float outParams[COLOR_PARAMS_COUNT] = {0.0f};
    if (src) {
        analyzeAutoEnhance(reinterpret_cast<const uint32_t*>(src), width, height, outParams);
        env->ReleaseIntArrayElements(srcPixels, src, JNI_ABORT);
    }
    jfloatArray res = env->NewFloatArray(COLOR_PARAMS_COUNT);
    if (res) env->SetFloatArrayRegion(res, 0, COLOR_PARAMS_COUNT, outParams);
    return res;
}

JNIEXPORT jintArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_processTileNative(
        JNIEnv* env, jobject /*thiz*/, jintArray inputPixels, jint tileW, jint tileH,
        jint mode, jfloat strength) {
    if (!inputPixels || tileW <= 0 || tileH <= 0) return nullptr;

    jint* srcElements = env->GetIntArrayElements(inputPixels, nullptr);
    if (!srcElements) return nullptr;

    const auto* src = reinterpret_cast<const uint32_t*>(srcElements);
    std::vector<uint32_t> dst;
    float clampedStrength = std::clamp(strength, 0.1f, 2.0f);

    switch (mode) {
        case MODE_SUPER_RESOLUTION:
        case MODE_COMBINED_ENHANCE: {
            const int scale = 4;
            int outW = tileW * scale, outH = tileH * scale;
            dst.resize(outW * outH);

            std::vector<uint32_t> preCleaned;
            const uint32_t* srInput = src;
            if (mode == MODE_COMBINED_ENHANCE) {
                preCleaned.resize(tileW * tileH);
                bilateralDenoise(src, preCleaned.data(), tileW, tileH, 1.8f, 9.0f);
                srInput = preCleaned.data();
            }

            bool ncnnDone = false;
#if USE_NCNN
            {
                std::lock_guard<std::mutex> lock(g_model_mutex);
                ncnn::Net* activeNet = (mode == MODE_COMBINED_ENHANCE) ? ensureWdnNet() : ensureSrNet();
                if (!activeNet) activeNet = ensureSrNet();
                if (activeNet) {
                    std::vector<unsigned char> rgbIn(tileW * tileH * 3);
                    for (int i = 0; i < tileW * tileH; ++i) {
                        uint32_t p = srInput[i];
                        rgbIn[i * 3 + 0] = (p >> 16) & 0xFF;
                        rgbIn[i * 3 + 1] = (p >> 8) & 0xFF;
                        rgbIn[i * 3 + 2] = p & 0xFF;
                    }
                    ncnn::Mat in = ncnn::Mat::from_pixels(rgbIn.data(), ncnn::Mat::PIXEL_RGB, tileW, tileH);
                    const float meanVals[3] = {0.0f, 0.0f, 0.0f};
                    const float normVals[3] = {1.0f / 255.0f, 1.0f / 255.0f, 1.0f / 255.0f};
                    in.substract_mean_normalize(meanVals, normVals);

                    ncnn::Extractor ex = activeNet->create_extractor();
                    ex.input("data", in);
                    ncnn::Mat out;
                    if (ex.extract("output", out) == 0 && out.w == outW && out.h == outH) {
                        const float denormVals[3] = {255.0f, 255.0f, 255.0f};
                        out.substract_mean_normalize(meanVals, denormVals);
                        std::vector<unsigned char> rgbOut(outW * outH * 3);
                        out.to_pixels(rgbOut.data(), ncnn::Mat::PIXEL_RGB);
                        for (int i = 0; i < outW * outH; ++i) {
                            dst[i] = (0xFFU << 24) |
                                     (static_cast<uint32_t>(rgbOut[i * 3 + 0]) << 16) |
                                     (static_cast<uint32_t>(rgbOut[i * 3 + 1]) << 8) |
                                     static_cast<uint32_t>(rgbOut[i * 3 + 2]);
                        }
                        ncnnDone = true;
                    }
                }
            }
#endif
            if (!ncnnDone) {
                superResolutionFallback(srInput, dst.data(), tileW, tileH, scale, 0.75f * clampedStrength);
            }

            if (clampedStrength < 0.98f) {
                std::vector<uint32_t> bicubicBase(outW * outH);
                bicubicResize(src, bicubicBase.data(), tileW, tileH, outW, outH);
                float alpha = std::clamp(clampedStrength, 0.0f, 1.0f);
                #pragma omp parallel for schedule(static)
                for (int i = 0; i < outW * outH; ++i) {
                    uint32_t pA = bicubicBase[i], pB = dst[i];
                    float r = ((pA >> 16) & 0xFF) * (1.0f - alpha) + ((pB >> 16) & 0xFF) * alpha;
                    float g = ((pA >> 8) & 0xFF) * (1.0f - alpha) + ((pB >> 8) & 0xFF) * alpha;
                    float b = (pA & 0xFF) * (1.0f - alpha) + (pB & 0xFF) * alpha;
                    dst[i] = 0xFF000000 | (clamp8(r) << 16) | (clamp8(g) << 8) | clamp8(b);
                }
            }
            break;
        }
        case MODE_DENOISE_ONLY:
        case MODE_BILATERAL_DENOISE: {
            dst.resize(tileW * tileH);
            // Edge-preserving bilateral denoise + gentle detail restoration
            std::vector<uint32_t> tmp(tileW * tileH);
            bilateralDenoise(src, tmp.data(), tileW, tileH, 2.5f * clampedStrength, 22.0f * clampedStrength);
            unsharpMask(tmp.data(), dst.data(), tileW, tileH, 0.25f * clampedStrength, 1.0f);
            break;
        }
        case MODE_UNSHARP_MASK: {
            dst.resize(tileW * tileH);
            unsharpMask(src, dst.data(), tileW, tileH, 1.0f * clampedStrength, 1.3f);
            break;
        }
        case MODE_FACE_RESTORE: {
            dst.resize(tileW * tileH);
            restoreFacesNeural(src, dst.data(), tileW, tileH, clampedStrength);
            break;
        }
        default: {
            dst.resize(tileW * tileH);
            std::memcpy(dst.data(), src, tileW * tileH * sizeof(uint32_t));
            break;
        }
    }

    env->ReleaseIntArrayElements(inputPixels, srcElements, JNI_ABORT);

    jintArray result = env->NewIntArray(static_cast<jsize>(dst.size()));
    if (result) {
        env->SetIntArrayRegion(result, 0, static_cast<jsize>(dst.size()), reinterpret_cast<const jint*>(dst.data()));
    }
    return result;
}

// Fixed RGBA_8888 (0xAABBGGRR) <-> ARGB_8888 (0xAARRGGBB) channel swap!
static inline uint32_t swapRB(uint32_t p) {
    return (p & 0xFF00FF00u) | ((p & 0x00FF0000u) >> 16) | ((p & 0x000000FFu) << 16);
}

JNIEXPORT jboolean JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_processBitmapNative(
        JNIEnv* env, jobject /*thiz*/, jobject inputBitmap, jobject outputBitmap,
        jint mode, jfloat strength) {
    AndroidBitmapInfo inInfo, outInfo;
    if (AndroidBitmap_getInfo(env, inputBitmap, &inInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, outputBitmap, &outInfo) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return JNI_FALSE;
    }
    if (inInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        outInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        inInfo.width != outInfo.width || inInfo.height != outInfo.height) {
        return JNI_FALSE;
    }

    void* inPixels = nullptr;
    void* outPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, inputBitmap, &inPixels) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    if (AndroidBitmap_lockPixels(env, outputBitmap, &outPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        AndroidBitmap_unlockPixels(env, inputBitmap);
        return JNI_FALSE;
    }

    int w = static_cast<int>(inInfo.width), h = static_cast<int>(inInfo.height);
    int total = w * h;
    std::vector<uint32_t> argbIn(total), argbOut(total);
    const auto* rawIn = static_cast<const uint32_t*>(inPixels);
    for (int i = 0; i < total; ++i) argbIn[i] = swapRB(rawIn[i]);

    float s = std::clamp(strength, 0.1f, 2.0f);
    switch (mode) {
        case MODE_DENOISE_ONLY:
        case MODE_BILATERAL_DENOISE:
            bilateralDenoise(argbIn.data(), argbOut.data(), w, h, 2.5f * s, 22.0f * s);
            break;
        case MODE_UNSHARP_MASK:
            unsharpMask(argbIn.data(), argbOut.data(), w, h, 1.0f * s, 1.3f);
            break;
        case MODE_FACE_RESTORE:
            restoreFacesNeural(argbIn.data(), argbOut.data(), w, h, s);
            break;
        default: {
            std::vector<uint32_t> tmp(total);
            bilateralDenoise(argbIn.data(), tmp.data(), w, h, 2.0f * s, 18.0f * s);
            unsharpMask(tmp.data(), argbOut.data(), w, h, 0.8f * s, 1.1f);
            break;
        }
    }

    auto* rawOut = static_cast<uint32_t*>(outPixels);
    for (int i = 0; i < total; ++i) rawOut[i] = swapRB(argbOut[i]);

    AndroidBitmap_unlockPixels(env, outputBitmap);
    AndroidBitmap_unlockPixels(env, inputBitmap);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_blendTileWithWeightNative(
        JNIEnv* env, jobject /*thiz*/, jintArray tilePixels, jintArray targetPixels, jfloatArray weightMap,
        jint tileW, jint tileH, jint targetW, jint targetH,
        jint offsetX, jint offsetY, jint overlapPx) {
    if (!tilePixels || !targetPixels || !weightMap) return;
    jint* tilePtr = env->GetIntArrayElements(tilePixels, nullptr);
    jint* targetPtr = env->GetIntArrayElements(targetPixels, nullptr);
    jfloat* weightPtr = env->GetFloatArrayElements(weightMap, nullptr);

    if (tilePtr && targetPtr && weightPtr) {
        cosineTileBlendWithOverlap(
            reinterpret_cast<const uint32_t*>(tilePtr), tileW, tileH, offsetX, offsetY,
            reinterpret_cast<uint32_t*>(targetPtr), weightPtr, targetW, targetH, overlapPx);
    }

    if (tilePtr) env->ReleaseIntArrayElements(tilePixels, tilePtr, JNI_ABORT);
    if (targetPtr) env->ReleaseIntArrayElements(targetPixels, targetPtr, 0);
    if (weightPtr) env->ReleaseFloatArrayElements(weightMap, weightPtr, 0);
}

JNIEXPORT jfloatArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_detectFacesNative(
        JNIEnv* env, jobject /*thiz*/, jintArray srcPixels, jint width, jint height) {
    if (!srcPixels || width <= 0 || height <= 0) return nullptr;
    jint* srcPtr = env->GetIntArrayElements(srcPixels, nullptr);
    if (!srcPtr) return nullptr;

    std::vector<DetectedFace> faces = detectFacesInternal(reinterpret_cast<const uint32_t*>(srcPtr), width, height);
    env->ReleaseIntArrayElements(srcPixels, srcPtr, JNI_ABORT);

    std::vector<float> flat;
    flat.reserve(faces.size() * 15);
    for (const auto& f : faces) {
        flat.push_back(f.x); flat.push_back(f.y); flat.push_back(f.w); flat.push_back(f.h); flat.push_back(f.score);
        for (int k = 0; k < 10; ++k) flat.push_back(f.landmarks[k]);
    }
    jfloatArray out = env->NewFloatArray(static_cast<jsize>(flat.size()));
    if (out && !flat.empty()) env->SetFloatArrayRegion(out, 0, static_cast<jsize>(flat.size()), flat.data());
    return out;
}

JNIEXPORT jintArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_restoreFacesNative(
        JNIEnv* env, jobject /*thiz*/, jintArray srcPixels, jint width, jint height, jfloat strength) {
    if (!srcPixels || width <= 0 || height <= 0) return nullptr;
    jint* srcPtr = env->GetIntArrayElements(srcPixels, nullptr);
    if (!srcPtr) return nullptr;

    std::vector<uint32_t> dst(width * height);
    restoreFacesNeural(reinterpret_cast<const uint32_t*>(srcPtr), dst.data(), width, height, strength);
    env->ReleaseIntArrayElements(srcPixels, srcPtr, JNI_ABORT);

    jintArray result = env->NewIntArray(width * height);
    if (result) env->SetIntArrayRegion(result, 0, width * height, reinterpret_cast<const jint*>(dst.data()));
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_extractAlphaMatteNative(
        JNIEnv* env, jobject /*thiz*/, jintArray srcPixels, jfloatArray coarseMask, jint width, jint height, jboolean isPortraitHint) {
    if (!srcPixels || width <= 0 || height <= 0) return nullptr;
    jint* srcPtr = env->GetIntArrayElements(srcPixels, nullptr);
    jfloat* maskPtr = coarseMask ? env->GetFloatArrayElements(coarseMask, nullptr) : nullptr;

    std::vector<float> outAlpha(width * height, 0.0f);
    if (srcPtr) {
        extractAlphaMatteInternal(reinterpret_cast<const uint32_t*>(srcPtr), maskPtr, outAlpha.data(), width, height, isPortraitHint == JNI_TRUE);
    }

    if (srcPtr) env->ReleaseIntArrayElements(srcPixels, srcPtr, JNI_ABORT);
    if (maskPtr) env->ReleaseFloatArrayElements(coarseMask, maskPtr, JNI_ABORT);

    jfloatArray result = env->NewFloatArray(width * height);
    if (result) env->SetFloatArrayRegion(result, 0, width * height, outAlpha.data());
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_estimateDepthNative(
        JNIEnv* env, jobject /*thiz*/, jintArray srcPixels, jfloatArray subjectMask, jint width, jint height) {
    if (!srcPixels || width <= 0 || height <= 0) return nullptr;
    jint* srcPtr = env->GetIntArrayElements(srcPixels, nullptr);
    jfloat* maskPtr = subjectMask ? env->GetFloatArrayElements(subjectMask, nullptr) : nullptr;

    std::vector<float> outDepth(width * height, 0.0f);
    if (srcPtr) {
        estimateDepthMapInternal(reinterpret_cast<const uint32_t*>(srcPtr), maskPtr, outDepth.data(), width, height);
    }

    if (srcPtr) env->ReleaseIntArrayElements(srcPixels, srcPtr, JNI_ABORT);
    if (maskPtr) env->ReleaseFloatArrayElements(subjectMask, maskPtr, JNI_ABORT);

    jfloatArray result = env->NewFloatArray(width * height);
    if (result) env->SetFloatArrayRegion(result, 0, width * height, outDepth.data());
    return result;
}

JNIEXPORT jintArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_inpaintNative(
        JNIEnv* env, jobject /*thiz*/, jintArray srcPixels, jintArray maskPixels,
        jint width, jint height, jint radius) {
    if (!srcPixels || !maskPixels || width <= 0 || height <= 0) return nullptr;
    jint* src = env->GetIntArrayElements(srcPixels, nullptr);
    jint* mask = env->GetIntArrayElements(maskPixels, nullptr);
    std::vector<uint32_t> dst(width * height);

    if (src && mask) {
        teleaInpaint(reinterpret_cast<const uint32_t*>(src), mask, dst.data(), width, height, radius);
    }

    if (src) env->ReleaseIntArrayElements(srcPixels, src, JNI_ABORT);
    if (mask) env->ReleaseIntArrayElements(maskPixels, mask, JNI_ABORT);

    jintArray result = env->NewIntArray(width * height);
    if (result) env->SetIntArrayRegion(result, 0, width * height, reinterpret_cast<const jint*>(dst.data()));
    return result;
}

JNIEXPORT jintArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_applyBokehNative(
        JNIEnv* env, jobject /*thiz*/, jintArray srcPixels, jfloatArray depthMask,
        jint width, jint height, jfloat blurRadius, jfloat focusDepth) {
    if (!srcPixels || !depthMask || width <= 0 || height <= 0) return nullptr;
    jint* src = env->GetIntArrayElements(srcPixels, nullptr);
    jfloat* depth = env->GetFloatArrayElements(depthMask, nullptr);
    std::vector<uint32_t> dst(width * height);

    if (src && depth) {
        depthBokehLinear(reinterpret_cast<const uint32_t*>(src), depth, dst.data(), width, height, blurRadius, focusDepth);
    }

    if (src) env->ReleaseIntArrayElements(srcPixels, src, JNI_ABORT);
    if (depth) env->ReleaseFloatArrayElements(depthMask, depth, JNI_ABORT);

    jintArray result = env->NewIntArray(width * height);
    if (result) env->SetIntArrayRegion(result, 0, width * height, reinterpret_cast<const jint*>(dst.data()));
    return result;
}

JNIEXPORT jintArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_applyRelightingNative(
        JNIEnv* env, jobject /*thiz*/, jintArray srcPixels, jfloatArray depthMask,
        jint width, jint height, jfloat lightX, jfloat lightY, jfloat lightZ,
        jfloat lightR, jfloat lightG, jfloat lightB, jfloat intensity) {
    if (!srcPixels || !depthMask || width <= 0 || height <= 0) return nullptr;
    jint* src = env->GetIntArrayElements(srcPixels, nullptr);
    jfloat* depth = env->GetFloatArrayElements(depthMask, nullptr);
    std::vector<uint32_t> dst(width * height);

    if (src && depth) {
        relighting3D(reinterpret_cast<const uint32_t*>(src), depth, dst.data(), width, height,
                     lightX, lightY, lightZ, lightR, lightG, lightB, intensity);
    }

    if (src) env->ReleaseIntArrayElements(srcPixels, src, JNI_ABORT);
    if (depth) env->ReleaseFloatArrayElements(depthMask, depth, JNI_ABORT);

    jintArray result = env->NewIntArray(width * height);
    if (result) env->SetIntArrayRegion(result, 0, width * height, reinterpret_cast<const jint*>(dst.data()));
    return result;
}

JNIEXPORT jintArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_applyAnimeStylizeNative(
        JNIEnv* env, jobject /*thiz*/, jintArray srcPixels, jint width, jint height,
        jint styleMode, jfloat strength) {
    if (!srcPixels || width <= 0 || height <= 0) return nullptr;
    jint* src = env->GetIntArrayElements(srcPixels, nullptr);
    std::vector<uint32_t> dst(width * height);

    if (src) {
        animeStylize(reinterpret_cast<const uint32_t*>(src), dst.data(), width, height, styleMode, strength);
    }

    if (src) env->ReleaseIntArrayElements(srcPixels, src, JNI_ABORT);

    jintArray result = env->NewIntArray(width * height);
    if (result) env->SetIntArrayRegion(result, 0, width * height, reinterpret_cast<const jint*>(dst.data()));
    return result;
}

JNIEXPORT jintArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_applyStudioShadowOutlineNative(
        JNIEnv* env, jobject /*thiz*/, jintArray srcPixels, jfloatArray mask,
        jint width, jint height, jint outlineWidth, jint outlineColor,
        jint shadowOffsetX, jint shadowOffsetY, jfloat shadowBlur, jfloat shadowAlpha, jint bgColor) {
    if (!srcPixels || !mask || width <= 0 || height <= 0) return nullptr;
    jint* src = env->GetIntArrayElements(srcPixels, nullptr);
    jfloat* m = env->GetFloatArrayElements(mask, nullptr);
    std::vector<uint32_t> dst(width * height);

    if (src && m) {
        studioShadowOutline(reinterpret_cast<const uint32_t*>(src), m, dst.data(), width, height,
                            outlineWidth, static_cast<uint32_t>(outlineColor),
                            shadowOffsetX, shadowOffsetY, shadowBlur, shadowAlpha, static_cast<uint32_t>(bgColor));
    }

    if (src) env->ReleaseIntArrayElements(srcPixels, src, JNI_ABORT);
    if (m) env->ReleaseFloatArrayElements(mask, m, JNI_ABORT);

    jintArray result = env->NewIntArray(width * height);
    if (result) env->SetIntArrayRegion(result, 0, width * height, reinterpret_cast<const jint*>(dst.data()));
    return result;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    initColorLuts();
    LOGI("ArAr Shot v3.0 native engine loaded (Linear-Light C++17 + Lazy NCNN)");
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* /*reserved*/) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
#if USE_NCNN
    releaseAllNcnnNetsLocked();
#endif
    JNIEnv* env = nullptr;
    if (g_asset_manager_ref && vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
        env->DeleteGlobalRef(g_asset_manager_ref);
    }
    g_asset_manager_ref = nullptr;
    g_asset_manager = nullptr;
}

} // extern "C"

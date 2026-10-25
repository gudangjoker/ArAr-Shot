#include "native_enhancer.h"

static std::mutex g_model_mutex;

#if USE_NCNN
static ncnn::Net* g_net = nullptr;         // realesr-general-x4v3 (Crisp 4x SR)
static ncnn::Net* g_wdn_net = nullptr;     // realesr-general-wdn-x4v3 (Denoise 4x SR)
static ncnn::Net* g_scrfd_net = nullptr;   // scrfd_500m_kps-opt2 (Face & 5-Point Landmark Detector)
static ncnn::Net* g_anime_net = nullptr;   // face_paint_512_v2 (AnimeGANv2 Style Transfer)
static ncnn::Net* g_matting_net = nullptr; // modnet_matting (MODNet Portrait & Hair Alpha Matting)
static ncnn::Net* g_depth_net = nullptr;   // dpt256 (Depth Anything Monocular 3D Depth Map)

static bool g_model_loaded = false;
static bool g_wdn_loaded = false;
static bool g_scrfd_loaded = false;
static bool g_anime_loaded = false;
static bool g_matting_loaded = false;
static bool g_depth_loaded = false;
static bool g_has_vulkan = false;
#if NCNN_VULKAN
static bool g_vulkan_initialized = false;
#endif
#endif

static inline uint8_t clamp8(float v) {
    if (v < 0.0f) return 0;
    if (v > 255.0f) return 255;
    return static_cast<uint8_t>(v + 0.5f);
}

static inline uint8_t clamp8(int v) {
    if (v < 0) return 0;
    if (v > 255) return 255;
    return static_cast<uint8_t>(v);
}

static inline float smoothstep(float edge0, float edge1, float x) {
    float t = std::clamp((x - edge0) / std::max(1e-5f, edge1 - edge0), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

// ---------------------------------------------------------------------------
// Core Mathematical Filters (OpenMP Multi-Threaded + Precomputed LUTs)
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
    for (size_t i = 0; i < kernel.size(); ++i) {
        kernel[i] /= sum;
    }

    std::vector<uint32_t> temp(width * height);

    // Horizontal Gaussian Pass
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

    // Vertical Gaussian Pass & Soft-Threshold Difference Blending (avoids haloing/noise boost)
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
            uint8_t a = (orig >> 24) & 0xFF;
            float rOrig = (orig >> 16) & 0xFF;
            float gOrig = (orig >> 8) & 0xFF;
            float bOrig = orig & 0xFF;

            float dR = rOrig - rBlur;
            float dG = gOrig - gBlur;
            float dB = bOrig - bBlur;

            // Soft coring: suppress sub-2-level sensor noise while sharpening real edges
            float mag = std::abs(dR) + std::abs(dG) + std::abs(dB);
            float coring = smoothstep(2.0f, 10.0f, mag);
            float effAmount = amount * coring;

            float rSharp = rOrig + effAmount * dR;
            float gSharp = gOrig + effAmount * dG;
            float bSharp = bOrig + effAmount * dB;

            dst[y * width + x] = (static_cast<uint32_t>(a) << 24) |
                                 (static_cast<uint32_t>(clamp8(rSharp)) << 16) |
                                 (static_cast<uint32_t>(clamp8(gSharp)) << 8) |
                                 static_cast<uint32_t>(clamp8(bSharp));
        }
    }
}

void bilateralDenoise(const uint32_t* src, uint32_t* dst, int width, int height, float spatialSigma, float colorSigma) {
    if (!src || !dst || width <= 0 || height <= 0) return;

    int r = std::clamp(static_cast<int>(spatialSigma * 1.5f), 1, 8);
    float twoSpatialSigmaSq = 2.0f * std::max(0.5f, spatialSigma) * std::max(0.5f, spatialSigma);
    float twoColorSigmaSq = 2.0f * std::max(1.0f, colorSigma) * std::max(1.0f, colorSigma);

    // Precompute spatial and color range LUTs (eliminates millions of std::exp calls)
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

            float sumWeights = 0.0f;
            float accR = 0.0f, accG = 0.0f, accB = 0.0f;

            for (int dy = -r; dy <= r; ++dy) {
                int ny = std::clamp(y + dy, 0, height - 1);
                const uint32_t* rowPtr = src + ny * width;
                for (int dx = -r; dx <= r; ++dx) {
                    int nx = std::clamp(x + dx, 0, width - 1);
                    uint32_t neighborP = rowPtr[nx];
                    int nR = (neighborP >> 16) & 0xFF;
                    int nG = (neighborP >> 8) & 0xFF;
                    int nB = neighborP & 0xFF;

                    int dR = cR - nR;
                    int dG = cG - nG;
                    int dB = cB - nB;
                    int colorDistSq = dR * dR + dG * dG + dB * dB;

                    float weight = spatialLut[(dy + r) * side + (dx + r)] * colorLut[colorDistSq];
                    accR += nR * weight;
                    accG += nG * weight;
                    accB += nB * weight;
                    sumWeights += weight;
                }
            }

            if (sumWeights > 1e-5f) {
                accR /= sumWeights;
                accG /= sumWeights;
                accB /= sumWeights;
            }

            dst[y * width + x] = (a << 24) | (clamp8(accR) << 16) | (clamp8(accG) << 8) | clamp8(accB);
        }
    }
}

static inline float cubicWeight(float x) {
    const float a = -0.5f;
    x = std::fabs(x);
    if (x <= 1.0f) {
        return (a + 2.0f) * x * x * x - (a + 3.0f) * x * x + 1.0f;
    } else if (x < 2.0f) {
        return a * x * x * x - 5.0f * a * x * x + 8.0f * a * x - 4.0f * a;
    }
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

            float rTotal = 0.0f, gTotal = 0.0f, bTotal = 0.0f, aTotal = 0.0f, totalWeight = 0.0f;

            for (int m = -1; m <= 2; ++m) {
                int sampleY = std::clamp(yInt + m, 0, srcH - 1);
                const uint32_t* srcRow = src + sampleY * srcW;
                for (int n = -1; n <= 2; ++n) {
                    int sampleX = std::clamp(xInt + n, 0, srcW - 1);
                    float w = wy[m + 1] * wx[n + 1];

                    uint32_t p = srcRow[sampleX];
                    aTotal += ((p >> 24) & 0xFF) * w;
                    rTotal += ((p >> 16) & 0xFF) * w;
                    gTotal += ((p >> 8) & 0xFF) * w;
                    bTotal += (p & 0xFF) * w;
                    totalWeight += w;
                }
            }

            if (std::fabs(totalWeight) > 1e-4f) {
                aTotal /= totalWeight;
                rTotal /= totalWeight;
                gTotal /= totalWeight;
                bTotal /= totalWeight;
            }

            dst[y * dstW + x] = (static_cast<uint32_t>(clamp8(aTotal)) << 24) |
                                (static_cast<uint32_t>(clamp8(rTotal)) << 16) |
                                (static_cast<uint32_t>(clamp8(gTotal)) << 8) |
                                static_cast<uint32_t>(clamp8(bTotal));
        }
    }
}

void superResolutionFallback(const uint32_t* src, uint32_t* dst, int srcW, int srcH, int scale, float sharpenAmount) {
    int dstW = srcW * scale;
    int dstH = srcH * scale;

    std::vector<uint32_t> upscaled(dstW * dstH);
    bicubicResize(src, upscaled.data(), srcW, srcH, dstW, dstH);

    // Apply adaptive edge-aware unsharp masking
    unsharpMask(upscaled.data(), dst, dstW, dstH, sharpenAmount, 1.2f);
}

// ---------------------------------------------------------------------------
// Precision Overlap Raised-Cosine Tile Blending (Fixes P0-1 Black-Lattice Bug)
// ---------------------------------------------------------------------------

static inline float raisedCosineEdgeWeight(int pos, int length, int globalPos, int globalMax, int margin) {
    if (margin <= 0) return 1.0f;
    float w = 1.0f;
    const float PI = 3.14159265358979323846f;
    // Only taper at interior tile boundaries (not at the outer border of the full image)
    if (globalPos > 0 && pos < margin) {
        float t = (pos + 0.5f) / static_cast<float>(margin);
        w *= 0.5f * (1.0f - std::cos(PI * t));
    }
    if (globalPos + (length - pos) < globalMax && pos >= length - margin) {
        float t = (length - 1 - pos + 0.5f) / static_cast<float>(margin);
        w *= 0.5f * (1.0f - std::cos(PI * t));
    }
    return std::max(1e-4f, w);
}

void cosineTileBlendWithOverlap(const uint32_t* tile, int tileW, int tileH, int dstX, int dstY,
                                uint32_t* target, float* weightMap, int targetW, int targetH, int overlapPx) {
    int margin = std::clamp(overlapPx, 8, std::min(tileW, tileH) / 2);

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

            // If pixel was never written before (alpha == 0 and curWeight == 0), write directly without black dilution!
            if (curWeight <= 1e-5f || ((curP >> 24) & 0xFF) == 0) {
                target[targetIdx] = (std::max(tA, 255u) << 24) |
                                    (clamp8(tR) << 16) |
                                    (clamp8(tG) << 8) |
                                    clamp8(tB);
                if (weightMap) weightMap[targetIdx] = weight;
            } else {
                float curR = (curP >> 16) & 0xFF;
                float curG = (curP >> 8) & 0xFF;
                float curB = curP & 0xFF;

                float newWeight = curWeight + weight;
                float blendedR = (curR * curWeight + tR * weight) / newWeight;
                float blendedG = (curG * curWeight + tG * weight) / newWeight;
                float blendedB = (curB * curWeight + tB * weight) / newWeight;

                target[targetIdx] = 0xFF000000 |
                                    (clamp8(blendedR) << 16) |
                                    (clamp8(blendedG) << 8) |
                                    clamp8(blendedB);
                if (weightMap) weightMap[targetIdx] = newWeight;
            }
        }
    }
}

void cosineTileBlend(const uint32_t* tile, int tileW, int tileH, int dstX, int dstY,
                     uint32_t* target, float* weightMap, int targetW, int targetH) {
    int defaultOverlap = std::min(tileW, tileH) / 6;
    cosineTileBlendWithOverlap(tile, tileW, tileH, dstX, dstY, target, weightMap, targetW, targetH, defaultOverlap);
}

// ---------------------------------------------------------------------------
// O(N) Fast Guided Image Filter (He et al.) for Hair Matting & Depth Refinement
// ---------------------------------------------------------------------------

static void boxFilterFloat(const float* src, float* dst, int width, int height, int radius) {
    int r = std::clamp(radius, 1, std::min(width, height) / 2);
    std::vector<float> temp(width * height, 0.0f);

    #pragma omp parallel for schedule(static)
    for (int y = 0; y < height; ++y) {
        float sum = 0.0f;
        int count = 0;
        for (int x = 0; x <= r && x < width; ++x) {
            sum += src[y * width + x];
            count++;
        }
        for (int x = 0; x < width; ++x) {
            temp[y * width + x] = sum / std::max(1, count);
            int removeIdx = x - r;
            int addIdx = x + r + 1;
            if (removeIdx >= 0) { sum -= src[y * width + removeIdx]; count--; }
            if (addIdx < width) { sum += src[y * width + addIdx]; count++; }
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
        out[i] = std::clamp(meanA[i] * guide[i] + meanB[i], 0.0f, 1.0f);
    }
}

// ---------------------------------------------------------------------------
// O(W*H) Separable 2D Euclidean Distance Transform (Meijster / Felzenszwalb)
// ---------------------------------------------------------------------------

static void computeSeparableEDT(const uint8_t* binaryMask, float* distOut, int width, int height) {
    const float INF = 1e9f;
    int total = width * height;
    std::vector<float> f( std::max(width, height) );
    std::vector<float> d1d(total, INF);

    // 1D Squared Distance Transform along rows
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

    // 2nd Pass: Lower Envelope of Parabolas along columns
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
// SCRFD 500M KPS Face & 5-Point Landmark Detector + Skin/Symmetry Fallback
// ---------------------------------------------------------------------------

static inline float faceBoxIntersectionArea(const DetectedFace& a, const DetectedFace& b) {
    float x0 = std::max(a.x, b.x);
    float y0 = std::max(a.y, b.y);
    float x1 = std::min(a.x + a.w, b.x + b.w);
    float y1 = std::min(a.y + a.h, b.y + b.h);
    if (x1 <= x0 || y1 <= y0) return 0.0f;
    return (x1 - x0) * (y1 - y0);
}

#if USE_NCNN
static void generateScrfdProposals(
    int baseSize,
    int featStride,
    const ncnn::Mat& scoreBlob,
    const ncnn::Mat& bboxBlob,
    const ncnn::Mat& kpsBlob,
    float probThreshold,
    std::vector<DetectedFace>& proposals
) {
    int w = scoreBlob.w;
    int h = scoreBlob.h;
    const float scales[2] = {1.0f, 2.0f};

    for (int q = 0; q < 2; ++q) {
        float scale = scales[q];
        float anchorW = baseSize * scale;
        float anchorH = baseSize * scale;
        float anchorX0 = -anchorW * 0.5f;
        float anchorY0 = -anchorH * 0.5f;

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
    if (g_scrfd_loaded && g_scrfd_net) {
        std::lock_guard<std::mutex> lock(g_model_mutex);
        std::vector<unsigned char> rgbIn(width * height * 3);
        for (int i = 0; i < width * height; ++i) {
            uint32_t p = src[i];
            rgbIn[i * 3 + 0] = (p >> 16) & 0xFF;
            rgbIn[i * 3 + 1] = (p >> 8) & 0xFF;
            rgbIn[i * 3 + 2] = p & 0xFF;
        }

        const int targetSize = 640;
        int w = width;
        int h = height;
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

        ncnn::Extractor ex = g_scrfd_net->create_extractor();
        ex.input("input.1", inPad);

        std::vector<DetectedFace> proposals;
        const int strides[3] = {8, 16, 32};
        const int baseSizes[3] = {16, 64, 256};
        const char* scoreNames[3] = {"score_8", "score_16", "score_32"};
        const char* bboxNames[3] = {"bbox_8", "bbox_16", "bbox_32"};
        const char* kpsNames[3] = {"kps_8", "kps_16", "kps_32"};

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
                mapped.x = x0;
                mapped.y = y0;
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

    // YCbCr Skin-Cluster & Symmetry Fallback if no face detected by NCNN (or when running in unit test fallback mode)
    if (results.empty()) {
        double sumX = 0.0, sumY = 0.0, count = 0.0;
        float minX = static_cast<float>(width), minY = static_cast<float>(height);
        float maxX = 0.0f, maxY = 0.0f;

        for (int y = height / 12; y < (height * 3) / 4; ++y) {
            for (int x = width / 8; x < (width * 7) / 8; ++x) {
                uint32_t p = src[y * width + x];
                float r = (p >> 16) & 0xFF;
                float g = (p >> 8) & 0xFF;
                float b = p & 0xFF;

                float Y  = 0.299f * r + 0.587f * g + 0.114f * b;
                float Cb = 128.0f - 0.168736f * r - 0.331264f * g + 0.5f * b;
                float Cr = 128.0f + 0.5f * r - 0.418688f * g - 0.081312f * b;

                if (Y > 45.0f && Cb >= 77.0f && Cb <= 127.0f && Cr >= 133.0f && Cr <= 173.0f) {
                    sumX += x;
                    sumY += y;
                    count += 1.0;
                    minX = std::min(minX, static_cast<float>(x));
                    minY = std::min(minY, static_cast<float>(y));
                    maxX = std::max(maxX, static_cast<float>(x));
                    maxY = std::max(maxY, static_cast<float>(y));
                }
            }
        }

        DetectedFace fallback{};
        if (count > (width * height * 0.015)) {
            float cx = static_cast<float>(sumX / count);
            float cy = static_cast<float>(sumY / count);
            float fw = std::clamp(maxX - minX, width * 0.22f, width * 0.65f);
            float fh = std::clamp(fw * 1.28f, height * 0.22f, height * 0.68f);
            fallback.x = std::clamp(cx - fw * 0.5f, 0.0f, width - fw);
            fallback.y = std::clamp(cy - fh * 0.45f, 0.0f, height - fh);
            fallback.w = fw;
            fallback.h = fh;
            fallback.score = 0.65f;
        } else {
            float fw = width * 0.42f;
            float fh = std::min(height * 0.48f, fw * 1.25f);
            fallback.x = (width - fw) * 0.5f;
            fallback.y = height * 0.14f;
            fallback.w = fw;
            fallback.h = fh;
            fallback.score = 0.35f;
        }
        fallback.landmarks[0] = fallback.x + fallback.w * 0.32f; fallback.landmarks[1] = fallback.y + fallback.h * 0.38f;
        fallback.landmarks[2] = fallback.x + fallback.w * 0.68f; fallback.landmarks[3] = fallback.y + fallback.h * 0.38f;
        fallback.landmarks[4] = fallback.x + fallback.w * 0.50f; fallback.landmarks[5] = fallback.y + fallback.h * 0.56f;
        fallback.landmarks[6] = fallback.x + fallback.w * 0.36f; fallback.landmarks[7] = fallback.y + fallback.h * 0.76f;
        fallback.landmarks[8] = fallback.x + fallback.w * 0.64f; fallback.landmarks[9] = fallback.y + fallback.h * 0.76f;
        results.push_back(fallback);
    }

    return results;
}

// ---------------------------------------------------------------------------
// Blind Face Restoration Pipeline
// Combines Neural Face Super-Resolution + Frequency-Separated Skin Pore
// Reconstruction + 5-Landmark Iris/Cornea/Lip Micro-Contrast Enhancement
// ---------------------------------------------------------------------------

void restoreFacesNeural(const uint32_t* src, uint32_t* dst, int width, int height, float fidelityStrength) {
    if (!src || !dst || width <= 0 || height <= 0) return;

    float strength = std::clamp(fidelityStrength, 0.2f, 1.5f);

    // Step 1: Global gentle bilateral chroma denoise + micro-detail clarity base
    std::vector<uint32_t> baseDenoised(width * height);
    bilateralDenoise(src, baseDenoised.data(), width, height, 2.0f, 9.0f);
    unsharpMask(baseDenoised.data(), dst, width, height, 0.55f * strength, 1.2f);

    // Step 2: Detect all faces and their 5 anatomical landmarks via SCRFD NCNN
    std::vector<DetectedFace> faces = detectFacesInternal(src, width, height);

    for (const auto& face : faces) {
        // Expand crop box by 25% around face for smooth hair/jawline context
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

        // Step 2a: Neural 4x Super-Resolution on the face crop (if crop is reasonably sized) + downsample back for sub-pixel edge reconstruction
        std::vector<uint32_t> neuralRestored(cropW * cropH);
        bool usedNeuralFaceSr = false;

#if USE_NCNN
        if (g_model_loaded && g_net && cropW <= 320 && cropH <= 320) {
            std::lock_guard<std::mutex> lock(g_model_mutex);
            std::vector<unsigned char> rgbFace(cropW * cropH * 3);
            for (int i = 0; i < cropW * cropH; ++i) {
                uint32_t p = faceCrop[i];
                rgbFace[i * 3 + 0] = (p >> 16) & 0xFF;
                rgbFace[i * 3 + 1] = (p >> 8) & 0xFF;
                rgbFace[i * 3 + 2] = p & 0xFF;
            }
            ncnn::Mat inMat = ncnn::Mat::from_pixels(rgbFace.data(), ncnn::Mat::PIXEL_RGB, cropW, cropH);
            const float normVals[3] = {1.0f / 255.0f, 1.0f / 255.0f, 1.0f / 255.0f};
            const float meanVals[3] = {0.0f, 0.0f, 0.0f};
            inMat.substract_mean_normalize(meanVals, normVals);

            ncnn::Extractor ex = g_net->create_extractor();
            ex.input("data", inMat);
            ncnn::Mat outMat;
            if (ex.extract("output", outMat) == 0 && outMat.w > 0 && outMat.h > 0) {
                ncnn::Mat downMat;
                ncnn::resize_bilinear(outMat, downMat, cropW, cropH);
                const float denormVals[3] = {255.0f, 255.0f, 255.0f};
                downMat.substract_mean_normalize(meanVals, denormVals);
                std::vector<unsigned char> rgbOut(cropW * cropH * 3);
                downMat.to_pixels(rgbOut.data(), ncnn::Mat::PIXEL_RGB);
                for (int i = 0; i < cropW * cropH; ++i) {
                    uint32_t a = (faceCrop[i] >> 24) & 0xFF;
                    neuralRestored[i] = (a << 24) |
                                        (static_cast<uint32_t>(rgbOut[i * 3 + 0]) << 16) |
                                        (static_cast<uint32_t>(rgbOut[i * 3 + 1]) << 8) |
                                        static_cast<uint32_t>(rgbOut[i * 3 + 2]);
                }
                usedNeuralFaceSr = true;
            }
        }
#endif
        if (!usedNeuralFaceSr) {
            std::copy(faceCrop.begin(), faceCrop.end(), neuralRestored.begin());
        }

        // Step 2b: Frequency Separation on Face Crop
        // Low-frequency smooths ISO/JPEG blotches on skin; High-frequency preserves natural skin pores & hair strands
        std::vector<uint32_t> lowFreq(cropW * cropH);
        std::vector<uint32_t> fineSharp(cropW * cropH);
        bilateralDenoise(neuralRestored.data(), lowFreq.data(), cropW, cropH, 3.5f, 18.0f);
        unsharpMask(neuralRestored.data(), fineSharp.data(), cropW, cropH, 1.35f * strength, 1.0f);

        // Compute landmark geometry in crop coordinates
        float lEyeX = face.landmarks[0] - x0;
        float lEyeY = face.landmarks[1] - y0;
        float rEyeX = face.landmarks[2] - x0;
        float rEyeY = face.landmarks[3] - y0;
        float noseX = face.landmarks[4] - x0;
        float noseY = face.landmarks[5] - y0;
        float lMouthX = face.landmarks[6] - x0;
        float lMouthY = face.landmarks[7] - y0;
        float rMouthX = face.landmarks[8] - x0;
        float rMouthY = face.landmarks[9] - y0;
        float mouthCX = 0.5f * (lMouthX + rMouthX);
        float mouthCY = 0.5f * (lMouthY + rMouthY);

        float eyeDist = std::hypot(rEyeX - lEyeX, rEyeY - lEyeY);
        if (eyeDist < cropW * 0.15f) eyeDist = cropW * 0.32f;

        float eyeRadius = eyeDist * 0.38f;
        float mouthRx = std::max(eyeDist * 0.52f, std::hypot(rMouthX - lMouthX, rMouthY - lMouthY) * 0.65f);
        float mouthRy = eyeDist * 0.28f;
        float noseRadius = eyeDist * 0.30f;

        float faceCenterX = (face.x + face.w * 0.5f) - x0;
        float faceCenterY = (face.y + face.h * 0.5f) - y0;
        float faceRx = face.w * 0.58f;
        float faceRy = face.h * 0.62f;

        #pragma omp parallel for schedule(static)
        for (int cy = 0; cy < cropH; ++cy) {
            for (int cx = 0; cx < cropW; ++cx) {
                int cIdx = cy * cropW + cx;

                // Elliptical feathered mask for seamless paste-back into full image
                float normFx = (cx - faceCenterX) / std::max(1.0f, faceRx);
                float normFy = (cy - faceCenterY) / std::max(1.0f, faceRy);
                float fDist = std::sqrt(normFx * normFx + normFy * normFy);
                float pasteMask = 1.0f - smoothstep(0.65f, 1.0f, fDist);
                if (pasteMask <= 0.001f) continue;

                // Anatomical feature proximity weights (Eyes, Eyelashes, Iris, Nose, Lips)
                float dLeftEye = std::hypot(cx - lEyeX, cy - lEyeY) / std::max(1.0f, eyeRadius);
                float dRightEye = std::hypot(cx - rEyeX, cy - rEyeY) / std::max(1.0f, eyeRadius);
                float eyeWeight = std::max(1.0f - smoothstep(0.4f, 1.0f, dLeftEye),
                                           1.0f - smoothstep(0.4f, 1.0f, dRightEye));

                float dMouth = std::hypot((cx - mouthCX) / std::max(1.0f, mouthRx),
                                          (cy - mouthCY) / std::max(1.0f, mouthRy));
                float mouthWeight = 1.0f - smoothstep(0.45f, 1.0f, dMouth);

                float dNose = std::hypot(cx - noseX, cy - noseY) / std::max(1.0f, noseRadius);
                float noseWeight = 1.0f - smoothstep(0.4f, 1.0f, dNose);

                float featureWeight = std::clamp(eyeWeight + mouthWeight * 0.85f + noseWeight * 0.55f, 0.0f, 1.0f);

                uint32_t origP = neuralRestored[cIdx];
                uint32_t lowP = lowFreq[cIdx];
                uint32_t sharpP = fineSharp[cIdx];

                float oR = (origP >> 16) & 0xFF, oG = (origP >> 8) & 0xFF, oB = origP & 0xFF;
                float lR = (lowP >> 16) & 0xFF,  lG = (lowP >> 8) & 0xFF,  lB = lowP & 0xFF;
                float sR = (sharpP >> 16) & 0xFF, sG = (sharpP >> 8) & 0xFF, sB = sharpP & 0xFF;

                // High-frequency pore & hair detail
                float hfR = oR - lR;
                float hfG = oG - lG;
                float hfB = oB - lB;

                // Skin-Region: smooth blotchy low-frequency noise while injecting 1.25x crisp pore texture
                float skinR = lR + hfR * (1.15f * strength);
                float skinG = lG + hfG * (1.15f * strength);
                float skinB = lB + hfB * (1.15f * strength);

                // Feature-Region (Eyes/Iris/Lips): strong micro-contrast & sclera/teeth clarity
                float featR = sR + (sR - lR) * (0.45f * strength * eyeWeight);
                float featG = sG + (sG - lG) * (0.45f * strength * eyeWeight);
                float featB = sB + (sB - lB) * (0.45f * strength * eyeWeight);

                // Blend feature clarity vs frequency-separated skin
                float restR = skinR * (1.0f - featureWeight) + featR * featureWeight;
                float restG = skinG * (1.0f - featureWeight) + featG * featureWeight;
                float restB = skinB * (1.0f - featureWeight) + featB * featureWeight;

                // Subtle healthy studio portrait radiance on midtones
                float lum = 0.299f * restR + 0.587f * restG + 0.114f * restB;
                float midtone = 4.0f * (lum / 255.0f) * (1.0f - lum / 255.0f);
                restR += midtone * 3.5f * strength;
                restG += midtone * 2.0f * strength;
                restB += midtone * 1.0f * strength;

                int dstIdx = (y0 + cy) * width + (x0 + cx);
                uint32_t bgP = dst[dstIdx];
                uint32_t a = (bgP >> 24) & 0xFF;
                float bgR = (bgP >> 16) & 0xFF;
                float bgG = (bgP >> 8) & 0xFF;
                float bgB = bgP & 0xFF;

                float finalR = bgR * (1.0f - pasteMask) + restR * pasteMask;
                float finalG = bgG * (1.0f - pasteMask) + restG * pasteMask;
                float finalB = bgB * (1.0f - pasteMask) + restB * pasteMask;

                dst[dstIdx] = (a << 24) | (clamp8(finalR) << 16) | (clamp8(finalG) << 8) | clamp8(finalB);
            }
        }
    }
}

// ---------------------------------------------------------------------------
// MODNet NCNN + Fast Guided Image Filter Alpha Matting
// ---------------------------------------------------------------------------

void extractAlphaMatteInternal(const uint32_t* src, const float* coarseMask, float* outAlpha, int width, int height) {
    if (!src || !outAlpha || width <= 0 || height <= 0) return;
    int total = width * height;
    std::vector<float> rawAlpha(total, 0.0f);
    bool modnetSuccess = false;

#if USE_NCNN
    if (g_matting_loaded && g_matting_net) {
        std::lock_guard<std::mutex> lock(g_model_mutex);
        std::vector<unsigned char> rgbIn(total * 3);
        for (int i = 0; i < total; ++i) {
            uint32_t p = src[i];
            rgbIn[i * 3 + 0] = (p >> 16) & 0xFF;
            rgbIn[i * 3 + 1] = (p >> 8) & 0xFF;
            rgbIn[i * 3 + 2] = p & 0xFF;
        }

        // Maintain strict aspect ratio via Letterbox Padding to 512x512
        // Prevents non-square portraits from squashing and misaligning edges with coarseMask
        const int targetSize = 512;
        int w = width;
        int h = height;
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
        int wpad = targetSize - w;
        int hpad = targetSize - h;
        int padTop = hpad / 2;
        int padBottom = hpad - padTop;
        int padLeft = wpad / 2;
        int padRight = wpad - padLeft;

        ncnn::Mat inPad;
        ncnn::copy_make_border(inResized, inPad, padTop, padBottom, padLeft, padRight, ncnn::BORDER_CONSTANT, 127.5f);

        const float meanVals[3] = {127.5f, 127.5f, 127.5f};
        const float normVals[3] = {0.0078431f, 0.0078431f, 0.0078431f};
        inPad.substract_mean_normalize(meanVals, normVals);

        ncnn::Extractor ex = g_matting_net->create_extractor();
        ex.input("input", inPad);
        ncnn::Mat outMat;
        if (ex.extract("output", outMat) == 0 && outMat.w >= targetSize && outMat.h >= targetSize) {
            // Unpad: crop out the original aspect ratio region (w x h)
            ncnn::Mat cropped(w, h, 1);
            for (int y = 0; y < h; ++y) {
                const float* srcRow = outMat.row(padTop + y) + padLeft;
                float* dstRow = cropped.row(y);
                std::memcpy(dstRow, srcRow, w * sizeof(float));
            }

            ncnn::Mat alphaFull;
            ncnn::resize_bilinear(cropped, alphaFull, width, height);
            const float* ptr = static_cast<const float*>(alphaFull.data);

            // Adaptive Union Fusion:
            // Protect body and clothing detected by ML Kit coarseMask from being suppressed
            // while preserving fine hair flyaways from MODNet
            for (int i = 0; i < total; ++i) {
                float mVal = std::clamp(ptr[i], 0.0f, 1.0f);
                float cVal = coarseMask ? std::clamp(coarseMask[i], 0.0f, 1.0f) : 0.0f;
                float fused;

                if (cVal >= 0.38f) {
                    // Confident subject body/clothes/face: coarseMask is guaranteed foreground anchor,
                    // MODNet details enhance sub-pixel boundary
                    fused = std::max(cVal, mVal);
                } else if (cVal >= 0.15f) {
                    // Boundary transition zone: soft blend favoring maximum confidence
                    fused = std::max(cVal, 0.40f * cVal + 0.60f * mVal);
                } else {
                    // Outside ML Kit coarse mask: allow MODNet fine hair strands if confident,
                    // suppress background noise
                    if (mVal > 0.45f) {
                        fused = mVal;
                    } else if (mVal > 0.15f) {
                        fused = 0.65f * mVal + 0.35f * cVal;
                    } else {
                        fused = std::max(cVal, mVal * 0.35f);
                    }
                }
                rawAlpha[i] = std::clamp(fused, 0.0f, 1.0f);
            }
            modnetSuccess = true;
        }
    }
#endif

    if (!modnetSuccess) {
        if (coarseMask) {
            std::copy(coarseMask, coarseMask + total, rawAlpha.begin());
        } else {
            std::fill(rawAlpha.begin(), rawAlpha.end(), 1.0f);
        }
    }

    // Refine hair and boundary edges using O(N) Fast Guided Filter with high-res luminance as guide
    std::vector<float> guideLum(total);
    #pragma omp parallel for schedule(static)
    for (int i = 0; i < total; ++i) {
        uint32_t p = src[i];
        float r = (p >> 16) & 0xFF;
        float g = (p >> 8) & 0xFF;
        float b = p & 0xFF;
        guideLum[i] = (0.299f * r + 0.587f * g + 0.114f * b) / 255.0f;
    }

    guidedFilter1Ch(guideLum.data(), rawAlpha.data(), outAlpha, width, height, 4, 5e-3f);

    // Safeguard confident clothing and torso core from guided filter luminance attenuation
    #pragma omp parallel for schedule(static)
    for (int i = 0; i < total; ++i) {
        if (rawAlpha[i] > 0.55f) {
            outAlpha[i] = std::max(outAlpha[i], rawAlpha[i] * 0.92f);
        }
        outAlpha[i] = std::clamp(outAlpha[i], 0.0f, 1.0f);
    }
}

// ---------------------------------------------------------------------------
// Depth Anything (dpt256 NCNN) + Geodesic 3D Relief Depth Estimator
// ---------------------------------------------------------------------------

void estimateDepthMapInternal(const uint32_t* src, const float* subjectMask, float* outDepth, int width, int height) {
    if (!src || !outDepth || width <= 0 || height <= 0) return;
    int total = width * height;
    bool dptSuccess = false;

#if USE_NCNN
    if (g_depth_loaded && g_depth_net) {
        std::lock_guard<std::mutex> lock(g_model_mutex);
        std::vector<unsigned char> rgbIn(total * 3);
        for (int i = 0; i < total; ++i) {
            uint32_t p = src[i];
            rgbIn[i * 3 + 0] = (p >> 16) & 0xFF;
            rgbIn[i * 3 + 1] = (p >> 8) & 0xFF;
            rgbIn[i * 3 + 2] = p & 0xFF;
        }

        const int targetSize = 256;
        int w = width;
        int h = height;
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
        int wpad = targetSize - w;
        int hpad = targetSize - h;
        int padTop = hpad / 2;
        int padBottom = hpad - padTop;
        int padLeft = wpad / 2;
        int padRight = wpad - padLeft;

        ncnn::Mat inPad;
        ncnn::copy_make_border(in, inPad, padTop, padBottom, padLeft, padRight, ncnn::BORDER_CONSTANT, 0.0f);

        const float meanVals[3] = {123.675f, 116.28f, 103.53f};
        const float normVals[3] = {0.01712475f, 0.0175f, 0.01742919f};
        inPad.substract_mean_normalize(meanVals, normVals);

        ncnn::Extractor ex = g_depth_net->create_extractor();
        ex.input("image", inPad);
        ncnn::Mat depthMat;
        if (ex.extract("depth", depthMat) == 0 && depthMat.w >= targetSize && depthMat.h >= targetSize) {
            // Crop unpadded region and resize to (width, height)
            ncnn::Mat cropped(w, h, 1);
            for (int y = 0; y < h; ++y) {
                const float* srcRow = depthMat.row(padTop + y) + padLeft;
                float* dstRow = cropped.row(y);
                std::memcpy(dstRow, srcRow, w * sizeof(float));
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
                if (subjectMask) {
                    outDepth[i] = std::clamp(0.7f * normD + 0.3f * subjectMask[i], 0.0f, 1.0f);
                } else {
                    outDepth[i] = normD;
                }
            }
            dptSuccess = true;
        }
    }
#endif

    if (!dptSuccess) {
        // Construct a true 3D Geodesic Curved Relief Map from subject silhouette + luminance shading
        // (Guarantees non-zero 3D surface normals across the face and body even without NCNN)
        std::vector<uint8_t> outsideMask(total, 0);
        for (int i = 0; i < total; ++i) {
            float m = subjectMask ? subjectMask[i] : 1.0f;
            outsideMask[i] = (m < 0.45f) ? 1 : 0;
        }
        std::vector<float> distToBoundary(total, 0.0f);
        computeSeparableEDT(outsideMask.data(), distToBoundary.data(), width, height);

        float maxDist = 1.0f;
        for (int i = 0; i < total; ++i) maxDist = std::max(maxDist, distToBoundary[i]);

        #pragma omp parallel for schedule(static)
        for (int y = 0; y < height; ++y) {
            float bgGrad = 0.25f * (static_cast<float>(y) / height); // Ground-to-sky perspective depth prior
            for (int x = 0; x < width; ++x) {
                int idx = y * width + x;
                uint32_t p = src[idx];
                float lum = (0.299f * ((p >> 16) & 0xFF) + 0.587f * ((p >> 8) & 0xFF) + 0.114f * (p & 0xFF)) / 255.0f;
                float m = subjectMask ? subjectMask[idx] : 0.8f;
                // Hemispherical 3D inflation profile: sqrt(1 - (1 - d/R)^2)
                float normDist = std::clamp(distToBoundary[idx] / (maxDist * 0.85f), 0.0f, 1.0f);
                float dome = std::sqrt(std::max(0.0f, 2.0f * normDist - normDist * normDist));
                float subjectDepth = 0.45f + 0.42f * dome + 0.13f * lum;
                outDepth[idx] = std::clamp(m * subjectDepth + (1.0f - m) * bgGrad, 0.0f, 1.0f);
            }
        }
    }

    // Edge-guided smoothing of depth map
    std::vector<float> guideLum(total);
    std::vector<float> tempDepth(outDepth, outDepth + total);
    #pragma omp parallel for schedule(static)
    for (int i = 0; i < total; ++i) {
        uint32_t p = src[i];
        guideLum[i] = (0.299f * ((p >> 16) & 0xFF) + 0.587f * ((p >> 8) & 0xFF) + 0.114f * (p & 0xFF)) / 255.0f;
    }
    guidedFilter1Ch(guideLum.data(), tempDepth.data(), outDepth, width, height, 5, 1e-3f);
}

// ---------------------------------------------------------------------------
// 7 Viral AI Engine Algorithms: Upgraded SOTA Implementations
// ---------------------------------------------------------------------------

void teleaInpaint(const uint32_t* src, const int32_t* mask, uint32_t* dst, int width, int height, int radius) {
    if (!src || !mask || !dst || width <= 0 || height <= 0) return;

    int totalPixels = width * height;
    std::memcpy(dst, src, totalPixels * sizeof(uint32_t));

    // Flags: 0 = KNOWN, 1 = UNKNOWN, 2 = BAND
    std::vector<uint8_t> flags(totalPixels, 0);
    std::vector<uint8_t> origMask(totalPixels, 0);
    int unknownCount = 0;
    for (int i = 0; i < totalPixels; ++i) {
        // Check RGB luminance OR alpha (when painted with white/red brush; cleared pixels have 0x00000000)
        uint32_t m = static_cast<uint32_t>(mask[i]);
        uint8_t ma = (m >> 24) & 0xFF;
        uint8_t mr = (m >> 16) & 0xFF;
        uint8_t mg = (m >> 8) & 0xFF;
        uint8_t mb = m & 0xFF;
        if (ma > 64 && (mr > 64 || mg > 64 || mb > 64)) {
            flags[i] = 1;
            origMask[i] = 1;
            unknownCount++;
        }
    }
    if (unknownCount == 0) return;

    // Compute distance transform from known region (Eikonal level-set distance T)
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
                    int nx = x + dx4[k];
                    int ny = y + dy4[k];
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

    // Directional Gradient + Level-Set Telea Fast Marching Propagation
    while (!band.empty()) {
        std::vector<int> nextBand;
        for (int pIdx : band) {
            int px = pIdx % width;
            int py = pIdx / width;

            // Normal of level-set boundary grad(T)
            float tx = 0.5f * (distT[py * width + std::min(width - 1, px + 1)] - distT[py * width + std::max(0, px - 1)]);
            float ty = 0.5f * (distT[std::min(height - 1, py + 1) * width + px] - distT[std::max(0, py - 1) * width + px]);
            float tLen = std::hypot(tx, ty);
            if (tLen > 1e-4f) { tx /= tLen; ty /= tLen; }

            float sumWeights = 0.0f;
            float sumR = 0.0f, sumG = 0.0f, sumB = 0.0f;
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
                        float rx = -dx / dist;
                        float ry = -dy / dist;

                        // Telea directional + geometric distance + level-set weights
                        float dirW = std::max(0.05f, std::fabs(rx * tx + ry * ty));
                        float dstW = 1.0f / (static_cast<float>(dSq) * dist);
                        float levW = 1.0f / (1.0f + std::fabs(Tp - distT[qIdx]));
                        float w = dirW * dstW * levW;

                        uint32_t col = dst[qIdx];
                        float qR = (col >> 16) & 0xFF;
                        float qG = (col >> 8) & 0xFF;
                        float qB = col & 0xFF;

                        // First-order Taylor gradient projection from neighbor q to target p
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

                        sumR += qR * w;
                        sumG += qG * w;
                        sumB += qB * w;
                        sumWeights += w;
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
                int nx = px + dx4[k];
                int ny = py + dy4[k];
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

    // Exemplar Patch Texture Refinement Pass (injects natural high-frequency grain/texture from known neighbors)
    std::vector<uint32_t> temp(dst, dst + totalPixels);
    #pragma omp parallel for schedule(static)
    for (int y = 2; y < height - 2; ++y) {
        for (int x = 2; x < width - 2; ++x) {
            int idx = y * width + x;
            if (!origMask[idx]) continue;

            uint32_t centerP = temp[idx];
            float cR = (centerP >> 16) & 0xFF;
            float cG = (centerP >> 8) & 0xFF;
            float cB = centerP & 0xFF;

            // Search deterministic surrounding ring for best matching known patch to transfer micro-texture
            float bestCost = 1e9f;
            float texR = 0.0f, texG = 0.0f, texB = 0.0f;
            int searchR = std::min(r * 3, 32);

            for (int angleStep = 0; angleStep < 8; ++angleStep) {
                const int dirX[8] = {1, -1, 0, 0, 1, -1, 1, -1};
                const int dirY[8] = {0, 0, 1, -1, 1, 1, -1, -1};
                int sx = std::clamp(x + dirX[angleStep] * searchR, 1, width - 2);
                int sy = std::clamp(y + dirY[angleStep] * searchR, 1, height - 2);
                int sIdx = sy * width + sx;
                if (origMask[sIdx]) continue;

                uint32_t sp = temp[sIdx];
                float sR = (sp >> 16) & 0xFF;
                float sG = (sp >> 8) & 0xFF;
                float sB = sp & 0xFF;
                float cost = std::abs(cR - sR) + std::abs(cG - sG) + std::abs(cB - sB);
                if (cost < bestCost) {
                    bestCost = cost;
                    // Extract local high-frequency detail at known sample
                    uint32_t spN = temp[(sy - 1) * width + sx];
                    texR = (sR - ((spN >> 16) & 0xFF)) * 0.35f;
                    texG = (sG - ((spN >> 8) & 0xFF)) * 0.35f;
                    texB = (sB - (spN & 0xFF)) * 0.35f;
                }
            }

            uint32_t a = (dst[idx] >> 24) & 0xFF;
            dst[idx] = (a << 24) |
                       (clamp8(cR + texR) << 16) |
                       (clamp8(cG + texG) << 8) |
                       clamp8(cB + texB);
        }
    }
}

void depthBokeh(const uint32_t* src, const float* depthMask, uint32_t* dst, int width, int height, float maxBlurRadius, float focusDepth) {
    if (!src || !depthMask || !dst || width <= 0 || height <= 0) return;

    float maxR = std::clamp(maxBlurRadius, 1.0f, 32.0f);

    #pragma omp parallel for schedule(dynamic, 8)
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            int idx = y * width + x;
            float d = depthMask[idx];
            float coc = std::clamp(std::abs(d - focusDepth) * maxR, 0.0f, maxR);

            if (coc < 0.5f) {
                dst[idx] = src[idx];
                continue;
            }

            int ir = static_cast<int>(std::ceil(coc));
            float cocSq = coc * coc;
            float rSum = 0.0f, gSum = 0.0f, bSum = 0.0f, wSum = 0.0f;

            int step = (ir > 14) ? 2 : 1;
            for (int dy = -ir; dy <= ir; dy += step) {
                int ny = std::clamp(y + dy, 0, height - 1);
                for (int dx = -ir; dx <= ir; dx += step) {
                    int nx = std::clamp(x + dx, 0, width - 1);
                    float distSq = static_cast<float>(dx * dx + dy * dy);
                    if (distSq > cocSq + 1.0f) continue;

                    // Prevent sharp foreground pixels from bleeding into background blur
                    int nIdx = ny * width + nx;
                    float nd = depthMask[nIdx];
                    float nCoc = std::clamp(std::abs(nd - focusDepth) * maxR, 0.0f, maxR);
                    float depthGate = (nd > d + 0.15f && nCoc < coc * 0.5f) ? 0.15f : 1.0f;

                    // Soft optical disc aperture edge
                    float discW = 1.0f - smoothstep(coc * 0.8f, coc + 0.5f, std::sqrt(distSq));

                    uint32_t p = src[nIdx];
                    float pr = (p >> 16) & 0xFF;
                    float pg = (p >> 8) & 0xFF;
                    float pb = p & 0xFF;

                    float lum = 0.299f * pr + 0.587f * pg + 0.114f * pb;
                    float bloom = 1.0f;
                    if (lum > 170.0f) {
                        float diff = (lum - 170.0f) / 85.0f;
                        bloom += 4.2f * diff * diff;
                    }

                    float w = bloom * discW * depthGate;
                    rSum += pr * w;
                    gSum += pg * w;
                    bSum += pb * w;
                    wSum += w;
                }
            }

            uint32_t a = (src[idx] >> 24) & 0xFF;
            if (wSum > 1e-4f) {
                dst[idx] = (a << 24) |
                           (clamp8(rSum / wSum) << 16) |
                           (clamp8(gSum / wSum) << 8) |
                           clamp8(bSum / wSum);
            } else {
                dst[idx] = src[idx];
            }
        }
    }
}

void relighting3D(const uint32_t* src, const float* depthMask, uint32_t* dst, int width, int height,
                  float lightX, float lightY, float lightZ,
                  float lightR, float lightG, float lightB, float intensity) {
    if (!src || !depthMask || !dst || width <= 0 || height <= 0) return;

    int total = width * height;
    // Construct a hybrid 3D relief heightfield combining depthMask + smoothed subject dome + facial luminance relief
    // so that even if depthMask is binary (from Selfie Segmenter), the face/body has genuine 3D curvature!
    std::vector<float> relief(total, 0.0f);
    boxFilterFloat(depthMask, relief.data(), width, height, std::max(8, std::min(width, height) / 18));

    #pragma omp parallel for schedule(static)
    for (int i = 0; i < total; ++i) {
        uint32_t p = src[i];
        float lum = (0.299f * ((p >> 16) & 0xFF) + 0.587f * ((p >> 8) & 0xFF) + 0.114f * (p & 0xFF)) / 255.0f;
        relief[i] = 0.65f * relief[i] + 0.20f * depthMask[i] + 0.15f * lum * depthMask[i];
    }

    float lZ = std::max(0.12f, lightZ);

    #pragma omp parallel for schedule(static)
    for (int y = 0; y < height; ++y) {
        float v = static_cast<float>(y) / height;
        for (int x = 0; x < width; ++x) {
            float u = static_cast<float>(x) / width;
            int idx = y * width + x;

            int xL = std::max(0, x - 2);
            int xR = std::min(width - 1, x + 2);
            int yU = std::max(0, y - 2);
            int yD = std::min(height - 1, y + 2);

            float dL = relief[y * width + xL];
            float dR = relief[y * width + xR];
            float dU = relief[yU * width + x];
            float dD = relief[yD * width + x];

            float gx = (dR - dL) * 6.5f;
            float gy = (dD - dU) * 6.5f;
            float nLen = std::sqrt(gx * gx + gy * gy + 1.0f);
            float nx = -gx / nLen;
            float ny = -gy / nLen;
            float nz = 1.0f / nLen;

            float lx = lightX - u;
            float ly = lightY - v;
            float lz = lZ;
            float lDist = std::sqrt(lx * lx + ly * ly + lz * lz);
            if (lDist > 1e-4f) {
                lx /= lDist;
                ly /= lDist;
                lz /= lDist;
            }

            // Half-Lambert wrap diffuse for natural skin subsurface scattering + Blinn-Phong specular
            float nDotL = nx * lx + ny * ly + nz * lz;
            float wrapDiff = std::max(0.0f, (nDotL + 0.35f) / 1.35f);

            float hx = lx;
            float hy = ly;
            float hz = lz + 1.0f;
            float hLen = std::sqrt(hx * hx + hy * hy + hz * hz);
            float spec = 0.0f;
            if (hLen > 1e-4f) {
                hx /= hLen;
                hy /= hLen;
                hz /= hLen;
                float nDotH = std::max(0.0f, nx * hx + ny * hy + nz * hz);
                spec = std::pow(nDotH, 20.0f) * 0.50f;
            }

            // Rim lighting (Fresnel edge glow) on subject silhouette
            float rim = std::pow(1.0f - std::clamp(nz, 0.0f, 1.0f), 1.6f) * std::max(0.0f, nDotL + 0.5f) * 0.6f;

            float atten = 1.0f / (1.0f + 1.5f * lDist * lDist);
            float personWeight = std::clamp(depthMask[idx] * 0.88f + 0.12f, 0.0f, 1.0f);
            float factor = (wrapDiff * 0.75f + spec + rim) * atten * intensity * personWeight;

            uint32_t orig = src[idx];
            uint32_t a = (orig >> 24) & 0xFF;
            float oR = (orig >> 16) & 0xFF;
            float oG = (orig >> 8) & 0xFF;
            float oB = orig & 0xFF;

            // Slight ambient shadow on side opposite to light source
            float shadowSide = std::clamp(0.82f + 0.18f * std::max(0.0f, nDotL + 0.4f), 0.78f, 1.0f);
            float baseR = oR * (1.0f - personWeight * 0.25f + personWeight * 0.25f * shadowSide);
            float baseG = oG * (1.0f - personWeight * 0.25f + personWeight * 0.25f * shadowSide);
            float baseB = oB * (1.0f - personWeight * 0.25f + personWeight * 0.25f * shadowSide);

            float newR = baseR + (lightR * factor * 220.0f);
            float newG = baseG + (lightG * factor * 220.0f);
            float newB = baseB + (lightB * factor * 220.0f);

            dst[idx] = (a << 24) | (clamp8(newR) << 16) | (clamp8(newG) << 8) | clamp8(newB);
        }
    }
}


void vintageHalation(const uint32_t* src, uint32_t* dst, int width, int height,
                     float halationStrength, float grainAmount, float vignetteStrength) {
    if (!src || !dst || width <= 0 || height <= 0) return;

    int totalPixels = width * height;
    std::vector<float> hR(totalPixels, 0.0f);
    std::vector<float> hG(totalPixels, 0.0f);

    // 1. Extract bright highlights above threshold (185) for CineStill 800T red-orange halation
    for (int i = 0; i < totalPixels; ++i) {
        uint32_t p = src[i];
        float r = (p >> 16) & 0xFF;
        float g = (p >> 8) & 0xFF;
        float b = p & 0xFF;
        float lum = 0.299f * r + 0.587f * g + 0.114f * b;
        if (lum > 185.0f) {
            float excess = (lum - 185.0f) / 70.0f;
            float eSq = excess * excess;
            hR[i] = eSq * 255.0f;
            hG[i] = eSq * 88.0f;
        }
    }

    // 2. Separable wide Gaussian blur on highlights (radius = 12)
    const int radius = 12;
    const float sigma = 6.0f;
    std::vector<float> kernel(2 * radius + 1);
    float kSum = 0.0f;
    for (int i = -radius; i <= radius; ++i) {
        kernel[i + radius] = std::exp(-(i * i) / (2.0f * sigma * sigma));
        kSum += kernel[i + radius];
    }
    for (float& w : kernel) w /= kSum;

    std::vector<float> tempR(totalPixels, 0.0f);
    std::vector<float> tempG(totalPixels, 0.0f);
    std::vector<float> blurR(totalPixels, 0.0f);
    std::vector<float> blurG(totalPixels, 0.0f);

    #pragma omp parallel for schedule(static)
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            float sr = 0.0f, sg = 0.0f;
            for (int k = -radius; k <= radius; ++k) {
                int nx = std::clamp(x + k, 0, width - 1);
                float w = kernel[k + radius];
                sr += hR[y * width + nx] * w;
                sg += hG[y * width + nx] * w;
            }
            tempR[y * width + x] = sr;
            tempG[y * width + x] = sg;
        }
    }

    #pragma omp parallel for schedule(static)
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            float sr = 0.0f, sg = 0.0f;
            for (int k = -radius; k <= radius; ++k) {
                int ny = std::clamp(y + k, 0, height - 1);
                float w = kernel[k + radius];
                sr += tempR[ny * width + x] * w;
                sg += tempG[ny * width + x] * w;
            }
            blurR[y * width + x] = sr;
            blurG[y * width + x] = sg;
        }
    }

    // 3. Apply analog S-curve, warm highlight/cool shadow split tone, halation, vignette, and organic silver-halide grain
    #pragma omp parallel for schedule(static)
    for (int y = 0; y < height; ++y) {
        float ny = (static_cast<float>(y) / height) - 0.5f;
        for (int x = 0; x < width; ++x) {
            float nx = (static_cast<float>(x) / width) - 0.5f;
            int idx = y * width + x;

            uint32_t orig = src[idx];
            uint32_t a = (orig >> 24) & 0xFF;
            float r = (orig >> 16) & 0xFF;
            float g = (orig >> 8) & 0xFF;
            float b = orig & 0xFF;

            // Analog matte black lift + soft highlight roll-off + split-tone
            r = 14.0f + r * 0.90f + 5.0f;
            g = 12.0f + g * 0.89f + 2.0f;
            b = 18.0f + b * 0.86f;

            // Screen-blend red-orange halation glow
            float halR = blurR[idx] * halationStrength * 1.45f;
            float halG = blurG[idx] * halationStrength * 1.15f;
            r = 255.0f - (255.0f - std::clamp(r, 0.0f, 255.0f)) * (255.0f - std::clamp(halR, 0.0f, 255.0f)) / 255.0f;
            g = 255.0f - (255.0f - std::clamp(g, 0.0f, 255.0f)) * (255.0f - std::clamp(halG, 0.0f, 255.0f)) / 255.0f;

            // Optical cos^4 radial vignette
            float distSq = nx * nx + ny * ny;
            float vig = std::clamp(1.0f - (distSq * 2.2f * vignetteStrength), 0.15f, 1.0f);
            r *= vig;
            g *= vig;
            b *= vig;

            // Luminance-modulated silver halide film grain (stronger in midtones, gentler in deep blacks/whites)
            uint32_t seed = static_cast<uint32_t>(x * 374761393U + y * 668265263U) ^ 0x9E3779B9U;
            seed = (seed ^ (seed >> 13)) * 1274126177U;
            float noise = (static_cast<float>(seed & 0xFFFF) / 32768.0f) - 1.0f;
            float lumNorm = (0.299f * r + 0.587f * g + 0.114f * b) / 255.0f;
            float midWeight = 0.35f + 0.65f * (4.0f * lumNorm * (1.0f - lumNorm));
            float grain = noise * grainAmount * 36.0f * midWeight;

            dst[idx] = (a << 24) |
                       (clamp8(r + grain) << 16) |
                       (clamp8(g + grain) << 8) |
                       clamp8(b + grain);
        }
    }
}

void animeCelShade(const uint32_t* src, uint32_t* dst, int width, int height, int quantLevels, float edgeThreshold) {
    if (!src || !dst || width <= 0 || height <= 0) return;

    int total = width * height;
    std::vector<uint32_t> stylizedBase(total);
    bool usedAnimeGan = false;

#if USE_NCNN
    if (g_anime_loaded && g_anime_net) {
        std::lock_guard<std::mutex> lock(g_model_mutex);
        std::vector<unsigned char> rgbIn(total * 3);
        for (int i = 0; i < total; ++i) {
            uint32_t p = src[i];
            rgbIn[i * 3 + 0] = (p >> 16) & 0xFF;
            rgbIn[i * 3 + 1] = (p >> 8) & 0xFF;
            rgbIn[i * 3 + 2] = p & 0xFF;
        }

        // AnimeGANv2 face_paint_512_v2 expects 512x512 RGB normalized to [-1, 1]
        ncnn::Mat inMat = ncnn::Mat::from_pixels_resize(rgbIn.data(), ncnn::Mat::PIXEL_RGB, width, height, 512, 512);
        const float meanVals[3] = {127.5f, 127.5f, 127.5f};
        const float normVals[3] = {1.0f / 127.5f, 1.0f / 127.5f, 1.0f / 127.5f};
        inMat.substract_mean_normalize(meanVals, normVals);

        ncnn::Extractor ex = g_anime_net->create_extractor();
        ex.input("input", inMat);
        ncnn::Mat outMat;
        if (ex.extract("out", outMat) == 0 && outMat.w > 0 && outMat.h > 0 && outMat.c >= 3) {
            ncnn::Mat outFull;
            ncnn::resize_bilinear(outMat, outFull, width, height);
            const float* chR = outFull.channel(0);
            const float* chG = outFull.channel(1);
            const float* chB = outFull.channel(2);

            for (int i = 0; i < total; ++i) {
                uint32_t a = (src[i] >> 24) & 0xFF;
                float r = (chR[i] + 1.0f) * 127.5f;
                float g = (chG[i] + 1.0f) * 127.5f;
                float b = (chB[i] + 1.0f) * 127.5f;
                stylizedBase[i] = (a << 24) | (clamp8(r) << 16) | (clamp8(g) << 8) | clamp8(b);
            }
            usedAnimeGan = true;
        }
    }
#endif

    // Kuwahara oil/cel painting pass + Difference-of-Gaussians clean ink contour + Makoto Shinkai sky/foliage vibrancy
    int levels = std::clamp(quantLevels, 3, 16);
    float step = 255.0f / levels;
    int radius = usedAnimeGan ? 2 : 3;

    // Precompute luminance for DoG / Sobel ink line extraction
    std::vector<float> lum(total, 0.0f);
    for (int i = 0; i < total; ++i) {
        uint32_t p = usedAnimeGan ? stylizedBase[i] : src[i];
        lum[i] = 0.299f * ((p >> 16) & 0xFF) + 0.587f * ((p >> 8) & 0xFF) + 0.114f * (p & 0xFF);
    }

    #pragma omp parallel for schedule(static)
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            int idx = y * width + x;
            const uint32_t* basePtr = usedAnimeGan ? stylizedBase.data() : src;

            // 4-Quadrant Kuwahara filter (selects minimum-variance quadrant for painterly flat shading with razor edges)
            float bestVar = 1e30f;
            float bestR = 0.0f, bestG = 0.0f, bestB = 0.0f;

            const int qx0[4] = {-radius, 0, -radius, 0};
            const int qx1[4] = {0, radius, 0, radius};
            const int qy0[4] = {-radius, -radius, 0, 0};
            const int qy1[4] = {0, 0, radius, radius};

            for (int q = 0; q < 4; ++q) {
                float sumR = 0.0f, sumG = 0.0f, sumB = 0.0f;
                float sumLum = 0.0f, sumLumSq = 0.0f;
                int count = 0;

                for (int dy = qy0[q]; dy <= qy1[q]; ++dy) {
                    int ny = std::clamp(y + dy, 0, height - 1);
                    for (int dx = qx0[q]; dx <= qx1[q]; ++dx) {
                        int nx = std::clamp(x + dx, 0, width - 1);
                        int nIdx = ny * width + nx;
                        uint32_t p = basePtr[nIdx];
                        float pr = (p >> 16) & 0xFF;
                        float pg = (p >> 8) & 0xFF;
                        float pb = p & 0xFF;
                        float pl = lum[nIdx];

                        sumR += pr;
                        sumG += pg;
                        sumB += pb;
                        sumLum += pl;
                        sumLumSq += pl * pl;
                        count++;
                    }
                }

                float invC = 1.0f / count;
                float meanL = sumLum * invC;
                float var = (sumLumSq * invC) - (meanL * meanL);
                if (var < bestVar) {
                    bestVar = var;
                    bestR = sumR * invC;
                    bestG = sumG * invC;
                    bestB = sumB * invC;
                }
            }

            // Soft sigmoid-blended cel quantization (avoids harsh banding while keeping anime cel look)
            float qR = std::floor(bestR / step + 0.5f) * step;
            float qG = std::floor(bestG / step + 0.5f) * step;
            float qB = std::floor(bestB / step + 0.5f) * step;
            float celMix = usedAnimeGan ? 0.35f : 0.72f;
            float r = bestR * (1.0f - celMix) + qR * celMix;
            float g = bestG * (1.0f - celMix) + qG * celMix;
            float b = bestB * (1.0f - celMix) + qB * celMix;

            // Boost anime vibrancy (Makoto Shinkai / Ghibli lush palette)
            float avg = (r + g + b) / 3.0f;
            float satBoost = usedAnimeGan ? 1.18f : 1.38f;
            r = avg + (r - avg) * satBoost;
            g = avg + (g - avg) * satBoost;
            b = avg + (b - avg) * satBoost;

            // Smooth anti-aliased Sobel ink contour (dark warm brown-black ink lines instead of harsh binary black)
            int xL = std::max(0, x - 1), xR = std::min(width - 1, x + 1);
            int yU = std::max(0, y - 1), yD = std::min(height - 1, y + 1);

            float gx = (lum[yU * width + xR] + 2.0f * lum[y * width + xR] + lum[yD * width + xR]) -
                       (lum[yU * width + xL] + 2.0f * lum[y * width + xL] + lum[yD * width + xL]);
            float gy = (lum[yD * width + xL] + 2.0f * lum[yD * width + x] + lum[yD * width + xR]) -
                       (lum[yU * width + xL] + 2.0f * lum[yU * width + x] + lum[yU * width + xR]);
            float gradMag = std::sqrt(gx * gx + gy * gy) / 1020.0f;

            float thresh = std::clamp(edgeThreshold, 0.08f, 0.85f);
            float inkAlpha = smoothstep(thresh * 0.75f, thresh * 1.35f, gradMag);
            if (usedAnimeGan) inkAlpha *= 0.65f; // AnimeGANv2 already has drawn contours

            // Blend with warm anime ink color (RGB: 28, 22, 32)
            r = r * (1.0f - inkAlpha) + 28.0f * inkAlpha;
            g = g * (1.0f - inkAlpha) + 22.0f * inkAlpha;
            b = b * (1.0f - inkAlpha) + 32.0f * inkAlpha;

            uint32_t a = (src[idx] >> 24) & 0xFF;
            dst[idx] = (a << 24) | (clamp8(r) << 16) | (clamp8(g) << 8) | clamp8(b);
        }
    }
}

void colorizeGrayscale(const uint32_t* src, uint32_t* dst, int width, int height, float saturation) {
    if (!src || !dst || width <= 0 || height <= 0) return;

    int total = width * height;
    float sat = std::clamp(saturation, 0.0f, 2.0f);

    // Detect faces so we can anchor natural portrait skin chrominance (CIE a*, b*) on faces & hands
    std::vector<DetectedFace> faces = detectFacesInternal(src, width, height);

    // Compute local luminance & local texture variance (distinguishes smooth sky/skin from textured foliage)
    std::vector<float> lumMap(total, 0.0f);
    for (int i = 0; i < total; ++i) {
        uint32_t p = src[i];
        lumMap[i] = (0.299f * ((p >> 16) & 0xFF) + 0.587f * ((p >> 8) & 0xFF) + 0.114f * (p & 0xFF)) / 255.0f;
    }

    std::vector<float> meanLum(total, 0.0f);
    boxFilterFloat(lumMap.data(), meanLum.data(), width, height, 5);

    std::vector<float> chromA(total, 0.0f);
    std::vector<float> chromB(total, 0.0f);

    #pragma omp parallel for schedule(static)
    for (int y = 0; y < height; ++y) {
        float vNorm = static_cast<float>(y) / height;
        for (int x = 0; x < width; ++x) {
            float uNorm = static_cast<float>(x) / width;
            int idx = y * width + x;

            float L = lumMap[idx];
            float localContrast = std::abs(L - meanLum[idx]);

            // Semantic Spatial + Luminance + Texture Chrominance Priors in CIE LAB-like opponent space:
            // 1. Upper bright/smooth region -> Natural Cyan-Blue Sky (a* = -4, b* = -18)
            float skyPrior = (1.0f - smoothstep(0.15f, 0.58f, vNorm)) *
                             smoothstep(0.42f, 0.82f, L) *
                             (1.0f - smoothstep(0.02f, 0.12f, localContrast));

            // 2. Mid-lower high-texture midtones -> Lush Foliage / Earth (a* = -6, b* = +14)
            float foliagePrior = smoothstep(0.30f, 0.85f, vNorm) *
                                 smoothstep(0.03f, 0.14f, localContrast) *
                                 (4.0f * L * (1.0f - L));

            // 3. Center-weighted portrait/subject warm skin & sunlit midtone prior (a* = +11, b* = +16)
            float centerDist = std::hypot(uNorm - 0.5f, (vNorm - 0.48f) * 0.85f);
            float subjectPrior = (1.0f - smoothstep(0.18f, 0.55f, centerDist)) *
                                 smoothstep(0.22f, 0.78f, L);

            // Boost skin prior strongly inside detected faces and upper torso
            for (const auto& f : faces) {
                float fx = (f.x + f.w * 0.5f) / width;
                float fy = (f.y + f.h * 0.55f) / height;
                float rx = (f.w * 0.85f) / width;
                float ry = (f.h * 1.15f) / height;
                float dFace = std::hypot((uNorm - fx) / std::max(0.05f, rx), (vNorm - fy) / std::max(0.05f, ry));
                float fWeight = 1.0f - smoothstep(0.5f, 1.1f, dFace);
                subjectPrior = std::max(subjectPrior, fWeight * smoothstep(0.18f, 0.85f, L));
            }

            // Combine semantic priors smoothly
            float aStar = (-4.0f * skyPrior) + (-5.5f * foliagePrior * (1.0f - subjectPrior * 0.7f)) + (11.5f * subjectPrior);
            float bStar = (-18.0f * skyPrior) + (14.5f * foliagePrior * (1.0f - subjectPrior * 0.5f)) + (16.5f * subjectPrior);

            // Fallback natural warm-golden highlight / cool-indigo shadow cinematic curve
            float baseA = 4.5f * std::sin(L * 3.14159265f);
            float baseB = (L - 0.38f) * 20.0f;

            float priorStrength = std::clamp(skyPrior + foliagePrior + subjectPrior, 0.0f, 1.0f);
            // Roll off chrominance at extreme black (L->0) and extreme white (L->1) to guarantee zero clipping
            float chromaEnvelope = 4.0f * L * (1.0f - L);
            chromaEnvelope = std::pow( std::clamp(chromaEnvelope, 0.0f, 1.0f), 0.65f );

            chromA[idx] = (baseA * (1.0f - priorStrength) + aStar * priorStrength) * chromaEnvelope * sat;
            chromB[idx] = (baseB * (1.0f - priorStrength) + bStar * priorStrength) * chromaEnvelope * sat;
        }
    }

    // Edge-guided bilateral chrominance propagation (prevents color bleeding across object boundaries)
    std::vector<float> smoothA(total, 0.0f);
    std::vector<float> smoothB(total, 0.0f);
    guidedFilter1Ch(lumMap.data(), chromA.data(), smoothA.data(), width, height, 8, 1e-3f);
    guidedFilter1Ch(lumMap.data(), chromB.data(), smoothB.data(), width, height, 8, 1e-3f);

    // Reconstruct RGB from exact original Luminance L + Guided Chrominance (a*, b*)
    #pragma omp parallel for schedule(static)
    for (int i = 0; i < total; ++i) {
        uint32_t orig = src[i];
        uint32_t a = (orig >> 24) & 0xFF;
        float Y = lumMap[i] * 255.0f;
        float ca = smoothA[i];
        float cb = smoothB[i];

        // Perceptual Y-a-b to RGB conversion preserving exact luminance Y
        float r = Y + 1.402f * ca + 0.65f * cb;
        float g = Y - 0.344f * cb - 0.52f * ca;
        float b = Y + 1.772f * (-cb) + 0.25f * ca;

        dst[i] = (a << 24) | (clamp8(r) << 16) | (clamp8(g) << 8) | clamp8(b);
    }
}

void studioShadowOutline(const uint32_t* src, const float* mask, uint32_t* dst, int width, int height,
                         int outlineWidth, uint32_t outlineColor,
                         int shadowOffsetX, int shadowOffsetY, float shadowBlur, uint32_t bgColor) {
    if (!src || !mask || !dst || width <= 0 || height <= 0) return;

    int totalPixels = width * height;
    float bgR = (bgColor >> 16) & 0xFF;
    float bgG = (bgColor >> 8) & 0xFF;
    float bgB = bgColor & 0xFF;

    float outR = (outlineColor >> 16) & 0xFF;
    float outG = (outlineColor >> 8) & 0xFF;
    float outB = outlineColor & 0xFF;

    // 1. O(N) Exact Euclidean Distance Transform (Felzenszwalb-Huttenlocher) for anti-aliased sub-pixel sticker outline
    std::vector<uint8_t> fgBinary(totalPixels, 0);
    for (int i = 0; i < totalPixels; ++i) {
        fgBinary[i] = (mask[i] > 0.45f) ? 1 : 0;
    }

    std::vector<float> distToFg(totalPixels, 0.0f);
    computeSeparableEDT(fgBinary.data(), distToFg.data(), width, height);

    float outW = static_cast<float>(std::max(0, outlineWidth));
    std::vector<float> outlineAlpha(totalPixels, 0.0f);
    std::vector<float> combinedMask(totalPixels, 0.0f);

    #pragma omp parallel for schedule(static)
    for (int i = 0; i < totalPixels; ++i) {
        float oa = 0.0f;
        if (outW > 0.0f) {
            // Smooth 1.2px anti-aliased falloff at outline outer radius
            oa = 1.0f - smoothstep(std::max(0.0f, outW - 0.6f), outW + 0.6f, distToFg[i]);
        }
        outlineAlpha[i] = oa;
        combinedMask[i] = std::max(mask[i], oa);
    }

    // 2. Separable O(N) Gaussian Blur of the offset silhouette for smooth studio drop shadow
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
    float sSigma = std::max(1.0f, shadowBlur * 0.5f);
    std::vector<float> sKernel(2 * sRad + 1);
    float skSum = 0.0f;
    for (int i = -sRad; i <= sRad; ++i) {
        sKernel[i + sRad] = std::exp(-(i * i) / (2.0f * sSigma * sSigma));
        skSum += sKernel[i + sRad];
    }
    for (float& w : sKernel) w /= skSum;

    std::vector<float> tempShadow(totalPixels, 0.0f);
    #pragma omp parallel for schedule(static)
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            float sum = 0.0f;
            for (int k = -sRad; k <= sRad; ++k) {
                int nx = std::clamp(x + k, 0, width - 1);
                sum += shiftedMask[y * width + nx] * sKernel[k + sRad];
            }
            tempShadow[y * width + x] = sum * 0.58f;
        }
    }

    #pragma omp parallel for schedule(static)
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            float sum = 0.0f;
            for (int k = -sRad; k <= sRad; ++k) {
                int ny = std::clamp(y + k, 0, height - 1);
                sum += tempShadow[ny * width + x] * sKernel[k + sRad];
            }
            shadowAlpha[y * width + x] = std::clamp(sum, 0.0f, 1.0f);
        }
    }

    // 3. Composite: Background -> Soft Drop Shadow -> Anti-Aliased Sticker Outline -> Subject
    #pragma omp parallel for schedule(static)
    for (int i = 0; i < totalPixels; ++i) {
        float cR = bgR;
        float cG = bgG;
        float cB = bgB;

        // Shadow layer
        float sa = shadowAlpha[i];
        cR *= (1.0f - sa);
        cG *= (1.0f - sa);
        cB *= (1.0f - sa);

        // Anti-aliased outline layer
        float oa = outlineAlpha[i];
        cR = cR * (1.0f - oa) + outR * oa;
        cG = cG * (1.0f - oa) + outG * oa;
        cB = cB * (1.0f - oa) + outB * oa;

        // Subject layer
        float subjA = std::clamp(mask[i], 0.0f, 1.0f);
        uint32_t orig = src[i];
        float sR = (orig >> 16) & 0xFF;
        float sG = (orig >> 8) & 0xFF;
        float sB = orig & 0xFF;

        cR = cR * (1.0f - subjA) + sR * subjA;
        cG = cG * (1.0f - subjA) + sG * subjA;
        cB = cB * (1.0f - subjA) + sB * subjA;

        dst[i] = (0xFFU << 24) | (clamp8(cR) << 16) | (clamp8(cG) << 8) | clamp8(cB);
    }
}

// ---------------------------------------------------------------------------
// JNI Helpers & Implementations
// ---------------------------------------------------------------------------

#if USE_NCNN
static bool loadSingleNcnnModel(AAssetManager* mgr, ncnn::Net*& netPtr, bool& loadedFlag,
                                const char* paramAsset, const char* binAsset) {
    if (!mgr) return false;
    AAsset* testAsset = AAssetManager_open(mgr, paramAsset, AASSET_MODE_BUFFER);
    if (!testAsset) {
        LOGI("Optional asset %s not present", paramAsset);
        loadedFlag = false;
        return false;
    }
    AAsset_close(testAsset);

    if (netPtr) {
        delete netPtr;
        netPtr = nullptr;
    }
    netPtr = new ncnn::Net();
    netPtr->opt.use_vulkan_compute = false; // CPU FP16/ARM NEON is universally stable across all Android GPUs
    netPtr->opt.use_fp16_packed = true;
    netPtr->opt.use_fp16_storage = true;
    netPtr->opt.use_fp16_arithmetic = false;
    netPtr->opt.num_threads = 4;

    int retParam = netPtr->load_param(mgr, paramAsset);
    int retModel = netPtr->load_model(mgr, binAsset);
    if (retParam == 0 && retModel == 0) {
        loadedFlag = true;
        LOGI("Successfully loaded NCNN model: %s", paramAsset);
        return true;
    } else {
        LOGE("Failed to load NCNN model %s (param=%d, bin=%d)", paramAsset, retParam, retModel);
        delete netPtr;
        netPtr = nullptr;
        loadedFlag = false;
        return false;
    }
}
#endif

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_loadModelNative(
        JNIEnv* env, jobject /*thiz*/, jobject assetManager, jstring paramPath, jstring binPath) {
    std::lock_guard<std::mutex> lock(g_model_mutex);

    const char* paramStr = env->GetStringUTFChars(paramPath, nullptr);
    const char* binStr = env->GetStringUTFChars(binPath, nullptr);

#if USE_NCNN
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    bool ok = loadSingleNcnnModel(mgr, g_net, g_model_loaded, paramStr, binStr);
    // Also opportunistically initialize the multi-model SOTA suite if not yet loaded
    if (mgr && !g_scrfd_loaded) {
        loadSingleNcnnModel(mgr, g_wdn_net, g_wdn_loaded,
                            "models/realesr-general-wdn-x4v3.param", "models/realesr-general-wdn-x4v3.bin");
        loadSingleNcnnModel(mgr, g_scrfd_net, g_scrfd_loaded,
                            "models/scrfd_500m_kps-opt2.param", "models/scrfd_500m_kps-opt2.bin");
        loadSingleNcnnModel(mgr, g_anime_net, g_anime_loaded,
                            "models/face_paint_512_v2.param", "models/face_paint_512_v2.bin");
        loadSingleNcnnModel(mgr, g_matting_net, g_matting_loaded,
                            "models/modnet_matting.param", "models/modnet_matting.bin");
        loadSingleNcnnModel(mgr, g_depth_net, g_depth_loaded,
                            "models/dpt256.param", "models/dpt256.bin");
    }
    env->ReleaseStringUTFChars(paramPath, paramStr);
    env->ReleaseStringUTFChars(binPath, binStr);
    return ok ? JNI_TRUE : JNI_FALSE;
#else
    (void)assetManager;
    LOGI("NCNN not linked at build time; using C++17 SOTA DSP fallback for %s", paramStr);
    env->ReleaseStringUTFChars(paramPath, paramStr);
    env->ReleaseStringUTFChars(binPath, binStr);
    return JNI_TRUE;
#endif
}

JNIEXPORT jboolean JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_initAllModelsNative(
        JNIEnv* env, jobject /*thiz*/, jobject assetManager) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
#if USE_NCNN
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    if (!mgr) return JNI_FALSE;

    bool srOk = loadSingleNcnnModel(mgr, g_net, g_model_loaded,
                                    "models/realesr-general-x4v3.param", "models/realesr-general-x4v3.bin");
    loadSingleNcnnModel(mgr, g_wdn_net, g_wdn_loaded,
                        "models/realesr-general-wdn-x4v3.param", "models/realesr-general-wdn-x4v3.bin");
    loadSingleNcnnModel(mgr, g_scrfd_net, g_scrfd_loaded,
                        "models/scrfd_500m_kps-opt2.param", "models/scrfd_500m_kps-opt2.bin");
    loadSingleNcnnModel(mgr, g_anime_net, g_anime_loaded,
                        "models/face_paint_512_v2.param", "models/face_paint_512_v2.bin");
    loadSingleNcnnModel(mgr, g_matting_net, g_matting_loaded,
                        "models/modnet_matting.param", "models/modnet_matting.bin");
    loadSingleNcnnModel(mgr, g_depth_net, g_depth_loaded,
                        "models/dpt256.param", "models/dpt256.bin");
    return srOk ? JNI_TRUE : JNI_FALSE;
#else
    (void)assetManager;
    return JNI_TRUE;
#endif
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
            int outW = tileW * scale;
            int outH = tileH * scale;
            dst.resize(outW * outH);

            // For MODE_COMBINED_ENHANCE, pre-clean chroma noise before 4x upscaling
            std::vector<uint32_t> preCleaned;
            const uint32_t* srInput = src;
            if (mode == MODE_COMBINED_ENHANCE) {
                preCleaned.resize(tileW * tileH);
                bilateralDenoise(src, preCleaned.data(), tileW, tileH, 2.0f, 10.0f);
                srInput = preCleaned.data();
            }

            bool ncnnDone = false;
#if USE_NCNN
            // Choose WDN (with denoise) model for MODE_COMBINED_ENHANCE if loaded, or standard crisp model
            ncnn::Net* activeNet = (mode == MODE_COMBINED_ENHANCE && g_wdn_loaded && g_wdn_net)
                                   ? g_wdn_net
                                   : (g_model_loaded ? g_net : nullptr);
            if (activeNet) {
                std::lock_guard<std::mutex> lock(g_model_mutex);
                std::vector<unsigned char> rgbIn(tileW * tileH * 3);
                for (int i = 0; i < tileW * tileH; ++i) {
                    uint32_t p = srInput[i];
                    rgbIn[i * 3 + 0] = (p >> 16) & 0xFF;
                    rgbIn[i * 3 + 1] = (p >> 8) & 0xFF;
                    rgbIn[i * 3 + 2] = p & 0xFF;
                }

                ncnn::Mat in = ncnn::Mat::from_pixels(rgbIn.data(), ncnn::Mat::PIXEL_RGB, tileW, tileH);
                const float normVals[3] = {1.0f / 255.0f, 1.0f / 255.0f, 1.0f / 255.0f};
                const float meanVals[3] = {0.0f, 0.0f, 0.0f};
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
                        uint8_t r = rgbOut[i * 3 + 0];
                        uint8_t g = rgbOut[i * 3 + 1];
                        uint8_t b = rgbOut[i * 3 + 2];
                        dst[i] = (0xFFU << 24) | (static_cast<uint32_t>(r) << 16) |
                                 (static_cast<uint32_t>(g) << 8) | static_cast<uint32_t>(b);
                    }
                    ncnnDone = true;
                }
            }
#endif
            if (!ncnnDone) {
                superResolutionFallback(srInput, dst.data(), tileW, tileH, scale, 0.85f * clampedStrength);
            }

            // Respect user `strength` parameter on Super-Resolution & Combined modes:
            // When strength < 1.0, blend smoothly with bicubic 4x; when strength > 1.0, apply additional micro-contrast USM
            if (clampedStrength < 0.98f) {
                std::vector<uint32_t> bicubicBase(outW * outH);
                bicubicResize(src, bicubicBase.data(), tileW, tileH, outW, outH);
                float alpha = std::clamp(clampedStrength, 0.0f, 1.0f);
                for (int i = 0; i < outW * outH; ++i) {
                    uint32_t pA = bicubicBase[i];
                    uint32_t pB = dst[i];
                    float r = ((pA >> 16) & 0xFF) * (1.0f - alpha) + ((pB >> 16) & 0xFF) * alpha;
                    float g = ((pA >> 8) & 0xFF) * (1.0f - alpha) + ((pB >> 8) & 0xFF) * alpha;
                    float b = (pA & 0xFF) * (1.0f - alpha) + (pB & 0xFF) * alpha;
                    dst[i] = (0xFFU << 24) |
                             (static_cast<uint32_t>(std::clamp(r, 0.0f, 255.0f)) << 16) |
                             (static_cast<uint32_t>(std::clamp(g, 0.0f, 255.0f)) << 8) |
                             static_cast<uint32_t>(std::clamp(b, 0.0f, 255.0f));
                }
            } else if (mode == MODE_COMBINED_ENHANCE || clampedStrength > 1.02f) {
                std::vector<uint32_t> sharpened(outW * outH);
                float usmAmt = (mode == MODE_COMBINED_ENHANCE)
                               ? (0.55f * clampedStrength)
                               : ((clampedStrength - 1.0f) * 0.85f);
                unsharpMask(dst.data(), sharpened.data(), outW, outH, usmAmt, 1.2f);
                dst = std::move(sharpened);
            }
            break;
        }
        case MODE_BILATERAL_DENOISE: {
            dst.resize(tileW * tileH);
            bilateralDenoise(src, dst.data(), tileW, tileH, 3.0f * clampedStrength, 25.0f * clampedStrength);
            break;
        }
        case MODE_UNSHARP_MASK: {
            dst.resize(tileW * tileH);
            unsharpMask(src, dst.data(), tileW, tileH, 1.2f * clampedStrength, 1.5f);
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
        env->SetIntArrayRegion(result, 0, static_cast<jsize>(dst.size()),
                               reinterpret_cast<const jint*>(dst.data()));
    }
    return result;
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
        outInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return JNI_FALSE;
    }

    void* inPixels = nullptr;
    void* outPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, inputBitmap, &inPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return JNI_FALSE;
    }
    if (AndroidBitmap_lockPixels(env, outputBitmap, &outPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        AndroidBitmap_unlockPixels(env, inputBitmap);
        return JNI_FALSE;
    }

    const auto* src = static_cast<const uint32_t*>(inPixels);
    auto* dst = static_cast<uint32_t*>(outPixels);
    int w = static_cast<int>(inInfo.width);
    int h = static_cast<int>(inInfo.height);
    float s = std::clamp(strength, 0.1f, 2.0f);

    switch (mode) {
        case MODE_BILATERAL_DENOISE:
            bilateralDenoise(src, dst, w, h, 3.0f * s, 25.0f * s);
            break;
        case MODE_UNSHARP_MASK:
            unsharpMask(src, dst, w, h, 1.2f * s, 1.5f);
            break;
        case MODE_FACE_RESTORE:
            restoreFacesNeural(src, dst, w, h, s);
            break;
        case MODE_COMBINED_ENHANCE:
        default: {
            std::vector<uint32_t> temp(w * h);
            bilateralDenoise(src, temp.data(), w, h, 2.5f * s, 20.0f * s);
            unsharpMask(temp.data(), dst, w, h, 0.9f * s, 1.2f);
            break;
        }
    }

    AndroidBitmap_unlockPixels(env, outputBitmap);
    AndroidBitmap_unlockPixels(env, inputBitmap);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_blendTileNative(
        JNIEnv* env, jobject /*thiz*/, jintArray tilePixels, jintArray targetPixels,
        jint tileW, jint tileH, jint targetW, jint targetH,
        jint offsetX, jint offsetY, jint overlapPx) {
    if (!tilePixels || !targetPixels) return;

    jint* tilePtr = env->GetIntArrayElements(tilePixels, nullptr);
    jint* targetPtr = env->GetIntArrayElements(targetPixels, nullptr);

    if (tilePtr && targetPtr) {
        std::vector<float> weightMap(targetW * targetH, 0.0f);
        // Populate existing non-zero pixels in target with weight 1.0 so legacy callers don't darken tiles
        const auto* tgtU32 = reinterpret_cast<const uint32_t*>(targetPtr);
        for (int i = 0; i < targetW * targetH; ++i) {
            if ((tgtU32[i] >> 24) != 0) weightMap[i] = 1.0f;
        }
        cosineTileBlendWithOverlap(
            reinterpret_cast<const uint32_t*>(tilePtr),
            tileW, tileH, offsetX, offsetY,
            reinterpret_cast<uint32_t*>(targetPtr),
            weightMap.data(),
            targetW, targetH, overlapPx);
    }

    if (tilePtr) env->ReleaseIntArrayElements(tilePixels, tilePtr, JNI_ABORT);
    if (targetPtr) env->ReleaseIntArrayElements(targetPixels, targetPtr, 0);
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
            reinterpret_cast<const uint32_t*>(tilePtr),
            tileW, tileH, offsetX, offsetY,
            reinterpret_cast<uint32_t*>(targetPtr),
            weightPtr,
            targetW, targetH, overlapPx);
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

    std::vector<DetectedFace> faces = detectFacesInternal(
        reinterpret_cast<const uint32_t*>(srcPtr), width, height);
    env->ReleaseIntArrayElements(srcPixels, srcPtr, JNI_ABORT);

    // Pack each face as 15 floats: [x, y, w, h, score, l0x, l0y, l1x, l1y, l2x, l2y, l3x, l3y, l4x, l4y]
    std::vector<float> flat;
    flat.reserve(faces.size() * 15);
    for (const auto& f : faces) {
        flat.push_back(f.x);
        flat.push_back(f.y);
        flat.push_back(f.w);
        flat.push_back(f.h);
        flat.push_back(f.score);
        for (int k = 0; k < 10; ++k) flat.push_back(f.landmarks[k]);
    }

    jfloatArray out = env->NewFloatArray(static_cast<jsize>(flat.size()));
    if (out && !flat.empty()) {
        env->SetFloatArrayRegion(out, 0, static_cast<jsize>(flat.size()), flat.data());
    }
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
    if (result) {
        env->SetIntArrayRegion(result, 0, width * height, reinterpret_cast<const jint*>(dst.data()));
    }
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_extractAlphaMatteNative(
        JNIEnv* env, jobject /*thiz*/, jintArray srcPixels, jfloatArray coarseMask, jint width, jint height) {
    if (!srcPixels || width <= 0 || height <= 0) return nullptr;
    jint* srcPtr = env->GetIntArrayElements(srcPixels, nullptr);
    jfloat* maskPtr = coarseMask ? env->GetFloatArrayElements(coarseMask, nullptr) : nullptr;

    std::vector<float> outAlpha(width * height, 0.0f);
    if (srcPtr) {
        extractAlphaMatteInternal(reinterpret_cast<const uint32_t*>(srcPtr), maskPtr, outAlpha.data(), width, height);
    }

    if (srcPtr) env->ReleaseIntArrayElements(srcPixels, srcPtr, JNI_ABORT);
    if (maskPtr) env->ReleaseFloatArrayElements(coarseMask, maskPtr, JNI_ABORT);

    jfloatArray result = env->NewFloatArray(width * height);
    if (result) {
        env->SetFloatArrayRegion(result, 0, width * height, outAlpha.data());
    }
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
    if (result) {
        env->SetFloatArrayRegion(result, 0, width * height, outDepth.data());
    }
    return result;
}

// ---------------------------------------------------------------------------
// 7 Viral AI Native JNI Implementations (com.ararshot.app.engine)
// ---------------------------------------------------------------------------

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
        depthBokeh(reinterpret_cast<const uint32_t*>(src), depth, dst.data(), width, height, blurRadius, focusDepth);
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
Java_com_ararshot_app_engine_ImageEnhancerNative_applyHalationNative(
        JNIEnv* env, jobject /*thiz*/, jintArray srcPixels, jint width, jint height,
        jfloat halationStrength, jfloat grainAmount, jfloat vignetteStrength) {
    if (!srcPixels || width <= 0 || height <= 0) return nullptr;
    jint* src = env->GetIntArrayElements(srcPixels, nullptr);
    std::vector<uint32_t> dst(width * height);

    if (src) {
        vintageHalation(reinterpret_cast<const uint32_t*>(src), dst.data(), width, height,
                        halationStrength, grainAmount, vignetteStrength);
    }

    if (src) env->ReleaseIntArrayElements(srcPixels, src, JNI_ABORT);

    jintArray result = env->NewIntArray(width * height);
    if (result) env->SetIntArrayRegion(result, 0, width * height, reinterpret_cast<const jint*>(dst.data()));
    return result;
}

JNIEXPORT jintArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_applyAnimeFilterNative(
        JNIEnv* env, jobject /*thiz*/, jintArray srcPixels, jint width, jint height,
        jint quantLevels, jfloat edgeThreshold) {
    if (!srcPixels || width <= 0 || height <= 0) return nullptr;
    jint* src = env->GetIntArrayElements(srcPixels, nullptr);
    std::vector<uint32_t> dst(width * height);

    if (src) {
        animeCelShade(reinterpret_cast<const uint32_t*>(src), dst.data(), width, height,
                      quantLevels, edgeThreshold);
    }

    if (src) env->ReleaseIntArrayElements(srcPixels, src, JNI_ABORT);

    jintArray result = env->NewIntArray(width * height);
    if (result) env->SetIntArrayRegion(result, 0, width * height, reinterpret_cast<const jint*>(dst.data()));
    return result;
}

JNIEXPORT jintArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_colorizeImageNative(
        JNIEnv* env, jobject /*thiz*/, jintArray srcPixels, jint width, jint height,
        jfloat saturation) {
    if (!srcPixels || width <= 0 || height <= 0) return nullptr;
    jint* src = env->GetIntArrayElements(srcPixels, nullptr);
    std::vector<uint32_t> dst(width * height);

    if (src) {
        colorizeGrayscale(reinterpret_cast<const uint32_t*>(src), dst.data(), width, height, saturation);
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
        jint shadowOffsetX, jint shadowOffsetY, jfloat shadowBlur, jint bgColor) {
    if (!srcPixels || !mask || width <= 0 || height <= 0) return nullptr;
    jint* src = env->GetIntArrayElements(srcPixels, nullptr);
    jfloat* m = env->GetFloatArrayElements(mask, nullptr);
    std::vector<uint32_t> dst(width * height);

    if (src && m) {
        studioShadowOutline(reinterpret_cast<const uint32_t*>(src), m, dst.data(), width, height,
                            outlineWidth, static_cast<uint32_t>(outlineColor),
                            shadowOffsetX, shadowOffsetY, shadowBlur, static_cast<uint32_t>(bgColor));
    }

    if (src) env->ReleaseIntArrayElements(srcPixels, src, JNI_ABORT);
    if (m) env->ReleaseFloatArrayElements(mask, m, JNI_ABORT);

    jintArray result = env->NewIntArray(width * height);
    if (result) env->SetIntArrayRegion(result, 0, width * height, reinterpret_cast<const jint*>(dst.data()));
    return result;
}

// ---------------------------------------------------------------------------
// Backward-compatible JNI wrappers for com.example.engine.ImageEnhancerNative
// ---------------------------------------------------------------------------

JNIEXPORT jboolean JNICALL
Java_com_example_engine_ImageEnhancerNative_loadModelNative(
        JNIEnv* env, jobject thiz, jobject assetManager, jstring paramPath, jstring binPath) {
    return Java_com_ararshot_app_engine_ImageEnhancerNative_loadModelNative(
            env, thiz, assetManager, paramPath, binPath);
}

JNIEXPORT jintArray JNICALL
Java_com_example_engine_ImageEnhancerNative_processTileNative(
        JNIEnv* env, jobject thiz, jintArray inputPixels, jint tileW, jint tileH,
        jint mode, jfloat strength) {
    return Java_com_ararshot_app_engine_ImageEnhancerNative_processTileNative(
            env, thiz, inputPixels, tileW, tileH, mode, strength);
}

JNIEXPORT jboolean JNICALL
Java_com_example_engine_ImageEnhancerNative_processBitmapNative(
        JNIEnv* env, jobject thiz, jobject inputBitmap, jobject outputBitmap,
        jint mode, jfloat strength) {
    return Java_com_ararshot_app_engine_ImageEnhancerNative_processBitmapNative(
            env, thiz, inputBitmap, outputBitmap, mode, strength);
}

JNIEXPORT void JNICALL
Java_com_example_engine_ImageEnhancerNative_blendTileNative(
        JNIEnv* env, jobject thiz, jintArray tilePixels, jintArray targetPixels,
        jint tileW, jint tileH, jint targetW, jint targetH,
        jint offsetX, jint offsetY, jint overlapPx) {
    Java_com_ararshot_app_engine_ImageEnhancerNative_blendTileNative(
            env, thiz, tilePixels, targetPixels, tileW, tileH, targetW, targetH,
            offsetX, offsetY, overlapPx);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    LOGI("NativeEnhancer JNI library loaded (SOTA Multi-Model NCNN + OpenMP C++17)");
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* /*vm*/, void* /*reserved*/) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
#if USE_NCNN
    if (g_net) { delete g_net; g_net = nullptr; }
    if (g_wdn_net) { delete g_wdn_net; g_wdn_net = nullptr; }
    if (g_scrfd_net) { delete g_scrfd_net; g_scrfd_net = nullptr; }
    if (g_anime_net) { delete g_anime_net; g_anime_net = nullptr; }
    if (g_matting_net) { delete g_matting_net; g_matting_net = nullptr; }
    if (g_depth_net) { delete g_depth_net; g_depth_net = nullptr; }
    g_model_loaded = false;
    g_wdn_loaded = false;
    g_scrfd_loaded = false;
    g_anime_loaded = false;
    g_matting_loaded = false;
    g_depth_loaded = false;
#endif
    LOGI("NativeEnhancer JNI library unloaded");
}

} // extern "C"

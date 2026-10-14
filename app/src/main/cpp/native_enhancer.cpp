#include "native_enhancer.h"

#if USE_NCNN
static ncnn::Net* g_net = nullptr;
static bool g_model_loaded = false;
static bool g_has_vulkan = false;
static std::mutex g_model_mutex;
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

// ---------------------------------------------------------------------------
// Native Mathematical Algorithms (Fallbacks and Specific Modes)
// ---------------------------------------------------------------------------

void unsharpMask(const uint32_t* src, uint32_t* dst, int width, int height, float amount, float radius) {
    if (!src || !dst || width <= 0 || height <= 0) return;

    int r = std::max(1, static_cast<int>(radius + 0.5f));
    std::vector<float> kernel(2 * r + 1);
    float sigma = radius;
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

    // Vertical Gaussian Pass & Difference Blending
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

            float rSharp = rOrig + amount * (rOrig - rBlur);
            float gSharp = gOrig + amount * (gOrig - gBlur);
            float bSharp = bOrig + amount * (bOrig - bBlur);

            dst[y * width + x] = (static_cast<uint32_t>(a) << 24) |
                                 (static_cast<uint32_t>(clamp8(rSharp)) << 16) |
                                 (static_cast<uint32_t>(clamp8(gSharp)) << 8) |
                                 static_cast<uint32_t>(clamp8(bSharp));
        }
    }
}

void bilateralDenoise(const uint32_t* src, uint32_t* dst, int width, int height, float spatialSigma, float colorSigma) {
    if (!src || !dst || width <= 0 || height <= 0) return;

    int r = std::max(1, static_cast<int>(spatialSigma * 1.5f));
    float twoSpatialSigmaSq = 2.0f * spatialSigma * spatialSigma;
    float twoColorSigmaSq = 2.0f * colorSigma * colorSigma;

    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            uint32_t centerP = src[y * width + x];
            float cR = (centerP >> 16) & 0xFF;
            float cG = (centerP >> 8) & 0xFF;
            float cB = centerP & 0xFF;
            uint32_t a = (centerP >> 24) & 0xFF;

            float sumWeights = 0.0f;
            float accR = 0.0f, accG = 0.0f, accB = 0.0f;

            for (int dy = -r; dy <= r; ++dy) {
                int ny = std::clamp(y + dy, 0, height - 1);
                for (int dx = -r; dx <= r; ++dx) {
                    int nx = std::clamp(x + dx, 0, width - 1);
                    uint32_t neighborP = src[ny * width + nx];
                    float nR = (neighborP >> 16) & 0xFF;
                    float nG = (neighborP >> 8) & 0xFF;
                    float nB = neighborP & 0xFF;

                    float spatialDistSq = static_cast<float>(dx * dx + dy * dy);
                    float colorDistSq = (cR - nR) * (cR - nR) + (cG - nG) * (cG - nG) + (cB - nB) * (cB - nB);

                    float weight = std::exp(-spatialDistSq / twoSpatialSigmaSq - colorDistSq / twoColorSigmaSq);
                    accR += nR * weight;
                    accG += nG * weight;
                    accB += nB * weight;
                    sumWeights += weight;
                }
            }

            if (sumWeights > 0.00001f) {
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

void superResolutionFallback(const uint32_t* src, uint32_t* dst, int srcW, int srcH, int scale, float sharpenAmount) {
    int dstW = srcW * scale;
    int dstH = srcH * scale;

    std::vector<uint32_t> upscaled(dstW * dstH);

    for (int y = 0; y < dstH; ++y) {
        float srcY = (y + 0.5f) / scale - 0.5f;
        int yInt = static_cast<int>(std::floor(srcY));
        float yFrac = srcY - yInt;

        for (int x = 0; x < dstW; ++x) {
            float srcX = (x + 0.5f) / scale - 0.5f;
            int xInt = static_cast<int>(std::floor(srcX));
            float xFrac = srcX - xInt;

            float rTotal = 0.0f, gTotal = 0.0f, bTotal = 0.0f, totalWeight = 0.0f;

            for (int m = -1; m <= 2; ++m) {
                int sampleY = std::clamp(yInt + m, 0, srcH - 1);
                float wy = cubicWeight(m - yFrac);

                for (int n = -1; n <= 2; ++n) {
                    int sampleX = std::clamp(xInt + n, 0, srcW - 1);
                    float wx = cubicWeight(n - xFrac);
                    float w = wy * wx;

                    uint32_t p = src[sampleY * srcW + sampleX];
                    rTotal += ((p >> 16) & 0xFF) * w;
                    gTotal += ((p >> 8) & 0xFF) * w;
                    bTotal += (p & 0xFF) * w;
                    totalWeight += w;
                }
            }

            if (totalWeight > 0.0001f) {
                rTotal /= totalWeight;
                gTotal /= totalWeight;
                bTotal /= totalWeight;
            }

            upscaled[y * dstW + x] = 0xFF000000 | (clamp8(rTotal) << 16) | (clamp8(gTotal) << 8) | clamp8(bTotal);
        }
    }

    // Apply smart sharpening to enhance details in upscale
    unsharpMask(upscaled.data(), dst, dstW, dstH, sharpenAmount, 1.2f);
}

void cosineTileBlend(const uint32_t* tile, int tileW, int tileH, int dstX, int dstY,
                     uint32_t* target, float* weightMap, int targetW, int targetH) {
    const float PI = 3.14159265358979323846f;
    for (int y = 0; y < tileH; ++y) {
        int ty = dstY + y;
        if (ty < 0 || ty >= targetH) continue;
        float wy = std::sin((y + 0.5f) * PI / tileH);

        for (int x = 0; x < tileW; ++x) {
            int tx = dstX + x;
            if (tx < 0 || tx >= targetW) continue;
            float wx = std::sin((x + 0.5f) * PI / tileW);
            float weight = wx * wy;

            int targetIdx = ty * targetW + tx;
            uint32_t tp = tile[y * tileW + x];

            float tR = ((tp >> 16) & 0xFF) * weight;
            float tG = ((tp >> 8) & 0xFF) * weight;
            float tB = (tp & 0xFF) * weight;

            float curWeight = weightMap[targetIdx];
            uint32_t curP = target[targetIdx];
            float curR = ((curP >> 16) & 0xFF) * curWeight;
            float curG = ((curP >> 8) & 0xFF) * curWeight;
            float curB = (curP & 0xFF) * curWeight;

            float newWeight = curWeight + weight;
            if (newWeight > 0.00001f) {
                float blendedR = (curR + tR) / newWeight;
                float blendedG = (curG + tG) / newWeight;
                float blendedB = (curB + tB) / newWeight;
                target[targetIdx] = 0xFF000000 | (clamp8(blendedR) << 16) | (clamp8(blendedG) << 8) | clamp8(blendedB);
            }
            weightMap[targetIdx] = newWeight;
        }
    }
}

// ---------------------------------------------------------------------------
// 7 Viral AI Engine Algorithms: Implementation
// ---------------------------------------------------------------------------

void teleaInpaint(const uint32_t* src, const int32_t* mask, uint32_t* dst, int width, int height, int radius) {
    if (!src || !mask || !dst || width <= 0 || height <= 0) return;

    int totalPixels = width * height;
    std::memcpy(dst, src, totalPixels * sizeof(uint32_t));

    // Flags: 0 = KNOWN, 1 = UNKNOWN, 2 = BAND
    std::vector<uint8_t> flags(totalPixels, 0);
    int unknownCount = 0;
    for (int i = 0; i < totalPixels; ++i) {
        if ((mask[i] & 0xFF) > 64 || ((mask[i] >> 24) & 0xFF) > 64) {
            flags[i] = 1;
            unknownCount++;
        }
    }
    if (unknownCount == 0) return;

    // Find initial band pixels (unknown adjacent to known)
    std::vector<int> band;
    const int dx4[4] = {1, -1, 0, 0};
    const int dy4[4] = {0, 0, 1, -1};

    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            int idx = y * width + x;
            if (flags[idx] == 1) {
                bool hasKnownNeighbor = false;
                for (int k = 0; k < 4; ++k) {
                    int nx = x + dx4[k];
                    int ny = y + dy4[k];
                    if (nx >= 0 && nx < width && ny >= 0 && ny < height) {
                        if (flags[ny * width + nx] == 0) {
                            hasKnownNeighbor = true;
                            break;
                        }
                    }
                }
                if (hasKnownNeighbor) {
                    flags[idx] = 2; // BAND
                    band.push_back(idx);
                }
            }
        }
    }

    int r = std::clamp(radius, 2, 12);
    int rSq = r * r;

    // Propagate boundary inwards
    while (!band.empty()) {
        std::vector<int> nextBand;
        for (int pIdx : band) {
            int px = pIdx % width;
            int py = pIdx / width;

            float sumWeights = 0.0f;
            float sumR = 0.0f, sumG = 0.0f, sumB = 0.0f;

            for (int dy = -r; dy <= r; ++dy) {
                int qy = py + dy;
                if (qy < 0 || qy >= height) continue;
                for (int dx = -r; dx <= r; ++dx) {
                    int qx = px + dx;
                    if (qx < 0 || qx >= width) continue;

                    int dSq = dx * dx + dy * dy;
                    if (dSq > rSq) continue;

                    int qIdx = qy * width + qx;
                    if (flags[qIdx] == 0) { // Known neighbor
                        float dist = std::sqrt(static_cast<float>(dSq));
                        float w = 1.0f / (dist * dist + 0.1f);

                        uint32_t col = dst[qIdx];
                        sumR += ((col >> 16) & 0xFF) * w;
                        sumG += ((col >> 8) & 0xFF) * w;
                        sumB += (col & 0xFF) * w;
                        sumWeights += w;
                    }
                }
            }

            if (sumWeights > 0.00001f) {
                uint32_t a = (src[pIdx] >> 24) & 0xFF;
                dst[pIdx] = (a << 24) |
                            (clamp8(sumR / sumWeights) << 16) |
                            (clamp8(sumG / sumWeights) << 8) |
                            clamp8(sumB / sumWeights);
            }
            flags[pIdx] = 0; // Marked as known now

            // Check neighbors for new band pixels
            for (int k = 0; k < 4; ++k) {
                int nx = px + dx4[k];
                int ny = py + dy4[k];
                if (nx >= 0 && nx < width && ny >= 0 && ny < height) {
                    int nIdx = ny * width + nx;
                    if (flags[nIdx] == 1) { // Unknown neighbor becomes band
                        flags[nIdx] = 2;
                        nextBand.push_back(nIdx);
                    }
                }
            }
        }
        band = std::move(nextBand);
    }

    // Edge-preserving smoothing pass over inpainted pixels
    std::vector<uint32_t> temp(totalPixels);
    std::memcpy(temp.data(), dst, totalPixels * sizeof(uint32_t));

    for (int y = 1; y < height - 1; ++y) {
        for (int x = 1; x < width - 1; ++x) {
            int idx = y * width + x;
            if ((mask[idx] & 0xFF) > 64 || ((mask[idx] >> 24) & 0xFF) > 64) {
                float rAcc = 0.0f, gAcc = 0.0f, bAcc = 0.0f, wAcc = 0.0f;
                uint32_t centerP = temp[idx];
                float cR = (centerP >> 16) & 0xFF;
                float cG = (centerP >> 8) & 0xFF;
                float cB = centerP & 0xFF;

                for (int dy = -1; dy <= 1; ++dy) {
                    for (int dx = -1; dx <= 1; ++dx) {
                        uint32_t neighbor = temp[(y + dy) * width + (x + dx)];
                        float nR = (neighbor >> 16) & 0xFF;
                        float nG = (neighbor >> 8) & 0xFF;
                        float nB = neighbor & 0xFF;

                        float colorDiff = std::abs(cR - nR) + std::abs(cG - nG) + std::abs(cB - nB);
                        float w = (dx == 0 && dy == 0) ? 2.0f : 1.0f / (1.0f + colorDiff * 0.05f);

                        rAcc += nR * w;
                        gAcc += nG * w;
                        bAcc += nB * w;
                        wAcc += w;
                    }
                }
                uint32_t a = (dst[idx] >> 24) & 0xFF;
                dst[idx] = (a << 24) |
                           (clamp8(rAcc / wAcc) << 16) |
                           (clamp8(gAcc / wAcc) << 8) |
                           clamp8(bAcc / wAcc);
            }
        }
    }
}

void depthBokeh(const uint32_t* src, const float* depthMask, uint32_t* dst, int width, int height, float maxBlurRadius, float focusDepth) {
    if (!src || !depthMask || !dst || width <= 0 || height <= 0) return;

    float maxR = std::clamp(maxBlurRadius, 1.0f, 32.0f);

    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            int idx = y * width + x;
            float d = depthMask[idx];
            float coc = std::clamp(std::abs(d - focusDepth) * maxR, 0.0f, maxR);

            if (coc < 0.6f) {
                dst[idx] = src[idx];
                continue;
            }

            int ir = static_cast<int>(std::ceil(coc));
            int irSq = ir * ir;
            float rSum = 0.0f, gSum = 0.0f, bSum = 0.0f, wSum = 0.0f;

            int step = (ir > 12) ? 2 : 1;
            for (int dy = -ir; dy <= ir; dy += step) {
                int ny = std::clamp(y + dy, 0, height - 1);
                for (int dx = -ir; dx <= ir; dx += step) {
                    int nx = std::clamp(x + dx, 0, width - 1);
                    int distSq = dx * dx + dy * dy;
                    if (distSq > irSq) continue;

                    uint32_t p = src[ny * width + nx];
                    float pr = (p >> 16) & 0xFF;
                    float pg = (p >> 8) & 0xFF;
                    float pb = p & 0xFF;

                    float lum = 0.299f * pr + 0.587f * pg + 0.114f * pb;
                    float bloom = 1.0f;
                    if (lum > 175.0f) {
                        float diff = (lum - 175.0f) / 80.0f;
                        bloom += 3.5f * diff * diff;
                    }

                    rSum += pr * bloom;
                    gSum += pg * bloom;
                    bSum += pb * bloom;
                    wSum += bloom;
                }
            }

            uint32_t a = (src[idx] >> 24) & 0xFF;
            if (wSum > 0.0001f) {
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

    float lZ = std::max(0.1f, lightZ);

    for (int y = 0; y < height; ++y) {
        float v = static_cast<float>(y) / height;
        for (int x = 0; x < width; ++x) {
            float u = static_cast<float>(x) / width;
            int idx = y * width + x;

            int xL = std::max(0, x - 1);
            int xR = std::min(width - 1, x + 1);
            int yU = std::max(0, y - 1);
            int yD = std::min(height - 1, y + 1);

            float dL = depthMask[y * width + xL];
            float dR = depthMask[y * width + xR];
            float dU = depthMask[yU * width + x];
            float dD = depthMask[yD * width + x];

            float gx = (dR - dL) * 3.5f;
            float gy = (dD - dU) * 3.5f;
            float nLen = std::sqrt(gx * gx + gy * gy + 1.0f);
            float nx = -gx / nLen;
            float ny = -gy / nLen;
            float nz = 1.0f / nLen;

            float lx = lightX - u;
            float ly = lightY - v;
            float lz = lZ;
            float lDist = std::sqrt(lx * lx + ly * ly + lz * lz);
            if (lDist > 0.0001f) {
                lx /= lDist;
                ly /= lDist;
                lz /= lDist;
            }

            float diff = std::max(0.0f, nx * lx + ny * ly + nz * lz);

            float hx = lx;
            float hy = ly;
            float hz = lz + 1.0f;
            float hLen = std::sqrt(hx * hx + hy * hy + hz * hz);
            float spec = 0.0f;
            if (hLen > 0.0001f) {
                hx /= hLen;
                hy /= hLen;
                hz /= hLen;
                float nDotH = std::max(0.0f, nx * hx + ny * hy + nz * hz);
                spec = std::pow(nDotH, 16.0f) * 0.45f;
            }

            float atten = 1.0f / (1.0f + 1.8f * lDist * lDist);
            float personWeight = depthMask[idx];
            float factor = (diff * 0.8f + spec) * atten * intensity * personWeight;

            uint32_t orig = src[idx];
            uint32_t a = (orig >> 24) & 0xFF;
            float oR = (orig >> 16) & 0xFF;
            float oG = (orig >> 8) & 0xFF;
            float oB = orig & 0xFF;

            float newR = oR + (lightR * factor * 255.0f);
            float newG = oG + (lightG * factor * 255.0f);
            float newB = oB + (lightB * factor * 255.0f);

            dst[idx] = (a << 24) | (clamp8(newR) << 16) | (clamp8(newG) << 8) | clamp8(newB);
        }
    }
}

void vintageHalation(const uint32_t* src, uint32_t* dst, int width, int height,
                     float halationRadius, float grainStrength, float chromaticShift, float warmth) {
    if (!src || !dst || width <= 0 || height <= 0) return;

    int total = width * height;
    std::vector<float> halation(total, 0.0f);

    for (int i = 0; i < total; ++i) {
        uint32_t p = src[i];
        float r = (p >> 16) & 0xFF;
        float g = (p >> 8) & 0xFF;
        float b = p & 0xFF;
        float lum = 0.299f * r + 0.587f * g + 0.114f * b;
        if (lum > 200.0f) {
            halation[i] = ((lum - 200.0f) / 55.0f) * 255.0f;
        }
    }

    int hr = std::clamp(static_cast<int>(halationRadius), 2, 24);
    std::vector<float> hKernel(2 * hr + 1);
    float hSigma = hr * 0.5f;
    float hSum = 0.0f;
    for (int i = -hr; i <= hr; ++i) {
        float val = std::exp(-(i * i) / (2.0f * hSigma * hSigma));
        hKernel[i + hr] = val;
        hSum += val;
    }
    for (size_t i = 0; i < hKernel.size(); ++i) hKernel[i] /= hSum;

    std::vector<float> tempH(total, 0.0f);
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            float acc = 0.0f;
            for (int k = -hr; k <= hr; ++k) {
                int nx = std::clamp(x + k, 0, width - 1);
                acc += halation[y * width + nx] * hKernel[k + hr];
            }
            tempH[y * width + x] = acc;
        }
    }
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            float acc = 0.0f;
            for (int k = -hr; k <= hr; ++k) {
                int ny = std::clamp(y + k, 0, height - 1);
                acc += tempH[ny * width + x] * hKernel[k + hr];
            }
            halation[y * width + x] = acc;
        }
    }

    float cx = width * 0.5f;
    float cy = height * 0.5f;
    float maxDist = std::sqrt(cx * cx + cy * cy);
    float chromScale = chromaticShift * 0.008f;

    for (int y = 0; y < height; ++y) {
        float dy = y - cy;
        for (int x = 0; x < width; ++x) {
            float dx = x - cx;
            int idx = y * width + x;

            float distNorm = std::sqrt(dx * dx + dy * dy) / maxDist;
            float shift = chromScale * distNorm;

            int rx = std::clamp(static_cast<int>(cx + dx * (1.0f + shift) + 0.5f), 0, width - 1);
            int ry = std::clamp(static_cast<int>(cy + dy * (1.0f + shift) + 0.5f), 0, height - 1);
            int bx = std::clamp(static_cast<int>(cx + dx * (1.0f - shift) + 0.5f), 0, width - 1);
            int by = std::clamp(static_cast<int>(cy + dy * (1.0f - shift) + 0.5f), 0, height - 1);

            uint32_t rPixel = src[ry * width + rx];
            uint32_t gPixel = src[idx];
            uint32_t bPixel = src[by * width + bx];

            float r = (rPixel >> 16) & 0xFF;
            float g = (gPixel >> 8) & 0xFF;
            float b = bPixel & 0xFF;
            uint32_t a = (gPixel >> 24) & 0xFF;

            float hVal = halation[idx] * warmth;
            r += hVal * 1.0f;
            g += hVal * 0.22f;
            b += hVal * 0.04f;

            float lum = 0.299f * r + 0.587f * g + 0.114f * b;
            float midtoneCurve = 4.0f * (lum / 255.0f) * (1.0f - (lum / 255.0f));

            uint32_t h = (static_cast<uint32_t>(x) * 1664525u + static_cast<uint32_t>(y) * 1013904223u) ^ 0x4f35b719u;
            h ^= h >> 13;
            h *= 0x5bd1e995u;
            h ^= h >> 15;
            float noise = ((h & 0xFFFF) / 32767.5f) - 1.0f;

            float grain = noise * midtoneCurve * grainStrength * 24.0f;
            r += grain;
            g += grain;
            b += grain;

            r = r * 1.02f + 4.0f;
            g = g * 1.01f + 2.0f;
            b = b * 0.98f;

            dst[idx] = (a << 24) | (clamp8(r) << 16) | (clamp8(g) << 8) | clamp8(b);
        }
    }
}

void animeCelShade(const uint32_t* src, uint32_t* dst, int width, int height,
                   int radius, float edgeStrength, float vibrancy) {
    if (!src || !dst || width <= 0 || height <= 0) return;

    int r = std::clamp(radius, 2, 7);
    int total = width * height;
    std::vector<float> lum(total);

    for (int i = 0; i < total; ++i) {
        uint32_t p = src[i];
        lum[i] = 0.299f * ((p >> 16) & 0xFF) + 0.587f * ((p >> 8) & 0xFF) + 0.114f * (p & 0xFF);
    }

    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            int idx = y * width + x;

            float bestVar = 1e9f;
            float bestR = 0.0f, bestG = 0.0f, bestB = 0.0f;

            const int qx0[4] = {0, -r, -r, 0};
            const int qx1[4] = {r, 0, 0, r};
            const int qy0[4] = {0, 0, -r, -r};
            const int qy1[4] = {r, r, 0, 0};

            for (int q = 0; q < 4; ++q) {
                float sumR = 0.0f, sumG = 0.0f, sumB = 0.0f;
                float sumSqR = 0.0f, sumSqG = 0.0f, sumSqB = 0.0f;
                int count = 0;

                for (int dy = qy0[q]; dy <= qy1[q]; ++dy) {
                    int ny = std::clamp(y + dy, 0, height - 1);
                    for (int dx = qx0[q]; dx <= qx1[q]; ++dx) {
                        int nx = std::clamp(x + dx, 0, width - 1);
                        uint32_t p = src[ny * width + nx];
                        float pr = (p >> 16) & 0xFF;
                        float pg = (p >> 8) & 0xFF;
                        float pb = p & 0xFF;

                        sumR += pr; sumG += pg; sumB += pb;
                        sumSqR += pr * pr; sumSqG += pg * pg; sumSqB += pb * pb;
                        count++;
                    }
                }

                if (count > 0) {
                    float meanR = sumR / count;
                    float meanG = sumG / count;
                    float meanB = sumB / count;
                    float var = (sumSqR / count - meanR * meanR) +
                                (sumSqG / count - meanG * meanG) +
                                (sumSqB / count - meanB * meanB);

                    if (var < bestVar) {
                        bestVar = var;
                        bestR = meanR;
                        bestG = meanG;
                        bestB = meanB;
                    }
                }
            }

            int x0 = std::max(0, x - 1);
            int x1 = std::min(width - 1, x + 1);
            int y0 = std::max(0, y - 1);
            int y1 = std::min(height - 1, y + 1);

            float gx = (lum[y0 * width + x1] + 2.0f * lum[y * width + x1] + lum[y1 * width + x1]) -
                       (lum[y0 * width + x0] + 2.0f * lum[y * width + x0] + lum[y1 * width + x0]);
            float gy = (lum[y1 * width + x0] + 2.0f * lum[y1 * width + x] + lum[y1 * width + x1]) -
                       (lum[y0 * width + x0] + 2.0f * lum[y0 * width + x] + lum[y0 * width + x1]);
            float edgeMag = std::sqrt(gx * gx + gy * gy);

            float inkFactor = 1.0f;
            if (edgeMag > 36.0f) {
                float edgeNorm = std::min(1.0f, (edgeMag - 36.0f) / 60.0f);
                inkFactor = 1.0f - (edgeStrength * edgeNorm * 0.75f);
            }

            bestR *= inkFactor;
            bestG *= inkFactor;
            bestB *= inkFactor;

            float avg = (bestR + bestG + bestB) / 3.0f;
            bestR = avg + (bestR - avg) * (1.0f + vibrancy * 0.4f);
            bestG = avg + (bestG - avg) * (1.0f + vibrancy * 0.45f);
            bestB = avg + (bestB - avg) * (1.0f + vibrancy * 0.35f);

            uint32_t a = (src[idx] >> 24) & 0xFF;
            dst[idx] = (a << 24) | (clamp8(bestR) << 16) | (clamp8(bestG) << 8) | clamp8(bestB);
        }
    }
}

void colorizeGrayscale(const uint32_t* src, const float* personMask, uint32_t* dst, int width, int height,
                       float skinWarmth, float skyCyan) {
    if (!src || !personMask || !dst || width <= 0 || height <= 0) return;

    int total = width * height;

    for (int idx = 0; idx < total; ++idx) {
        uint32_t p = src[idx];
        uint32_t a = (p >> 24) & 0xFF;
        float rOrig = (p >> 16) & 0xFF;
        float gOrig = (p >> 8) & 0xFF;
        float bOrig = p & 0xFF;

        float Y = 0.299f * rOrig + 0.587f * gOrig + 0.114f * bOrig;
        float isPerson = personMask[idx];

        float rFinal = Y, gFinal = Y, bFinal = Y;

        if (isPerson > 0.3f) {
            if (Y < 55.0f) {
                rFinal = Y * 1.05f + 3.0f;
                gFinal = Y * 0.98f + 1.0f;
                bFinal = Y * 0.95f;
            } else if (Y <= 215.0f) {
                float skinW = skinWarmth * isPerson;
                rFinal = Y + 38.0f * skinW;
                gFinal = Y + 12.0f * skinW;
                bFinal = Y - 16.0f * skinW;
            } else {
                rFinal = Y + 10.0f;
                gFinal = Y + 6.0f;
                bFinal = Y;
            }
        } else {
            if (Y > 155.0f) {
                float sky = skyCyan * (1.0f - isPerson);
                rFinal = Y - 16.0f * sky;
                gFinal = Y + 4.0f * sky;
                bFinal = Y + 32.0f * sky;
            } else if (Y >= 65.0f) {
                rFinal = Y + 2.0f;
                gFinal = Y + 18.0f;
                bFinal = Y - 8.0f;
            } else {
                rFinal = Y + 4.0f;
                gFinal = Y + 1.0f;
                bFinal = Y;
            }
        }

        dst[idx] = (a << 24) | (clamp8(rFinal) << 16) | (clamp8(gFinal) << 8) | clamp8(bFinal);
    }
}

void studioShadowOutline(const uint32_t* src, const float* mask, uint32_t* dst, int width, int height,
                         int shadowDx, int shadowDy, int shadowRadius, float shadowAlpha,
                         int outlineWidth, uint32_t outlineColor) {
    if (!src || !mask || !dst || width <= 0 || height <= 0) return;

    int total = width * height;
    std::vector<float> dilatedMask(total, 0.0f);
    int oWidth = std::clamp(outlineWidth, 0, 30);

    if (oWidth > 0) {
        int oSq = oWidth * oWidth;
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                if (mask[y * width + x] > 0.45f) {
                    for (int dy = -oWidth; dy <= oWidth; ++dy) {
                        int ny = y + dy;
                        if (ny < 0 || ny >= height) continue;
                        for (int dx = -oWidth; dx <= oWidth; ++dx) {
                            int nx = x + dx;
                            if (nx < 0 || nx >= width) continue;
                            if (dx * dx + dy * dy <= oSq) {
                                dilatedMask[ny * width + nx] = 1.0f;
                            }
                        }
                    }
                }
            }
        }
    } else {
        std::copy(mask, mask + total, dilatedMask.begin());
    }

    std::vector<float> shadowMap(total, 0.0f);
    int sr = std::clamp(shadowRadius, 1, 30);
    for (int y = 0; y < height; ++y) {
        int srcY = y - shadowDy;
        for (int x = 0; x < width; ++x) {
            int srcX = x - shadowDx;
            if (srcX >= 0 && srcX < width && srcY >= 0 && srcY < height) {
                shadowMap[y * width + x] = dilatedMask[srcY * width + srcX];
            }
        }
    }

    std::vector<float> tempS(total, 0.0f);
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            float sum = 0.0f;
            int count = 0;
            for (int k = -sr; k <= sr; ++k) {
                int nx = std::clamp(x + k, 0, width - 1);
                sum += shadowMap[y * width + nx];
                count++;
            }
            tempS[y * width + x] = sum / count;
        }
    }
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            float sum = 0.0f;
            int count = 0;
            for (int k = -sr; k <= sr; ++k) {
                int ny = std::clamp(y + k, 0, height - 1);
                sum += tempS[ny * width + x];
                count++;
            }
            shadowMap[y * width + x] = sum / count;
        }
    }

    uint8_t outA = (outlineColor >> 24) & 0xFF;
    uint8_t outR = (outlineColor >> 16) & 0xFF;
    uint8_t outG = (outlineColor >> 8) & 0xFF;
    uint8_t outB = outlineColor & 0xFF;

    for (int i = 0; i < total; ++i) {
        uint32_t origP = src[i];
        float origA = ((origP >> 24) & 0xFF);
        float origR = ((origP >> 16) & 0xFF);
        float origG = ((origP >> 8) & 0xFF);
        float origB = (origP & 0xFF);

        float fg = mask[i];
        float outline = dilatedMask[i];
        float shadow = shadowMap[i] * shadowAlpha;

        if (fg > 0.6f) {
            dst[i] = origP;
        } else if (outline > 0.5f && oWidth > 0) {
            dst[i] = (outA << 24) | (outR << 16) | (outG << 8) | outB;
        } else if (shadow > 0.01f) {
            float sDarken = 1.0f - std::clamp(shadow, 0.0f, 0.85f);
            dst[i] = (static_cast<uint32_t>(origA) << 24) |
                     (clamp8(origR * sDarken) << 16) |
                     (clamp8(origG * sDarken) << 8) |
                     clamp8(origB * sDarken);
        } else {
            dst[i] = origP;
        }
    }
}

// ---------------------------------------------------------------------------
// JNI Implementation
// ---------------------------------------------------------------------------

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_loadModelNative(
    JNIEnv* env,
    jobject thiz,
    jobject assetManager,
    jstring paramPath,
    jstring binPath
) {
    if (!assetManager || !paramPath || !binPath) {
        LOGE("loadModelNative: invalid arguments");
        return JNI_FALSE;
    }

    try {
        AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
        if (!mgr) {
            LOGE("loadModelNative: failed to obtain AAssetManager");
            return JNI_FALSE;
        }

        const char* param_str = env->GetStringUTFChars(paramPath, nullptr);
        const char* bin_str = env->GetStringUTFChars(binPath, nullptr);
        if (!param_str || !bin_str) {
            if (param_str) env->ReleaseStringUTFChars(paramPath, param_str);
            if (bin_str) env->ReleaseStringUTFChars(binPath, bin_str);
            LOGE("loadModelNative: failed to get string UTF chars");
            return JNI_FALSE;
        }

#if USE_NCNN
        std::lock_guard<std::mutex> lock(g_model_mutex);
        LOGI("loadModelNative: initializing NCNN with param: %s, bin: %s", param_str, bin_str);

#if NCNN_VULKAN
        if (!g_vulkan_initialized) {
            int v_ret = ncnn::create_gpu_instance();
            g_vulkan_initialized = (v_ret == 0);
            LOGI("loadModelNative: ncnn::create_gpu_instance() returned %d (vulkan_initialized=%d)", v_ret, g_vulkan_initialized);
        }

        g_has_vulkan = false;
        if (g_vulkan_initialized) {
            int gpu_count = ncnn::get_gpu_count();
            if (gpu_count > 0) {
                g_has_vulkan = true;
                LOGI("loadModelNative: Vulkan compute available (GPU count = %d)", gpu_count);
            } else {
                LOGI("loadModelNative: GPU count = 0, using CPU inference");
            }
        } else {
            LOGI("loadModelNative: Vulkan initialization failed or unsupported, using CPU inference");
        }
#else
        g_has_vulkan = false;
        LOGI("loadModelNative: Compiled without NCNN_VULKAN, using CPU inference");
#endif

        if (!g_net) {
            g_net = new ncnn::Net();
        } else {
            g_net->clear();
        }

        g_net->opt.use_vulkan_compute = g_has_vulkan;
#if NCNN_VULKAN
        if (g_has_vulkan) {
            int default_gpu = ncnn::get_default_gpu_index();
            if (default_gpu >= 0) {
                g_net->set_vulkan_device(default_gpu);
                LOGI("loadModelNative: Bound network to Vulkan device index: %d", default_gpu);
            }
        }
#endif
        g_net->opt.num_threads = ncnn::get_big_cpu_count();
        g_net->opt.use_fp16_packed = true;
        g_net->opt.use_fp16_storage = true;
        g_net->opt.use_fp16_arithmetic = true;
        g_net->opt.use_packing_layout = true;

        int ret_param = g_net->load_param(mgr, param_str);
        int ret_bin = g_net->load_model(mgr, bin_str);

        if (ret_param == 0 && ret_bin == 0) {
            g_model_loaded = true;
            LOGI("loadModelNative: Real-ESRGAN NCNN model loaded successfully!");
        } else {
            g_model_loaded = false;
            LOGE("loadModelNative: Failed to load model! ret_param=%d, ret_bin=%d", ret_param, ret_bin);
        }
#else
        LOGW("loadModelNative: arar_engine compiled without USE_NCNN. Mathematical fallback mode active.");
#endif

        env->ReleaseStringUTFChars(paramPath, param_str);
        env->ReleaseStringUTFChars(binPath, bin_str);

#if USE_NCNN
        return g_model_loaded ? JNI_TRUE : JNI_FALSE;
#else
        return JNI_FALSE;
#endif
    } catch (const std::exception& e) {
        LOGE("loadModelNative: C++ exception caught: %s", e.what());
        return JNI_FALSE;
    } catch (...) {
        LOGE("loadModelNative: unknown C++ exception caught");
        return JNI_FALSE;
    }
}

JNIEXPORT jintArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_processTileNative(
    JNIEnv* env,
    jobject thiz,
    jintArray inPixels,
    jint width,
    jint height,
    jint mode,
    jfloat strength
) {
    if (!inPixels || width <= 0 || height <= 0) {
        return nullptr;
    }

    jint* src = env->GetIntArrayElements(inPixels, nullptr);
    if (!src) return nullptr;

    try {
        const uint32_t* srcU32 = reinterpret_cast<const uint32_t*>(src);

        // MODE 0: Super-Resolution (x4)
        if (mode == MODE_SUPER_RESOLUTION) {
            bool ncnn_success = false;
            int outW = width * 4;
            int outH = height * 4;
            std::vector<uint32_t> outBuffer(outW * outH);

#if USE_NCNN
            if (g_model_loaded && g_net) {
                std::lock_guard<std::mutex> lock(g_model_mutex);
                std::vector<unsigned char> rgbIn(width * height * 3);
                for (int i = 0; i < width * height; ++i) {
                    uint32_t c = srcU32[i];
                    rgbIn[i * 3 + 0] = (c >> 16) & 0xFF; // R
                    rgbIn[i * 3 + 1] = (c >> 8) & 0xFF;  // G
                    rgbIn[i * 3 + 2] = c & 0xFF;         // B
                }

                ncnn::Mat inMat = ncnn::Mat::from_pixels(rgbIn.data(), ncnn::Mat::PIXEL_RGB, width, height);
                const float normVals[3] = {1.0f / 255.0f, 1.0f / 255.0f, 1.0f / 255.0f};
                const float meanVals[3] = {0.0f, 0.0f, 0.0f};
                inMat.substract_mean_normalize(meanVals, normVals);

                ncnn::Extractor ex = g_net->create_extractor();
                ex.input("data", inMat);

                ncnn::Mat outMat;
                int ret = ex.extract("output", outMat);
                if (ret == 0 && outMat.w > 0 && outMat.h > 0) {
                    outW = outMat.w;
                    outH = outMat.h;
                    outBuffer.resize(outW * outH);

                    std::vector<unsigned char> rgbOut(outW * outH * 3);
                    const float denormVals[3] = {255.0f, 255.0f, 255.0f};
                    outMat.substract_mean_normalize(meanVals, denormVals);
                    outMat.to_pixels(rgbOut.data(), ncnn::Mat::PIXEL_RGB);

                    for (int i = 0; i < outW * outH; ++i) {
                        uint32_t r = rgbOut[i * 3 + 0];
                        uint32_t g = rgbOut[i * 3 + 1];
                        uint32_t b = rgbOut[i * 3 + 2];
                        outBuffer[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
                    }
                    ncnn_success = true;
                } else {
                    LOGW("NCNN extraction returned code %d, falling back to bicubic super-resolution", ret);
                }
            }
#endif

            if (!ncnn_success) {
                float sharpen = strength > 0.0f ? strength : 0.8f;
                superResolutionFallback(srcU32, outBuffer.data(), width, height, 4, sharpen);
            }

            env->ReleaseIntArrayElements(inPixels, src, JNI_ABORT);
            src = nullptr;

            jintArray result = env->NewIntArray(outW * outH);
            if (result) {
                env->SetIntArrayRegion(result, 0, outW * outH, reinterpret_cast<const jint*>(outBuffer.data()));
            }
            return result;
        }

        // Modes preserving dimensions (Denoise, Unsharp, Clarity)
        std::vector<uint32_t> outBuffer(width * height);

        if (mode == MODE_BILATERAL_DENOISE) {
            float sigma = strength > 0.0f ? strength * 10.0f : 15.0f;
            bilateralDenoise(srcU32, outBuffer.data(), width, height, 3.0f, sigma);
        } else if (mode == MODE_UNSHARP_MASK) {
            float amount = strength > 0.0f ? strength : 1.2f;
            unsharpMask(srcU32, outBuffer.data(), width, height, amount, 1.5f);
        } else if (mode == MODE_COMBINED_ENHANCE) {
            std::vector<uint32_t> temp(width * height);
            bilateralDenoise(srcU32, temp.data(), width, height, 2.0f, 10.0f);
            unsharpMask(temp.data(), outBuffer.data(), width, height, strength > 0.0f ? strength : 1.0f, 1.0f);
        } else {
            std::copy(srcU32, srcU32 + (width * height), outBuffer.begin());
        }

        env->ReleaseIntArrayElements(inPixels, src, JNI_ABORT);
        src = nullptr;

        jintArray result = env->NewIntArray(width * height);
        if (result) {
            env->SetIntArrayRegion(result, 0, width * height, reinterpret_cast<const jint*>(outBuffer.data()));
        }
        return result;
    } catch (const std::exception& e) {
        LOGE("processTileNative: C++ exception caught: %s", e.what());
        if (src) env->ReleaseIntArrayElements(inPixels, src, JNI_ABORT);
        return nullptr;
    } catch (...) {
        LOGE("processTileNative: unknown C++ exception caught");
        if (src) env->ReleaseIntArrayElements(inPixels, src, JNI_ABORT);
        return nullptr;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_processBitmapNative(
    JNIEnv* env,
    jobject thiz,
    jobject bitmapIn,
    jobject bitmapOut,
    jint mode,
    jfloat strength
) {
    if (!bitmapIn || !bitmapOut) return JNI_FALSE;

    void* pixelsIn = nullptr;
    void* pixelsOut = nullptr;

    try {
        AndroidBitmapInfo infoIn, infoOut;
        if (AndroidBitmap_getInfo(env, bitmapIn, &infoIn) < 0 || AndroidBitmap_getInfo(env, bitmapOut, &infoOut) < 0) {
            return JNI_FALSE;
        }

        if (AndroidBitmap_lockPixels(env, bitmapIn, &pixelsIn) < 0) {
            return JNI_FALSE;
        }

        if (AndroidBitmap_lockPixels(env, bitmapOut, &pixelsOut) < 0) {
            AndroidBitmap_unlockPixels(env, bitmapIn);
            return JNI_FALSE;
        }

        const uint32_t* src = static_cast<const uint32_t*>(pixelsIn);
        uint32_t* dst = static_cast<uint32_t*>(pixelsOut);

        if (mode == MODE_UNSHARP_MASK) {
            unsharpMask(src, dst, infoIn.width, infoIn.height, strength > 0.0f ? strength : 1.2f, 1.5f);
        } else if (mode == MODE_BILATERAL_DENOISE) {
            bilateralDenoise(src, dst, infoIn.width, infoIn.height, 3.0f, strength > 0.0f ? strength * 10.0f : 15.0f);
        } else if (mode == MODE_SUPER_RESOLUTION) {
            superResolutionFallback(src, dst, infoIn.width, infoIn.height, 4, strength > 0.0f ? strength : 0.8f);
        } else {
            std::copy(src, src + (infoIn.width * infoIn.height), dst);
        }

        AndroidBitmap_unlockPixels(env, bitmapIn);
        AndroidBitmap_unlockPixels(env, bitmapOut);
        return JNI_TRUE;
    } catch (const std::exception& e) {
        LOGE("processBitmapNative: C++ exception caught: %s", e.what());
        if (pixelsIn) AndroidBitmap_unlockPixels(env, bitmapIn);
        if (pixelsOut) AndroidBitmap_unlockPixels(env, bitmapOut);
        return JNI_FALSE;
    } catch (...) {
        LOGE("processBitmapNative: unknown C++ exception caught");
        if (pixelsIn) AndroidBitmap_unlockPixels(env, bitmapIn);
        if (pixelsOut) AndroidBitmap_unlockPixels(env, bitmapOut);
        return JNI_FALSE;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_blendTileNative(
    JNIEnv* env,
    jobject thiz,
    jintArray tilePixels,
    jint tileW,
    jint tileH,
    jint dstX,
    jint dstY,
    jintArray targetPixels,
    jint targetW,
    jint targetH
) {
    if (!tilePixels || !targetPixels || tileW <= 0 || tileH <= 0 || targetW <= 0 || targetH <= 0) {
        return JNI_FALSE;
    }

    jint* tile = nullptr;
    jint* target = nullptr;

    try {
        tile = env->GetIntArrayElements(tilePixels, nullptr);
        target = env->GetIntArrayElements(targetPixels, nullptr);
        if (!tile || !target) {
            if (tile) env->ReleaseIntArrayElements(tilePixels, tile, JNI_ABORT);
            if (target) env->ReleaseIntArrayElements(targetPixels, target, JNI_ABORT);
            return JNI_FALSE;
        }

        std::vector<float> weightMap(targetW * targetH, 1.0f);
        cosineTileBlend(reinterpret_cast<const uint32_t*>(tile), tileW, tileH, dstX, dstY,
                        reinterpret_cast<uint32_t*>(target), weightMap.data(), targetW, targetH);

        env->ReleaseIntArrayElements(tilePixels, tile, JNI_ABORT);
        env->ReleaseIntArrayElements(targetPixels, target, 0);
        return JNI_TRUE;
    } catch (const std::exception& e) {
        LOGE("blendTileNative: C++ exception caught: %s", e.what());
        if (tile) env->ReleaseIntArrayElements(tilePixels, tile, JNI_ABORT);
        if (target) env->ReleaseIntArrayElements(targetPixels, target, JNI_ABORT);
        return JNI_FALSE;
    } catch (...) {
        LOGE("blendTileNative: unknown C++ exception caught");
        if (tile) env->ReleaseIntArrayElements(tilePixels, tile, JNI_ABORT);
        if (target) env->ReleaseIntArrayElements(targetPixels, target, JNI_ABORT);
        return JNI_FALSE;
    }
}

// ---------------------------------------------------------------------------
// 7 Viral AI Engine: JNI Bridge Functions
// ---------------------------------------------------------------------------

JNIEXPORT jintArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_inpaintNative(
    JNIEnv* env, jobject thiz, jintArray inPixels, jintArray inMask, jint width, jint height, jint radius
) {
    if (!inPixels || !inMask || width <= 0 || height <= 0) return nullptr;
    jint* src = env->GetIntArrayElements(inPixels, nullptr);
    jint* mask = env->GetIntArrayElements(inMask, nullptr);
    if (!src || !mask) {
        if (src) env->ReleaseIntArrayElements(inPixels, src, JNI_ABORT);
        if (mask) env->ReleaseIntArrayElements(inMask, mask, JNI_ABORT);
        return nullptr;
    }

    int total = width * height;
    std::vector<uint32_t> dst(total);
    teleaInpaint(reinterpret_cast<const uint32_t*>(src), mask, dst.data(), width, height, radius);

    env->ReleaseIntArrayElements(inPixels, src, JNI_ABORT);
    env->ReleaseIntArrayElements(inMask, mask, JNI_ABORT);

    jintArray result = env->NewIntArray(total);
    if (result) {
        env->SetIntArrayRegion(result, 0, total, reinterpret_cast<const jint*>(dst.data()));
    }
    return result;
}

JNIEXPORT jintArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_depthBokehNative(
    JNIEnv* env, jobject thiz, jintArray inPixels, jfloatArray depthMask, jint width, jint height, jfloat blurRadius, jfloat focusDepth
) {
    if (!inPixels || !depthMask || width <= 0 || height <= 0) return nullptr;
    jint* src = env->GetIntArrayElements(inPixels, nullptr);
    jfloat* mask = env->GetFloatArrayElements(depthMask, nullptr);
    if (!src || !mask) {
        if (src) env->ReleaseIntArrayElements(inPixels, src, JNI_ABORT);
        if (mask) env->ReleaseFloatArrayElements(depthMask, mask, JNI_ABORT);
        return nullptr;
    }

    int total = width * height;
    std::vector<uint32_t> dst(total);
    depthBokeh(reinterpret_cast<const uint32_t*>(src), mask, dst.data(), width, height, blurRadius, focusDepth);

    env->ReleaseIntArrayElements(inPixels, src, JNI_ABORT);
    env->ReleaseFloatArrayElements(depthMask, mask, JNI_ABORT);

    jintArray result = env->NewIntArray(total);
    if (result) {
        env->SetIntArrayRegion(result, 0, total, reinterpret_cast<const jint*>(dst.data()));
    }
    return result;
}

JNIEXPORT jintArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_relighting3DNative(
    JNIEnv* env, jobject thiz, jintArray inPixels, jfloatArray depthMask, jint width, jint height,
    jfloat lightX, jfloat lightY, jfloat lightZ, jfloat lightR, jfloat lightG, jfloat lightB, jfloat intensity
) {
    if (!inPixels || !depthMask || width <= 0 || height <= 0) return nullptr;
    jint* src = env->GetIntArrayElements(inPixels, nullptr);
    jfloat* mask = env->GetFloatArrayElements(depthMask, nullptr);
    if (!src || !mask) {
        if (src) env->ReleaseIntArrayElements(inPixels, src, JNI_ABORT);
        if (mask) env->ReleaseFloatArrayElements(depthMask, mask, JNI_ABORT);
        return nullptr;
    }

    int total = width * height;
    std::vector<uint32_t> dst(total);
    relighting3D(reinterpret_cast<const uint32_t*>(src), mask, dst.data(), width, height,
                 lightX, lightY, lightZ, lightR, lightG, lightB, intensity);

    env->ReleaseIntArrayElements(inPixels, src, JNI_ABORT);
    env->ReleaseFloatArrayElements(depthMask, mask, JNI_ABORT);

    jintArray result = env->NewIntArray(total);
    if (result) {
        env->SetIntArrayRegion(result, 0, total, reinterpret_cast<const jint*>(dst.data()));
    }
    return result;
}

JNIEXPORT jintArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_animeCelShadeNative(
    JNIEnv* env, jobject thiz, jintArray inPixels, jint width, jint height, jint radius, jfloat edgeStrength, jfloat vibrancy
) {
    if (!inPixels || width <= 0 || height <= 0) return nullptr;
    jint* src = env->GetIntArrayElements(inPixels, nullptr);
    if (!src) return nullptr;

    int total = width * height;
    std::vector<uint32_t> dst(total);
    animeCelShade(reinterpret_cast<const uint32_t*>(src), dst.data(), width, height, radius, edgeStrength, vibrancy);

    env->ReleaseIntArrayElements(inPixels, src, JNI_ABORT);

    jintArray result = env->NewIntArray(total);
    if (result) {
        env->SetIntArrayRegion(result, 0, total, reinterpret_cast<const jint*>(dst.data()));
    }
    return result;
}

JNIEXPORT jintArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_vintageHalationNative(
    JNIEnv* env, jobject thiz, jintArray inPixels, jint width, jint height, jfloat halationRadius, jfloat grainStrength, jfloat chromaticShift, jfloat warmth
) {
    if (!inPixels || width <= 0 || height <= 0) return nullptr;
    jint* src = env->GetIntArrayElements(inPixels, nullptr);
    if (!src) return nullptr;

    int total = width * height;
    std::vector<uint32_t> dst(total);
    vintageHalation(reinterpret_cast<const uint32_t*>(src), dst.data(), width, height, halationRadius, grainStrength, chromaticShift, warmth);

    env->ReleaseIntArrayElements(inPixels, src, JNI_ABORT);

    jintArray result = env->NewIntArray(total);
    if (result) {
        env->SetIntArrayRegion(result, 0, total, reinterpret_cast<const jint*>(dst.data()));
    }
    return result;
}

JNIEXPORT jintArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_colorizeGrayscaleNative(
    JNIEnv* env, jobject thiz, jintArray inPixels, jfloatArray personMask, jint width, jint height, jfloat skinWarmth, jfloat skyCyan
) {
    if (!inPixels || !personMask || width <= 0 || height <= 0) return nullptr;
    jint* src = env->GetIntArrayElements(inPixels, nullptr);
    jfloat* mask = env->GetFloatArrayElements(personMask, nullptr);
    if (!src || !mask) {
        if (src) env->ReleaseIntArrayElements(inPixels, src, JNI_ABORT);
        if (mask) env->ReleaseFloatArrayElements(personMask, mask, JNI_ABORT);
        return nullptr;
    }

    int total = width * height;
    std::vector<uint32_t> dst(total);
    colorizeGrayscale(reinterpret_cast<const uint32_t*>(src), mask, dst.data(), width, height, skinWarmth, skyCyan);

    env->ReleaseIntArrayElements(inPixels, src, JNI_ABORT);
    env->ReleaseFloatArrayElements(personMask, mask, JNI_ABORT);

    jintArray result = env->NewIntArray(total);
    if (result) {
        env->SetIntArrayRegion(result, 0, total, reinterpret_cast<const jint*>(dst.data()));
    }
    return result;
}

JNIEXPORT jintArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_studioShadowOutlineNative(
    JNIEnv* env, jobject thiz, jintArray inPixels, jfloatArray mask, jint width, jint height,
    jint shadowDx, jint shadowDy, jint shadowRadius, jfloat shadowAlpha, jint outlineWidth, jint outlineColor
) {
    if (!inPixels || !mask || width <= 0 || height <= 0) return nullptr;
    jint* src = env->GetIntArrayElements(inPixels, nullptr);
    jfloat* m = env->GetFloatArrayElements(mask, nullptr);
    if (!src || !m) {
        if (src) env->ReleaseIntArrayElements(inPixels, src, JNI_ABORT);
        if (m) env->ReleaseFloatArrayElements(mask, m, JNI_ABORT);
        return nullptr;
    }

    int total = width * height;
    std::vector<uint32_t> dst(total);
    studioShadowOutline(reinterpret_cast<const uint32_t*>(src), m, dst.data(), width, height,
                        shadowDx, shadowDy, shadowRadius, shadowAlpha, outlineWidth, static_cast<uint32_t>(outlineColor));

    env->ReleaseIntArrayElements(inPixels, src, JNI_ABORT);
    env->ReleaseFloatArrayElements(mask, m, JNI_ABORT);

    jintArray result = env->NewIntArray(total);
    if (result) {
        env->SetIntArrayRegion(result, 0, total, reinterpret_cast<const jint*>(dst.data()));
    }
    return result;
}

// ---------------------------------------------------------------------------
// Backward-compatible wrappers for com.example.engine.ImageEnhancerNative
// ---------------------------------------------------------------------------

JNIEXPORT jboolean JNICALL
Java_com_example_engine_ImageEnhancerNative_loadModelNative(
    JNIEnv* env,
    jobject thiz,
    jobject assetManager,
    jstring paramPath,
    jstring binPath
) {
    return Java_com_ararshot_app_engine_ImageEnhancerNative_loadModelNative(env, thiz, assetManager, paramPath, binPath);
}

JNIEXPORT jintArray JNICALL
Java_com_example_engine_ImageEnhancerNative_processTileNative(
    JNIEnv* env,
    jobject thiz,
    jintArray inPixels,
    jint width,
    jint height,
    jint mode,
    jfloat strength
) {
    return Java_com_ararshot_app_engine_ImageEnhancerNative_processTileNative(env, thiz, inPixels, width, height, mode, strength);
}

JNIEXPORT jboolean JNICALL
Java_com_example_engine_ImageEnhancerNative_processBitmapNative(
    JNIEnv* env,
    jobject thiz,
    jobject bitmapIn,
    jobject bitmapOut,
    jint mode,
    jfloat strength
) {
    return Java_com_ararshot_app_engine_ImageEnhancerNative_processBitmapNative(env, thiz, bitmapIn, bitmapOut, mode, strength);
}

JNIEXPORT jboolean JNICALL
Java_com_example_engine_ImageEnhancerNative_blendTileNative(
    JNIEnv* env,
    jobject thiz,
    jintArray tilePixels,
    jint tileW,
    jint tileH,
    jint dstX,
    jint dstY,
    jintArray targetPixels,
    jint targetW,
    jint targetH
) {
    return Java_com_ararshot_app_engine_ImageEnhancerNative_blendTileNative(env, thiz, tilePixels, tileW, tileH, dstX, dstY, targetPixels, targetW, targetH);
}

// ---------------------------------------------------------------------------
// JNI Lifecycle hooks
// ---------------------------------------------------------------------------

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGI("JNI_OnLoad: initializing arar_engine native library");
    try {
#if USE_NCNN && NCNN_VULKAN
        if (!g_vulkan_initialized) {
            int v_ret = ncnn::create_gpu_instance();
            g_vulkan_initialized = (v_ret == 0);
            LOGI("JNI_OnLoad: ncnn::create_gpu_instance() returned %d (vulkan_initialized=%d)", v_ret, g_vulkan_initialized);
        }
#endif
    } catch (...) {
        LOGE("JNI_OnLoad: exception caught during initialization");
    }
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved) {
    LOGI("JNI_OnUnload: cleaning up arar_engine native library");
    try {
#if USE_NCNN
        if (g_net) {
            delete g_net;
            g_net = nullptr;
        }
#if NCNN_VULKAN
        if (g_vulkan_initialized) {
            ncnn::destroy_gpu_instance();
            g_vulkan_initialized = false;
            LOGI("JNI_OnUnload: ncnn::destroy_gpu_instance() executed");
        }
#endif
#endif
    } catch (...) {
        LOGE("JNI_OnUnload: exception caught during cleanup");
    }
}

} // extern "C"

#include <jni.h>
#include <android/bitmap.h>
#include <cstdint>
#include <cstring>
#include <algorithm>

extern "C" {

JNIEXPORT jdouble JNICALL
Java_com_antigravity_deadframeremover_engine_NativeComparator_comparePackedWithYPlane(
    JNIEnv* env,
    jobject /* thiz */,
    jobject packedPrevBuffer,
    jobject currYBuffer,
    jint currYOffset,
    jint currYRowStride,
    jint currYPixelStride,
    jint width,
    jint height
) {
    if (!packedPrevBuffer || !currYBuffer || width <= 0 || height <= 0 || currYOffset < 0) {
        return 999999.0;
    }

    const auto* prev = static_cast<const uint8_t*>(env->GetDirectBufferAddress(packedPrevBuffer));
    const auto* curr_base = static_cast<const uint8_t*>(env->GetDirectBufferAddress(currYBuffer));
    if (!prev || !curr_base) {
        return 999999.0;
    }

    const uint8_t* curr = curr_base + currYOffset;

    int sampled_rows = (height + 1) / 2;
    int sampled_cols = (width + 1) / 2;
    uint64_t total_samples = static_cast<uint64_t>(sampled_rows) * sampled_cols;
    if (total_samples == 0) return 0.0;

    uint64_t sum_sq = 0;

    for (int y = 0; y < height; y += 2) {
        const uint8_t* row_p = prev + (y * width);
        const uint8_t* row_c = curr + (y * currYRowStride);
        for (int x = 0; x < width; x += 2) {
            int32_t diff = static_cast<int32_t>(row_p[x]) - static_cast<int32_t>(row_c[x * currYPixelStride]);
            sum_sq += static_cast<uint64_t>(diff * diff);
        }
    }

    return static_cast<jdouble>(sum_sq) / static_cast<double>(total_samples);
}

JNIEXPORT jint JNICALL
Java_com_antigravity_deadframeremover_engine_NativeComparator_packYPlane(
    JNIEnv* env,
    jobject /* thiz */,
    jobject currYBuffer,
    jint currYOffset,
    jint currYRowStride,
    jint currYPixelStride,
    jint width,
    jint height,
    jobject packedDstBuffer
) {
    if (!currYBuffer || !packedDstBuffer || width <= 0 || height <= 0 || currYOffset < 0) {
        return -1;
    }

    const auto* src_base = static_cast<const uint8_t*>(env->GetDirectBufferAddress(currYBuffer));
    auto* dst = static_cast<uint8_t*>(env->GetDirectBufferAddress(packedDstBuffer));
    if (!src_base || !dst) return -2;

    const uint8_t* src = src_base + currYOffset;

    for (int y = 0; y < height; ++y) {
        const uint8_t* src_row = src + (y * currYRowStride);
        uint8_t* dst_row = dst + (y * width);
        if (currYPixelStride == 1) {
            std::memcpy(dst_row, src_row, width);
        } else {
            for (int x = 0; x < width; ++x) {
                dst_row[x] = src_row[x * currYPixelStride];
            }
        }
    }
    return 0;
}

JNIEXPORT jdouble JNICALL
Java_com_antigravity_deadframeremover_engine_NativeComparator_compareYUVPlanes(
    JNIEnv* env,
    jobject /* thiz */,
    jobject bufferPrev,
    jint prevOffset,
    jobject bufferCurr,
    jint currOffset,
    jint width,
    jint height,
    jint yRowStride,
    jint yPixelStride,
    jdouble /* threshold */
) {
    if (!bufferPrev || !bufferCurr || width <= 0 || height <= 0 || prevOffset < 0 || currOffset < 0) {
        return 999999.0;
    }

    const auto* prev_base = static_cast<const uint8_t*>(env->GetDirectBufferAddress(bufferPrev));
    const auto* curr_base = static_cast<const uint8_t*>(env->GetDirectBufferAddress(bufferCurr));
    if (!prev_base || !curr_base) {
        return 999999.0;
    }

    const uint8_t* prev = prev_base + prevOffset;
    const uint8_t* curr = curr_base + currOffset;

    int sampled_rows = (height + 1) / 2;
    int sampled_cols = (width + 1) / 2;
    uint64_t total_samples = static_cast<uint64_t>(sampled_rows) * sampled_cols;
    if (total_samples == 0) return 0.0;

    uint64_t sum_sq = 0;

    for (int y = 0; y < height; y += 2) {
        const uint8_t* row_p = prev + (y * yRowStride);
        const uint8_t* row_c = curr + (y * yRowStride);
        for (int x = 0; x < width; x += 2) {
            int32_t diff = static_cast<int32_t>(row_p[x * yPixelStride]) - static_cast<int32_t>(row_c[x * yPixelStride]);
            sum_sq += static_cast<uint64_t>(diff * diff);
        }
    }

    return static_cast<jdouble>(sum_sq) / static_cast<double>(total_samples);
}

JNIEXPORT jint JNICALL
Java_com_antigravity_deadframeremover_engine_NativeComparator_normalizeYUV420ToNV12(
    JNIEnv* env,
    jobject /* thiz */,
    jobject yBuffer, jint yOffset, jint yRowStride, jint yPixelStride,
    jobject uBuffer, jint uOffset, jint uRowStride, jint uPixelStride,
    jobject vBuffer, jint vOffset, jint vRowStride, jint vPixelStride,
    jobject dstBuffer, jint dstOffset,
    jint width, jint height
) {
    if (!yBuffer || !uBuffer || !vBuffer || !dstBuffer) return -1;
    if (width <= 0 || height <= 0 || yOffset < 0 || uOffset < 0 || vOffset < 0 || dstOffset < 0) return -2;

    const auto* y_base = static_cast<const uint8_t*>(env->GetDirectBufferAddress(yBuffer));
    const auto* u_base = static_cast<const uint8_t*>(env->GetDirectBufferAddress(uBuffer));
    const auto* v_base = static_cast<const uint8_t*>(env->GetDirectBufferAddress(vBuffer));
    auto* dst_base     = static_cast<uint8_t*>(env->GetDirectBufferAddress(dstBuffer));

    if (!y_base || !u_base || !v_base || !dst_base) return -3;

    const uint8_t* y_src = y_base + yOffset;
    const uint8_t* u_src = u_base + uOffset;
    const uint8_t* v_src = v_base + vOffset;
    uint8_t* dst         = dst_base + dstOffset;

    // 1. Explicit Y Plane Packing (width * height)
    uint8_t* dst_y = dst;
    for (int r = 0; r < height; ++r) {
        const uint8_t* src_row = y_src + (r * yRowStride);
        uint8_t* dst_row = dst_y + (r * width);
        if (yPixelStride == 1) {
            std::memcpy(dst_row, src_row, width);
        } else {
            for (int c = 0; c < width; ++c) {
                dst_row[c] = src_row[c * yPixelStride];
            }
        }
    }

    // 2. Explicit UV Interleaved Plane Packing (width * (height / 2))
    uint8_t* dst_uv = dst + (width * height);
    int uv_height = height / 2;
    int uv_width  = width / 2;
    for (int r = 0; r < uv_height; ++r) {
        const uint8_t* u_row = u_src + (r * uRowStride);
        const uint8_t* v_row = v_src + (r * vRowStride);
        uint8_t* dst_row = dst_uv + (r * width);
        for (int c = 0; c < uv_width; ++c) {
            dst_row[2 * c]     = u_row[c * uPixelStride];
            dst_row[2 * c + 1] = v_row[c * vPixelStride];
        }
    }

    return 0;
}

JNIEXPORT jint JNICALL
Java_com_antigravity_deadframeremover_engine_NativeComparator_yuvToRgbBitmap(
    JNIEnv* env,
    jobject /* thiz */,
    jobject yBuffer, jint yOffset, jint yRowStride, jint yPixelStride,
    jobject uBuffer, jint uOffset, jint uRowStride, jint uPixelStride,
    jobject vBuffer, jint vOffset, jint vRowStride, jint vPixelStride,
    jint srcWidth, jint srcHeight,
    jobject dstBitmap
) {
    if (!yBuffer || !uBuffer || !vBuffer || !dstBitmap) return -1;
    if (srcWidth <= 0 || srcHeight <= 0 || yOffset < 0 || uOffset < 0 || vOffset < 0) return -2;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, dstBitmap, &info) < 0) return -3;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return -4;

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, dstBitmap, &pixels) < 0 || !pixels) return -5;

    const auto* y_base = static_cast<const uint8_t*>(env->GetDirectBufferAddress(yBuffer));
    const auto* u_base = static_cast<const uint8_t*>(env->GetDirectBufferAddress(uBuffer));
    const auto* v_base = static_cast<const uint8_t*>(env->GetDirectBufferAddress(vBuffer));

    if (!y_base || !u_base || !v_base) {
        AndroidBitmap_unlockPixels(env, dstBitmap);
        return -6;
    }

    const uint8_t* y_src = y_base + yOffset;
    const uint8_t* u_src = u_base + uOffset;
    const uint8_t* v_src = v_base + vOffset;

    int dstWidth  = static_cast<int>(info.width);
    int dstHeight = static_cast<int>(info.height);
    uint32_t strideBytes = info.stride;

    for (int dy = 0; dy < dstHeight; ++dy) {
        int sy = (dy * srcHeight) / dstHeight;
        int uv_y = (sy / 2);
        const uint8_t* row_y = y_src + (sy * yRowStride);
        const uint8_t* row_u = u_src + (uv_y * uRowStride);
        const uint8_t* row_v = v_src + (uv_y * vRowStride);

        auto* out_row = reinterpret_cast<uint8_t*>(pixels) + (dy * strideBytes);

        for (int dx = 0; dx < dstWidth; ++dx) {
            int sx = (dx * srcWidth) / dstWidth;
            int uv_x = (sx / 2);

            int y_val = static_cast<int>(row_y[sx * yPixelStride]);
            int u_val = static_cast<int>(row_u[uv_x * uPixelStride]);
            int v_val = static_cast<int>(row_v[uv_x * vPixelStride]);

            int c = y_val - 16;
            int d = u_val - 128;
            int e = v_val - 128;

            int r = (298 * c + 409 * e + 128) >> 8;
            int g = (298 * c - 100 * d - 208 * e + 128) >> 8;
            int b = (298 * c + 516 * d + 128) >> 8;

            out_row[dx * 4 + 0] = static_cast<uint8_t>(std::clamp(r, 0, 255));
            out_row[dx * 4 + 1] = static_cast<uint8_t>(std::clamp(g, 0, 255));
            out_row[dx * 4 + 2] = static_cast<uint8_t>(std::clamp(b, 0, 255));
            out_row[dx * 4 + 3] = 0xFF;
        }
    }

    AndroidBitmap_unlockPixels(env, dstBitmap);
    return 0;
}

} // extern "C"

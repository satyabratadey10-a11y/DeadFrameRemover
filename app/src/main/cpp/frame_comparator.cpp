#include <jni.h>
#include <cstdint>
#include <cstring>
#include <algorithm>

extern "C" {

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
    jdouble threshold
) {
    if (!bufferPrev || !bufferCurr) {
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

    uint64_t max_allowed_sum_sq = static_cast<uint64_t>(threshold * static_cast<double>(total_samples));
    uint64_t sum_sq = 0;

    for (int y = 0; y < height; y += 2) {
        const uint8_t* row_p = prev + (y * yRowStride);
        const uint8_t* row_c = curr + (y * yRowStride);
        for (int x = 0; x < width; x += 2) {
            int32_t diff = static_cast<int32_t>(row_p[x * yPixelStride]) - static_cast<int32_t>(row_c[x * yPixelStride]);
            sum_sq += static_cast<uint64_t>(diff * diff);
        }
        // Early exit: if accumulated sum_sq already exceeds max allowed for duplicate
        if (sum_sq > max_allowed_sum_sq) {
            return static_cast<jdouble>(sum_sq) / static_cast<double>(total_samples);
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

    const auto* y_base = static_cast<const uint8_t*>(env->GetDirectBufferAddress(yBuffer));
    const auto* u_base = static_cast<const uint8_t*>(env->GetDirectBufferAddress(uBuffer));
    const auto* v_base = static_cast<const uint8_t*>(env->GetDirectBufferAddress(vBuffer));
    auto* dst_base     = static_cast<uint8_t*>(env->GetDirectBufferAddress(dstBuffer));

    if (!y_base || !u_base || !v_base || !dst_base) return -2;

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

} // extern "C"

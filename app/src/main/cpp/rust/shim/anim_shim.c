/* Animated WebP re-encode with baked overlay, FPS normalization, looping and trimming.
 * Exposes a scalar/buffer-only ABI so Rust never has to mirror libwebp structs. */

#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

#include "src/webp/decode.h"
#include "src/webp/encode.h"
#include "src/webp/demux.h"
#include "src/webp/mux.h"

void wa_anim_free(uint8_t* p) { free(p); }

/* source-over blend of non-premultiplied RGBA */
static void blend_over(uint8_t* d, const uint8_t* s) {
    const int sa = s[3];
    if (sa == 0) return;
    if (sa == 255) { d[0] = s[0]; d[1] = s[1]; d[2] = s[2]; d[3] = 255; return; }
    const int da = d[3];
    const int outa = sa + da * (255 - sa) / 255;
    if (outa == 0) { d[0] = d[1] = d[2] = d[3] = 0; return; }
    for (int c = 0; c < 3; ++c) {
        const int num = s[c] * sa + d[c] * da * (255 - sa) / 255;
        d[c] = (uint8_t)(num / outa);
    }
    d[3] = (uint8_t)outa;
}

static void sample_bilinear(const uint8_t* f, int fw, int fh,
                            float sx, float sy, uint8_t* o) {
    if (sx < -0.5f || sy < -0.5f || sx >= fw - 0.5f || sy >= fh - 0.5f) {
        o[0] = o[1] = o[2] = o[3] = 0;
        return;
    }
    const float cx = sx < 0 ? 0 : (sx > fw - 1 ? fw - 1 : sx);
    const float cy = sy < 0 ? 0 : (sy > fh - 1 ? fh - 1 : sy);
    const int x0 = (int)cx, y0 = (int)cy;
    const int x1 = (x0 + 1 < fw) ? x0 + 1 : x0;
    const int y1 = (y0 + 1 < fh) ? y0 + 1 : y0;
    const float fx = cx - x0, fy = cy - y0;
    const uint8_t* p00 = f + ((size_t)y0 * fw + x0) * 4;
    const uint8_t* p10 = f + ((size_t)y0 * fw + x1) * 4;
    const uint8_t* p01 = f + ((size_t)y1 * fw + x0) * 4;
    const uint8_t* p11 = f + ((size_t)y1 * fw + x1) * 4;
    for (int c = 0; c < 4; ++c) {
        const float top = p00[c] * (1.f - fx) + p10[c] * fx;
        const float bot = p01[c] * (1.f - fx) + p11[c] * fx;
        o[c] = (uint8_t)(top * (1.f - fy) + bot * fy + 0.5f);
    }
}

typedef struct {
    uint8_t* rgba;       /* out_size * out_size * 4 */
    int duration_ms;     /* original duration of this frame */
} AFrame;

/* Find the source frame index covering time t (ms) in the original timeline. */
static int find_frame_index(const int* starts, const int* durs, int n, int t) {
    (void)durs;
    for (int i = n - 1; i >= 0; --i) {
        if (t >= starts[i]) return i;
    }
    return 0;
}

/*
 * Re-encode an animated WebP:
 *  - applies affine transform + overlay to every source frame
 *  - loops the animation ONLY if target_duration_ms > original duration
 *  - trims to max_duration_ms (hard ceiling) when needed
 *  - resamples the whole thing to target_fps
 *
 * Matrix maps source coordinates to destination 512x512 coordinates:
 *   dst_x = m0*src_x + m1*src_y + m2
 *   dst_y = m3*src_x + m4*src_y + m5
 *
 * target_duration_ms < 0  -> keep original duration (still resample FPS)
 * target_duration_ms > original -> loop to reach it
 * target_duration_ms <= original -> do NOT trim to match audio (rule)
 * max_duration_ms > 0 -> hard ceiling applied to the final duration
 * target_fps <= 0 -> default to 10
 *
 * Returns 1 on success; *out is malloc'd and must be released with wa_anim_free.
 */
int wa_anim_overlay(const uint8_t* src, size_t src_len,
                    const uint8_t* ov, int ov_w, int ov_h,
                    float m0, float m1, float m2,
                    float m3, float m4, float m5,
                    int out_size, float quality,
                    int target_duration_ms, int max_duration_ms, int target_fps,
                    uint8_t** out, size_t* out_len) {
    *out = NULL;
    *out_len = 0;

    if (!src || src_len == 0 || out_size <= 0) return 0;
    if (target_fps <= 0) target_fps = 10;

    const float det = m0 * m4 - m1 * m3;
    if (fabsf(det) < 1e-8f) return 0;
    const float inv_det = 1.f / det;
    const float ia =  m4 * inv_det;
    const float ib = -m1 * inv_det;
    const float id = -m3 * inv_det;
    const float ie =  m0 * inv_det;

    WebPData data = { src, src_len };

    /* ---- decode frame durations via demuxer ---- */
    WebPDemuxer* demux = WebPDemux(&data);
    if (!demux) return 0;

    const int nframes = WebPDemuxGetI(demux, WEBP_FF_FRAME_COUNT);
    if (nframes <= 0 || nframes > 4096) {
        WebPDemuxDelete(demux);
        return 0;
    }

    int* durs = (int*)calloc((size_t)nframes, sizeof(int));
    if (!durs) { WebPDemuxDelete(demux); return 0; }

    {
        WebPIterator it;
        int idx = 0;
        if (WebPDemuxGetFrame(demux, 1, &it)) {
            do {
                if (idx < nframes) {
                    durs[idx] = it.duration > 0 ? it.duration : 100;
                    idx++;
                }
            } while (WebPDemuxNextFrame(&it));
            WebPDemuxReleaseIterator(&it);
        }
        for (int i = idx; i < nframes; ++i) durs[i] = 100;
    }
    WebPDemuxDelete(demux);

    /* ---- decode pixels via anim decoder ---- */
    WebPAnimDecoderOptions dopts;
    if (!WebPAnimDecoderOptionsInit(&dopts)) { free(durs); return 0; }
    dopts.color_mode = MODE_RGBA;
    dopts.use_threads = 0;

    WebPAnimDecoder* dec = WebPAnimDecoderNew(&data, &dopts);
    if (!dec) { free(durs); return 0; }

    WebPAnimInfo info;
    memset(&info, 0, sizeof(info));
    if (!WebPAnimDecoderGetInfo(dec, &info)) {
        WebPAnimDecoderDelete(dec); free(durs); return 0;
    }
    const int fw = (int)info.canvas_width;
    const int fh = (int)info.canvas_height;
    if (fw <= 0 || fh <= 0 || fw > 8192 || fh > 8192) {
        WebPAnimDecoderDelete(dec); free(durs); return 0;
    }

    const size_t frame_bytes = (size_t)fw * fh * 4;
    const size_t canvas_bytes = (size_t)out_size * out_size * 4;

    AFrame* frames = (AFrame*)calloc((size_t)nframes, sizeof(AFrame));
    uint8_t* frame_buf = (uint8_t*)malloc(frame_bytes);
    if (!frames || !frame_buf) {
        free(frames); free(frame_buf); free(durs);
        WebPAnimDecoderDelete(dec);
        return 0;
    }

    int fi = 0;
    int ok = 1;
    while (ok && WebPAnimDecoderHasMoreFrames(dec) && fi < nframes) {
        uint8_t* buf = NULL;
        int ts = 0;
        if (!WebPAnimDecoderGetNext(dec, &buf, &ts) || !buf) { ok = 0; break; }
        memcpy(frame_buf, buf, frame_bytes);

        uint8_t* dst = (uint8_t*)malloc(canvas_bytes);
        if (!dst) { ok = 0; break; }
        memset(dst, 0, canvas_bytes);

        for (int y = 0; y < out_size; ++y) {
            const float dy = y + 0.5f;
            for (int x = 0; x < out_size; ++x) {
                const float dx = x + 0.5f;
                const float px = dx - m2;
                const float py = dy - m5;
                const float sx = ia * px + ib * py - 0.5f;
                const float sy = id * px + ie * py - 0.5f;
                uint8_t* d = dst + ((size_t)y * out_size + x) * 4;
                sample_bilinear(frame_buf, fw, fh, sx, sy, d);
                if (ov && x < ov_w && y < ov_h) {
                    blend_over(d, ov + ((size_t)y * ov_w + x) * 4);
                }
            }
        }

        frames[fi].rgba = dst;
        frames[fi].duration_ms = durs[fi];
        fi++;
    }
    WebPAnimDecoderDelete(dec);
    free(frame_buf);
    free(durs);

    if (!ok || fi == 0) {
        for (int i = 0; i < fi; ++i) free(frames[i].rgba);
        free(frames);
        return 0;
    }
    const int decoded = fi;

    /* ---- original timeline ---- */
    int* starts = (int*)malloc((size_t)decoded * sizeof(int));
    if (!starts) {
        for (int i = 0; i < decoded; ++i) free(frames[i].rgba);
        free(frames);
        return 0;
    }
    int acc = 0;
    for (int i = 0; i < decoded; ++i) {
        starts[i] = acc;
        acc += frames[i].duration_ms;
    }
    const int total_ms = acc;

    /* ---- decide final duration ---- */
    int final_ms = total_ms;
    if (target_duration_ms > 0 && target_duration_ms > total_ms) {
        final_ms = target_duration_ms;   // extend via loop
    }
    if (max_duration_ms > 0 && final_ms > max_duration_ms) {
        final_ms = max_duration_ms;      // hard ceiling trim
    }
    if (final_ms <= 0) final_ms = total_ms;

    /* ---- build resampled sequence at target_fps ---- */
    const int frame_dur = 1000 / target_fps;
    int out_frames = (final_ms + frame_dur - 1) / frame_dur;
    if (out_frames <= 0) out_frames = 1;

    WebPAnimEncoderOptions eopts;
    if (!WebPAnimEncoderOptionsInit(&eopts)) {
        free(starts);
        for (int i = 0; i < decoded; ++i) free(frames[i].rgba);
        free(frames);
        return 0;
    }
    eopts.allow_mixed = 1;
    eopts.minimize_size = 0;
    eopts.verbose = 0;

    WebPAnimEncoder* enc = WebPAnimEncoderNew(out_size, out_size, &eopts);
    if (!enc) {
        free(starts);
        for (int i = 0; i < decoded; ++i) free(frames[i].rgba);
        free(frames);
        return 0;
    }

    WebPConfig cfg;
    if (!WebPConfigPreset(&cfg, WEBP_PRESET_DEFAULT, quality)) {
        WebPAnimEncoderDelete(enc);
        free(starts);
        for (int i = 0; i < decoded; ++i) free(frames[i].rgba);
        free(frames);
        return 0;
    }
    cfg.method = 4;
    cfg.thread_level = 0;

    ok = 1;
    int timestamp = 0;
    for (int i = 0; ok && i < out_frames; ++i) {
        const int t = i * frame_dur;
        const int t_mod = total_ms > 0 ? (t % total_ms) : 0;
        const int src_idx = find_frame_index(starts, NULL, decoded, t_mod);

        int dur = frame_dur;
        if (i == out_frames - 1) {
            const int remaining = final_ms - (out_frames - 1) * frame_dur;
            if (remaining > 0) dur = remaining;
        }

        WebPPicture pic;
        if (!WebPPictureInit(&pic)) { ok = 0; break; }
        pic.width = out_size;
        pic.height = out_size;
        pic.use_argb = 1;

        if (!WebPPictureImportRGBA(&pic, frames[src_idx].rgba, out_size * 4)) {
            WebPPictureFree(&pic);
            ok = 0;
            break;
        }

        if (!WebPAnimEncoderAdd(enc, &pic, timestamp, &cfg)) {
            WebPPictureFree(&pic);
            ok = 0;
            break;
        }
        WebPPictureFree(&pic);

        timestamp += dur;
    }

    if (ok && out_frames > 0) {
        if (!WebPAnimEncoderAdd(enc, NULL, timestamp, NULL)) ok = 0;
    } else {
        ok = 0;
    }

    WebPData assembled = { NULL, 0 };
    if (ok && !WebPAnimEncoderAssemble(enc, &assembled)) ok = 0;

    /* Re-mux to carry over the source loop count (0 = infinite) and keep the
     * canvas transparent. */
    WebPData remuxed = { NULL, 0 };
    int have_remux = 0;
    if (ok && assembled.bytes && assembled.size > 0) {
        WebPMux* mux = WebPMuxCreate(&assembled, 1);
        if (mux) {
            WebPMuxAnimParams params;
            memset(&params, 0, sizeof(params));
            params.loop_count = (int)info.loop_count;
            params.bgcolor = 0;
            if (WebPMuxSetAnimationParams(mux, &params) == WEBP_MUX_OK &&
                WebPMuxAssemble(mux, &remuxed) == WEBP_MUX_OK &&
                remuxed.bytes && remuxed.size > 0) {
                have_remux = 1;
            }
            WebPMuxDelete(mux);
        }
    }

    const WebPData* result = have_remux ? &remuxed : &assembled;
    if (ok && result->bytes && result->size > 0) {
        *out = (uint8_t*)malloc(result->size);
        if (*out) {
            memcpy(*out, result->bytes, result->size);
            *out_len = result->size;
        } else {
            ok = 0;
        }
    } else {
        ok = 0;
    }

    WebPDataClear(&remuxed);
    WebPDataClear(&assembled);

    WebPAnimEncoderDelete(enc);
    free(starts);
    for (int i = 0; i < decoded; ++i) free(frames[i].rgba);
    free(frames);

    return (ok && *out != NULL) ? 1 : 0;
}
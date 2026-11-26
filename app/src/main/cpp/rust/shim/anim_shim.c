/* Animated WebP re-encode with a baked overlay, using real libwebp headers.
 * Exposes a scalar/buffer-only ABI so Rust never has to mirror libwebp structs.
 */

#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

#include "src/webp/decode.h"
#include "src/webp/encode.h"
#include "src/webp/demux.h"
#include "src/webp/mux.h"

void wa_anim_free(uint8_t* p) {
    free(p);
}

/* source-over blend of non-premultiplied RGBA */
static void blend_over(uint8_t* d, const uint8_t* s) {
    const int sa = s[3];

    if (sa == 0) return;

    if (sa == 255) {
        d[0] = s[0];
        d[1] = s[1];
        d[2] = s[2];
        d[3] = 255;
        return;
    }

    const int da = d[3];
    const int outa = sa + da * (255 - sa) / 255;

    if (outa == 0) {
        d[0] = d[1] = d[2] = d[3] = 0;
        return;
    }

    for (int c = 0; c < 3; ++c) {
        const int num = s[c] * sa + d[c] * da * (255 - sa) / 255;
        d[c] = (uint8_t)(num / outa);
    }

    d[3] = (uint8_t)outa;
}

static void sample_bilinear(
        const uint8_t* f,
        int fw,
        int fh,
        float sx,
        float sy,
        uint8_t* o
) {
    if (sx < -0.5f || sy < -0.5f || sx >= fw - 0.5f || sy >= fh - 0.5f) {
        o[0] = o[1] = o[2] = o[3] = 0;
        return;
    }

    const float cx = sx < 0 ? 0 : (sx > fw - 1 ? fw - 1 : sx);
    const float cy = sy < 0 ? 0 : (sy > fh - 1 ? fh - 1 : sy);

    const int x0 = (int)cx;
    const int y0 = (int)cy;
    const int x1 = (x0 + 1 < fw) ? x0 + 1 : x0;
    const int y1 = (y0 + 1 < fh) ? y0 + 1 : y0;

    const float fx = cx - x0;
    const float fy = cy - y0;

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

/* Returns 1 on success; *out is malloc'd and must be released with wa_anim_free.
 *
 * Matrix maps source coordinates to destination 512x512 coordinates:
 *
 * dst_x = a * src_x + b * src_y + c
 * dst_y = d * src_x + e * src_y + f
 */
int wa_anim_overlay(
        const uint8_t* src,
        size_t src_len,
        const uint8_t* ov,
        int ov_w,
        int ov_h,
        float a,
        float b,
        float c,
        float d,
        float e,
        float f,
        int out_size,
        float quality,
        uint8_t** out,
        size_t* out_len
) {
    *out = NULL;
    *out_len = 0;

    if (!src || src_len == 0 || out_size <= 0) return 0;

    const float det = a * e - b * d;
    if (fabsf(det) < 1e-8f) return 0;

    const float inv_det = 1.f / det;
    const float ia = e * inv_det;
    const float ib = -b * inv_det;
    const float id = -d * inv_det;
    const float ie = a * inv_det;

    WebPData data = { src, src_len };

    WebPAnimDecoderOptions dopts;
    if (!WebPAnimDecoderOptionsInit(&dopts)) return 0;

    dopts.color_mode = MODE_RGBA;
    dopts.use_threads = 0;

    WebPAnimDecoder* dec = WebPAnimDecoderNew(&data, &dopts);
    if (!dec) return 0;

    WebPAnimInfo info;
    memset(&info, 0, sizeof(info));

    if (!WebPAnimDecoderGetInfo(dec, &info)) {
        WebPAnimDecoderDelete(dec);
        return 0;
    }

    const int fw = (int)info.canvas_width;
    const int fh = (int)info.canvas_height;

    if (fw <= 0 || fh <= 0 || fw > 8192 || fh > 8192) {
        WebPAnimDecoderDelete(dec);
        return 0;
    }

    WebPAnimEncoderOptions eopts;
    if (!WebPAnimEncoderOptionsInit(&eopts)) {
        WebPAnimDecoderDelete(dec);
        return 0;
    }

    eopts.allow_mixed = 1;
    eopts.minimize_size = 0;
    eopts.verbose = 0;

    WebPAnimEncoder* enc = WebPAnimEncoderNew(out_size, out_size, &eopts);
    if (!enc) {
        WebPAnimDecoderDelete(dec);
        return 0;
    }

    WebPConfig cfg;
    if (!WebPConfigPreset(&cfg, WEBP_PRESET_DEFAULT, quality)) {
        WebPAnimEncoderDelete(enc);
        WebPAnimDecoderDelete(dec);
        return 0;
    }

    cfg.method = 4;
    cfg.thread_level = 0;

    const size_t canvas_bytes = (size_t)out_size * out_size * 4;
    const size_t frame_bytes = (size_t)fw * fh * 4;

    uint8_t* canvas = (uint8_t*)malloc(canvas_bytes);
    uint8_t* frame = (uint8_t*)malloc(frame_bytes);

    int ok = (canvas != NULL && frame != NULL);

    int prev_ts = 0;
    int nframes = 0;

    while (ok && WebPAnimDecoderHasMoreFrames(dec)) {
        uint8_t* buf = NULL;
        int ts = 0;

        if (!WebPAnimDecoderGetNext(dec, &buf, &ts) || !buf) {
            ok = 0;
            break;
        }

        memcpy(frame, buf, frame_bytes);
        memset(canvas, 0, canvas_bytes);

        for (int y = 0; y < out_size; ++y) {
            const float dy = y + 0.5f;

            for (int x = 0; x < out_size; ++x) {
                const float dx = x + 0.5f;

                const float px = dx - c;
                const float py = dy - f;

                const float sx = ia * px + ib * py - 0.5f;
                const float sy = id * px + ie * py - 0.5f;

                uint8_t* d = canvas + ((size_t)y * out_size + x) * 4;

                sample_bilinear(frame, fw, fh, sx, sy, d);

                if (ov && x < ov_w && y < ov_h) {
                    blend_over(d, ov + ((size_t)y * ov_w + x) * 4);
                }
            }
        }

        WebPPicture pic;
        if (!WebPPictureInit(&pic)) {
            ok = 0;
            break;
        }

        pic.width = out_size;
        pic.height = out_size;
        pic.use_argb = 1;

        if (!WebPPictureImportRGBA(&pic, canvas, out_size * 4)) {
            WebPPictureFree(&pic);
            ok = 0;
            break;
        }

        const int added = WebPAnimEncoderAdd(enc, &pic, prev_ts, &cfg);
        WebPPictureFree(&pic);

        if (!added) {
            ok = 0;
            break;
        }

        prev_ts = ts;
        ++nframes;
    }

    if (ok && nframes > 0) {
        if (!WebPAnimEncoderAdd(enc, NULL, prev_ts, NULL)) {
            ok = 0;
        }
    } else {
        ok = 0;
    }

    WebPData assembled = { NULL, 0 };

    if (ok && !WebPAnimEncoderAssemble(enc, &assembled)) {
        ok = 0;
    }

    /* Re-mux to carry over the source loop count (0 = infinite) and keep the
     * canvas transparent. Without this the mux default would play once only.
     */
    WebPData remuxed = { NULL, 0 };
    int have_remux = 0;

    if (ok && assembled.bytes && assembled.size > 0) {
        WebPMux* mux = WebPMuxCreate(&assembled, 1);

        if (mux) {
            WebPMuxAnimParams params;
            memset(&params, 0, sizeof(params));
            params.loop_count = (int)info.loop_count;
            params.bgcolor = 0; /* transparent */

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

    free(canvas);
    free(frame);

    WebPAnimEncoderDelete(enc);
    WebPAnimDecoderDelete(dec);

    return (ok && *out != NULL) ? 1 : 0;
}
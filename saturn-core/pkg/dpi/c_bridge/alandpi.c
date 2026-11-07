/**
 * Alan DPI Engine - C Native Bridge Library (libalandpi)
 * Implementation of Alan DPI evasion algorithms
 */

#include "alandpi.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
  #include <winsock2.h>
  #include <windows.h>
  #define socket_send(s, buf, len, flags) send((SOCKET)(s), (const char*)(buf), (int)(len), (flags))
  #define sleep_ms(ms) Sleep(ms)
#else
  #include <sys/socket.h>
  #include <sys/types.h>
  #include <unistd.h>
  #define socket_send(s, buf, len, flags) send((int)(s), (const void*)(buf), (size_t)(len), (flags))
  #define sleep_ms(ms) usleep((ms) * 1000)
#endif

const uint8_t ALANDPI_POLY_OOB_POOL[28] = {
    0x21, 0x23, 0x24, 0x25, 0x26, 0x2a, 0x2b, 0x2d, 0x2e, 0x33, 0x3b, 0x3d, 0x3f,
    0x40, 0x42, 0x47, 0x4b, 0x4e, 0x51, 0x57, 0x5a, 0x5e, 0x61, 0x6d, 0x71, 0x78, 0x7a, 0x7e
};

static uint8_t get_random_oob_byte(void) {
    int idx = rand() % (int)sizeof(ALANDPI_POLY_OOB_POOL);
    return ALANDPI_POLY_OOB_POOL[idx];
}

static uint16_t get_poly_record_version(uint16_t base_ver) {
    if (base_ver == 0x0304) {
        int r = rand() % 3;
        return (r == 0) ? 0x0301 : ((r == 1) ? 0x0303 : 0x0304);
    }
    return (rand() % 2 == 0) ? 0x0301 : 0x0303;
}

int alandpi_inspect_client_hello(const uint8_t *record, size_t rec_len, alandpi_offsets_t *info) {
    if (!record || !info) return 0;
    memset(info, 0, sizeof(alandpi_offsets_t));
    info->record_len = (int)rec_len;
    info->is_valid_ch = 0;
    info->alpn_ext_pos = -1;
    info->sup_ver_ext_pos = -1;
    info->key_share_ext_pos = -1;

    int mid_guess = (int)(rec_len / 3);
    if (mid_guess < 14) mid_guess = 14;
    if (mid_guess > 180) mid_guess = 180;

    info->cipher_suites_pos = 43;
    info->cipher_suites_len = 32;
    info->ext_total_len_pos = mid_guess - 12;
    if (info->ext_total_len_pos < 48) info->ext_total_len_pos = 48;
    if (info->ext_total_len_pos > 120) info->ext_total_len_pos = 120;
    info->sni_ext_pos = mid_guess;
    info->sni_list_len_pos = mid_guess + 4;
    info->sni_name_len_pos = mid_guess + 7;
    info->sni_name_pos = mid_guess + 9;
    info->sni_name_len = 20;
    info->sni_first_dot_pos = mid_guess + 15;
    info->sni_last_dot_pos = mid_guess + 21;

    if (rec_len < 48 || record[0] != 0x16 || record[5] != 0x01) {
        return 0;
    }

    size_t pos = 43;
    if (pos >= rec_len) return 0;
    uint8_t sid_len = record[pos];
    pos += 1 + sid_len;
    if (pos + 2 > rec_len) return 0;

    info->cipher_suites_pos = (int)pos;
    uint16_t ciph_len = ((uint16_t)record[pos] << 8) | record[pos + 1];
    info->cipher_suites_len = (int)ciph_len;
    pos += 2 + ciph_len;
    if (pos + 1 > rec_len) return 0;

    uint8_t comp_len = record[pos];
    pos += 1 + comp_len;
    if (pos + 2 > rec_len) return 0;

    info->ext_total_len_pos = (int)pos;
    uint16_t ext_total_len = ((uint16_t)record[pos] << 8) | record[pos + 1];
    pos += 2;
    size_t end = pos + ext_total_len;
    if (end > rec_len) end = rec_len;
    info->is_valid_ch = 1;

    while (pos + 4 <= end) {
        uint16_t etype = ((uint16_t)record[pos] << 8) | record[pos + 1];
        uint16_t elen = ((uint16_t)record[pos + 2] << 8) | record[pos + 3];
        size_t ext_body_end = pos + 4 + elen;
        if (ext_body_end > end) break;

        if (etype == 0x0000 && (pos + 9 <= ext_body_end)) {
            uint16_t name_len = ((uint16_t)record[pos + 7] << 8) | record[pos + 8];
            size_t name_start = pos + 9;
            size_t name_end = name_start + name_len;
            if (name_end > ext_body_end) name_end = ext_body_end;

            info->sni_ext_pos = (int)pos;
            info->sni_list_len_pos = (int)(pos + 4);
            info->sni_name_len_pos = (int)(pos + 7);
            info->sni_name_pos = (int)name_start;
            info->sni_name_len = (int)(name_end - name_start);
            if (info->sni_name_len < 1) info->sni_name_len = 1;

            int first_dot = -1;
            int last_dot = -1;
            for (size_t i = name_start; i < name_end; i++) {
                if (record[i] == '.') {
                    if (first_dot < 0) first_dot = (int)i;
                    last_dot = (int)i;
                }
            }
            if (first_dot > 0) {
                info->sni_first_dot_pos = first_dot;
            } else {
                info->sni_first_dot_pos = (int)name_start + info->sni_name_len / 2;
            }
            if (last_dot > 0) {
                info->sni_last_dot_pos = last_dot;
            } else {
                info->sni_last_dot_pos = info->sni_first_dot_pos;
            }
        } else if (etype == 0x0010) {
            info->alpn_ext_pos = (int)pos;
        } else if (etype == 0x002B) {
            info->sup_ver_ext_pos = (int)pos;
        } else if (etype == 0x0033) {
            info->key_share_ext_pos = (int)pos;
        }

        pos = ext_body_end;
    }

    return 1;
}

size_t alandpi_rewrite_outer_version(uint8_t *record, size_t rec_len, uint16_t version) {
    if (!record || rec_len < 5 || record[0] != 0x16) return rec_len;
    record[1] = (uint8_t)((version >> 8) & 0xFF);
    record[2] = (uint8_t)(version & 0xFF);
    return rec_len;
}

size_t alandpi_build_preamble_bytes(const char *mode, uint8_t *out_buf, size_t max_len) {
    if (!mode || !out_buf || max_len < 5) return 0;
    if (strcmp(mode, "zero0304") == 0) {
        static const uint8_t zero0304[] = {0x16, 0x03, 0x04, 0x00, 0x00};
        memcpy(out_buf, zero0304, 5);
        return 5;
    }
    if (strcmp(mode, "2xzero") == 0 && max_len >= 10) {
        static const uint8_t two_x[] = {0x16, 0x03, 0x01, 0x00, 0x00, 0x16, 0x03, 0x04, 0x00, 0x00};
        memcpy(out_buf, two_x, 10);
        return 10;
    }
    if (strcmp(mode, "4xzero") == 0 && max_len >= 20) {
        static const uint8_t four_x[] = {
            0x16, 0x03, 0x01, 0x00, 0x00,
            0x16, 0x03, 0x02, 0x00, 0x00,
            0x16, 0x03, 0x03, 0x00, 0x00,
            0x16, 0x03, 0x04, 0x00, 0x00
        };
        memcpy(out_buf, four_x, 20);
        return 20;
    }
    return 0;
}

size_t alandpi_split_record_atomic(const uint8_t *record, size_t rec_len, size_t cut_offset, uint16_t version, uint8_t *out_buf, size_t max_len) {
    if (!record || !out_buf || rec_len < 10 || cut_offset <= 5 || cut_offset >= rec_len) {
        if (out_buf && max_len >= rec_len && record) {
            memcpy(out_buf, record, rec_len);
            alandpi_rewrite_outer_version(out_buf, rec_len, version);
            return rec_len;
        }
        return 0;
    }
    size_t cut_rel = cut_offset - 5;
    size_t part1_len = cut_rel;
    size_t part2_len = (rec_len - 5) - cut_rel;
    size_t total_out = 5 + part1_len + 5 + part2_len;
    if (max_len < total_out) return 0;

    /* Record 1: 0x16 0x0301 part1_len */
    out_buf[0] = 0x16;
    out_buf[1] = 0x03;
    out_buf[2] = 0x01;
    out_buf[3] = (uint8_t)((part1_len >> 8) & 0xFF);
    out_buf[4] = (uint8_t)(part1_len & 0xFF);
    memcpy(out_buf + 5, record + 5, part1_len);

    /* Record 2: 0x16 version part2_len */
    size_t off2 = 5 + part1_len;
    out_buf[off2] = 0x16;
    out_buf[off2 + 1] = (uint8_t)((version >> 8) & 0xFF);
    out_buf[off2 + 2] = (uint8_t)(version & 0xFF);
    out_buf[off2 + 3] = (uint8_t)((part2_len >> 8) & 0xFF);
    out_buf[off2 + 4] = (uint8_t)(part2_len & 0xFF);
    memcpy(out_buf + off2 + 5, record + 5 + part1_len, part2_len);

    return total_out;
}

/* 24 Bypass Vectors Registry */
static const alandpi_vector_t ALL_VECTORS[24] = {
    {"VEC-01", "Adaptive Core Alpha", "PROPRIETARY", "Primary structural stream desync with stride slicing", "none", 0x0304, "sni_tag", {400}, 1, 0, "none", 1, 0, 0, 0, 0, 1, "none", 0, 0, 0, "none"},
    {"VEC-02", "Adaptive Core Sigma", "PROPRIETARY", "Mid-label structural alignment with stride slicing", "none", 0x0304, "mid_host", {400}, 1, 0, "none", 1, 0, 0, 0, 0, 1, "none", 0, 0, 0, "none"},
    {"VEC-03", "Dual-Phase Boundary", "PROPRIETARY", "Bimodal boundary pointer with forward delta offset", "none", 0x0304, "sni_tag", {0}, 0, 400, "none", 1, 0, 0, 0, 0, 1, "none", 0, 0, 0, "none"},
    {"VEC-04", "Preamble Fusion + Stride", "PROPRIETARY", "Empty TLS 1.3 record fusion with forward stride cut", "zero0304", 0x0304, "sni_tag", {400}, 1, 0, "none", 1, 0, 0, 0, 0, 1, "none", 0, 0, 0, "none"},
    {"VEC-05", "Stepped Cascade 200/400", "PROPRIETARY", "Multi-stage forward window segmentation", "none", 0x0304, "sni_tag", {200, 400}, 2, 0, "none", 1, 0, 0, 0, 0, 1, "none", 0, 0, 0, "none"},
    {"VEC-06", "Dual-Tag Vector", "PROPRIETARY", "Dual structural header offset alignment", "none", 0x0304, "dual", {400}, 1, 0, "none", 1, 0, 0, 0, 0, 1, "none", 0, 0, 0, "none"},
    {"VEC-07", "Length-Field Desync", "PROPRIETARY", "Extension list length boundary segmentation", "none", 0x0304, "sni_len", {400}, 1, 0, "none", 1, 0, 0, 0, 0, 1, "none", 0, 0, 0, "none"},
    {"VEC-08", "Zero-Drift Dual Preamble", "COALESCE", "Sequence-aligned dual empty record coalescing", "2xzero", 0x0304, "none", {400}, 1, 0, "none", 1, 0, 0, 0, 0, 1, "none", 0, 0, 0, "none"},
    {"VEC-09", "Atomic Mid-SNI Record Split", "COALESCE", "Single-buffer TLS record split with forward stride", "zero0304", 0x0304, "none", {400}, 1, 0, "1pkt_midsni", 1, 0, 0, 0, 0, 1, "none", 0, 0, 0, "none"},
    {"VEC-10", "Tri-Stage Structural Core", "PROPRIETARY", "Three-point extension header boundary alignment", "zero0304", 0x0304, "triple", {400}, 1, 0, "none", 1, 0, 0, 0, 0, 1, "none", 0, 0, 0, "none"},
    {"VEC-11", "Micro-Fragment Header (5-16B)", "FRAGMENT", "TLS Hello micro-slicing (5-16B, 2-3ms interval)", "none", 0x0303, "none", {0}, 0, 0, "none", 0, 5, 16, 2, 3, 1, "none", 0, 0, 0, "none"},
    {"VEC-12", "Window-5B Multi-Flight (1-3)", "FRAGMENT", "5-byte fixed header window exhaustion across flights 1-3", "none", 0x0303, "none", {0}, 0, 0, "none", 0, 5, 5, 12, 20, 3, "none", 0, 0, 0, "none"},
    {"VEC-13", "Rapid TLS Fragment (12-28B)", "FRAGMENT", "Low-latency TLS Hello slicing (12-28B, 1-2ms interval)", "zero0304", 0x0304, "none", {0}, 0, 0, "none", 1, 12, 28, 1, 2, 1, "none", 0, 0, 0, "none"},
    {"VEC-14", "Deep Frame Fragment (24-64B)", "FRAGMENT", "Medium-chunk multi-flight TLS fragmentation (flights 1-2)", "none", 0x0304, "none", {0}, 0, 0, "none", 0, 24, 64, 3, 6, 2, "none", 0, 0, 0, "none"},
    {"VEC-15", "Window-Boundary 512", "PROPRIETARY", "DPI 512-byte inspection buffer edge cut", "zero0304", 0x0304, "sni_tag", {512}, 1, 0, "none", 1, 0, 0, 0, 0, 1, "none", 0, 0, 0, "none"},
    {"VEC-16", "Quad-Preamble Burst", "COALESCE", "4x mixed-version empty records with single-burst flush", "4xzero", 0x0304, "none", {400}, 1, 0, "none", 1, 0, 0, 0, 0, 1, "none", 0, 0, 0, "none"},
    {"VEC-17", "Aligned Record Relay", "COALESCE", "Multi-frame record boundary alignment over single buffer flush", "none", 0x0303, "none", {0}, 0, 0, "none", 0, 0, 0, 0, 0, 1, "sni_dot_3rec", 0, 1380, 0, "none"},
    {"VEC-18", "Adaptive Core Delta", "PROPRIETARY", "Early header pointer alignment with trailing segment split", "none", 0x0304, "cipher_pivot", {0}, 0, 0, "none", 0, 0, 0, 0, 0, 1, "none", 0, 1360, 36, "none"},
    {"VEC-19", "Stream Window Balance", "PROPRIETARY", "Dual-frame handshake alignment with outbound stream pacing", "none", 0x0303, "none", {0}, 0, 0, "none", 0, 0, 0, 0, 0, 1, "sni_mid_2rec", 1, 1360, 0, "none"},
    {"VEC-20", "Progressive Frame Sync", "COALESCE", "Stepped multi-record frame segmentation with tail pacing", "none", 0x0303, "none", {0}, 0, 0, "none", 0, 0, 0, 0, 0, 1, "fibonacci_7rec", 0, 1380, 0, "none"},
    {"VEC-21", "Adaptive Core Omega", "PROPRIETARY", "Composite header boundary shift with forward window cut", "none", 0x0303, "none", {0}, 0, 0, "none", 0, 0, 0, 0, 0, 1, "none", 0, 1380, 0, "phantom_seam_oob"},
    {"VEC-22", "Dual-Window Stream Core", "PROPRIETARY", "Segmented header cell alignment with secondary window cut", "none", 0x0304, "none", {0}, 0, 0, "none", 0, 0, 0, 0, 0, 1, "none", 0, 1360, 0, "sni_microcell_pincer"},
    {"VEC-23", "Interleaved Record Sync", "COALESCE", "Alternating record header framing with forward segment split", "none", 0x0301, "none", {0}, 0, 0, "none", 0, 0, 0, 0, 0, 1, "none", 0, 1380, 0, "chameleon_interleave"},
    {"VEC-00", "Direct Tunnel Passthrough", "DIRECT", "Zero-mutation kernel relay for domestic tunnels and encrypted streams", "none", 0x0303, "none", {0}, 0, 0, "none", 1, 0, 0, 0, 0, 1, "none", 0, 0, 0, "none"}
};

/* Autopilot priority order matching Python models.py */
static const char *AUTOPILOT_ORDER[] = {
    "VEC-21", "VEC-22", "VEC-01", "VEC-18", "VEC-17", "VEC-19",
    "VEC-20", "VEC-23", "VEC-04", "VEC-02", "VEC-03", "VEC-05",
    "VEC-06", "VEC-07", "VEC-08", "VEC-09", "VEC-10", "VEC-15",
    "VEC-16", "VEC-11", "VEC-13", "VEC-14", "VEC-12", "VEC-00"
};

const alandpi_vector_t *alandpi_get_vector(const char *vector_id) {
    if (!vector_id) return &ALL_VECTORS[20]; /* Default to VEC-21 */
    for (int i = 0; i < 24; i++) {
        if (strcmp(ALL_VECTORS[i].vector_id, vector_id) == 0) {
            return &ALL_VECTORS[i];
        }
    }
    return &ALL_VECTORS[20]; /* Default to VEC-21 */
}

const alandpi_vector_t *alandpi_get_autopilot_vector(int priority_index) {
    if (priority_index < 0 || priority_index >= 24) priority_index = 0;
    return alandpi_get_vector(AUTOPILOT_ORDER[priority_index]);
}

int alandpi_get_vector_count(void) {
    return 24;
}

int alandpi_safe_send_urgent(int socket_fd, const uint8_t *chunk, size_t chunk_len, uint8_t decoy_byte) {
    if (!chunk || chunk_len == 0) return 0;
    if (decoy_byte == 0) decoy_byte = get_random_oob_byte();

    uint8_t *combined = (uint8_t *)malloc(chunk_len + 1);
    if (!combined) {
        return socket_send(socket_fd, chunk, chunk_len, 0);
    }
    memcpy(combined, chunk, chunk_len);
    combined[chunk_len] = decoy_byte;

    int sent = socket_send(socket_fd, combined, chunk_len + 1, MSG_OOB);
    if (sent < 0) {
        /* MSG_OOB unsupported or failed, fallback to normal send */
        sent = socket_send(socket_fd, chunk, chunk_len, 0);
    } else if ((size_t)sent < chunk_len + 1) {
        if ((size_t)sent < chunk_len) {
            socket_send(socket_fd, chunk + sent, chunk_len - sent, 0);
        }
    }
    free(combined);
    return (int)chunk_len;
}

static int send_tail_segments(int socket_fd, const uint8_t *tail, size_t tail_len, const int *cuts, int num_cuts, int jitter, int second_urgent) {
    if (!tail || tail_len == 0) return 0;
    if (second_urgent > 0) {
        int jit = (jitter > 0) ? ((rand() % (2 * jitter + 1)) - jitter) : 0;
        int target = second_urgent + jit;
        if (target < 24) target = 24;
        size_t cut_pos = (target < (int)tail_len) ? (size_t)target : ((tail_len * 2) / 3);
        if (cut_pos > 0 && cut_pos < tail_len) {
            alandpi_safe_send_urgent(socket_fd, tail, cut_pos, 0);
            sleep_ms(1);
            socket_send(socket_fd, tail + cut_pos, tail_len - cut_pos, 0);
            return (int)tail_len;
        }
    }

    if (num_cuts <= 0) {
        socket_send(socket_fd, tail, tail_len, 0);
        return (int)tail_len;
    }

    size_t prev = 0;
    for (int i = 0; i < num_cuts; i++) {
        int base = cuts[i];
        int jit = (base > 60 && jitter > 0) ? ((rand() % (2 * jitter + 1)) - jitter) : 0;
        int cut = base + jit;
        if (cut < 20) cut = 20;
        if ((size_t)cut > prev && (size_t)cut < tail_len) {
            socket_send(socket_fd, tail + prev, (size_t)cut - prev, 0);
            sleep_ms(2);
            prev = (size_t)cut;
        }
    }
    if (prev < tail_len) {
        socket_send(socket_fd, tail + prev, tail_len - prev, 0);
    }
    return (int)tail_len;
}

int alandpi_transmit_client_hello(int socket_fd, const uint8_t *ch_record, size_t ch_len, const alandpi_vector_t *vec, int custom_stride, int jitter_bytes) {
    if (!ch_record || ch_len == 0) return 0;
    if (!vec || strcmp(vec->family, "DIRECT") == 0 || ch_len < 16) {
        return socket_send(socket_fd, ch_record, ch_len, 0);
    }

    alandpi_offsets_t off;
    alandpi_inspect_client_hello(ch_record, ch_len, &off);
    uint16_t ver = get_poly_record_version(vec->outer_version);

    /* Hybrid Mode: Phantom Seam OOB (VEC-21) */
    if (strcmp(vec->hybrid_mode, "phantom_seam_oob") == 0 && ch_len >= 48) {
        size_t hs_len = ch_len - 5;
        const uint8_t *hs_body = ch_record + 5;
        size_t sni_ext_rel = (off.sni_ext_pos > 11) ? (size_t)(off.sni_ext_pos - 5) : 6;
        size_t sni_name_rel = (off.sni_name_pos > 15) ? (size_t)(off.sni_name_pos - 5) : 10;
        size_t sni_len = (off.sni_name_len > 4) ? (size_t)off.sni_name_len : 4;

        size_t c1 = (sni_ext_rel > 44) ? (sni_ext_rel - 4) : (sni_ext_rel - 2);
        if (c1 < 8) c1 = 8;
        if (c1 > hs_len - 16) c1 = hs_len - 16;

        size_t part1_len = c1;
        size_t part2_len = hs_len - c1;
        size_t combined_len = 5 + part1_len + 5 + part2_len;
        uint8_t *comb = (uint8_t *)malloc(combined_len);
        if (!comb) return socket_send(socket_fd, ch_record, ch_len, 0);

        comb[0] = 0x16; comb[1] = 0x03; comb[2] = 0x01;
        comb[3] = (uint8_t)(part1_len >> 8); comb[4] = (uint8_t)part1_len;
        memcpy(comb + 5, hs_body, part1_len);

        size_t off2 = 5 + part1_len;
        comb[off2] = 0x16; comb[off2 + 1] = 0x03; comb[off2 + 2] = 0x04;
        comb[off2 + 3] = (uint8_t)(part2_len >> 8); comb[off2 + 4] = (uint8_t)part2_len;
        memcpy(comb + off2 + 5, hs_body + part1_len, part2_len);

        size_t target_host = sni_name_rel + 10 + (sni_len / 3);
        if (target_host >= combined_len - 4) target_host = combined_len - 4;

        alandpi_safe_send_urgent(socket_fd, comb, target_host, 0);
        int stride_val = (custom_stride > 0) ? custom_stride : 400;
        int cuts[1] = {stride_val};
        send_tail_segments(socket_fd, comb + target_host, combined_len - target_host, cuts, 1, jitter_bytes, 0);
        free(comb);
        return (int)combined_len;
    }

    /* Hybrid Mode: SNI Microcell Pincer (VEC-22) */
    if (strcmp(vec->hybrid_mode, "sni_microcell_pincer") == 0 && ch_len >= 48) {
        size_t hs_len = ch_len - 5;
        const uint8_t *hs_body = ch_record + 5;
        size_t sni_name_rel = (off.sni_name_pos > 15) ? (size_t)(off.sni_name_pos - 5) : 10;
        size_t sni_len = (off.sni_name_len > 4) ? (size_t)off.sni_name_len : 4;

        size_t cell_start = sni_name_rel + (sni_len / 4);
        if (cell_start < 8) cell_start = 8;
        if (cell_start > hs_len - 16) cell_start = hs_len - 16;

        size_t cell_len = sni_len / 2;
        if (cell_len < 2) cell_len = 2;
        if (cell_len > 5) cell_len = 5;
        if (cell_start + cell_len + 6 > hs_len) cell_len = 2;
        size_t cell_end = cell_start + cell_len;

        size_t p1_len = cell_start;
        size_t p2_len = cell_len;
        size_t p3_len = hs_len - cell_end;
        size_t total_3rec = 15 + hs_len;
        uint8_t *buf3 = (uint8_t *)malloc(total_3rec);
        if (!buf3) return socket_send(socket_fd, ch_record, ch_len, 0);

        buf3[0] = 0x16; buf3[1] = 0x03; buf3[2] = 0x04;
        buf3[3] = (uint8_t)(p1_len >> 8); buf3[4] = (uint8_t)p1_len;
        memcpy(buf3 + 5, hs_body, p1_len);

        size_t o2 = 5 + p1_len;
        buf3[o2] = 0x16; buf3[o2 + 1] = 0x03; buf3[o2 + 2] = 0x01;
        buf3[o2 + 3] = (uint8_t)(p2_len >> 8); buf3[o2 + 4] = (uint8_t)p2_len;
        memcpy(buf3 + o2 + 5, hs_body + cell_start, p2_len);

        size_t o3 = o2 + 5 + p2_len;
        buf3[o3] = 0x16; buf3[o3 + 1] = 0x03; buf3[o3 + 2] = 0x04;
        buf3[o3 + 3] = (uint8_t)(p3_len >> 8); buf3[o3 + 4] = (uint8_t)p3_len;
        memcpy(buf3 + o3 + 5, hs_body + cell_end, p3_len);

        size_t oob1_cut = (off.sni_ext_pos > 10) ? (size_t)(off.sni_ext_pos + 1) : 10;
        if (oob1_cut >= 5 + p1_len) oob1_cut = 5 + p1_len - 2;

        alandpi_safe_send_urgent(socket_fd, buf3, oob1_cut, 0);
        size_t rem_len = total_3rec - oob1_cut;
        int jit = (jitter_bytes > 0) ? ((rand() % (2 * jitter_bytes + 1)) - jitter_bytes) : 0;
        size_t pincer_stride = (size_t)(360 + jit);
        if (pincer_stride < 48) pincer_stride = 48;

        if (rem_len > pincer_stride + 24) {
            alandpi_safe_send_urgent(socket_fd, buf3 + oob1_cut, pincer_stride, 0);
            sleep_ms(2);
            socket_send(socket_fd, buf3 + oob1_cut + pincer_stride, rem_len - pincer_stride, 0);
        } else {
            socket_send(socket_fd, buf3 + oob1_cut, rem_len, 0);
        }
        free(buf3);
        return (int)total_3rec;
    }

    /* Standard Vector Flow: Preamble + Mutated Record + Cuts */
    uint8_t preamble_buf[32];
    size_t pre_len = alandpi_build_preamble_bytes(vec->preamble, preamble_buf, sizeof(preamble_buf));
    if (pre_len > 0) {
        socket_send(socket_fd, preamble_buf, pre_len, 0);
    }

    uint8_t *mut = (uint8_t *)malloc(ch_len);
    if (!mut) return socket_send(socket_fd, ch_record, ch_len, 0);
    memcpy(mut, ch_record, ch_len);
    alandpi_rewrite_outer_version(mut, ch_len, ver);

    if (strcmp(vec->urgent_mode, "sni_tag") == 0 && off.sni_ext_pos > 0) {
        size_t cut = (size_t)(off.sni_ext_pos + 1);
        if (cut < ch_len) {
            alandpi_safe_send_urgent(socket_fd, mut, cut, 0);
            send_tail_segments(socket_fd, mut + cut, ch_len - cut, vec->forward_cuts, vec->num_forward_cuts, jitter_bytes, vec->second_urgent_delta);
            free(mut);
            return (int)ch_len;
        }
    }

    if (strcmp(vec->urgent_mode, "mid_host") == 0 && off.sni_name_pos > 0) {
        size_t cut = (size_t)(off.sni_name_pos + (off.sni_name_len / 2));
        if (cut < ch_len) {
            alandpi_safe_send_urgent(socket_fd, mut, cut, 0);
            send_tail_segments(socket_fd, mut + cut, ch_len - cut, vec->forward_cuts, vec->num_forward_cuts, jitter_bytes, vec->second_urgent_delta);
            free(mut);
            return (int)ch_len;
        }
    }

    send_tail_segments(socket_fd, mut, ch_len, vec->forward_cuts, vec->num_forward_cuts, jitter_bytes, vec->second_urgent_delta);
    free(mut);
    return (int)ch_len;
}

int alandpi_transmit_plain_http(int socket_fd, const uint8_t *http_payload, size_t payload_len, const alandpi_vector_t *vec, int custom_stride, int jitter_bytes) {
    if (!http_payload || payload_len == 0) return 0;
    if (!vec || strcmp(vec->family, "DIRECT") == 0 || payload_len < 16) {
        return socket_send(socket_fd, http_payload, payload_len, 0);
    }

    /* Find \r\nhost: */
    for (size_t i = 0; i + 7 < payload_len; i++) {
        if ((http_payload[i] == '\r') && (http_payload[i+1] == '\n') &&
            (http_payload[i+2] == 'h' || http_payload[i+2] == 'H') &&
            (http_payload[i+3] == 'o' || http_payload[i+3] == 'O') &&
            (http_payload[i+4] == 's' || http_payload[i+4] == 'S') &&
            (http_payload[i+5] == 't' || http_payload[i+5] == 'T') &&
            (http_payload[i+6] == ':')) {
            size_t val_start = i + 7;
            while (val_start < payload_len && (http_payload[val_start] == ' ' || http_payload[val_start] == '\t')) {
                val_start++;
            }
            size_t cut = val_start + 3;
            if (cut > payload_len - 2) cut = payload_len - 2;

            if (strcmp(vec->urgent_mode, "none") != 0 ||
                strcmp(vec->hybrid_mode, "phantom_seam_oob") == 0 ||
                strcmp(vec->hybrid_mode, "sni_microcell_pincer") == 0) {
                alandpi_safe_send_urgent(socket_fd, http_payload, cut, 0);
                int stride = (custom_stride > 0) ? custom_stride : 400;
                int cuts[1] = {stride};
                send_tail_segments(socket_fd, http_payload + cut, payload_len - cut, cuts, 1, jitter_bytes, 0);
                return (int)payload_len;
            } else {
                socket_send(socket_fd, http_payload, cut, 0);
                sleep_ms(2);
                socket_send(socket_fd, http_payload + cut, payload_len - cut, 0);
                return (int)payload_len;
            }
        }
    }

    return socket_send(socket_fd, http_payload, payload_len, 0);
}

int alandpi_transmit_uplink_shield_frame(int socket_fd, const uint8_t *payload, size_t payload_len, int inject_oob, int mss_clamp) {
    if (!payload || payload_len == 0) return 0;
    size_t offset = 0;

    /* Inject OOB at application data header (0x17 0x03) */
    if (inject_oob && payload_len > 20 && payload[0] == 0x17 && payload[1] == 0x03) {
        alandpi_safe_send_urgent(socket_fd, payload, 3, 0);
        offset = 3;
    }

    if (mss_clamp > 0 && (payload_len - offset) > (size_t)mss_clamp) {
        int jit_mss = mss_clamp + ((rand() % 49) - 24);
        if (jit_mss < 512) jit_mss = 512;
        while (offset < payload_len) {
            size_t chunk = payload_len - offset;
            if (chunk > (size_t)jit_mss) chunk = (size_t)jit_mss;
            socket_send(socket_fd, payload + offset, chunk, 0);
            offset += chunk;
        }
    } else if (offset < payload_len) {
        socket_send(socket_fd, payload + offset, payload_len - offset, 0);
    }
    return (int)payload_len;
}


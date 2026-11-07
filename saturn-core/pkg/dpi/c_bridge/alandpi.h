/**
 * Alan DPI Engine - C Native Bridge Library (libalandpi)
 * 
 * High-performance C implementation of Alan DPI evasion algorithms:
 * - 24 Bypass Vectors (VEC-00 to VEC-23)
 * - TLS 1.3 ClientHello Structural Inspection (SNI, CipherSuites, Extensions)
 * - Urgent OOB (Out-Of-Band) Data Desynchronization (MSG_OOB)
 * - SNI Fibonacci 7-Record Segmentation
 * - Microcell Pincer & Phantom Seam OOB Slicing
 * - Outer Record Version Masquerading & Preambles
 */

#ifndef ALANDPI_H
#define ALANDPI_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define ALANDPI_VERSION "1.1.0"

/* Offsets found in TLS ClientHello */
typedef struct {
    int is_valid_ch;
    int record_len;
    int cipher_suites_pos;
    int cipher_suites_len;
    int ext_total_len_pos;
    int sni_ext_pos;
    int sni_list_len_pos;
    int sni_name_len_pos;
    int sni_name_pos;
    int sni_name_len;
    int sni_first_dot_pos;
    int sni_last_dot_pos;
    int alpn_ext_pos;
    int sup_ver_ext_pos;
    int key_share_ext_pos;
} alandpi_offsets_t;

/* Bypass Vector Definition */
typedef struct {
    const char *vector_id;
    const char *code_name;
    const char *family;
    const char *summary;
    const char *preamble;
    uint16_t outer_version;
    const char *urgent_mode;
    int forward_cuts[4];
    int num_forward_cuts;
    int second_urgent_delta;
    const char *split_mode;
    int coalesce_flight2;
    int frag_slice_min;
    int frag_slice_max;
    int frag_delay_min_ms;
    int frag_delay_max_ms;
    int frag_flights;
    const char *record_envelope;
    int uplink_oob_shield;
    int uplink_mss_clamp;
    int tail_anchor_bytes;
    const char *hybrid_mode;
} alandpi_vector_t;

/* Polymorphic OOB Decoy Bytes Pool */
extern const uint8_t ALANDPI_POLY_OOB_POOL[28];

/* Core inspection functions */
int alandpi_inspect_client_hello(const uint8_t *record, size_t rec_len, alandpi_offsets_t *out_offsets);

/* Mutation functions */
size_t alandpi_rewrite_outer_version(uint8_t *record, size_t rec_len, uint16_t version);
size_t alandpi_build_preamble_bytes(const char *preamble_mode, uint8_t *out_buf, size_t max_len);
size_t alandpi_split_record_atomic(const uint8_t *record, size_t rec_len, size_t cut_offset, uint16_t version, uint8_t *out_buf, size_t max_len);

/* Vector registry */
const alandpi_vector_t *alandpi_get_vector(const char *vector_id);
const alandpi_vector_t *alandpi_get_autopilot_vector(int priority_index);
int alandpi_get_vector_count(void);

/* Socket transmission functions (socket_fd is an OS socket descriptor) */
int alandpi_safe_send_urgent(int socket_fd, const uint8_t *chunk, size_t chunk_len, uint8_t decoy_byte);
int alandpi_transmit_client_hello(int socket_fd, const uint8_t *ch_record, size_t ch_len, const alandpi_vector_t *vec, int custom_stride, int jitter_bytes);
int alandpi_transmit_plain_http(int socket_fd, const uint8_t *http_payload, size_t payload_len, const alandpi_vector_t *vec, int custom_stride, int jitter_bytes);
int alandpi_transmit_uplink_shield_frame(int socket_fd, const uint8_t *payload, size_t payload_len, int inject_oob, int mss_clamp);

#ifdef __cplusplus
}
#endif

#endif /* ALANDPI_H */


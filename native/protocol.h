/**
 * FuryTerminal — Shared daemon protocol definitions and helpers.
 *
 * Used by both libterm.c (client) and ftyd.c (server).
 * All messages: [type:uint8][length:uint32 BE][data:length]
 *
 * Copyright (c) FuryForm. MIT License.
 */

#pragma once

#include <stdint.h>
#include <unistd.h>
#include <errno.h>
#include <signal.h>

/* =================== PROTOCOL CONSTANTS =================== */

#define FTYD_DATA      0x01
#define FTYD_RESIZE    0x02
#define FTYD_SIGNAL    0x03
#define FTYD_CLOSE     0x04
#define FTYD_EXEC      0x05  /* Client→Server: execute command (payload = command string) */
#define FTYD_EXIT_CODE 0x06  /* Server→Client: process exited (payload = 4-byte int32 BE exit code) */

/*
 * Extended payload formats:
 *
 * FTYD_RESIZE (interactive session, initial message only):
 *   Basic:    [rows:2 BE][cols:2 BE]  (4 bytes)
 *   Extended: [rows:2 BE][cols:2 BE][shell\0cwd\0KEY1=VAL1\0KEY2=VAL2\0...]
 *   If msg_len == 4: old behavior (daemon default shell, no env, no cwd)
 *   If msg_len > 4:  bytes 4+ are NUL-separated fields: shell, cwd, env vars
 *   Subsequent RESIZE during session: always 4 bytes (rows+cols only)
 *
 * FTYD_EXEC (exec session):
 *   Basic:    shell\0command  (2 NUL-separated fields)
 *   Extended: shell\0command\0cwd\0KEY1=VAL1\0KEY2=VAL2\0...
 *   Field count by NUL separators:
 *     2 fields: shell + command (backward compatible)
 *     3 fields: shell + command + cwd
 *     4+ fields: shell + command + cwd + env vars
 */

/** Maximum allowed message payload (1 MB). Prevents DoS via huge length fields. */
#define FTYD_MAX_MSG_LEN (1024 * 1024)

/** Allowed signals that clients may send via FTYD_SIGNAL. */
static inline int ftyd_signal_allowed(int sig) {
    switch (sig) {
    case SIGHUP:
    case SIGINT:
    case SIGQUIT:
    case SIGKILL:
    case SIGTERM:
    case SIGCONT:
    case SIGTSTP:
    case SIGWINCH:
        return 1;
    default:
        return 0;
    }
}

/* =================== I/O HELPERS =================== */

/**
 * Read exactly `n` bytes from `fd` into `buf`.
 * Retries on partial reads and EINTR.
 * Returns 0 on success, -1 on EOF or error.
 */
static inline int proto_read_exact(int fd, uint8_t *buf, int n) {
    int total = 0;
    while (total < n) {
        ssize_t r = read(fd, buf + total, (size_t)(n - total));
        if (r < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (r == 0) return -1; /* EOF */
        total += (int)r;
    }
    return 0;
}

/**
 * Write exactly `n` bytes from `buf` to `fd`.
 * Retries on partial writes and EINTR.
 * Returns 0 on success, -1 on error.
 */
static inline int proto_write_all(int fd, const uint8_t *buf, int n) {
    int total = 0;
    while (total < n) {
        ssize_t w = write(fd, buf + total, (size_t)(n - total));
        if (w < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (w == 0) return -1;
        total += (int)w;
    }
    return 0;
}

/* =================== MESSAGE FRAMING =================== */

/**
 * Send a framed message: [type:1][length:4 BE][data:length].
 * Returns 0 on success, -1 on error.
 */
static inline int proto_send(int fd, uint8_t type, const uint8_t *data, uint32_t len) {
    uint8_t hdr[5];
    hdr[0] = type;
    hdr[1] = (uint8_t)(len >> 24);
    hdr[2] = (uint8_t)(len >> 16);
    hdr[3] = (uint8_t)(len >> 8);
    hdr[4] = (uint8_t)(len);
    if (proto_write_all(fd, hdr, 5) < 0) return -1;
    if (len > 0 && proto_write_all(fd, data, (int)len) < 0) return -1;
    return 0;
}

/**
 * Drain (discard) exactly `n` bytes from `fd`.
 * Returns 0 on success, -1 on error.
 */
static inline int proto_drain(int fd, uint32_t n) {
    uint8_t tmp[256];
    while (n > 0) {
        uint32_t chunk = n < 256 ? n : 256;
        if (proto_read_exact(fd, tmp, (int)chunk) < 0) return -1;
        n -= chunk;
    }
    return 0;
}

/**
 * Read one framed message from `fd`.
 * On success, sets *type, fills buf (up to buf_size), sets *out_len.
 * If message payload exceeds buf_size, reads buf_size bytes and drains the rest.
 * If message payload exceeds FTYD_MAX_MSG_LEN, returns -1 (protocol error).
 * Returns 0 on success, -1 on error.
 */
static inline int proto_recv(int fd, uint8_t *type, uint8_t *buf, int buf_size, uint32_t *out_len) {
    uint8_t hdr[5];
    if (proto_read_exact(fd, hdr, 5) < 0) return -1;
    *type = hdr[0];
    uint32_t len = ((uint32_t)hdr[1] << 24) | ((uint32_t)hdr[2] << 16)
                 | ((uint32_t)hdr[3] <<  8) |  (uint32_t)hdr[4];

    if (len > FTYD_MAX_MSG_LEN) return -1; /* SEC-3: reject oversized messages */

    if ((int)len > buf_size) {
        /* Read what fits, drain the rest */
        if (proto_read_exact(fd, buf, buf_size) < 0) return -1;
        if (proto_drain(fd, len - (uint32_t)buf_size) < 0) return -1;
        *out_len = (uint32_t)buf_size;
    } else {
        if (len > 0 && proto_read_exact(fd, buf, (int)len) < 0) return -1;
        *out_len = len;
    }
    return 0;
}

/**
 * Client-side: read one message, returning DATA or EXIT_CODE.
 * For DATA: fills buf, returns bytes read.
 * For EXIT_CODE: sets *exit_code and returns 0.
 * For CLOSE: returns 0 with *exit_code = -1.
 * Returns -1 on error.
 */
static inline int proto_recv_data_ex(int fd, uint8_t *buf, int buf_size, int *exit_code) {
    *exit_code = -1;
    for (;;) {
        uint8_t hdr[5];
        if (proto_read_exact(fd, hdr, 5) < 0) return -1;
        uint8_t  type = hdr[0];
        uint32_t len  = ((uint32_t)hdr[1] << 24) | ((uint32_t)hdr[2] << 16)
                      | ((uint32_t)hdr[3] <<  8) |  (uint32_t)hdr[4];

        if (len > FTYD_MAX_MSG_LEN) return -1;

        if (type == FTYD_DATA) {
            if ((int)len > buf_size) {
                if (proto_read_exact(fd, buf, buf_size) < 0) return -1;
                if (proto_drain(fd, len - (uint32_t)buf_size) < 0) return -1;
                return buf_size;
            }
            if (len > 0 && proto_read_exact(fd, buf, (int)len) < 0) return -1;
            return (int)len;
        }
        if (type == FTYD_EXIT_CODE) {
            if (len >= 4) {
                uint8_t ec[4];
                if (proto_read_exact(fd, ec, 4) < 0) return -1;
                *exit_code = (int)(((uint32_t)ec[0] << 24) | ((uint32_t)ec[1] << 16)
                            | ((uint32_t)ec[2] << 8) | (uint32_t)ec[3]);
                if (len > 4 && proto_drain(fd, len - 4) < 0) return -1;
            }
            return 0;
        }
        if (type == FTYD_CLOSE) return 0;
        if (proto_drain(fd, len) < 0) return -1;
    }
}

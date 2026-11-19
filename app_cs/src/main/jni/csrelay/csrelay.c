/*
 * CS Relay client — libcsrelay.so
 *
 * Tunnels a local "Open to LAN" world through the Craft Studio relay
 * (play.craftstudios.site:25210). One TCP connection to the relay,
 * "HOST <player>\n" handshake answered by "OK <joinAddress>\n", then yamux v0
 * frames: every stream the relay opens for a guest is bridged to
 * 127.0.0.1:<lanPort>.
 *
 * Written in C on purpose. The game runs in this very process next to the
 * Android runtime; busy ART Java threads doing socket I/O while HotSpot owns
 * the process' signal handlers kill the process without a trace (the old
 * virtual LAN and the first Java relay client died exactly like that on the
 * first guest). Plain pthreads + BSD sockets never enter ART, and the Java
 * side only polls state from the main thread (no callbacks from native
 * threads).
 *
 * Zero dependencies; no door policy here (that stays with e4mc).
 */

#include <arpa/inet.h>
#include <errno.h>
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "CsRelay", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "CsRelay", __VA_ARGS__)
#else
#define LOGI(...) do { fprintf(stderr, "I/CsRelay: " __VA_ARGS__); fputc('\n', stderr); } while (0)
#define LOGW(...) do { fprintf(stderr, "W/CsRelay: " __VA_ARGS__); fputc('\n', stderr); } while (0)
#endif

#ifndef MSG_NOSIGNAL
#define MSG_NOSIGNAL 0x4000
#endif

/* ---- yamux v0 ---- */
#define TYPE_DATA 0
#define TYPE_WINDOW_UPDATE 1
#define TYPE_PING 2
#define TYPE_GO_AWAY 3
#define FLAG_SYN 0x1
#define FLAG_ACK 0x2
#define FLAG_FIN 0x4
#define FLAG_RST 0x8
#define INITIAL_WINDOW (256 * 1024)
#define MAX_FRAME (4 * 1024 * 1024)
#define MAX_STREAMS 64

/* ---- state machine seen by Java ---- */
#define STATE_CONNECTING 0
#define STATE_UP 1
#define STATE_FAILED 2
#define STATE_STOPPED 3

typedef struct relay relay_t;

typedef struct stream {
    relay_t *relay;
    uint32_t id;
    int fd;                      /* local world socket, -1 when gone */
    int in_use;
    int closed;                  /* fully torn down */
    int local_eof;               /* local world stopped sending (FIN sent to relay) */
    int remote_fin;              /* relay sent FIN (shutdown(SHUT_WR) on local) */
    int32_t send_window;         /* bytes we may still send to the relay */
    uint32_t recv_unacked;       /* bytes delivered locally since last WINDOW_UPDATE */
    pthread_mutex_t lock;
    pthread_cond_t window_cv;
    pthread_t thread;
    int thread_started;
} stream_t;

struct relay {
    char host[256];
    int port;
    int local_port;
    char player[64];

    int fd;                      /* relay socket */
    pthread_mutex_t write_lock;
    pthread_mutex_t state_lock;
    int state;
    char join_address[256];
    char problem[256];
    volatile int running;

    stream_t streams[MAX_STREAMS];
    pthread_mutex_t streams_lock;
    volatile int open_streams;

    pthread_t main_thread;
    pthread_t ping_thread;
    int ping_started;
};

/* ---- small helpers ---- */

static void set_state(relay_t *r, int state, const char *problem) {
    pthread_mutex_lock(&r->state_lock);
    if (r->state != STATE_FAILED && r->state != STATE_STOPPED) {
        r->state = state;
        if (problem) {
            strncpy(r->problem, problem, sizeof(r->problem) - 1);
            r->problem[sizeof(r->problem) - 1] = 0;
        }
    } else if (state == STATE_STOPPED && r->state == STATE_FAILED) {
        /* keep FAILED + reason: Java shows it */
    }
    pthread_mutex_unlock(&r->state_lock);
}

static int send_all(relay_t *r, const uint8_t *buf, size_t len) {
    while (len > 0) {
        ssize_t n = send(r->fd, buf, len, MSG_NOSIGNAL);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (n == 0) return -1;
        buf += n;
        len -= (size_t) n;
    }
    return 0;
}

/* One frame (header + optional payload) under the write lock so frames never interleave. */
static int send_frame(relay_t *r, int type, int flags, uint32_t stream_id, const uint8_t *data, uint32_t length) {
    uint8_t hdr[12];
    hdr[0] = 0;
    hdr[1] = (uint8_t) type;
    hdr[2] = (uint8_t) (flags >> 8);
    hdr[3] = (uint8_t) flags;
    hdr[4] = (uint8_t) (stream_id >> 24); hdr[5] = (uint8_t) (stream_id >> 16);
    hdr[6] = (uint8_t) (stream_id >> 8);  hdr[7] = (uint8_t) stream_id;
    hdr[8] = (uint8_t) (length >> 24);    hdr[9] = (uint8_t) (length >> 16);
    hdr[10] = (uint8_t) (length >> 8);    hdr[11] = (uint8_t) length;
    int rc;
    pthread_mutex_lock(&r->write_lock);
    rc = send_all(r, hdr, sizeof(hdr));
    if (rc == 0 && data && length > 0) rc = send_all(r, data, length);
    pthread_mutex_unlock(&r->write_lock);
    return rc;
}

static int recv_all(int fd, uint8_t *buf, size_t len) {
    while (len > 0) {
        ssize_t n = recv(fd, buf, len, 0);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (n == 0) return -1;
        buf += n;
        len -= (size_t) n;
    }
    return 0;
}

static int set_timeout(int fd, int opt, int seconds) {
    struct timeval tv;
    tv.tv_sec = seconds;
    tv.tv_usec = 0;
    return setsockopt(fd, SOL_SOCKET, opt, &tv, sizeof(tv));
}

/* ---- streams ---- */

static stream_t *stream_find(relay_t *r, uint32_t id) {
    for (int i = 0; i < MAX_STREAMS; i++) {
        stream_t *s = &r->streams[i];
        if (s->in_use && !s->closed && s->id == id) return s;
    }
    return NULL;
}

/* Marks the stream dead, closes the local socket, optionally tells the relay. Idempotent. */
static void stream_close(stream_t *s, int send_rst) {
    relay_t *r = s->relay;
    pthread_mutex_lock(&s->lock);
    if (s->closed) {
        pthread_mutex_unlock(&s->lock);
        return;
    }
    s->closed = 1;
    int fd = s->fd;
    pthread_cond_broadcast(&s->window_cv);
    pthread_mutex_unlock(&s->lock);
    if (fd >= 0) shutdown(fd, SHUT_RDWR); /* wakes the bridge thread; the fd is closed by it */
    if (send_rst && r->running) send_frame(r, TYPE_WINDOW_UPDATE, FLAG_RST, s->id, NULL, 0);
    pthread_mutex_lock(&r->streams_lock);
    if (r->open_streams > 0) r->open_streams--;
    pthread_mutex_unlock(&r->streams_lock);
}

/* Both directions done → the slot can be reused. Called by the bridge thread on exit. */
static void stream_release(stream_t *s) {
    relay_t *r = s->relay;
    pthread_mutex_lock(&r->streams_lock);
    /* The fd stays open (shut down) until the slot is reused or the relay stops, so a late
       write from the main loop hits a dead socket instead of a recycled descriptor. */
    s->in_use = 0;
    pthread_mutex_unlock(&r->streams_lock);
}

/* Local world → relay. Reads the local socket, sends DATA frames within the send window. */
static void *stream_bridge(void *arg) {
    stream_t *s = (stream_t *) arg;
    relay_t *r = s->relay;
    uint8_t buf[16 * 1024];
    for (;;) {
        ssize_t n = recv(s->fd, buf, sizeof(buf), 0);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) break;
        size_t off = 0;
        while (off < (size_t) n) {
            uint32_t to_send;
            pthread_mutex_lock(&s->lock);
            while (s->send_window <= 0 && !s->closed && r->running) {
                struct timespec ts;
                struct timeval now;
                gettimeofday(&now, NULL);
                ts.tv_sec = now.tv_sec + 1;
                ts.tv_nsec = now.tv_usec * 1000;
                pthread_cond_timedwait(&s->window_cv, &s->lock, &ts);
            }
            if (s->closed || !r->running) {
                pthread_mutex_unlock(&s->lock);
                goto out;
            }
            to_send = (uint32_t) ((size_t) s->send_window < (size_t) n - off ? (size_t) s->send_window : (size_t) n - off);
            s->send_window -= (int32_t) to_send;
            pthread_mutex_unlock(&s->lock);
            if (send_frame(r, TYPE_DATA, 0, s->id, buf + off, to_send) != 0) goto out;
            off += to_send;
        }
    }
    /* Local world finished sending. */
    pthread_mutex_lock(&s->lock);
    int already_closed = s->closed;
    s->local_eof = 1;
    int done = s->remote_fin;
    pthread_mutex_unlock(&s->lock);
    if (!already_closed && r->running) send_frame(r, TYPE_WINDOW_UPDATE, FLAG_FIN, s->id, NULL, 0);
    if (done) stream_close(s, 0);
    else {
        /* Half-closed: wait for the relay's FIN/RST (delivered by the main loop), bounded. */
        for (int i = 0; i < 300 && r->running; i++) {
            pthread_mutex_lock(&s->lock);
            int c = s->closed;
            pthread_mutex_unlock(&s->lock);
            if (c) break;
            usleep(100 * 1000);
        }
        stream_close(s, 1);
    }
out:
    stream_close(s, 1);
    stream_release(s);
    return NULL;
}

/* The relay opened a stream: connect to the local world, ACK, start the bridge. */
static stream_t *stream_open(relay_t *r, uint32_t id) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return NULL;
    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
    set_timeout(fd, SO_SNDTIMEO, 15);
    struct sockaddr_in a;
    memset(&a, 0, sizeof(a));
    a.sin_family = AF_INET;
    a.sin_port = htons((uint16_t) r->local_port);
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (connect(fd, (struct sockaddr *) &a, sizeof(a)) != 0) {
        LOGW("local world 127.0.0.1:%d refused: %s", r->local_port, strerror(errno));
        close(fd);
        return NULL;
    }
    stream_t *s = NULL;
    pthread_mutex_lock(&r->streams_lock);
    for (int i = 0; i < MAX_STREAMS; i++) {
        if (!r->streams[i].in_use) {
            s = &r->streams[i];
            if (s->thread_started) { pthread_join(s->thread, NULL); s->thread_started = 0; }
            if (s->fd >= 0) close(s->fd);
            s->relay = r;
            s->id = id;
            s->fd = fd;
            s->in_use = 1;
            s->closed = 0;
            s->local_eof = 0;
            s->remote_fin = 0;
            s->send_window = INITIAL_WINDOW;
            s->recv_unacked = 0;
            r->open_streams++;
            break;
        }
    }
    pthread_mutex_unlock(&r->streams_lock);
    if (!s) {
        LOGW("too many streams");
        close(fd);
        return NULL;
    }
    if (send_frame(r, TYPE_WINDOW_UPDATE, FLAG_ACK, id, NULL, 0) != 0) {
        stream_close(s, 0);
        stream_release(s);
        return NULL;
    }
    if (pthread_create(&s->thread, NULL, stream_bridge, s) != 0) {
        stream_close(s, 1);
        stream_release(s);
        return NULL;
    }
    s->thread_started = 1;
    return s;
}

/* ---- relay connection ---- */

static int relay_connect(relay_t *r, char *err, size_t errlen) {
    struct addrinfo hints, *res = NULL, *ai;
    char portstr[16];
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    snprintf(portstr, sizeof(portstr), "%d", r->port);
    int gai = getaddrinfo(r->host, portstr, &hints, &res);
    if (gai != 0 || !res) {
        snprintf(err, errlen, "Cannot resolve %s", r->host);
        return -1;
    }
    int fd = -1;
    for (ai = res; ai; ai = ai->ai_next) {
        fd = socket(ai->ai_family, ai->ai_socktype, ai->ai_protocol);
        if (fd < 0) continue;
        set_timeout(fd, SO_SNDTIMEO, 15);
        set_timeout(fd, SO_RCVTIMEO, 15); /* handshake only; cleared below */
        if (connect(fd, ai->ai_addr, ai->ai_addrlen) == 0) break;
        close(fd);
        fd = -1;
    }
    freeaddrinfo(res);
    if (fd < 0) {
        snprintf(err, errlen, "Cannot reach %s:%d", r->host, r->port);
        return -1;
    }
    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
    setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &one, sizeof(one));
    return fd;
}

/* Sends hello, reads the OK line. 1 = ok, 0 = relay hung up without answering, -1 = refused. */
static int relay_handshake(relay_t *r, int fd, const char *hello, char *err, size_t errlen) {
    if (send(fd, hello, strlen(hello), MSG_NOSIGNAL) != (ssize_t) strlen(hello)) {
        snprintf(err, errlen, "Relay write failed");
        return -1;
    }
    char line[300];
    size_t n = 0;
    for (;;) {
        char c;
        ssize_t k = recv(fd, &c, 1, 0);
        if (k < 0 && errno == EINTR) continue;
        if (k <= 0) {
            if (n == 0) return 0;
            snprintf(err, errlen, "Relay closed during handshake");
            return -1;
        }
        if (c == '\n') break;
        if (n < sizeof(line) - 1) line[n++] = c;
    }
    line[n] = 0;
    while (n > 0 && (line[n - 1] == '\r' || line[n - 1] == ' ')) line[--n] = 0;
    if (strncmp(line, "OK", 2) != 0) {
        snprintf(err, errlen, "Relay: %s", n ? line : "refused");
        return -1;
    }
    const char *addr = line + 2;
    while (*addr == ' ') addr++;
    if (*addr == 0 || !strchr(addr, ':')) {
        snprintf(r->join_address, sizeof(r->join_address), "%s:%d", r->host, r->port);
    } else {
        strncpy(r->join_address, addr, sizeof(r->join_address) - 1);
        r->join_address[sizeof(r->join_address) - 1] = 0;
    }
    return 1;
}

static void *ping_loop(void *arg) {
    relay_t *r = (relay_t *) arg;
    uint32_t opaque = 1;
    while (r->running) {
        for (int i = 0; i < 300 && r->running; i++) usleep(100 * 1000); /* 30 s */
        if (!r->running) break;
        if (send_frame(r, TYPE_PING, FLAG_SYN, 0, NULL, opaque++) != 0) break;
    }
    return NULL;
}

static void relay_teardown(relay_t *r, const char *reason, int failed) {
    if (failed) set_state(r, STATE_FAILED, reason);
    r->running = 0;
    if (r->fd >= 0) {
        send_frame(r, TYPE_GO_AWAY, 0, 0, NULL, 0);
        shutdown(r->fd, SHUT_RDWR);
    }
    for (int i = 0; i < MAX_STREAMS; i++) {
        if (r->streams[i].in_use) stream_close(&r->streams[i], 0);
    }
    set_state(r, STATE_STOPPED, reason);
}

static void *main_loop(void *arg) {
    relay_t *r = (relay_t *) arg;
    char err[256] = {0};
    char hello[128];

    int fd = relay_connect(r, err, sizeof(err));
    if (fd < 0) { relay_teardown(r, err, 1); return NULL; }

    if (r->player[0]) snprintf(hello, sizeof(hello), "HOST %s\n", r->player);
    else snprintf(hello, sizeof(hello), "HOST\n");
    int hs = relay_handshake(r, fd, hello, err, sizeof(err));
    if (hs == 0 && r->player[0]) {
        /* A relay that does not take a name yet hangs up: retry anonymously. */
        LOGW("relay closed on named handshake; retrying anonymous");
        close(fd);
        fd = relay_connect(r, err, sizeof(err));
        if (fd < 0) { relay_teardown(r, err, 1); return NULL; }
        hs = relay_handshake(r, fd, "HOST\n", err, sizeof(err));
    }
    if (hs <= 0) {
        close(fd);
        relay_teardown(r, hs == 0 ? "Relay closed before handshake" : err, 1);
        return NULL;
    }
    set_timeout(fd, SO_RCVTIMEO, 0);
    r->fd = fd;
    LOGI("published %s -> 127.0.0.1:%d", r->join_address, r->local_port);
    set_state(r, STATE_UP, NULL);

    if (pthread_create(&r->ping_thread, NULL, ping_loop, r) == 0) r->ping_started = 1;

    uint8_t *payload = NULL;
    size_t payload_cap = 0;
    const char *reason = "Tunnel closed";
    int failed = 1;

    while (r->running) {
        uint8_t hdr[12];
        if (recv_all(r->fd, hdr, sizeof(hdr)) != 0) {
            if (!r->running) { reason = "Stopped by host"; failed = 0; }
            else reason = "Relay connection lost";
            break;
        }
        if (hdr[0] != 0) { reason = "Bad yamux version"; break; }
        int type = hdr[1];
        int flags = (hdr[2] << 8) | hdr[3];
        uint32_t sid = ((uint32_t) hdr[4] << 24) | ((uint32_t) hdr[5] << 16) | ((uint32_t) hdr[6] << 8) | hdr[7];
        uint32_t length = ((uint32_t) hdr[8] << 24) | ((uint32_t) hdr[9] << 16) | ((uint32_t) hdr[10] << 8) | hdr[11];

        if (type == TYPE_PING) {
            if (flags & FLAG_SYN) send_frame(r, TYPE_PING, FLAG_ACK, 0, NULL, length);
            continue;
        }
        if (type == TYPE_GO_AWAY) { reason = "Relay closed the tunnel"; break; }
        if (type != TYPE_DATA && type != TYPE_WINDOW_UPDATE) continue;

        if (type == TYPE_DATA && length > 0) {
            if (length > MAX_FRAME) { reason = "Frame too large"; break; }
            if (payload_cap < length) {
                uint8_t *p = realloc(payload, length);
                if (!p) { reason = "Out of memory"; break; }
                payload = p;
                payload_cap = length;
            }
            if (recv_all(r->fd, payload, length) != 0) { reason = "Relay connection lost"; break; }
        }

        stream_t *s;
        pthread_mutex_lock(&r->streams_lock);
        s = stream_find(r, sid);
        pthread_mutex_unlock(&r->streams_lock);
        if ((flags & FLAG_SYN) && !s) {
            s = stream_open(r, sid);
            if (!s) {
                send_frame(r, TYPE_WINDOW_UPDATE, FLAG_RST, sid, NULL, 0);
                continue;
            }
        }
        if (!s) continue;

        if (type == TYPE_WINDOW_UPDATE && length > 0) {
            pthread_mutex_lock(&s->lock);
            s->send_window += (int32_t) length;
            pthread_cond_broadcast(&s->window_cv);
            pthread_mutex_unlock(&s->lock);
        }
        if (type == TYPE_DATA && length > 0) {
            /* Relay → local world. Blocking write with SO_SNDTIMEO; a stuck world resets the stream. */
            const uint8_t *p = payload;
            size_t left = length;
            int ok = 1;
            while (left > 0) {
                ssize_t n = send(s->fd, p, left, MSG_NOSIGNAL);
                if (n < 0 && errno == EINTR) continue;
                if (n <= 0) { ok = 0; break; }
                p += n;
                left -= (size_t) n;
            }
            if (!ok) {
                stream_close(s, 1);
                continue;
            }
            s->recv_unacked += length;
            if (s->recv_unacked >= 32 * 1024) {
                send_frame(r, TYPE_WINDOW_UPDATE, 0, s->id, NULL, s->recv_unacked);
                s->recv_unacked = 0;
            }
        }
        if (flags & FLAG_FIN) {
            pthread_mutex_lock(&s->lock);
            s->remote_fin = 1;
            int done = s->local_eof;
            pthread_mutex_unlock(&s->lock);
            if (s->fd >= 0) shutdown(s->fd, SHUT_WR);
            if (done) stream_close(s, 0);
        }
        if (flags & FLAG_RST) stream_close(s, 0);
    }
    free(payload);
    relay_teardown(r, reason, failed);
    return NULL;
}

/* ---- public C API (used by JNI and by the test main) ---- */

relay_t *csrelay_start(const char *host, int port, int local_port, const char *player) {
    relay_t *r = calloc(1, sizeof(relay_t));
    if (!r) return NULL;
    strncpy(r->host, host && *host ? host : "play.craftstudios.site", sizeof(r->host) - 1);
    r->port = port > 0 ? port : 25210;
    r->local_port = local_port;
    r->fd = -1;
    /* Player name: trimmed, no whitespace, ASCII only (the relay turns it into a subdomain). */
    if (player) {
        size_t j = 0;
        for (const char *p = player; *p && j < sizeof(r->player) - 1; p++) {
            unsigned char c = (unsigned char) *p;
            if (c <= ' ' || c >= 127) continue;
            r->player[j++] = (char) c;
        }
        r->player[j] = 0;
    }
    pthread_mutex_init(&r->write_lock, NULL);
    pthread_mutex_init(&r->state_lock, NULL);
    pthread_mutex_init(&r->streams_lock, NULL);
    for (int i = 0; i < MAX_STREAMS; i++) {
        r->streams[i].fd = -1;
        pthread_mutex_init(&r->streams[i].lock, NULL);
        pthread_cond_init(&r->streams[i].window_cv, NULL);
    }
    r->state = STATE_CONNECTING;
    r->running = 1;
    if (pthread_create(&r->main_thread, NULL, main_loop, r) != 0) {
        free(r);
        return NULL;
    }
    return r;
}

int csrelay_state(relay_t *r) {
    pthread_mutex_lock(&r->state_lock);
    int s = r->state;
    pthread_mutex_unlock(&r->state_lock);
    return s;
}

/* Copies into buf; returns buf, or NULL when empty. */
const char *csrelay_join_address(relay_t *r, char *buf, size_t len) {
    pthread_mutex_lock(&r->state_lock);
    int up = r->state == STATE_UP;
    if (up) { strncpy(buf, r->join_address, len - 1); buf[len - 1] = 0; }
    pthread_mutex_unlock(&r->state_lock);
    return up && buf[0] ? buf : NULL;
}

const char *csrelay_problem(relay_t *r, char *buf, size_t len) {
    pthread_mutex_lock(&r->state_lock);
    int has = r->problem[0] != 0;
    if (has) { strncpy(buf, r->problem, len - 1); buf[len - 1] = 0; }
    pthread_mutex_unlock(&r->state_lock);
    return has ? buf : NULL;
}

int csrelay_streams(relay_t *r) {
    pthread_mutex_lock(&r->streams_lock);
    int n = r->open_streams;
    pthread_mutex_unlock(&r->streams_lock);
    return n;
}

/* Stops everything, joins the threads and frees the handle. */
void csrelay_stop(relay_t *r) {
    if (!r) return;
    r->running = 0;
    if (r->fd >= 0) {
        send_frame(r, TYPE_GO_AWAY, 0, 0, NULL, 0);
        shutdown(r->fd, SHUT_RDWR);
    }
    pthread_join(r->main_thread, NULL);
    if (r->ping_started) pthread_join(r->ping_thread, NULL);
    for (int i = 0; i < MAX_STREAMS; i++) {
        stream_t *s = &r->streams[i];
        if (s->in_use) stream_close(s, 0);
        if (s->thread_started) { pthread_join(s->thread, NULL); s->thread_started = 0; }
        if (s->fd >= 0) { close(s->fd); s->fd = -1; }
        pthread_mutex_destroy(&s->lock);
        pthread_cond_destroy(&s->window_cv);
    }
    if (r->fd >= 0) { close(r->fd); r->fd = -1; }
    pthread_mutex_destroy(&r->write_lock);
    pthread_mutex_destroy(&r->state_lock);
    pthread_mutex_destroy(&r->streams_lock);
    free(r);
}

/* ---- JNI ---- */
#ifndef CSRELAY_NO_JNI
#include <jni.h>

static void jstr_copy(JNIEnv *env, jstring js, char *out, size_t len) {
    out[0] = 0;
    if (!js) return;
    const char *c = (*env)->GetStringUTFChars(env, js, NULL);
    if (!c) return;
    strncpy(out, c, len - 1);
    out[len - 1] = 0;
    (*env)->ReleaseStringUTFChars(env, js, c);
}

JNIEXPORT jlong JNICALL
Java_com_craftstudio_cslauncher_e4mc_CsRelayNative_nativeStart(JNIEnv *env, jclass clazz, jstring host, jint port, jint localPort, jstring player) {
    (void) clazz;
    char h[256], p[128];
    jstr_copy(env, host, h, sizeof(h));
    jstr_copy(env, player, p, sizeof(p));
    return (jlong) (intptr_t) csrelay_start(h, port, localPort, p);
}

JNIEXPORT jint JNICALL
Java_com_craftstudio_cslauncher_e4mc_CsRelayNative_nativeState(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    relay_t *r = (relay_t *) (intptr_t) handle;
    return r ? csrelay_state(r) : STATE_STOPPED;
}

JNIEXPORT jstring JNICALL
Java_com_craftstudio_cslauncher_e4mc_CsRelayNative_nativeJoinAddress(JNIEnv *env, jclass clazz, jlong handle) {
    (void) clazz;
    relay_t *r = (relay_t *) (intptr_t) handle;
    char buf[256];
    if (!r || !csrelay_join_address(r, buf, sizeof(buf))) return NULL;
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jstring JNICALL
Java_com_craftstudio_cslauncher_e4mc_CsRelayNative_nativeProblem(JNIEnv *env, jclass clazz, jlong handle) {
    (void) clazz;
    relay_t *r = (relay_t *) (intptr_t) handle;
    char buf[256];
    if (!r || !csrelay_problem(r, buf, sizeof(buf))) return NULL;
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jint JNICALL
Java_com_craftstudio_cslauncher_e4mc_CsRelayNative_nativeStreams(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    relay_t *r = (relay_t *) (intptr_t) handle;
    return r ? csrelay_streams(r) : 0;
}

JNIEXPORT void JNICALL
Java_com_craftstudio_cslauncher_e4mc_CsRelayNative_nativeStop(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    csrelay_stop((relay_t *) (intptr_t) handle);
}
#endif

/* ---- standalone test: csrelay_test <localPort> [player] ---- */
#ifdef CSRELAY_TEST_MAIN
int main(int argc, char **argv) {
    int local = argc > 1 ? atoi(argv[1]) : 25565;
    relay_t *r = csrelay_start(NULL, 0, local, argc > 2 ? argv[2] : NULL);
    char buf[256];
    int iters = argc > 3 ? atoi(argv[3]) : 600;
    for (int i = 0; i < iters; i++) {
        int st = csrelay_state(r);
        if (st == STATE_UP && csrelay_join_address(r, buf, sizeof(buf))) printf("UP %s streams=%d\n", buf, csrelay_streams(r));
        if (st == STATE_FAILED || st == STATE_STOPPED) {
            printf("ENDED state=%d problem=%s\n", st, csrelay_problem(r, buf, sizeof(buf)) ? buf : "-");
            break;
        }
        fflush(stdout);
        usleep(500 * 1000);
    }
    csrelay_stop(r);
    printf("stopped\n");
    return 0;
}
#endif

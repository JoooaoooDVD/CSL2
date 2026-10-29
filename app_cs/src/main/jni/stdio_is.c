#include <jni.h>
#include <sys/types.h>
#include <stdbool.h>
#include <unistd.h>
#include <pthread.h>
#include <stdio.h>
#include <fcntl.h>
#include <string.h>
#include <errno.h>
#include <stdlib.h>

//
// Created by maks on 17.02.21.
//

static int pfd[2];
static pthread_t logger;
static jmethodID logger_onEventLogged;
static volatile jobject logListener = NULL;
static int latestlog_fd = -1;


static bool recordBuffer(char* buf, ssize_t len) {
    if(strstr(buf, "Session ID is")) return false;
    if(latestlog_fd != -1) {
        write(latestlog_fd, buf, len);
        fdatasync(latestlog_fd);
    }
    return true;
}

/*
 * ART only accepts *Modified* UTF-8 in NewStringUTF. Anything else (a 4-byte emoji, a
 * multi-byte character cut in half by the pipe read boundary, a stray byte from a mod
 * printing binary data) is "JNI DETECTED ERROR IN APPLICATION: input is not valid Modified
 * UTF-8" on a CheckJNI-enabled (debuggable) build - which ABORTS THE WHOLE GAME PROCESS
 * silently, with no Java crash report and no JVM hs_err file. Player names, world names and
 * chat lines from LAN / multiplayer sessions are exactly where such text shows up, so the
 * log stream is re-encoded here before it ever reaches the VM:
 *   - ASCII and valid 2/3-byte sequences pass through,
 *   - 4-byte sequences become the MUTF-8 surrogate pair (text stays intact),
 *   - invalid / truncated bytes become '?',
 *   - an incomplete sequence at the END of a read is carried over to the next read.
 * Returns the number of bytes written to out (out must hold at least 2*len + 1 bytes).
 */
static size_t to_modified_utf8(const unsigned char *in, size_t len, unsigned char *out) {
    size_t o = 0;
    size_t i = 0;
    while (i < len) {
        unsigned char c = in[i];
        if (c < 0x80) {
            if (c == 0) { out[o++] = '?'; i++; continue; } // embedded NUL would end the C string early
            out[o++] = c; i++;
            continue;
        }
        size_t need; unsigned int cp; unsigned char min_c1 = 0x80, max_c1 = 0xBF;
        if ((c & 0xE0) == 0xC0) { need = 1; cp = c & 0x1F; if (c < 0xC2) { out[o++] = '?'; i++; continue; } }
        else if ((c & 0xF0) == 0xE0) { need = 2; cp = c & 0x0F; if (c == 0xE0) min_c1 = 0xA0; }
        else if ((c & 0xF8) == 0xF0) { need = 3; cp = c & 0x07; if (c == 0xF0) min_c1 = 0x90; else if (c == 0xF4) max_c1 = 0x8F; else if (c > 0xF4) { out[o++] = '?'; i++; continue; } }
        else { out[o++] = '?'; i++; continue; }
        if (i + need >= len) { // the sequence needs bytes beyond this buffer: truncated
            for (size_t k = i; k < len; k++) out[o++] = '?';
            break;
        }
        bool ok = true;
        for (size_t k = 1; k <= need; k++) {
            unsigned char cc = in[i + k];
            if (cc < 0x80 || cc > 0xBF || (k == 1 && (cc < min_c1 || cc > max_c1))) { ok = false; break; }
            cp = (cp << 6) | (cc & 0x3F);
        }
        if (!ok) { out[o++] = '?'; i++; continue; }
        if (need == 3) {
            // U+10000..U+10FFFF -> surrogate pair, each surrogate as a 3-byte sequence (MUTF-8)
            unsigned int v = cp - 0x10000;
            unsigned int hi = 0xD800 | (v >> 10), lo = 0xDC00 | (v & 0x3FF);
            out[o++] = 0xE0 | (hi >> 12); out[o++] = 0x80 | ((hi >> 6) & 0x3F); out[o++] = 0x80 | (hi & 0x3F);
            out[o++] = 0xE0 | (lo >> 12); out[o++] = 0x80 | ((lo >> 6) & 0x3F); out[o++] = 0x80 | (lo & 0x3F);
        } else {
            for (size_t k = 0; k <= need; k++) out[o++] = in[i + k];
        }
        i += need + 1;
    }
    out[o] = 0;
    return o;
}

/* How many bytes at the end of buf form an incomplete (but so far valid) UTF-8 sequence */
static size_t incomplete_tail(const unsigned char *buf, size_t len) {
    size_t back = len < 3 ? len : 3;
    for (size_t b = 1; b <= back; b++) {
        unsigned char c = buf[len - b];
        if ((c & 0xC0) == 0x80) continue; // continuation byte, keep looking for the lead
        size_t need = 0;
        if ((c & 0xE0) == 0xC0) need = 1; else if ((c & 0xF0) == 0xE0) need = 2; else if ((c & 0xF8) == 0xF0) need = 3;
        else return 0; // ASCII or invalid lead: nothing to carry
        return (b - 1) < need ? b : 0; // lead + (b-1) continuations seen, sequence wants `need`
    }
    return 0;
}

/* Hand one chunk to the Java listener; a Java exception thrown by a listener is logged and
 * cleared instead of being left pending on this native thread (CheckJNI aborts on that). */
static void deliver_to_listener(JNIEnv *env, const char *text) {
    jobject listener = logListener;
    if (listener == NULL) return;
    jstring writeString = (*env)->NewStringUTF(env, text);
    if (writeString == NULL) { if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env); return; }
    (*env)->CallVoidMethod(env, listener, logger_onEventLogged, writeString);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
    (*env)->DeleteLocalRef(env, writeString);
}

static void *logger_thread(void* param) {
    JNIEnv *env;
    JavaVM* dvm = (JavaVM*) param;
    (*dvm)->AttachCurrentThread(dvm, (void**) &env, NULL);
    ssize_t  rsize;
    static unsigned char buf[2050 + 4];   // + room for a carried-over partial sequence
    static unsigned char clean[2 * (2050 + 4) + 8];
    size_t carry = 0;
    while((rsize = read(pfd[0], buf + carry, 2049 - carry)) > 0) {
        size_t total = carry + (size_t) rsize;
        buf[total] = 0; // recordBuffer scans it as a C string
        bool shouldRecordString = recordBuffer((char*) buf + carry, rsize); //record with newline int latestlog
        size_t tail = incomplete_tail(buf, total);
        size_t usable = total - tail;
        if (usable > 0 && buf[usable - 1] == '\n') usable--; // truncate the newline for the app
        if (shouldRecordString && logListener != NULL && usable > 0) {
            to_modified_utf8(buf, usable, clean);
            deliver_to_listener(env, (const char*) clean);
        }
        // keep the incomplete sequence for the next read
        if (tail > 0 && tail <= 3) { memmove(buf, buf + total - tail, tail); carry = tail; }
        else carry = 0;
    }
    (*dvm)->DetachCurrentThread(dvm);
    return NULL;
}


JNIEXPORT void JNICALL
Java_com_craftstudio_cslauncher_Logger_begin(JNIEnv *env, __attribute((unused)) jclass clazz, jstring logPath) {
    if(latestlog_fd != -1) {
        int localfd = latestlog_fd;
        latestlog_fd = -1;
        close(localfd);
    }
    if(logger_onEventLogged == NULL) {
        jclass eventLogListener = (*env)->FindClass(env, "com/craftstudio/cslauncher/Logger$eventLogListener");
        logger_onEventLogged = (*env)->GetMethodID(env, eventLogListener, "onEventLogged", "(Ljava/lang/String;)V");
    }
    jclass ioeClass = (*env)->FindClass(env, "java/io/IOException");


    setvbuf(stdout, 0, _IOLBF, 0); // make stdout line-buffered
    setvbuf(stderr, 0, _IONBF, 0); // make stderr unbuffered

    /* create the pipe and redirect stdout and stderr */
    pipe(pfd);
    dup2(pfd[1], 1);
    dup2(pfd[1], 2);

    /* open latestlog.txt for writing */
    const char* logFilePath = (*env)->GetStringUTFChars(env, logPath, NULL);
    latestlog_fd = open(logFilePath, O_WRONLY | O_TRUNC);
    if(latestlog_fd == -1) {
        latestlog_fd = 0;
        (*env)->ThrowNew(env, ioeClass, strerror(errno));
        return;
    }
    (*env)->ReleaseStringUTFChars(env, logPath, logFilePath);

    JavaVM* vm = NULL;
    (*env)->GetJavaVM(env, &vm);

    /* spawn the logging thread */
    int result = pthread_create(&logger, 0, logger_thread, vm);
    if(result != 0) {
        close(latestlog_fd);
        (*env)->ThrowNew(env, ioeClass, strerror(result));
    }
    pthread_detach(logger);
}

JNIEXPORT void JNICALL Java_com_craftstudio_cslauncher_Logger_appendToLog(JNIEnv *env, __attribute((unused)) jclass clazz, jstring text) {
    if (text == NULL) return;
    jsize appendStringLength = (*env)->GetStringUTFLength(env, text);
    // heap, not a VLA: a long stack trace handed over by Java must never overflow a native stack
    char *newChars = malloc((size_t) appendStringLength + 2);
    if (newChars == NULL) return;
    (*env)->GetStringUTFRegion(env, text, 0, (*env)->GetStringLength(env, text), newChars);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); free(newChars); return; }
    newChars[appendStringLength] = '\n';
    newChars[appendStringLength+1] = 0;
    if(recordBuffer(newChars, appendStringLength+1) && logListener != NULL) {
        (*env)->CallVoidMethod(env, logListener, logger_onEventLogged, text);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
        }
    }
    free(newChars);
}

JNIEXPORT void JNICALL
Java_com_craftstudio_cslauncher_Logger_setLogListener(JNIEnv *env, __attribute((unused)) jclass clazz, jobject log_listener) {
    jobject logListenerLocal = logListener;
    if(log_listener == NULL) {
        logListener = NULL;
    }else{
        logListener = (*env)->NewGlobalRef(env, log_listener);
    }
    if(logListenerLocal != NULL) (*env)->DeleteGlobalRef(env, logListenerLocal);
}
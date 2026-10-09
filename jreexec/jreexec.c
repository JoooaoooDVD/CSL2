//
// Created by maks on 30.06.2026.
//

#include <jreexec.h>
#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <driver_helper/nsbypass.h>
#include <dlfcn.h>

jreexec_renderspec_t jreexec_renderspec;

const char* jreexec_native_dir = NULL;
char* native_egl_path = NULL;
bool egl_use_bypass = false;

static void save_jvm_string(JNIEnv* env, char** target, jstring str) {
    if(*target != NULL) free(*target);
    if(str == NULL) {
        *target = NULL;
        return;
    }

    const char* path = (*env)->GetStringUTFChars(env, str, NULL);
    *target = strdup(path);
    (*env)->ReleaseStringUTFChars(env, str, path);
}

JNIEXPORT jboolean JNICALL
Java_com_craftstudio_jreexec_JreExec_prepareEgl(JNIEnv *env, jclass clazz, jstring egl_path,
                                               jboolean use_bypass, jboolean use_gles,
                                               jint gles_version) {
    save_jvm_string(env, &native_egl_path, egl_path);
    if(native_egl_path == NULL) return false;
    if(use_bypass && !linker_ns_load(jreexec_native_dir)) return false;
    egl_use_bypass = use_bypass;
    void* preload_handle = jreexec_acq_egl_handle();
    if(!preload_handle) {
        printf("JreExec: Failed to load EGL: %s\n", dlerror());
        return false;
    }
    printf("JreExec: Loaded EGL %s (in namespace: %i)\n", native_egl_path, egl_use_bypass);
    jreexec_renderspec.force_gles_context = use_gles;
    jreexec_renderspec.override_major_version = gles_version;
    return true;
}

JNIEXPORT void JNICALL
Java_com_craftstudio_jreexec_JreExec_setNativeLibraryDir(JNIEnv *env, jclass clazz, jstring dir) {
    save_jvm_string(env, (char **) &jreexec_native_dir, dir);
    return;
}

JNIEXPORT void JNICALL
Java_com_craftstudio_jreexec_JreExec_setDisplayParams(JNIEnv *env, jclass clazz, jint width,
                                                     jint height, jfloat hz) {
    // TODO: implement setDisplayParams()
    jreexec_renderspec.disp_width = width;
    jreexec_renderspec.disp_height = height;
    jreexec_renderspec.disp_hz = hz;
}

void* jreexec_acq_egl_handle() {
    int flags = RTLD_LOCAL | RTLD_NOW;
    if(egl_use_bypass) return linker_ns_dlopen(native_egl_path, flags);
    else return dlopen(native_egl_path, flags);
}


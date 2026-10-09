//
// Created by maks on 30.06.2026.
//

#include <stdbool.h>

#ifndef POJAVLAUNCHER_JREEXEC_H
#define POJAVLAUNCHER_JREEXEC_H


typedef struct {
    int force_gles_context;
    int override_major_version;
    int disp_width;
    int disp_height;
    float disp_hz;
} jreexec_renderspec_t;

extern jreexec_renderspec_t jreexec_renderspec;
extern const char* jreexec_native_dir;
void* jreexec_acq_vulkan_handle();
void* jreexec_acq_egl_handle();

#endif //POJAVLAUNCHER_JREEXEC_H

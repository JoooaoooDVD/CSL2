#include <jni.h>
#include <android/log.h>
#include <string>
#include <mutex>
#include <cstring>

#include "discord_sdk/include/cdiscord.h"

#define TAG "DiscordSdkBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Use recursive_mutex so that callbacks dispatched during Discord_RunCallbacks
// can safely re-enter native bridge methods without deadlocking.
static std::recursive_mutex g_discord_mutex;
static Discord_Client g_discord_client = {nullptr};
static bool g_discord_initialized = false;
static JavaVM* g_jvm = nullptr;
static jobject g_auth_callback_global = nullptr;
static std::string g_last_code_verifier;

static inline Discord_String make_discord_string(const char* str) {
    Discord_String ds;
    if (str != nullptr) {
        ds.ptr = (uint8_t*)str;
        ds.size = strlen(str);
    } else {
        ds.ptr = nullptr;
        ds.size = 0;
    }
    return ds;
}

static inline std::string to_std_string(Discord_String ds) {
    if (ds.ptr != nullptr && ds.size > 0) {
        return std::string((const char*)ds.ptr, ds.size);
    }
    return std::string();
}

jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_kdt_pojavlaunch_discord_DiscordSdkBridge_nativeInit(
        JNIEnv* env, jclass clazz, jlong applicationId) {
    std::lock_guard<std::recursive_mutex> lock(g_discord_mutex);
    try {
        if (!g_discord_initialized) {
            LOGI("Initializing Discord Social SDK Client for App ID %llu via C API", (unsigned long long)applicationId);
            Discord_Client_Init(&g_discord_client);
            Discord_Client_SetApplicationId(&g_discord_client, static_cast<uint64_t>(applicationId));
            g_discord_initialized = true;
            LOGI("Discord Social SDK Client initialized successfully");
        }
        return JNI_TRUE;
    } catch (const std::exception& e) {
        LOGE("Failed to initialize Discord Social SDK Client: %s", e.what());
        return JNI_FALSE;
    } catch (...) {
        LOGE("Unknown error initializing Discord Social SDK Client");
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_discord_DiscordSdkBridge_nativeShutdown(
        JNIEnv* env, jclass clazz) {
    std::lock_guard<std::recursive_mutex> lock(g_discord_mutex);
    if (g_discord_initialized) {
        try {
            Discord_Client_ClearRichPresence(&g_discord_client);
            Discord_RunCallbacks();
        } catch (...) {}
    }
    if (g_auth_callback_global != nullptr && env != nullptr) {
        env->DeleteGlobalRef(g_auth_callback_global);
        g_auth_callback_global = nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_discord_DiscordSdkBridge_nativeRunCallbacks(
        JNIEnv* env, jclass clazz) {
    std::lock_guard<std::recursive_mutex> lock(g_discord_mutex);
    if (g_discord_initialized) {
        try {
            Discord_RunCallbacks();
        } catch (const std::exception& e) {
            LOGW("Exception in Discord_RunCallbacks: %s", e.what());
        } catch (...) {}
    }
}

static void on_rich_presence_updated(Discord_ClientResult* result, void* userData) {
    if (result != nullptr) {
        if (Discord_ClientResult_Successful(result)) {
            LOGI("Discord_Client_UpdateRichPresence succeeded");
        } else {
            Discord_String errStr = {nullptr, 0};
            Discord_ClientResult_ToString(result, &errStr);
            LOGW("Discord_Client_UpdateRichPresence callback result: %s", to_std_string(errStr).c_str());
            if (errStr.ptr != nullptr) {
                Discord_Free(errStr.ptr);
            }
        }
    }
}

struct FetchConnectedUserData {
    jobject callbackGlobal;
};

static bool extract_user_info(Discord_UserHandle* user,
                              std::string& outUserId,
                              std::string& outUsername,
                              std::string& outDisplayName,
                              std::string& outAvatarUrl) {
    if (user == nullptr) return false;
    uint64_t uid = Discord_UserHandle_Id(user);
    if (uid != 0) {
        outUserId = std::to_string(uid);
    }

    Discord_String nameStr = {nullptr, 0};
    Discord_UserHandle_DisplayName(user, &nameStr);
    outDisplayName = to_std_string(nameStr);
    if (nameStr.ptr != nullptr) Discord_Free(nameStr.ptr);

    Discord_String userStr = {nullptr, 0};
    Discord_UserHandle_Username(user, &userStr);
    outUsername = to_std_string(userStr);
    if (userStr.ptr != nullptr) Discord_Free(userStr.ptr);

    Discord_String avatarStr = {nullptr, 0};
    Discord_UserHandle_AvatarUrl(user, Discord_UserHandle_AvatarType_Png, Discord_UserHandle_AvatarType_Png, &avatarStr);
    outAvatarUrl = to_std_string(avatarStr);
    if (avatarStr.ptr != nullptr) Discord_Free(avatarStr.ptr);

    return !outUserId.empty() || !outUsername.empty() || !outDisplayName.empty();
}

static void on_get_connected_user(Discord_ClientResult* result, Discord_UserHandle* user, void* userData) {
    LOGI("Discord_Client_GetDiscordClientConnectedUser callback invoked");
    auto* cbData = static_cast<FetchConnectedUserData*>(userData);
    if (cbData == nullptr) return;
    jobject cbGlobal = cbData->callbackGlobal;
    delete cbData;

    if (g_jvm == nullptr || cbGlobal == nullptr) return;

    JNIEnv* env = nullptr;
    bool attached = false;
    jint res = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) == 0) {
            attached = true;
        }
    }

    if (env != nullptr) {
        jclass cbClass = env->GetObjectClass(cbGlobal);
        std::string userId, username, displayName, avatarUrl;
        if (result != nullptr && Discord_ClientResult_Successful(result) && user != nullptr &&
            extract_user_info(user, userId, username, displayName, avatarUrl)) {

            jmethodID onSuccessMethod = env->GetMethodID(cbClass, "onSuccess",
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
            if (onSuccessMethod != nullptr) {
                jstring jUserId = env->NewStringUTF(userId.c_str());
                jstring jUsername = env->NewStringUTF(username.c_str());
                jstring jDisplayName = env->NewStringUTF(displayName.c_str());
                jstring jAvatarUrl = env->NewStringUTF(avatarUrl.c_str());

                env->CallVoidMethod(cbGlobal, onSuccessMethod, jUserId, jUsername, jDisplayName, jAvatarUrl);

                env->DeleteLocalRef(jUserId);
                env->DeleteLocalRef(jUsername);
                env->DeleteLocalRef(jDisplayName);
                env->DeleteLocalRef(jAvatarUrl);
            }
        } else {
            jmethodID onErrorMethod = env->GetMethodID(cbClass, "onError", "(Ljava/lang/String;)V");
            if (onErrorMethod != nullptr) {
                std::string errStr = "Failed to fetch connected Discord user";
                if (result != nullptr) {
                    Discord_String resErr = {nullptr, 0};
                    Discord_ClientResult_ToString(result, &resErr);
                    errStr = to_std_string(resErr);
                    if (resErr.ptr != nullptr) Discord_Free(resErr.ptr);
                }
                jstring jErr = env->NewStringUTF(errStr.c_str());
                env->CallVoidMethod(cbGlobal, onErrorMethod, jErr);
                env->DeleteLocalRef(jErr);
            }
        }
        env->DeleteGlobalRef(cbGlobal);
    }

    if (attached && g_jvm != nullptr) {
        g_jvm->DetachCurrentThread();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_discord_DiscordSdkBridge_nativeFetchConnectedUser(
        JNIEnv* env, jclass clazz, jlong applicationId, jobject callback) {
    std::lock_guard<std::recursive_mutex> lock(g_discord_mutex);
    if (!g_discord_initialized || callback == nullptr) {
        if (callback != nullptr) {
            jclass cbClass = env->GetObjectClass(callback);
            jmethodID onErrorMethod = env->GetMethodID(cbClass, "onError", "(Ljava/lang/String;)V");
            if (onErrorMethod != nullptr) {
                jstring errStr = env->NewStringUTF("Discord client not initialized");
                env->CallVoidMethod(callback, onErrorMethod, errStr);
                env->DeleteLocalRef(errStr);
            }
        }
        return;
    }

    auto* data = new FetchConnectedUserData();
    data->callbackGlobal = env->NewGlobalRef(callback);

    Discord_Client_GetDiscordClientConnectedUser(&g_discord_client, static_cast<uint64_t>(applicationId),
            on_get_connected_user, nullptr, data);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_kdt_pojavlaunch_discord_DiscordSdkBridge_nativeUpdateRichPresence(
        JNIEnv* env, jclass clazz,
        jstring jName,
        jstring jDetails,
        jstring jState,
        jstring jLargeImage,
        jstring jLargeText,
        jstring jSmallImage,
        jstring jSmallText,
        jlong startTimestampMs) {
    std::lock_guard<std::recursive_mutex> lock(g_discord_mutex);
    if (!g_discord_initialized) {
        return JNI_FALSE;
    }

    try {
        Discord_Activity activity;
        Discord_Activity_Init(&activity);
        Discord_Activity_SetType(&activity, Discord_ActivityTypes_Playing);
        Discord_Activity_SetSupportedPlatforms(&activity, Discord_ActivityGamePlatforms_Android);

        // Explicitly set the Application / Activity Name to "CS Launcher Pro Beta" (or jName if provided)
        if (jName != nullptr) {
            const char* nameChars = env->GetStringUTFChars(jName, nullptr);
            Discord_String nameStr = make_discord_string(nameChars);
            Discord_Activity_SetName(&activity, nameStr);
            env->ReleaseStringUTFChars(jName, nameChars);
        } else {
            Discord_String defaultName = make_discord_string("CS Launcher Plus");
            Discord_Activity_SetName(&activity, defaultName);
        }

        if (jDetails != nullptr) {
            const char* detailsChars = env->GetStringUTFChars(jDetails, nullptr);
            Discord_String detailsStr = make_discord_string(detailsChars);
            Discord_Activity_SetDetails(&activity, &detailsStr);
            env->ReleaseStringUTFChars(jDetails, detailsChars);
        }

        if (jState != nullptr) {
            const char* stateChars = env->GetStringUTFChars(jState, nullptr);
            Discord_String stateStr = make_discord_string(stateChars);
            Discord_Activity_SetState(&activity, &stateStr);
            env->ReleaseStringUTFChars(jState, stateChars);
        }

        Discord_ActivityAssets assets;
        Discord_ActivityAssets_Init(&assets);
        bool hasAssets = false;

        if (jLargeImage != nullptr) {
            const char* s = env->GetStringUTFChars(jLargeImage, nullptr);
            Discord_String ds = make_discord_string(s);
            Discord_ActivityAssets_SetLargeImage(&assets, &ds);
            env->ReleaseStringUTFChars(jLargeImage, s);
            hasAssets = true;
        }

        if (jLargeText != nullptr) {
            const char* s = env->GetStringUTFChars(jLargeText, nullptr);
            Discord_String ds = make_discord_string(s);
            Discord_ActivityAssets_SetLargeText(&assets, &ds);
            env->ReleaseStringUTFChars(jLargeText, s);
            hasAssets = true;
        }

        if (jSmallImage != nullptr) {
            const char* s = env->GetStringUTFChars(jSmallImage, nullptr);
            Discord_String ds = make_discord_string(s);
            Discord_ActivityAssets_SetSmallImage(&assets, &ds);
            env->ReleaseStringUTFChars(jSmallImage, s);
            hasAssets = true;
        }

        if (jSmallText != nullptr) {
            const char* s = env->GetStringUTFChars(jSmallText, nullptr);
            Discord_String ds = make_discord_string(s);
            Discord_ActivityAssets_SetSmallText(&assets, &ds);
            env->ReleaseStringUTFChars(jSmallText, s);
            hasAssets = true;
        }

        if (hasAssets) {
            Discord_Activity_SetAssets(&activity, &assets);
        }

        if (startTimestampMs > 0) {
            Discord_ActivityTimestamps timestamps;
            Discord_ActivityTimestamps_Init(&timestamps);
            Discord_ActivityTimestamps_SetStart(&timestamps, static_cast<uint64_t>(startTimestampMs / 1000));
            Discord_Activity_SetTimestamps(&activity, &timestamps);
            Discord_ActivityTimestamps_Drop(&timestamps);
        }

        Discord_Client_UpdateRichPresence(&g_discord_client, &activity, on_rich_presence_updated, nullptr, nullptr);

        Discord_ActivityAssets_Drop(&assets);
        Discord_Activity_Drop(&activity);

        Discord_RunCallbacks();
        return JNI_TRUE;
    } catch (const std::exception& e) {
        LOGE("Exception in nativeUpdateRichPresence: %s", e.what());
        return JNI_FALSE;
    } catch (...) {
        LOGE("Unknown error in nativeUpdateRichPresence");
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_discord_DiscordSdkBridge_nativeClearRichPresence(
        JNIEnv* env, jclass clazz) {
    std::lock_guard<std::recursive_mutex> lock(g_discord_mutex);
    if (g_discord_initialized) {
        try {
            Discord_Client_ClearRichPresence(&g_discord_client);
            Discord_RunCallbacks();
            LOGI("Rich presence cleared");
        } catch (...) {}
    }
}

static void on_authorization_complete(Discord_ClientResult* result, Discord_String code, Discord_String redirectUri, void* userData) {
    LOGI("Discord_Client_Authorize callback invoked");

    if (g_jvm == nullptr || g_auth_callback_global == nullptr) return;

    JNIEnv* env = nullptr;
    bool attached = false;
    jint res = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) == 0) {
            attached = true;
        }
    }

    if (env != nullptr && g_auth_callback_global != nullptr) {
        jclass cbClass = env->GetObjectClass(g_auth_callback_global);
        if (result != nullptr && Discord_ClientResult_Successful(result)) {
            jmethodID onSuccessMethod = env->GetMethodID(cbClass, "onSuccess",
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
            std::string codeStrCpp = to_std_string(code);
            std::string redirectStrCpp = to_std_string(redirectUri);
            jstring codeStr = env->NewStringUTF(codeStrCpp.c_str());
            jstring redirectStr = env->NewStringUTF(redirectStrCpp.c_str());
            jstring verifierStr = env->NewStringUTF(g_last_code_verifier.c_str());

            if (onSuccessMethod != nullptr) {
                env->CallVoidMethod(g_auth_callback_global, onSuccessMethod, codeStr, redirectStr, verifierStr);
            }

            env->DeleteLocalRef(codeStr);
            env->DeleteLocalRef(redirectStr);
            env->DeleteLocalRef(verifierStr);
        } else {
            jmethodID onErrorMethod = env->GetMethodID(cbClass, "onError", "(Ljava/lang/String;)V");
            std::string errStrCpp = "Authorization failed";
            if (result != nullptr) {
                Discord_String resErr = {nullptr, 0};
                Discord_ClientResult_ToString(result, &resErr);
                errStrCpp = to_std_string(resErr);
                if (resErr.ptr != nullptr) {
                    Discord_Free(resErr.ptr);
                }
            }
            jstring errStr = env->NewStringUTF(errStrCpp.c_str());
            if (onErrorMethod != nullptr) {
                env->CallVoidMethod(g_auth_callback_global, onErrorMethod, errStr);
            }
            env->DeleteLocalRef(errStr);
        }
        env->DeleteGlobalRef(g_auth_callback_global);
        g_auth_callback_global = nullptr;
    }

    // Do NOT free code.ptr or redirectUri.ptr here!
    // The partner SDK's AuthenticationClientCallback_onAuthorizationComplete JNI implementation
    // handles deletion of these strings after the callback returns.

    if (attached && g_jvm != nullptr) {
        g_jvm->DetachCurrentThread();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_discord_DiscordSdkBridge_nativeAuthorize(
        JNIEnv* env, jclass clazz, jlong clientId, jstring jScopes, jobject callback) {
    std::lock_guard<std::recursive_mutex> lock(g_discord_mutex);
    if (!g_discord_initialized) {
        if (callback != nullptr) {
            jclass cbClass = env->GetObjectClass(callback);
            jmethodID onErrorMethod = env->GetMethodID(cbClass, "onError", "(Ljava/lang/String;)V");
            jstring errStr = env->NewStringUTF("Discord client not initialized");
            env->CallVoidMethod(callback, onErrorMethod, errStr);
            env->DeleteLocalRef(errStr);
        }
        return;
    }

    if (g_auth_callback_global != nullptr) {
        env->DeleteGlobalRef(g_auth_callback_global);
        g_auth_callback_global = nullptr;
    }
    if (callback != nullptr) {
        g_auth_callback_global = env->NewGlobalRef(callback);
    }

    try {
        Discord_AuthorizationArgs args;
        Discord_AuthorizationArgs_Init(&args);
        Discord_AuthorizationArgs_SetClientId(&args, static_cast<uint64_t>(clientId));

        // Default to "identify" scope so user profile (name, avatar) can be retrieved
        std::string scopesToUse = "identify";
        if (jScopes != nullptr) {
            const char* scopesChars = env->GetStringUTFChars(jScopes, nullptr);
            if (scopesChars != nullptr && strlen(scopesChars) > 0) {
                scopesToUse = scopesChars;
            }
            env->ReleaseStringUTFChars(jScopes, scopesChars);
        }
        Discord_String scopesStr = make_discord_string(scopesToUse.c_str());
        Discord_AuthorizationArgs_SetScopes(&args, scopesStr);

        // Generate and record PKCE code challenge and verifier
        Discord_AuthorizationCodeVerifier verifier;
        Discord_Client_CreateAuthorizationCodeVerifier(&g_discord_client, &verifier);

        Discord_AuthorizationCodeChallenge challenge;
        Discord_AuthorizationCodeVerifier_Challenge(&verifier, &challenge);
        Discord_AuthorizationArgs_SetCodeChallenge(&args, &challenge);

        Discord_String verifierStrNative = {nullptr, 0};
        Discord_AuthorizationCodeVerifier_Verifier(&verifier, &verifierStrNative);
        g_last_code_verifier = to_std_string(verifierStrNative);
        if (verifierStrNative.ptr != nullptr) {
            Discord_Free(verifierStrNative.ptr);
        }

        LOGI("Calling official Discord_Client_Authorize via C API for ClientId %llu with PKCE challenge", (unsigned long long)clientId);
        Discord_Client_Authorize(&g_discord_client, &args, on_authorization_complete, nullptr, nullptr);

        // Keep challenge and verifier alive until after Discord_Client_Authorize returns!
        Discord_AuthorizationCodeChallenge_Drop(&challenge);
        Discord_AuthorizationCodeVerifier_Drop(&verifier);
        Discord_AuthorizationArgs_Drop(&args);
    } catch (const std::exception& e) {
        LOGE("Exception in nativeAuthorize: %s", e.what());
    } catch (...) {
        LOGE("Unknown error in nativeAuthorize");
    }
}

struct TokenExchangeData {
    jobject callbackGlobal;
};

static void on_token_exchange(Discord_ClientResult* result,
                              Discord_String accessToken,
                              Discord_String refreshToken,
                              Discord_AuthorizationTokenType tokenType,
                              int32_t expiresIn,
                              Discord_String scopes,
                              void* userData) {
    LOGI("Discord_Client_GetToken callback invoked");
    auto* data = static_cast<TokenExchangeData*>(userData);
    if (data == nullptr) return;
    jobject cbGlobal = data->callbackGlobal;
    delete data;

    if (g_jvm == nullptr || cbGlobal == nullptr) return;

    JNIEnv* env = nullptr;
    bool attached = false;
    jint res = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) == 0) {
            attached = true;
        }
    }

    if (env != nullptr) {
        jclass cbClass = env->GetObjectClass(cbGlobal);
        if (result != nullptr && Discord_ClientResult_Successful(result)) {
            jmethodID onSuccessMethod = env->GetMethodID(cbClass, "onSuccess",
                    "(Ljava/lang/String;Ljava/lang/String;)V");
            std::string accessStrCpp = to_std_string(accessToken);
            std::string refreshStrCpp = to_std_string(refreshToken);
            jstring jAccess = env->NewStringUTF(accessStrCpp.c_str());
            jstring jRefresh = env->NewStringUTF(refreshStrCpp.c_str());

            if (onSuccessMethod != nullptr) {
                env->CallVoidMethod(cbGlobal, onSuccessMethod, jAccess, jRefresh);
            }
            env->DeleteLocalRef(jAccess);
            env->DeleteLocalRef(jRefresh);
        } else {
            jmethodID onErrorMethod = env->GetMethodID(cbClass, "onError", "(Ljava/lang/String;)V");
            std::string errStr = "Token exchange failed";
            if (result != nullptr) {
                Discord_String resErr = {nullptr, 0};
                Discord_ClientResult_ToString(result, &resErr);
                errStr = to_std_string(resErr);
                if (resErr.ptr != nullptr) Discord_Free(resErr.ptr);
            }
            jstring jErr = env->NewStringUTF(errStr.c_str());
            if (onErrorMethod != nullptr) {
                env->CallVoidMethod(cbGlobal, onErrorMethod, jErr);
            }
            env->DeleteLocalRef(jErr);
        }
        env->DeleteGlobalRef(cbGlobal);
    }

    if (accessToken.ptr != nullptr) Discord_Free(accessToken.ptr);
    if (refreshToken.ptr != nullptr) Discord_Free(refreshToken.ptr);
    if (scopes.ptr != nullptr) Discord_Free(scopes.ptr);

    if (attached && g_jvm != nullptr) {
        g_jvm->DetachCurrentThread();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_discord_DiscordSdkBridge_nativeGetToken(
        JNIEnv* env, jclass clazz,
        jlong applicationId,
        jstring jCode,
        jstring jVerifier,
        jstring jRedirectUri,
        jobject callback) {
    std::lock_guard<std::recursive_mutex> lock(g_discord_mutex);
    if (!g_discord_initialized || callback == nullptr) {
        if (callback != nullptr) {
            jclass cbClass = env->GetObjectClass(callback);
            jmethodID onErrorMethod = env->GetMethodID(cbClass, "onError", "(Ljava/lang/String;)V");
            jstring errStr = env->NewStringUTF("Discord client not initialized");
            env->CallVoidMethod(callback, onErrorMethod, errStr);
            env->DeleteLocalRef(errStr);
        }
        return;
    }

    std::string codeStr;
    if (jCode != nullptr) {
        const char* cChars = env->GetStringUTFChars(jCode, nullptr);
        if (cChars != nullptr) {
            codeStr = cChars;
            env->ReleaseStringUTFChars(jCode, cChars);
        }
    }

    std::string verifierStr;
    if (jVerifier != nullptr) {
        const char* vChars = env->GetStringUTFChars(jVerifier, nullptr);
        if (vChars != nullptr) {
            verifierStr = vChars;
            env->ReleaseStringUTFChars(jVerifier, vChars);
        }
    }

    std::string redirectStr;
    if (jRedirectUri != nullptr) {
        const char* rChars = env->GetStringUTFChars(jRedirectUri, nullptr);
        if (rChars != nullptr) {
            redirectStr = rChars;
            env->ReleaseStringUTFChars(jRedirectUri, rChars);
        }
    }
    if (redirectStr.empty()) {
        redirectStr = "discord-" + std::to_string(applicationId) + ":/authorize/callback";
    }

    Discord_String dsCode = make_discord_string(codeStr.c_str());
    Discord_String dsVerifier = make_discord_string(verifierStr.c_str());
    Discord_String dsRedirect = make_discord_string(redirectStr.c_str());

    auto* data = new TokenExchangeData();
    data->callbackGlobal = env->NewGlobalRef(callback);

    LOGI("Calling Discord_Client_GetToken for Application %llu with PKCE verifier", (unsigned long long)applicationId);
    Discord_Client_GetToken(&g_discord_client, static_cast<uint64_t>(applicationId),
            dsCode, dsVerifier, dsRedirect, on_token_exchange, nullptr, data);
}

// ----------------------------------------------------------------------------
// Aliases for com.craftstudio.cslauncher.discord.DiscordSdkBridge
// ----------------------------------------------------------------------------

extern "C" JNIEXPORT jboolean JNICALL
Java_com_craftstudio_cslauncher_discord_DiscordSdkBridge_nativeInit(
        JNIEnv* env, jclass clazz, jlong applicationId) {
    return Java_net_kdt_pojavlaunch_discord_DiscordSdkBridge_nativeInit(env, clazz, applicationId);
}

extern "C" JNIEXPORT void JNICALL
Java_com_craftstudio_cslauncher_discord_DiscordSdkBridge_nativeShutdown(
        JNIEnv* env, jclass clazz) {
    Java_net_kdt_pojavlaunch_discord_DiscordSdkBridge_nativeShutdown(env, clazz);
}

extern "C" JNIEXPORT void JNICALL
Java_com_craftstudio_cslauncher_discord_DiscordSdkBridge_nativeRunCallbacks(
        JNIEnv* env, jclass clazz) {
    Java_net_kdt_pojavlaunch_discord_DiscordSdkBridge_nativeRunCallbacks(env, clazz);
}

extern "C" JNIEXPORT void JNICALL
Java_com_craftstudio_cslauncher_discord_DiscordSdkBridge_nativeFetchConnectedUser(
        JNIEnv* env, jclass clazz, jlong applicationId, jobject callback) {
    Java_net_kdt_pojavlaunch_discord_DiscordSdkBridge_nativeFetchConnectedUser(env, clazz, applicationId, callback);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_craftstudio_cslauncher_discord_DiscordSdkBridge_nativeUpdateRichPresence(
        JNIEnv* env, jclass clazz,
        jstring jName,
        jstring jDetails,
        jstring jState,
        jstring jLargeImage,
        jstring jLargeText,
        jstring jSmallImage,
        jstring jSmallText,
        jlong startTimestampMs) {
    return Java_net_kdt_pojavlaunch_discord_DiscordSdkBridge_nativeUpdateRichPresence(
            env, clazz, jName, jDetails, jState, jLargeImage, jLargeText, jSmallImage, jSmallText, startTimestampMs);
}

extern "C" JNIEXPORT void JNICALL
Java_com_craftstudio_cslauncher_discord_DiscordSdkBridge_nativeClearRichPresence(
        JNIEnv* env, jclass clazz) {
    Java_net_kdt_pojavlaunch_discord_DiscordSdkBridge_nativeClearRichPresence(env, clazz);
}

extern "C" JNIEXPORT void JNICALL
Java_com_craftstudio_cslauncher_discord_DiscordSdkBridge_nativeAuthorize(
        JNIEnv* env, jclass clazz, jlong clientId, jstring jScopes, jobject callback) {
    Java_net_kdt_pojavlaunch_discord_DiscordSdkBridge_nativeAuthorize(env, clazz, clientId, jScopes, callback);
}

extern "C" JNIEXPORT void JNICALL
Java_com_craftstudio_cslauncher_discord_DiscordSdkBridge_nativeGetToken(
        JNIEnv* env, jclass clazz,
        jlong applicationId,
        jstring jCode,
        jstring jVerifier,
        jstring jRedirectUri,
        jobject callback) {
    Java_net_kdt_pojavlaunch_discord_DiscordSdkBridge_nativeGetToken(
            env, clazz, applicationId, jCode, jVerifier, jRedirectUri, callback);
}


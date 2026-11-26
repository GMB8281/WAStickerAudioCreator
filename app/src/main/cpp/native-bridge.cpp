#include <jni.h>

extern "C" {
char* wa_root_status();
char* wa_env_check(const char* req);
char* wa_inject_pack(const char* req);
char* wa_inject_favorite(const char* req);
char* wa_remove_pack(const char* req);
void  wa_free_string(char* p);
}

static jstring wrap(JNIEnv* env, char* res) {
    jstring s = env->NewStringUTF(res ? res : "{\"ok\":false,\"error\":\"null native result\"}");
    wa_free_string(res);
    return s;
}

static jstring call1(JNIEnv* env, jstring req, char* (*fn)(const char*)) {
    const char* c = env->GetStringUTFChars(req, nullptr);
    char* r = fn(c ? c : "");
    env->ReleaseStringUTFChars(req, c);
    return wrap(env, r);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_marinov_wastickeraudiocreator_whatsapp_WhatsAppBridge_nativeRootStatus(JNIEnv* env, jobject) {
    return wrap(env, wa_root_status());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_marinov_wastickeraudiocreator_whatsapp_WhatsAppBridge_nativeEnvCheck(JNIEnv* env, jobject, jstring req) {
    return call1(env, req, wa_env_check);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_marinov_wastickeraudiocreator_whatsapp_WhatsAppBridge_nativeInjectPack(JNIEnv* env, jobject, jstring req) {
    return call1(env, req, wa_inject_pack);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_marinov_wastickeraudiocreator_whatsapp_WhatsAppBridge_nativeInjectFavorite(JNIEnv* env, jobject, jstring req) {
    return call1(env, req, wa_inject_favorite);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_marinov_wastickeraudiocreator_whatsapp_WhatsAppBridge_nativeRemovePack(JNIEnv* env, jobject, jstring req) {
    return call1(env, req, wa_remove_pack);
}

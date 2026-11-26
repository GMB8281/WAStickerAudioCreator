package com.marinov.wastickeraudiocreator.whatsapp

/** JNI bridge: Kotlin -> C++ (native-bridge.cpp) -> Rust (libwa_core.a). */
object WhatsAppBridge {

    init {
        System.loadLibrary("wa_bridge")
    }

    external fun nativeRootStatus(): String
    external fun nativeEnvCheck(requestJson: String): String
    external fun nativeInjectPack(requestJson: String): String
    external fun nativeInjectFavorite(requestJson: String): String
    external fun nativeRemovePack(requestJson: String): String
}

#include <jni.h>

extern "C" JNIEXPORT jstring JNICALL
Java_chat_vinlagon_secret_agent_NativeRuntime_backendInfo(JNIEnv* env, jobject /* thiz */) {
    return env->NewStringUTF("JNI bridge ready; llama.cpp backend is not bundled in this starter build.");
}

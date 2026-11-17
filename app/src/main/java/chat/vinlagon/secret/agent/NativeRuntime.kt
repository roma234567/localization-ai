package chat.vinlagon.secret.agent

/** Thin JNI entry point. A production GGUF build must link llama.cpp separately per ABI. */
object NativeRuntime {
    init { System.loadLibrary("vinlagon_native") }
    external fun backendInfo(): String
}

#include <jni>
#include "co_usc_ulord_CryptoHelloContext.h"
#include <hello/PoW.h>

JNIEXPORT void JNICALL Java_co_usc_ulord_CryptoHelloContext_helloHash
    (JNIEnv *env, jclass class, jbyteArray mess, jlong messLen, jbyteArray output) {
        helloHash(mess, messLen, output);
}
#include "co_usc_ulord_hello_CryptoHelloContext.h"
#include "PoW.h"
#include "common.h"

JNIEXPORT void JNICALL Java_co_usc_ulord_hello_CryptoHelloContext_helloHash
  (JNIEnv *jenv, jclass jclass, jstring jmess, jlong jsize, jstring joutput) {

    // Step 1: Convert the JNI String (jstring) into C-String (char*)
    const unsigned char *cmess = (*jenv)->GetStringUTFChars(jenv, jmess, NULL);

    unsigned char *mess = malloc((strlen(cmess) + 1) * sizeof(unsigned char));
    strcpy(mess, cmess);

    long size = jsize;

    if (NULL == cmess) {
        printf("Input message is empty");
        return;
    }

    char coutput[OUTPUT_LEN];
    helloHash(mess, size, coutput);

    printf("Hash output %s", coutput);

    joutput = (*jenv)->NewStringUTF(jenv, coutput);   // Convert char* to JNI String

    (*jenv)->ReleaseStringUTFChars(jenv, jmess, cmess);  // release resources
    (*jenv)->ReleaseStringUTFChars(jenv, joutput, coutput);  // release resources
    free(mess);
}
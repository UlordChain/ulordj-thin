#include "co_usc_ulord_hello_CryptoHelloContext.h"
#include "PoW.h"
#include "oneWayFunction.h"
#include "common.h"

//view_data_u8(const char *mess, uint8_t *data, uint32_t len)
//helloHash(uint8_t *mess, uint32_t messLen, uint8_t output[OUTPUT_LEN])
JNIEXPORT void JNICALL Java_co_usc_ulord_hello_CryptoHelloContext_helloHash
  (JNIEnv *jenv, jclass jclass, jbyteArray jmess, jlong jsz, jbyteArray joutput) {

    int i;
    //jsize jmess_size = (*jenv)->GetArrayLength(jenv, jmess);
    jbyte* const jmess_carray = (*jenv)->GetByteArrayElements(jenv, jmess, 0);
    unsigned char* mess = (unsigned char*)jmess_carray;
    //view_data_u8("Input Data: ", mess, jsz);

    unsigned char coutput[OUTPUT_LEN];
    for( i = 0; i < OUTPUT_LEN - 1; ++i)
        coutput[i] = '0';

    initOneWayFunction();
    helloHash(mess, jsz, coutput);
    (*jenv)->SetByteArrayRegion(jenv, joutput, 0, OUTPUT_LEN, (jbyte*)coutput);
    //view_data_u8("helloHash: ", coutput, OUTPUT_LEN);
    (*jenv)->ReleaseByteArrayElements(jenv, jmess, jmess_carray, 0);
}
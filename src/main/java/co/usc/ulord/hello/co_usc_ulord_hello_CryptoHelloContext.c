#include "co_usc_ulord_hello_CryptoHelloContext.h"
#include "PoW.h"
#include "common.h"

JNIEXPORT void JNICALL Java_co_usc_ulord_hello_CryptoHelloContext_helloHash
  (JNIEnv *jenv, jclass jclass, jstring jmess, jlong jsize, jbyteArray joutput) {

    //Convert the JNI String (jstring) into C-String (char*)
    //const unsigned char *cmess = (*jenv)->GetStringUTFChars(jenv, jmess, NULL);
    unsigned char *mess = malloc((strlen((*jenv)->GetStringUTFChars(jenv, jmess, NULL)) + 1) * sizeof(unsigned char));

    if (NULL == mess) {
        printf("Input message is empty\n");
        return;
    }
    strcpy(mess, (*jenv)->GetStringUTFChars(jenv, jmess, NULL));

    printf("C Input Message: %s\n", mess);

    unsigned char coutput[OUTPUT_LEN];
    helloHash(mess, jsize, coutput);

    printf("C Hash output: %s, of size: %lu\n", coutput, strlen(coutput));

    joutput = (*jenv)->NewStringUTF(jenv, coutput);   // Convert char* to JNI String

//    const unsigned char *cmess = (*jenv)->GetStringUTFChars(jenv, joutput, JNI_FALSE);
//    printf("jstring output: %s, of size: %lu\n", cmess, strlen(cmess));

//    int j;
//    for(j = 0; j < strlen(cmess); ++j) {
//        char data1 = mess[j];
//        char data2 = cmess[j];
//        int i;
//        for (i = 0; i < 8; i++) {
//            printf("%d", !!((data1 << i) & 0x80));
//        }
//        printf(" ");
//        for (i = 0; i < 8; i++) {
//                    printf("%d", !!((data2 << i) & 0x80));
//                }
//        printf("\n");
//    }

//    (*jenv)->ReleaseStringUTFChars(jenv, jmess, cmess);  // release resources
//    (*jenv)->ReleaseStringUTFChars(jenv, joutput, coutput);  // release resources
    free(mess);
}
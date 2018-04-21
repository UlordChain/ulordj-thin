#include "co_usc_ulord_hello_CryptoHelloContext.h"
#include "PoW.h"
#include "oneWayFunction.h"
#include "common.h"

JNIEXPORT void JNICALL Java_co_usc_ulord_hello_CryptoHelloContext_helloHash
  (JNIEnv *jenv, jclass jclass, jbyteArray jmess, jlong jsz, jbyteArray joutput) {

    int i;
    jsize jmess_size = (*jenv)->GetArrayLength(jenv, jmess);

    jbyte* const jmess_carray = (*jenv)->GetByteArrayElements(jenv, jmess, 0);
    unsigned char* mess = (unsigned char*)jmess_carray;
    printf("Input Data of %d size: ", jmess_size);
    for(i = 0; i < jmess_size; i++){
        printf("%d ", *mess);
        mess++;
    }
    printf("\n");

    unsigned char coutput[OUTPUT_LEN];
    initOneWayFunction();
    helloHash(mess, jmess_size, coutput);
    (*jenv)->SetByteArrayRegion(jenv, joutput, 0, OUTPUT_LEN, (jbyte*)coutput);
    view_data_u8("Input Data: ", coutput, OUTPUT_LEN);
    (*jenv)->ReleaseByteArrayElements(jenv, jmess, jmess_carray, 0);


/*
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

    printf("mess size %lu", strlen(mess));
    printf("coutput size %lu", strlen(coutput));
    view_data_u8("PoW 2", mess, jsize);
    view_data_u8("PoW 2", coutput, strlen(coutput));
    printf("%-18s\t", "PoW 2");
	for (uint32_t i = 0; i < jsize; ++i)
		printf("%.2x", mess[i]);
	printf("\n");

	printf("%-18s\t", "PoW 2");
    	for (uint32_t i = 0; i < OUTPUT_LEN; ++i)
    		printf("%.2x", coutput[i]);
    	printf("\n");

    joutput = (*jenv)->NewStringUTF(jenv, coutput);   // Convert char* to JNI String
*/

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
    //free(mess);
}
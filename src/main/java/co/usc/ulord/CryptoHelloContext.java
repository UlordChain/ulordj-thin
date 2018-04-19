package co.usc.ulord;

/**
 * This class holds the context reference used in native methods to handle Hashing operations.
 */
public class CryptoHelloContext {
    static {
        System.loadLibrary("hello/CryotoHello");
    }

    /**
    * A native method to handle Ulord hashing algorithm
    * void helloHash(uint8_t *mess, uint32_t messLen, uint8_t output[OUTPUT_LEN])
    * @param mess input message to be hashed
    * @param messLen the length of message
    * @param outPut  the result of hashing
    */
    public static native void helloHash(byte[] mess, long messLen, byte[] outPut);
}

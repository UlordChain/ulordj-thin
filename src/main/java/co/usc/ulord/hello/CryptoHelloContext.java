//Copyright 2016 - 2018 Ulord developer team.

package co.usc.ulord.hello;

/**
 * This class holds the context reference used in native methods to handle Hashing operations.
 */
public class CryptoHelloContext {
    static {
        try {
            //System.out.println(System.getProperty("java.library.path"));
            System.loadLibrary("CryptoHello");
        } catch (UnsatisfiedLinkError e) {
            System.err.println("Native code library failed to load.\n" + e);
            System.exit(1);
        }

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

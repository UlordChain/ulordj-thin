/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package co.usc.ulordj.crypto;

import co.usc.ulordj.core.UldECKey;
import co.usc.ulordj.core.UldTransaction;
import co.usc.ulordj.core.VerificationException;
import co.usc.ulordj.core.UldTransaction.SigHash;
import com.google.common.base.Preconditions;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;

/**
 * A TransactionSignature wraps an {@link UldECKey.ECDSASignature} and adds methods for handling
 * the additional SIGHASH mode byte that is used.
 */
public class TransactionSignature extends UldECKey.ECDSASignature {
    /**
     * A byte that controls which parts of a transaction are signed. This is exposed because signatures
     * parsed off the wire may have sighash flags that aren't "normal" serializations of the enum values.
     * Because Bitcoin Core works via bit testing, we must not lose the exact value when round-tripping
     * otherwise we'll fail to verify signature hashes.
     */
    public final int sighashFlags;

    /** Constructs a signature with the given components and SIGHASH_ALL. */
    public TransactionSignature(BigInteger r, BigInteger s) {
        this(r, s, UldTransaction.SigHash.ALL.value);
    }

    /** Constructs a signature with the given components and raw sighash flag bytes (needed for rule compatibility). */
    public TransactionSignature(BigInteger r, BigInteger s, int sighashFlags) {
        super(r, s);
        this.sighashFlags = sighashFlags;
    }

    /** Constructs a transaction signature based on the ECDSA signature. */
    public TransactionSignature(UldECKey.ECDSASignature signature, UldTransaction.SigHash mode, boolean anyoneCanPay) {
        super(signature.r, signature.s);
        sighashFlags = calcSigHashValue(mode, anyoneCanPay);
    }

    /**
     * Returns a dummy invalid signature whose R/S values are set such that they will take up the same number of
     * encoded bytes as a real signature. This can be useful when you want to fill out a transaction to be of the
     * right size (e.g. for fee calculations) but don't have the requisite signing key yet and will fill out the
     * real signature later.
     */
    public static TransactionSignature dummy() {
        BigInteger val = UldECKey.HALF_CURVE_ORDER;
        return new TransactionSignature(val, val);
    }

    /** Calculates the byte used in the protocol to represent the combination of mode and anyoneCanPay. */
    public static int calcSigHashValue(UldTransaction.SigHash mode, boolean anyoneCanPay) {
        Preconditions.checkArgument(SigHash.ALL == mode || SigHash.NONE == mode || SigHash.SINGLE == mode); // enforce compatibility since this code was made before the SigHash enum was updated
        int sighashFlags = mode.value;
        if (anyoneCanPay)
            sighashFlags |= UldTransaction.SigHash.ANYONECANPAY.value;
        return sighashFlags;
    }

    /**
     * Returns true if the given signature is has canonical encoding, and will thus be accepted as standard by
     * Bitcoin Core. DER and the SIGHASH encoding allow for quite some flexibility in how the same structures
     * are encoded, and this can open up novel attacks in which a man in the middle takes a transaction and then
     * changes its signature such that the transaction hash is different but it's still valid. This can confuse wallets
     * and generally violates people's mental model of how Bitcoin should work, thus, non-canonical signatures are now
     * not relayed by default.
     */
    public static boolean isEncodingCanonical(byte[] signature) {
        // See Bitcoin Core's IsCanonicalSignature, https://bitcointalk.org/index.php?topic=8392.msg127623#msg127623
        // A canonical signature exists of: <30> <total len> <02> <len R> <R> <02> <len S> <S> <hashtype>
        // Where R and S are not negative (their first byte has its highest bit not set), and not
        // excessively padded (do not start with a 0 byte, unless an otherwise negative number follows,
        // in which case a single 0 byte is necessary and even required).
        if (signature.length < 9 || signature.length > 73)
            return false;

        int hashType = (signature[signature.length-1] & 0xff) & ~UldTransaction.SigHash.ANYONECANPAY.value; // mask the byte to prevent sign-extension hurting us
        if (hashType < UldTransaction.SigHash.ALL.value || hashType > UldTransaction.SigHash.SINGLE.value)
            return false;

        //                   "wrong type"                  "wrong length marker"
        if ((signature[0] & 0xff) != 0x30 || (signature[1] & 0xff) != signature.length-3)
            return false;

        int lenR = signature[3] & 0xff;
        if (5 + lenR >= signature.length || lenR == 0)
            return false;
        int lenS = signature[5+lenR] & 0xff;
        if (lenR + lenS + 7 != signature.length || lenS == 0)
            return false;

        //    R value type mismatch          R value negative
        if (signature[4-2] != 0x02 || (signature[4] & 0x80) == 0x80)
            return false;
        if (lenR > 1 && signature[4] == 0x00 && (signature[4+1] & 0x80) != 0x80)
            return false; // R value excessively padded

        //       S value type mismatch                    S value negative
        if (signature[6 + lenR - 2] != 0x02 || (signature[6 + lenR] & 0x80) == 0x80)
            return false;
        if (lenS > 1 && signature[6 + lenR] == 0x00 && (signature[6 + lenR + 1] & 0x80) != 0x80)
            return false; // S value excessively padded

        return true;
    }

    public boolean anyoneCanPay() {
        return (sighashFlags & UldTransaction.SigHash.ANYONECANPAY.value) != 0;
    }

    public UldTransaction.SigHash sigHashMode() {
        final int mode = sighashFlags & 0x1f;
        if (mode == UldTransaction.SigHash.NONE.value)
            return UldTransaction.SigHash.NONE;
        else if (mode == UldTransaction.SigHash.SINGLE.value)
            return UldTransaction.SigHash.SINGLE;
        else
            return UldTransaction.SigHash.ALL;
    }

    /**
     * What we get back from the signer are the two components of a signature, r and s. To get a flat byte stream
     * of the type used by Bitcoin we have to encode them using DER encoding, which is just a way to pack the two
     * components into a structure, and then we append a byte to the end for the sighash flags.
     */
    public byte[] encodeToUlord() {
        try {
            ByteArrayOutputStream bos = derByteStream();
            bos.write(sighashFlags);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);  // Cannot happen.
        }
    }

    @Override
    public UldECKey.ECDSASignature toCanonicalised() {
        return new TransactionSignature(super.toCanonicalised(), sigHashMode(), anyoneCanPay());
    }

    /**
     * Returns a decoded signature.
     *
     * @param requireCanonicalEncoding if the encoding of the signature must
     * be canonical.
     * @throws RuntimeException if the signature is invalid or unparseable in some way.
     * @deprecated use {@link #decodeFromUlord(byte[], boolean, boolean} instead}.
     */
    @Deprecated
    public static TransactionSignature decodeFromUlord(byte[] bytes,
                                                       boolean requireCanonicalEncoding) throws VerificationException {
        return decodeFromUlord(bytes, requireCanonicalEncoding, false);
    }

    /**
     * Returns a decoded signature.
     *
     * @param requireCanonicalEncoding if the encoding of the signature must
     * be canonical.
     * @param requireCanonicalSValue if the S-value must be canonical (below half
     * the order of the curve).
     * @throws RuntimeException if the signature is invalid or unparseable in some way.
     */
    public static TransactionSignature decodeFromUlord(byte[] bytes,
                                                       boolean requireCanonicalEncoding,
                                                       boolean requireCanonicalSValue) throws VerificationException {
        // Bitcoin encoding is DER signature + sighash byte.
        if (requireCanonicalEncoding && !isEncodingCanonical(bytes))
            throw new VerificationException("Signature encoding is not canonical.");
        UldECKey.ECDSASignature sig;
        try {
            sig = UldECKey.ECDSASignature.decodeFromDER(bytes);
        } catch (IllegalArgumentException e) {
            throw new VerificationException("Could not decode DER", e);
        }
        if (requireCanonicalSValue && !sig.isCanonical())
            throw new VerificationException("S-value is not canonical.");

        // In Bitcoin, any value of the final byte is valid, but not necessarily canonical. See javadocs for
        // isEncodingCanonical to learn more about this. So we must store the exact byte found.
        return new TransactionSignature(sig.r, sig.s, bytes[bytes.length - 1]);
    }
}

package co.usc.ulordj.core;

import co.usc.ulordj.crypto.TransactionSignature;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public class TransactionWitness {
    static TransactionWitness empty = new TransactionWitness(0);

    public static TransactionWitness getEmpty() {
        return empty;
    }

    private List<byte[]> pushes;

    public TransactionWitness(int pushCount) {
        pushes = new ArrayList<byte[]>(Math.min(pushCount, Utils.MAX_INITIAL_ARRAY_LENGTH));
    }

    public byte[] getPush(int i) {
        return pushes.get(i);
    }

    public int getPushCount() {
        return pushes.size();
    }

    public void setPush(int i, byte[] value) {
        while (i >= pushes.size()) {
            pushes.add(new byte[]{});
        }
        pushes.set(i, value);
    }

    /**
     * Create a witness that can redeem a pay-to-witness-pubkey-hash output.
     */
    public static TransactionWitness createWitness(@Nullable final TransactionSignature signature, final UldECKey pubKey) {
        final byte[] sigBytes = signature != null ? signature.encodeToUlord() : new byte[]{};
        final byte[] pubKeyBytes = pubKey.getPubKey();
        final TransactionWitness witness = new TransactionWitness(2);
        witness.setPush(0, sigBytes);
        witness.setPush(1, pubKeyBytes);
        return witness;
    }

    public byte[] getScriptBytes() {
        if (getPushCount() == 0)
            return new byte[0];
        else
            return pushes.get(pushes.size() - 1);
    }
}


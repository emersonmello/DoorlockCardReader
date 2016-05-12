package uk.ac.ncl.cs.mello.doorlockcardreader;


import android.nfc.tech.IsoDep;

import java.io.IOException;
import java.security.SecureRandom;

public class IsoDepTransceiver implements Runnable{

    public interface OnMessageReceived {
        void onMessage(byte[] message);
        void onError(Exception exception);
    }

    private static final String DOORLOCK_AID = "F222222222";
    // Format: [Class | Instruction | Parameter 1 | Parameter 2]
    private static final String SELECT_APDU_HEADER = "00A40400";
    // "OK" status word sent in response to SELECT AID command (0x9000)
    private static final byte[] SELECT_OK_SW = {(byte) 0x90, (byte) 0x00};
    private static final byte[] CLA_INS_P1_P2 = { 0x00, (byte)0xA4, 0x04, 0x00 };
    //private static final byte[] AID_ANDROID = BuildSelectApdu(DOORLOCK_AID);//{ (byte)0xF0, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06 };
    private static final byte[] AID_ANDROID = { (byte)0xF0, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06 };
    private IsoDep isoDep;
    private OnMessageReceived onMessageReceived;

    private SecureRandom secureRandom = new SecureRandom();

    public IsoDepTransceiver(IsoDep isoDep, OnMessageReceived onMessageReceived) {
        this.isoDep = isoDep;
        this.onMessageReceived = onMessageReceived;
    }


    private byte[] createSelectAidApdu(byte[] aid) {
        byte[] result = new byte[6 + aid.length];
        System.arraycopy(CLA_INS_P1_P2, 0, result, 0, CLA_INS_P1_P2.length);
        result[4] = (byte)aid.length;
        System.arraycopy(aid, 0, result, 5, aid.length);
        result[result.length - 1] = 0;
        return result;
    }

    @Override
    public void run() {
        int messageCounter = 0;
        try {
            isoDep.connect();
            byte[] response = isoDep.transceive(createSelectAidApdu(AID_ANDROID));
            onMessageReceived.onMessage(response);
            String challenge = new String(response) + " "+ secureRandom.nextInt();
            while (isoDep.isConnected() && !Thread.interrupted()) {
                response = isoDep.transceive(challenge.getBytes());
                onMessageReceived.onMessage(response);
            }
            isoDep.close();
        }
        catch (IOException e) {
            onMessageReceived.onError(e);
        }
    }



    /**
     * Build APDU for SELECT AID command. This command indicates which service a reader is
     * interested in communicating with. See ISO 7816-4.
     *
     * @param aid Application ID (AID) to select
     * @return APDU for SELECT AID command
     */
    public static byte[] BuildSelectApdu(String aid) {
        // Format: [CLASS | INSTRUCTION | PARAMETER 1 | PARAMETER 2 | LENGTH | DATA]
        return HexStringToByteArray(SELECT_APDU_HEADER + String.format("%02X", aid.length() / 2) + aid);
    }

    /**
     * Utility class to convert a byte array to a hexadecimal string.
     *
     * @param bytes Bytes to convert
     * @return String, containing hexadecimal representation.
     */
    public static String ByteArrayToHexString(byte[] bytes) {
        final char[] hexArray = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'};
        char[] hexChars = new char[bytes.length * 2];
        int v;
        for (int j = 0; j < bytes.length; j++) {
            v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

    /**
     * Utility class to convert a hexadecimal string to a byte string.
     * <p/>
     * <p>Behavior with input strings containing non-hexadecimal characters is undefined.
     *
     * @param s String containing hexadecimal characters to convert
     * @return Byte array generated from input
     */
    public static byte[] HexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

}

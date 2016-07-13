package uk.ac.ncl.cs.mello.doorlockcardreader;

import android.content.Intent;
import android.content.SharedPreferences;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.gson.Gson;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static uk.ac.ncl.cs.mello.doorlockcardreader.DoorProtocol.*;

public class MainActivity extends AppCompatActivity implements NfcAdapter.ReaderCallback {

    public static int READER_FLAGS =
            NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK;

    private NfcAdapter nfcAdapter;
    private ListView listView;
    private IsoDepAdapter isoDepAdapter;
    private CardCommunicationTask mCardCommunicationTask = null;
    private SharedPreferences mSharedPreferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        listView = (ListView) findViewById(R.id.listView);
        isoDepAdapter = new IsoDepAdapter(getLayoutInflater());
        listView.setAdapter(isoDepAdapter);
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

        Button clear = (Button) findViewById(R.id.button_clear);
        assert clear != null;
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                isoDepAdapter.clearMessages();
                Toast.makeText(view.getContext(), "Done", Toast.LENGTH_SHORT).show();
            }
        });
        ImageView imageView = (ImageView) findViewById(R.id.img_logo);
        TextView doorStatus = (TextView) findViewById(R.id.door_message);
        imageView.setImageResource(R.mipmap.close);
        doorStatus.setText(R.string.door_message);
        if (mCardCommunicationTask != null) {
            mCardCommunicationTask.onCancelled();
            mCardCommunicationTask = null;
        }
    }


    @Override
    public void onPause() {
        super.onPause();
        nfcAdapter.disableReaderMode(this);
        if (mCardCommunicationTask != null) {
            mCardCommunicationTask.onCancelled();
            mCardCommunicationTask = null;
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        nfcAdapter.enableReaderMode(this, this, READER_FLAGS, null);
    }

    @Override
    public void onTagDiscovered(Tag tag) {
        IsoDep isoDep = IsoDep.get(tag);
        Log.d("CardReader", tag.toString());
        if (mCardCommunicationTask != null) {
            mCardCommunicationTask.onCancelled();
            mCardCommunicationTask = null;
        }
        mCardCommunicationTask = new CardCommunicationTask();
        mCardCommunicationTask.execute(isoDep);
    }


    public class CardCommunicationTask extends AsyncTask<IsoDep, String, DoorProtocol> {

        private IsoDep isoDep;


        @Override
        protected DoorProtocol doInBackground(IsoDep... params) {
            byte[] byteResponse;
            String cardResponse;
            ArrayList<String> arrayList = new ArrayList<>();
            isoDep = params[0];

            try {
                isoDep.connect();
                byteResponse = isoDep.transceive(NFCUtils.createSelectAidApdu(NFCUtils.AID_ANDROID));
            } catch (Exception e) {
                Log.d("initial", "connect + apdu: " + e.toString());
                return ERROR;
            }
            cardResponse = new String(byteResponse);
            publishProgress(cardResponse);

            // First message, do uafRequest to FIDO Server
            if (!cardResponse.equals(HELLO.getDesc())) {
                Log.d("background", "First card message is wrong, bye!");
                return ERROR;
            }

            String message = "";

            try {
                String mUAFMessage = "";
                String url = mSharedPreferences.getString("fido_server_endpoint", "");
                String endpoint = mSharedPreferences.getString("fido_auth_request", "");

                mUAFMessage = HttpUtils.get(url + endpoint).getPayload();
                arrayList = (ArrayList<String>) splitEqually(mUAFMessage, 259);
                message = "BLOCK:" + arrayList.size();

            } catch (Exception e) {
                Log.d("background", "HTTP Exception: " + e.toString());
                publishProgress("UAF AUTH REQUEST ERROR");
                return ERROR;
            }

            boolean nextMessageIsUAFResponse = false;
            String lastSentMessage = "";
            String lastReceivedMessage = "";

            while (isoDep.isConnected() && !Thread.interrupted()) {

                try {
                    byteResponse = isoDep.transceive(message.getBytes());
                } catch (Exception e) {
                    Log.d("nfc_loop", "transceive error: " + e.toString());
                    return ERROR;
                }
                cardResponse = new String(byteResponse);

                // ******************************************
                // DEBUG purpose only
                if (!lastSentMessage.equals(message)) {
                    Log.d("SENT", "Message sent: " + message);
                    lastSentMessage = message;
                }
                if (!lastReceivedMessage.equals(cardResponse)) {
                    Log.d("REC", "Message received: " + cardResponse);
                    lastReceivedMessage = cardResponse;
                }
                // ******************************************

                if (cardResponse.equals(ERROR.getDesc())) {
                    publishProgress("Error on card side");
                    return ERROR;
                }

                if (cardResponse.equals(NEXT.getDesc())) {
                    for (int i = 0; i < arrayList.size(); i++) {
                        try {
                            byteResponse = isoDep.transceive(arrayList.get(i).getBytes());
                            Log.d("nfc_loop", "FOR: card response: " + new String(byteResponse));
                        } catch (Exception e) {
                            Log.d("nfc_loop", "FOR loop: transceive error: " + e.toString());
                            return ERROR;
                        }
                    }
                    message = READY.getDesc();
                    continue;
                }

                if (cardResponse.equals(WAIT.getDesc())) {
                    message = READY.getDesc();
                    continue;
                }

                if (cardResponse.equals(DONE.getDesc())) {
                    message = RESPONSE.getDesc();
                    nextMessageIsUAFResponse = true;
                    continue;
                }

                if (nextMessageIsUAFResponse == true) {
                    String uafResponseJson;
                    StringBuilder stringBuilder = new StringBuilder();
                    try {
                        String sSize = cardResponse.split(":")[1];
                        int size = Integer.parseInt(sSize);
                        for (int i = 0; i < size; i++){
                            byteResponse = isoDep.transceive(NEXT.getDesc().getBytes());
                            stringBuilder.append(new String(byteResponse));
                        }
                        uafResponseJson = stringBuilder.toString();

                        String decoded = "";
                        publishProgress("Checking card UAF response");



                        JSONObject json = new JSONObject(uafResponseJson);
                        decoded = json.getString("uafProtocolMessage").replace("\\", "");

                        // Sending card response to FIDO Server. Is it valid?
                        String url = mSharedPreferences.getString("fido_server_endpoint", "");
                        String endpoint = mSharedPreferences.getString("fido_auth_response", "");
                        String serverResponse = HttpUtils.post(url + endpoint, decoded).getPayload();

                        // ******************************************************
                        // TODO Check if it is valid response
                        // IF VALID, then door opening and sending a positive response to card
                        // ******************************************************
                        Gson gson = new Gson();
                        ServerResponse sResponse = gson.fromJson(serverResponse.substring(1, serverResponse.length() - 1), ServerResponse.class);
                        if (!sResponse.getStatus().contains("KEY_NOT_REGISTERED")) {
                            // Ok card, your access is granted
                            cardResponse = new String(isoDep.transceive(GRANTED.getDesc().getBytes()));
                            publishProgress("Access granted!");
                            return GRANTED;
                        } else {
                            // I'm sorry, you are not allowed to enter
                            message = DENY.getDesc();
                            cardResponse = new String(isoDep.transceive(message.getBytes()));
                            publishProgress("Access denied!");
                            return DENY;
                        }


                        // ******************************************************
                        // ******************************************************
                        // ******************************************************


                    } catch (Exception e) {
                        Log.d("uafResponse", "To process uafMessage: " + e.toString());
                        return ERROR;
                    }
                }


            }//while
            return OK;
        }

        @Override
        protected void onPostExecute(DoorProtocol result) {
            super.onPostExecute(result);
            Log.d("postexec", "end");
            try {
                isoDep.close();
            } catch (IOException e) {
                Log.d("postexec", "trying to close connection: " + e.toString());
            }
            mCardCommunicationTask = null;
            updateInterface(result);
        }

        @Override
        protected void onProgressUpdate(String... values) {
            super.onProgressUpdate(values);
            if (!values[0].isEmpty()) {
                isoDepAdapter.addMessage(values[0]);
                listView.smoothScrollToPosition(isoDepAdapter.getCount() - 1);
            }
        }

        @Override
        protected void onCancelled() {
            super.onCancelled();
            mCardCommunicationTask = null;
        }
    }

    public void updateInterface(DoorProtocol result) {
        final ImageView imageView = (ImageView) findViewById(R.id.img_logo);
        final TextView doorStatus = (TextView) findViewById(R.id.door_message);
        if (result == GRANTED) {
            imageView.setImageResource(R.mipmap.open);
            doorStatus.setText("Opening");
            new CountDownTimer(10000, 100) {
                public void onTick(long millisUntilFinished) {
                }

                public void onFinish() {
                    imageView.setImageResource(R.mipmap.close);
                    doorStatus.setText(R.string.door_message);
                }
            }.start();
        }
    }


    public void updateImage(boolean locked, String message) {
        final ImageView imageView = (ImageView) findViewById(R.id.img_logo);
        final TextView doorStatus = (TextView) findViewById(R.id.door_message);

        int image = (locked) ? R.mipmap.close : R.mipmap.open;
        message = (message == null) ? "Locked" : message;

        imageView.setImageResource(image);
        doorStatus.setText(message);

        if (image == R.mipmap.open) {
            new CountDownTimer(10000, 100) {
                public void onTick(long millisUntilFinished) {
                }

                public void onFinish() {
                    imageView.setImageResource(R.mipmap.close);
                    doorStatus.setText(R.string.door_message);
                }
            }.start();
        }
    }

    @Override
    public void onStop() {
        super.onStop();

    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
//        outState.putBoolean("doorlocked", mDoorIsLocked);
//        outState.putString("door_message", mDoorMessage);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
//        mDoorIsLocked = savedInstanceState.getBoolean("doorlocked");
//        mDoorMessage = savedInstanceState.getString("door_message");
//        updateImage(mDoorIsLocked, mDoorMessage);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        switch (id) {
            case R.id.action_settings:
                Intent intent = new Intent(this, SettingsActivity.class);
                startActivity(intent);
                break;
            case R.id.action_clear:
                isoDepAdapter.clearMessages();
                Toast.makeText(getApplicationContext(), "Done", Toast.LENGTH_SHORT).show();
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    public static List<String> splitEqually(String text, int size) {
        List<String> ret = new ArrayList<String>((text.length() + size - 1) / size);
        for (int start = 0; start < text.length(); start += size) {
            ret.add(text.substring(start, Math.min(text.length(), start + size)));
        }
        return ret;
    }


}

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

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements NfcAdapter.ReaderCallback {

    public static int READER_FLAGS =
            NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK;

    private NfcAdapter nfcAdapter;
    private ListView listView;
    private IsoDepAdapter isoDepAdapter;
    private CardCommunicationTask mCardCommunicationTask = null;
    private SharedPreferences mSharedPreferences;
    private String mUAFMessage;
    private boolean mDoorIsLocked;
    private String mDoorMessage;
    private String lastReceivedMessage;
    private String lastSentMessage;


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
        initialState();
        ImageView imageView = (ImageView) findViewById(R.id.img_logo);
        TextView doorStatus = (TextView) findViewById(R.id.door_message);
        imageView.setImageResource(R.mipmap.close);
        doorStatus.setText(R.string.door_message);
    }


    public void initialState() {
        mDoorMessage = "Locked";
        mDoorIsLocked = true;
        lastReceivedMessage = "";
        lastSentMessage = "";
        mUAFMessage = "";
        mCardCommunicationTask = null;
    }


    @Override
    public void onPause() {
        super.onPause();
        nfcAdapter.disableReaderMode(this);
        mCardCommunicationTask = null;
    }

    @Override
    public void onResume() {
        super.onResume();
        nfcAdapter.enableReaderMode(this, this, READER_FLAGS, null);
    }

    @Override
    public void onTagDiscovered(Tag tag) {
        initialState();
        IsoDep isoDep = IsoDep.get(tag);
        isoDep.setTimeout(3000);
        Log.d("CardReader", tag.toString());
        mCardCommunicationTask = new CardCommunicationTask(this);
        mCardCommunicationTask.execute(isoDep);
    }


    public class CardCommunicationTask extends AsyncTask<IsoDep, String, String> {

        private MainActivity mMainActivity;
        private String mStatus;

        public CardCommunicationTask(MainActivity activity) {
            this.mMainActivity = activity;
        }

        @Override
        protected String doInBackground(IsoDep... params) {
            String result = "";
            try {
                ArrayList<String> arrayList = new ArrayList<>();

                IsoDep isoDep = params[0];
                isoDep.connect();

                byte[] cardResponse = isoDep.transceive(NFCUtils.createSelectAidApdu(NFCUtils.AID_ANDROID));
                String stringCardMessage = new String(cardResponse);
                publishProgress(stringCardMessage);

                String message = "";

                // First message, do uafRequest to FIDO Server
                if (stringCardMessage.equals(DoorProtocol.HELLO.getDesc())) {
                    String url = mSharedPreferences.getString("fido_server_endpoint", "");
                    String endpoint = mSharedPreferences.getString("fido_auth_request", "");
                    mUAFMessage = "";
                    try {
                        mUAFMessage = HttpUtils.get(url + endpoint).getPayload();
                        arrayList = (ArrayList<String>) splitEqually(mUAFMessage, 259);
                        message = "BLOCK:" + arrayList.size();
                    } catch (Exception e) {
                        Log.d("exception", e.toString());
                        publishProgress("UAF AUTH REQUEST ERROR");
                        message = DoorProtocol.READER_ERROR.getDesc();
                        Log.d("background", "reader said: " + message);
                        stringCardMessage = new String(isoDep.transceive(message.getBytes()));
                        Log.d("background", "card said: " + stringCardMessage);
                        publishProgress("card said: " + stringCardMessage);
                        return "UAF AUTH REQUEST ERROR";
                    }
                }
                boolean uafResponse = false;

                lastSentMessage = message;
                lastReceivedMessage = stringCardMessage;

                while (isoDep.isConnected() && !Thread.interrupted()) {

                    // Sending block size information
                    if (!lastSentMessage.equals(message)) {
                        Log.d("NFCLOOP", "reader said: " + message);
                        lastSentMessage = message;
                    }

                    stringCardMessage = new String(isoDep.transceive(message.getBytes()));

                    if (!lastReceivedMessage.equals(stringCardMessage)) {
                        publishProgress("card said: " + stringCardMessage);
                        Log.d("NFCLOOP", "card said:   " + stringCardMessage);
                        lastReceivedMessage = stringCardMessage;
                    }

                    // Sending uafAuthRequest sliced in several 259bits blocks
                    if (stringCardMessage.equals(DoorProtocol.NEXT.getDesc())) {
                        for (int i = 0; i < arrayList.size(); i++) {
                            isoDep.transceive(arrayList.get(i).getBytes());
                        }
                        // uafAuthRequest sent! So card, are you read to send me the response?
                        message = DoorProtocol.READY.getDesc();

                    } else {

                        // Card does not have the uafResponse yet
                        if (!uafResponse) {

                            // Card does not have the response yet, please wait few seconds before ask again
                            if (stringCardMessage.equals(DoorProtocol.WAIT.getDesc())) {
//                                if (!lastReceivedMessage.equals(stringCardResponse)) {
//                                    publishProgress("card said: " + DoorProtocol.WAIT.getDesc());
//                                    Log.d("nfcloop", DoorProtocol.WAIT.getDesc());
//                                }
                                message = DoorProtocol.READY.getDesc();

                                //Card is done! It has the response.
                            } else if (stringCardMessage.equals(DoorProtocol.DONE.getDesc())) {
                                publishProgress("card said: " + DoorProtocol.DONE.getDesc());
//                                Log.d("nfcloop", "done");
                                result = "done";
                                message = "RESPONSE";
                                uafResponse = true; //Card is done! It has the response.


                                // Ops, something was wrong if the card.
                            } else if (stringCardMessage.equals(DoorProtocol.ERROR.getDesc())) {
                                publishProgress("card said: " + DoorProtocol.ERROR.getDesc());
//                                Log.d("nfcloop", "card said: error");
                                result = "CARD ERROR";
                                mDoorIsLocked = true;
                                mDoorMessage = "Locked";
                                return result;
                            }

                            // Yes, card is ready to send the uafResponse
                        } else {
//                            Log.d("cardreader", "uafResponse: " + stringCardMessage);

                            String uafResponseJson = stringCardMessage;
                            String decoded = "";
                            try {
                                JSONObject json = new JSONObject(uafResponseJson);
                                decoded = json.getString("uafProtocolMessage").replace("\\", "");

                                // Sending card response to FIDO Server. Is it valid?
                                String url = mSharedPreferences.getString("fido_server_endpoint", "");
                                String endpoint = mSharedPreferences.getString("fido_auth_response", "");
                                String serverResponse = HttpUtils.post(url + endpoint, decoded).getPayload();


//                                Log.d("cardreader", "ServerResponse: " + serverResponse);

                                // ******************************************************
                                // TODO Check if it is valid response
                                // IF VALID, then door opening and sending a positive response to card
                                // ******************************************************
                                Gson gson = new Gson();
                                ServerResponse sResponse = gson.fromJson(serverResponse.substring(1, serverResponse.length() - 1), ServerResponse.class);
//                                Log.d("fidoserver", sResponse.getStatus());
                                if (!sResponse.getStatus().contains("KEY_NOT_REGISTERED")) {
                                    mStatus = "Welcome, " + sResponse.getUsername();
                                    mDoorMessage = "Opening";
                                    mDoorIsLocked = false;
                                    // Ok card, your access is granted
                                    message = DoorProtocol.GRANTED.getDesc();
                                    stringCardMessage = new String(isoDep.transceive(message.getBytes()));
                                    result = DoorProtocol.GRANTED.getDesc();
                                } else {
                                    // I'm sorry, you are not allowed to enter
                                    mDoorMessage = "Locked";
                                    mDoorIsLocked = true;
                                    message = DoorProtocol.DENY.getDesc();
                                    stringCardMessage = new String(isoDep.transceive(message.getBytes()));
                                    result = DoorProtocol.DENY.getDesc();
                                }
                                uafResponse = false;


                                // ******************************************************
                                // ******************************************************
                                // ******************************************************

                            } catch (Exception e) {
                                Log.d("exception", e.toString());
                                return "COMMUNICATION ERROR";
                            }
                            return result;
                        }
                    }
                }
                Log.d("CardReader", "Losing connection..bye bye");
                publishProgress("Losing connection..bye bye");
                isoDep.close();
            } catch (Exception e) {
                publishProgress(e.toString());
                Log.d("ERROR", e.toString());
            }
            return result;
        }

        @Override
        protected void onPostExecute(String s) {
            super.onPostExecute(s);
            if ((s.contains("ERROR")) || s.contains("LOSING CONNECTION")) {
                mStatus = "Locked";
                mDoorMessage = mStatus;
            }
            if (!s.equals("UAF AUTH REQUEST ERROR") && (!s.isEmpty())) {
                isoDepAdapter.addMessage(s);
                listView.smoothScrollToPosition(isoDepAdapter.getCount() - 1);
            }
            updateImage(mDoorIsLocked, mStatus);
//            mMainActivity.initialState();
            mCardCommunicationTask = null;
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
            mStatus = "Locked";
            mDoorMessage = mStatus;
            mDoorIsLocked = true;
//            updateImage(mDoorIsLocked, mDoorMessage);
            mMainActivity.initialState();
            mCardCommunicationTask = null;
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
                    mDoorIsLocked = true;
                    mDoorMessage = "Locked";
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
        outState.putBoolean("doorlocked", mDoorIsLocked);
        outState.putString("door_message", mDoorMessage);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        mDoorIsLocked = savedInstanceState.getBoolean("doorlocked");
        mDoorMessage = savedInstanceState.getString("door_message");
        updateImage(mDoorIsLocked, mDoorMessage);
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

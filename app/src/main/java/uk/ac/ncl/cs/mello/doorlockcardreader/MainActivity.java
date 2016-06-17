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
import android.support.design.widget.FloatingActionButton;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.gson.Gson;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements NfcAdapter.ReaderCallback {

    public static int READER_FLAGS =
            NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK;

    private NfcAdapter nfcAdapter;
    private ListView listView;
    private IsoDepAdapter isoDepAdapter;
    private MyTask mMyTask = null;
    private SharedPreferences mSharedPreferences;
    private String mUAFMessage;
    private int mDoorIcon;
    private String mDoorMessage;


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


        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        assert fab != null;
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                isoDepAdapter.clearMessages();
                Toast.makeText(view.getContext(), "Done", Toast.LENGTH_SHORT).show();
            }
        });
        mDoorIcon = R.mipmap.close;
        mDoorMessage = "Locked";
        updateImage(R.mipmap.close, mDoorMessage);
    }

    @Override
    public void onPause() {
        super.onPause();
        nfcAdapter.disableReaderMode(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        nfcAdapter.enableReaderMode(this, this, READER_FLAGS, null);
    }

    @Override
    public void onTagDiscovered(Tag tag) {
        Log.i("CardReader", "Tag discovery" + tag.toString());
        IsoDep isoDep = IsoDep.get(tag);
        isoDep.setTimeout(3000);
        Log.i("CardReader", "MaxTransceiveLength: " + isoDep.getMaxTransceiveLength());
        mMyTask = new MyTask(this);
        mMyTask.execute(isoDep);
    }


    public class MyTask extends AsyncTask<IsoDep, String, String> {

        private MainActivity mMainActivity;
        private String mStatus;

        public MyTask(MainActivity activity) {
            this.mMainActivity = activity;
        }

        @Override
        protected String doInBackground(IsoDep... params) {
            String result = "";
            try {
                IsoDep isoDep = params[0];
                isoDep.connect();
                byte[] response = isoDep.transceive(NFCUtils.createSelectAidApdu(NFCUtils.AID_ANDROID));

                String sResp = new String(response);
                publishProgress(sResp);

                if (sResp.equals(DoorProtocol.HELLO.getDesc())) {
                    String url = mSharedPreferences.getString("fido_server_endpoint", "");
                    String endpoint = mSharedPreferences.getString("fido_auth_request", "");
                    mUAFMessage = "";
                    try {
                        mUAFMessage = HttpUtils.get(url + endpoint).getPayload();
                    } catch (NoSuchAlgorithmException e) {
                        e.printStackTrace();
                    } catch (KeyManagementException e) {
                        e.printStackTrace();
                    }


                }
                //handshake
                ArrayList<String> arrayList = (ArrayList<String>) splitEqually(mUAFMessage, 260);
                String message = "BLOCK:" + arrayList.size();

                boolean uafResponse = false;

                while (isoDep.isConnected() && !Thread.interrupted()) {

                    response = isoDep.transceive(message.getBytes());
                    sResp = new String(response);
                    if (sResp.equals("NEXT")) {
                        for (int i = 0; i < arrayList.size(); i++) {
                            isoDep.transceive(arrayList.get(i).getBytes());
                        }
                        message = DoorProtocol.READY.getDesc();
                    } else {

                        if (!uafResponse) {
                            if (sResp.equals(DoorProtocol.WAIT.getDesc())) {
                                publishProgress("wait...");
                                Log.d("nfcloop", "wait");
                                Thread.sleep(2000);
                                message = DoorProtocol.READY.getDesc();
                            } else if (sResp.equals(DoorProtocol.DONE.getDesc())) {
                                publishProgress("done!");
                                Log.d("nfcloop", "done");
                                result = "done";
                                message = "RESPONSE";
                                uafResponse = true;
                            } else if (sResp.equals(DoorProtocol.ERROR.getDesc())) {
                                publishProgress("error!");
                                Log.d("nfcloop", "error");
                                result = "error";
                                mDoorIcon = R.mipmap.close;
                                return result;
                            }
                        } else {
                            Log.d("cardreader", "uafResponse: " + sResp);
                            String uafResponseJson = sResp;
                            StringBuffer res = new StringBuffer();
                            String decoded = "";
                            try {
                                JSONObject json = new JSONObject(uafResponseJson);
                                decoded = json.getString("uafProtocolMessage").replace("\\", "");

                                res.append("#uafMessageegOut\n" + decoded);
                                res.append("\n\n#ServerResponse\n");
                                String url = mSharedPreferences.getString("fido_server_endpoint", "");
                                String endpoint = mSharedPreferences.getString("fido_auth_response", "");
                                String serverResponse = HttpUtils.post(url + endpoint, decoded).getPayload();
                                res.append(serverResponse);
                                String r = res.toString();
                                Log.d("cardreader", "ServerResponse: " + r);

                                // ******************************************************
                                // TODO Check if it is valid response
                                // IF VALID, then door opening and sending a positive response to card
                                Gson gson = new Gson();
                                ServerResponse sResponse = gson.fromJson(serverResponse.substring(1, serverResponse.length() - 1), ServerResponse.class);

                                mStatus = "Welcome, " + sResponse.getUsername();
                                mDoorMessage = mStatus;
                                mDoorIcon = R.mipmap.open;

                                isoDep.transceive(DoorProtocol.GRANTED.getDesc().getBytes());
                                uafResponse = false;

                                // ******************************************************

                            } catch (JSONException e) {
                                return "PROTOCOL ERROR";
                            }

                            return result;
                        }
                    }
                }
                Log.d("CardReader", "Losing connection..bye bye");
                if (mStatus.equals("Authenticating")){
                    mStatus = "Locked";
                    updateImage(R.mipmap.close,"Locked");
                }
                isoDep.close();
            } catch (IOException e) {
                publishProgress(e.getMessage());
            } catch (InterruptedException e) {
                e.printStackTrace();
                Log.d("ERROR", e.toString());
            }
            return result;
        }

        @Override
        protected void onPostExecute(String s) {
            super.onPostExecute(s);
            if (s.equals("PROTOCOL ERROR")) {
                mStatus = "Locked";
                mDoorMessage = mStatus;
            }
            isoDepAdapter.addMessage(s);
            listView.smoothScrollToPosition(isoDepAdapter.getCount() - 1);
            updateImage(mDoorIcon, mStatus);
        }

        @Override
        protected void onProgressUpdate(String... values) {
            super.onProgressUpdate(values);
            isoDepAdapter.addMessage(values[0]);
            listView.smoothScrollToPosition(isoDepAdapter.getCount() - 1);
            mDoorMessage = "Authenticating...";
            updateImage(-1, mDoorMessage);
        }

        @Override
        protected void onCancelled() {
            super.onCancelled();

        }
    }


    public void updateImage(int image, String message) {
        final ImageView imageView = (ImageView) findViewById(R.id.img_logo);
        final TextView doorStatus = (TextView) findViewById(R.id.door_message);

        if (image > 0) {
            imageView.setImageResource(image);
        }
        doorStatus.setText(message);

        if (image == R.mipmap.open) {
            new CountDownTimer(10000, 100) {
                public void onTick(long millisUntilFinished) {
                }

                public void onFinish() {
                    mDoorIcon = R.mipmap.close;
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
        ImageView imageView = (ImageView) findViewById(R.id.img_logo);
        outState.putInt("door_icon", this.mDoorIcon);
        outState.putString("door_message",mDoorMessage );
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        mDoorIcon = savedInstanceState.getInt("door_icon");
        mDoorMessage = savedInstanceState.getString("door_message");
        updateImage(mDoorIcon, mDoorMessage);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
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

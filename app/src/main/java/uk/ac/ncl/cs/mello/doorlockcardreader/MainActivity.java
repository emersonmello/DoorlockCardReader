package uk.ac.ncl.cs.mello.doorlockcardreader;

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
import android.util.Log;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.Toast;

import java.io.IOException;

public class MainActivity extends AppCompatActivity implements NfcAdapter.ReaderCallback {

    public static int READER_FLAGS =
            NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK;

    private NfcAdapter nfcAdapter;
    private ListView listView;
    private IsoDepAdapter isoDepAdapter;
    private MyTask mMyTask = null;
    private SharedPreferences mSharedPreferences;
    private int mDoorIcon;


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
        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);


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
        updateImage(R.mipmap.close);
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
        mMyTask = new MyTask();
        mMyTask.execute(isoDep);
    }


    public class MyTask extends AsyncTask<IsoDep, String, String> {

        @Override
        protected String doInBackground(IsoDep... params) {
            String result = "";
            try {
                IsoDep isoDep = params[0];
                isoDep.connect();
                byte[] response = isoDep.transceive(NFCUtils.createSelectAidApdu(NFCUtils.AID_ANDROID));

                publishProgress(new String(response));

                //handshake
                String message = DoorProtocol.READY.getDesc();

                while (isoDep.isConnected() && !Thread.interrupted()) {

                    response = isoDep.transceive(message.getBytes());
                    String sResp = new String(response);

                    if (sResp.equals(DoorProtocol.WAIT.getDesc())) {
                        publishProgress("wait...");
                        Log.d("nfcloop", "wait");
                        Thread.sleep(2000);
                    } else if (sResp.equals(DoorProtocol.DONE.getDesc())) {
                        publishProgress("done!");
                        Log.d("nfcloop", "done");
                        result = "done";
                        mDoorIcon = R.mipmap.open;
                        return result;
                    } else if (sResp.equals(DoorProtocol.ERROR.getDesc())) {
                        publishProgress("error!");
                        Log.d("nfcloop", "error");
                        result = "error";
                        mDoorIcon = R.mipmap.close;
                        return result;
                    }
                }
                Log.d("CardReader", "Losing connection..bye bye");
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
            isoDepAdapter.addMessage(s);
            listView.smoothScrollToPosition(isoDepAdapter.getCount() - 1);
            updateImage(mDoorIcon);
        }

        @Override
        protected void onProgressUpdate(String... values) {
            super.onProgressUpdate(values);
            isoDepAdapter.addMessage(values[0]);
            listView.smoothScrollToPosition(isoDepAdapter.getCount() - 1);
        }

        @Override
        protected void onCancelled() {
            super.onCancelled();

        }
    }


    public void updateImage(int image) {
        final ImageView imageView = (ImageView) findViewById(R.id.img_logo);
        imageView.setImageResource(image);
        if (image == R.mipmap.open) {
            new CountDownTimer(10000, 100) {
                public void onTick(long millisUntilFinished) {
                }

                public void onFinish() {
                    mDoorIcon = R.mipmap.close;
                    imageView.setImageResource(R.mipmap.close);
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
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        mDoorIcon = savedInstanceState.getInt("door_icon");
        updateImage(mDoorIcon);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }


}

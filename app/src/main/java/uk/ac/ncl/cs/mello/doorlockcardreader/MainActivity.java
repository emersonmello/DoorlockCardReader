package uk.ac.ncl.cs.mello.doorlockcardreader;

import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
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


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        listView = (ListView)findViewById(R.id.listView);
        isoDepAdapter = new IsoDepAdapter(getLayoutInflater());
        listView.setAdapter(isoDepAdapter);
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);

        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        assert fab != null;
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                isoDepAdapter.clearMessages();
                Toast.makeText(view.getContext(),"Done",Toast.LENGTH_SHORT).show();
            }
        });
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
        Log.i("CardReader","Tag discovery" + tag.toString());
        IsoDep isoDep = IsoDep.get(tag);
        isoDep.setTimeout(3000);
        mMyTask = new MyTask();
        mMyTask.execute(isoDep);
    }


    public class MyTask extends AsyncTask<IsoDep, String, String>{

        @Override
        protected String doInBackground(IsoDep... params) {
            String result = "";
            try {
                IsoDep isoDep = params[0];
                isoDep.connect();
                byte[] response = isoDep.transceive(NFCUtils.createSelectAidApdu(NFCUtils.AID_ANDROID));

                //handshake
                Log.i("CardReader","Response: " + new String(response));
                publishProgress(new String(response));
                String message = DoorProtocol.READY.getDesc();

                while (isoDep.isConnected() && !Thread.interrupted()) {
                    response = isoDep.transceive(message.getBytes());
                    String sResp = new String (response);
                    Log.i("CardReader","Response: " + sResp);

                    if (sResp.equals(DoorProtocol.WAIT.getDesc())){
                        publishProgress(sResp);
                        Thread.sleep(2000);
                    }else if (sResp.equals(DoorProtocol.DONE.getDesc())){
                        publishProgress(sResp);
                        result = "done";
                        return result;
                    }
                }
                Log.i("CardReader","Losing connection..bye bye");
                isoDep.close();
            }
            catch (IOException e) {
                publishProgress(e.getMessage());
            } catch (InterruptedException e) {
                e.printStackTrace();
                Log.i("ERROR", e.toString());
            }
            return result;
        }

        @Override
        protected void onPostExecute(String s) {
            super.onPostExecute(s);
            isoDepAdapter.addMessage(s);
        }

        @Override
        protected void onProgressUpdate(String... values) {
            super.onProgressUpdate(values);
            isoDepAdapter.addMessage(values[0]);
        }

        @Override
        protected void onCancelled() {
            super.onCancelled();
        }
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

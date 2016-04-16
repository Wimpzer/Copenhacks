package com.mediator.lyngby.copenhacks;

import android.Manifest;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import com.choosemuse.libmuse.AnnotationData;
import com.choosemuse.libmuse.ConnectionState;
import com.choosemuse.libmuse.Eeg;
import com.choosemuse.libmuse.LibmuseVersion;
import com.choosemuse.libmuse.MessageType;
import com.choosemuse.libmuse.Muse;
import com.choosemuse.libmuse.MuseArtifactPacket;
import com.choosemuse.libmuse.MuseConfiguration;
import com.choosemuse.libmuse.MuseConnectionListener;
import com.choosemuse.libmuse.MuseConnectionPacket;
import com.choosemuse.libmuse.MuseDataListener;
import com.choosemuse.libmuse.MuseDataPacket;
import com.choosemuse.libmuse.MuseDataPacketType;
import com.choosemuse.libmuse.MuseFileFactory;
import com.choosemuse.libmuse.MuseFileReader;
import com.choosemuse.libmuse.MuseFileWriter;
import com.choosemuse.libmuse.MuseListener;
import com.choosemuse.libmuse.MuseManagerAndroid;
import com.choosemuse.libmuse.MuseVersion;
import com.choosemuse.libmuse.Result;
import com.choosemuse.libmuse.ResultLevel;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {
    private final String TAG = "MUSEAPP";

    private ArrayAdapter<String> spinnerAdapter;
    private MuseManagerAndroid manager = null;
    private Muse muse = null;
    private ConnectionListener connectionListener = null;
    private DataListener dataListener = null;

    // Note: the array lengths here are taken from the comments in
    // MuseDataPacketType, which specify 3 values for accelerometer and 6
    // values for EEG and EEG-derived packets.
    private final double[] eegBuffer = new double[6];
    private boolean eegStale = false;

    Button startButton;

    private void getEegChannelValues(double[] buffer, MuseDataPacket p) {
        buffer[1] = p.getEegChannelValue(Eeg.EEG2);
    }

    private final Handler handler = new Handler();

    private final Runnable tickUi = new Runnable() {
        @Override
        public void run() {
            if (eegStale) {
                updateEeg();
            }
            handler.postDelayed(tickUi, 1000 / 60);
        }
    };

    private final AtomicReference<MuseFileWriter> fileWriter = new AtomicReference<>();
    private final AtomicReference<Handler> fileHandler = new AtomicReference<>();

    private final Thread fileThread = new Thread() {
        @Override
        public void run() {
            Looper.prepare();
            fileHandler.set(new Handler());
            final File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            fileWriter.set(MuseFileFactory.getMuseFileWriter(new File(dir, "new_muse_file.muse")));
            Looper.loop();
        }
    };

    static {
        // Try to load our own all-in-one JNI lib. If it fails, rely on libmuse
        // to load libmuse_android.so for us.
        try {
            System.loadLibrary("TestLibMuseAndroid");
        } catch (UnsatisfiedLinkError e) {
        }
    }

    public void receiveMuseConnectionPacket(final MuseConnectionPacket p) {
        final ConnectionState current = p.getCurrentConnectionState();
        final String status = p.getPreviousConnectionState().toString().
                concat(" -> ").
                concat(current.toString());
        Log.i(TAG, status);
        if (p.getCurrentConnectionState() == ConnectionState.DISCONNECTED) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (muse != null) {
                        muse.runAsynchronously();
                    }
                }
            }, 20);
        }
    }


    public void receiveMuseDataPacket(final MuseDataPacket p) {
        Handler h = fileHandler.get();
        if (h != null) {
            h.post(new Runnable() {
                @Override
                public void run() {
                    fileWriter.get().addDataPacket(0, p);
                }
            });
        }
        final long n = p.valuesSize();
        switch (p.packetType()) {
            case EEG:
                assert(eegBuffer.length >= n);
                getEegChannelValues(eegBuffer,p);
                eegStale = true;
                break;
            default:
                break;
        }
    }

    private void updateEeg() {
        TextView betaWaveTextView = (TextView)findViewById(R.id.betaWaveTextView);
        betaWaveTextView.setText(String.format("%6.2f", eegBuffer[1]));
    }

    public void receiveMuseArtifactPacket(final MuseArtifactPacket p) {
    }

    public void museListChanged() {
        final ArrayList<Muse> list = manager.getMuses();
        spinnerAdapter.clear();
        for (Muse m : list) {
            spinnerAdapter.add(m.getName().concat(m.getMacAddress()));
        }
    }

    public MainActivity() {
        for (int i = 0; i < eegBuffer.length; ++i) {
            eegBuffer[i] = 0.0;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        manager = MuseManagerAndroid.getInstance();
        manager.setContext(this);
        fileThread.start();

        Log.i(TAG, "libmuse version=" + LibmuseVersion.instance().getString());

        // The ACCESS_COARSE_LOCATION permission is required to use the
        // BlueTooth LE library and must be requested at runtime for Android 6.0+
        // On an Android 6.0 device, the following code will display 2 dialogs,
        // one to provide context and the second to request the permission.
        // On an Android device running an earlier version, nothing is displayed
        // as the permission is granted from the manifest.
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
        {
            DialogInterface.OnClickListener buttonListener =
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which){
                            dialog.dismiss();
                            ActivityCompat.requestPermissions(MainActivity.this,
                                    new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},
                                    0);
                        }
                    };

            AlertDialog introDialog = new AlertDialog.Builder(this)
                    .setTitle("Muse needs your permission")
                    .setMessage("Muse needs a few permissions to work properly. On the next screens, tap 'Allow' to proceed. If you deny, Muse will not work properly until you go into your Android settings and allow.")
                    .setPositiveButton("I understand", buttonListener)
                    .create();
            introDialog.show();
        }

        WeakReference<MainActivity> weakActivity =
                new WeakReference<MainActivity>(this);
        connectionListener = new ConnectionListener(weakActivity);
        dataListener = new DataListener(weakActivity);
        manager.setMuseListener(new MuseL(weakActivity));

        setContentView(R.layout.activity_main);

        startButton = (Button) findViewById(R.id.startButton);
        startButton.setOnClickListener(this);

        spinnerAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item);
        Spinner musesSpinner = (Spinner) findViewById(R.id.museSpinner);
        musesSpinner.setAdapter(spinnerAdapter);

        handler.post(tickUi);
    }

    @Override
    protected void onPause() {
        super.onPause();
        manager.stopListening();
    }

    @Override
    public void onClick(View v) {
        Spinner musesSpinner = (Spinner) findViewById(R.id.museSpinner);
        if (v.getId() == R.id.refreshButton) {
            manager.stopListening();
            manager.startListening();
        } else if (v.getId() == R.id.startButton) {
            manager.stopListening();
            List<Muse> pairedMuses = manager.getMuses();
            if (pairedMuses.size() < 1 ||
                    musesSpinner.getAdapter().getCount() < 1) {
                Log.w("MUSEAPP", "There is nothing to connect to");
            } else {
                muse = pairedMuses.get(musesSpinner.getSelectedItemPosition());
                muse.unregisterAllListeners();
                muse.registerConnectionListener(connectionListener);
                muse.registerDataListener(dataListener, MuseDataPacketType.EEG);
                muse.registerDataListener(dataListener, MuseDataPacketType.ALPHA_RELATIVE);
                muse.registerDataListener(dataListener, MuseDataPacketType.ACCELEROMETER);
                muse.registerDataListener(dataListener, MuseDataPacketType.BATTERY);
                muse.registerDataListener(dataListener, MuseDataPacketType.DRL_REF);
                muse.registerDataListener(dataListener, MuseDataPacketType.QUANTIZATION);
                muse.runAsynchronously();
            }
        }
    }

    /*
     * Simple example of getting data from the "*.muse" file
     */
    private void playMuseFile(String name) {
        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File file = new File(dir, name);
        final String tag = "Muse File Reader";
        if (!file.exists()) {
            Log.w(tag, "file doesn't exist");
            return;
        }
        MuseFileReader fileReader = MuseFileFactory.getMuseFileReader(file);
        Result res = fileReader.gotoNextMessage();
        while (res.getLevel() == ResultLevel.R_INFO && !res.getInfo().contains("EOF")) {
            MessageType type = fileReader.getMessageType();
            int id = fileReader.getMessageId();
            long timestamp = fileReader.getMessageTimestamp();
            Log.i(tag, "type: " + type.toString() +
                    " id: " + Integer.toString(id) +
                    " timestamp: " + String.valueOf(timestamp));
            switch(type) {
                case EEG: case BATTERY: case ACCELEROMETER: case QUANTIZATION: case GYRO:
                    MuseDataPacket packet = fileReader.getDataPacket();
                    Log.i(tag, "data packet: " + packet.packetType().toString());
                    break;
                case VERSION:
                    MuseVersion version = fileReader.getVersion();
                    Log.i(tag, "version" + version.getFirmwareType());
                    break;
                case CONFIGURATION:
                    MuseConfiguration config = fileReader.getConfiguration();
                    Log.i(tag, "config" + config.getBluetoothMac());
                    break;
                case ANNOTATION:
                    AnnotationData annotation = fileReader.getAnnotation();
                    Log.i(tag, "annotation" + annotation.getData());
                    break;
                default:
                    break;
            }
            res = fileReader.gotoNextMessage();
        }
    }

    // Listener translators follow.
    class ConnectionListener extends MuseConnectionListener {
        final WeakReference<MainActivity> activityRef;

        ConnectionListener(final WeakReference<MainActivity> activityRef) {
            this.activityRef = activityRef;
        }

        @Override
        public void receiveMuseConnectionPacket(final MuseConnectionPacket p, final Muse muse) {
            activityRef.get().receiveMuseConnectionPacket(p);
        }
    }

    class DataListener extends MuseDataListener {
        final WeakReference<MainActivity> activityRef;

        DataListener(final WeakReference<MainActivity> activityRef) {
            this.activityRef = activityRef;
        }

        @Override
        public void receiveMuseDataPacket(final MuseDataPacket p, final Muse muse) {
            activityRef.get().receiveMuseDataPacket(p);
        }

        @Override
        public void receiveMuseArtifactPacket(final MuseArtifactPacket p, final Muse muse) {
            activityRef.get().receiveMuseArtifactPacket(p);
        }
    }


    class MuseL extends MuseListener {
        final WeakReference<MainActivity> activityRef;

        MuseL(final WeakReference<MainActivity> activityRef) {
            this.activityRef = activityRef;
        }

        @Override
        public void museListChanged() {
            activityRef.get().museListChanged();
        }
    }
}

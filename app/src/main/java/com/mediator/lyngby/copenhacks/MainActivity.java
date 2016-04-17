package com.mediator.lyngby.copenhacks;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import com.choosemuse.libmuse.Accelerometer;
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
import com.firebase.client.DataSnapshot;
import com.firebase.client.Firebase;
import com.firebase.client.FirebaseError;
import com.firebase.client.ValueEventListener;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class MainActivity extends Activity implements View.OnClickListener {
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
    private final double[] alphaBuffer = new double[6];
    private boolean alphaStale = false;
    private final double[] accelBuffer = new double[3];
    private boolean accelStale = false;

    // helper methods to get different packet values
    private void getEegChannelValues(double[] buffer, MuseDataPacket p) {
        buffer[0] = p.getEegChannelValue(Eeg.EEG1);
        buffer[1] = p.getEegChannelValue(Eeg.EEG2);
        buffer[2] = p.getEegChannelValue(Eeg.EEG3);
        buffer[3] = p.getEegChannelValue(Eeg.EEG4);
        buffer[4] = p.getEegChannelValue(Eeg.AUX_LEFT);
        buffer[5] = p.getEegChannelValue(Eeg.AUX_RIGHT);
    }

    private void getAccelValues(MuseDataPacket p) {
        accelBuffer[0] = p.getAccelerometerValue(Accelerometer.FORWARD_BACKWARD);
        accelBuffer[1] = p.getAccelerometerValue(Accelerometer.UP_DOWN);
        accelBuffer[2] = p.getAccelerometerValue(Accelerometer.LEFT_RIGHT);
    }

    private final Handler handler = new Handler();

    // We update the UI from this Runnable instead of in packet handlers
    // because packets come in at high frequency -- 220Hz or more for raw EEG
    // -- and it only makes sense to update the UI at about 60fps. The update
    // functions do some string allocation, so this reduces our memory
    // footprint and makes GC pauses less frequent/noticeable.
    private final Runnable tickUi = new Runnable() {
        @Override
        public void run() {
            if (eegStale) {
                updateEeg();
            }
            if (accelStale) {
                updateAccel();
            }
            if (alphaStale) {
                updateAlpha();
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
        handler.post(new Runnable() {
            @Override public void run() {
                if (current == ConnectionState.CONNECTED) {
                    final MuseVersion museVersion = muse.getMuseVersion();
                    final String version = museVersion.getFirmwareType().
                            concat(" - ").concat(museVersion.getFirmwareVersion()).
                            concat(" - ").concat(Integer.toString(museVersion.getProtocolVersion()));
                }
            }
        });
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
            case ACCELEROMETER:
                assert(accelBuffer.length >= n);
                getAccelValues(p);
                accelStale = true;
                break;
            case ALPHA_RELATIVE:
                assert(alphaBuffer.length >= n);
                getEegChannelValues(alphaBuffer,p);
                alphaStale = true;
                break;
            default:
                break;
        }
    }

    private void updateAccel() {
    }

    private void updateEeg() {
        TextView fp1 = (TextView)findViewById(R.id.betaWaveTextView);
        double betaWave = eegBuffer[1];
        fp1.setText(String.format("%6.2f", betaWave));
        setAverageBetaWave(betaWave);
        setMood();
    }

    Firebase myFirebaseRef;
    moodContainer mc;

    private void setAverageBetaWave(double betaWave) {
        myFirebaseRef.child("betaWave").setValue(betaWave);



    }

    private void setMood() {
        String mood = null;

        TextView moodTextView = (TextView) findViewById(R.id.moodTextView);
//        getSentimentalScore();

        if(mc.getAverageBetaWave() > 0) {
            if(mc.getSentimentalScore() <= 0.25) {
                mood = "Angry";
            }else if(0.25 < mc.getSentimentalScore() || mc.getSentimentalScore() <= 0.5) {
                mood = "Frustreted";
            }else if(0.5 < mc.getSentimentalScore() || mc.getSentimentalScore() <= 0.6) {
                mood = "Normal";
            }else if(0.6 < mc.getSentimentalScore()) {
                mood = "Happy";
            }
        }
        mc.setMood(mood);
        moodTextView.setText(mood);
        myFirebaseRef.child("mood").setValue(mood);
    }

    private void getSentimentalScore() {
        Firebase.setAndroidContext(this);
        myFirebaseRef = new Firebase("https://mediatorbot.firebaseio.com/");
        myFirebaseRef.child("sentimentalScore").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                mc = new moodContainer();
                mc.setSentimentalScore((Double) dataSnapshot.getValue());
            }

            @Override
            public void onCancelled(FirebaseError firebaseError) {
            }
        });
    }

    private void updateAlpha() {
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
            alphaBuffer[i] = 0.0;
        }
        for (int i = 0; i < accelBuffer.length; ++i) {
            accelBuffer[i] = 0.0;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // XX this must come before other libmuse API calls; it loads the
        // library.
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
                    .setTitle("Muse Needs Your Permission")
                    .setMessage("Muse needs a few permissions to work properly. On the next screens, tap \"Allow\" to proceed. If you deny, Muse will not work properly until you go into your Android settings and allow.")
                    .setPositiveButton("I Understand", buttonListener)
                    .create();
            introDialog.show();
        }

        WeakReference<MainActivity> weakActivity =
                new WeakReference<MainActivity>(this);
        connectionListener = new ConnectionListener(weakActivity);
        dataListener = new DataListener(weakActivity);
        manager.setMuseListener(new MuseL(weakActivity));

        setContentView(R.layout.activity_main);
        Button refreshButton = (Button) findViewById(R.id.refreshButton);
        refreshButton.setOnClickListener(this);
        Button connectButton = (Button) findViewById(R.id.startButton);
        connectButton.setOnClickListener(this);

        spinnerAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item);
        Spinner musesSpinner = (Spinner) findViewById(R.id.museSpinner);
        musesSpinner.setAdapter(spinnerAdapter);

        getSentimentalScore();

        handler.post(tickUi);
    }

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

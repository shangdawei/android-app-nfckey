/*
 * Copyright (C) 2012 Gemtek Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nfc.gemkey;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLEncoder;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.StrictMode;
import android.util.Log;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbAccessory;

public class MainActivity extends Activity {

    private final static String TAG = "GemKey";
    private final static String ROOM_ID = "201";
    private final static String HOST = "http://sw8ftp.no-ip.org:5984/gem_door_sys/_design/door_info/_view/demo?key=";
    private final static int MSG_CHECK_TAG_ID_COMPLETE = 1;
    private final static boolean DEBUG = true;

    // NFC
    private NfcAdapter mAdapter;
    private PendingIntent mPendingIntent;
    private IntentFilter[] mFilters;
    private TextView tagId;

    // USB Accessory
    private static final String ACTION_USB_PERMISSION = "com.kaija.usbcontrol.USB_PERMISSION";
    private UsbManager mUsbManager;
    private PendingIntent mPermissionIntent;
    private boolean mPermissionRequestPending;
    private ToggleButton buttonLED;

    UsbAccessory mAccessory;
    ParcelFileDescriptor mFileDescriptor;
    FileInputStream mInputStream;
    FileOutputStream mOutputStream;

    private CheckTagInfoThread mCheckTagInfoThread;
    private MediaPlayer mp;

    @Override
    public void onCreate(Bundle icicle) {
        if (DEBUG) Log.d(TAG, "onCreate");
        super.onCreate(icicle);
        setContentView(R.layout.activity_main);

        mAdapter = NfcAdapter.getDefaultAdapter(this);

        // Create a generic PendingIntent that will be deliver to this activity. The NFC stack
        // will fill in the intent with the details of the discovered tag before delivering to
        // this activity.
        mPendingIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), 0);

        // Setup an intent filter for all MIME based dispatches
        IntentFilter tagDetected = new IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED);
        tagDetected.addCategory(Intent.CATEGORY_DEFAULT);
        mFilters = new IntentFilter[] {
            tagDetected
        };

        mUsbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        mPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
        registerReceiver(mUsbReceiver, filter);

        if (getLastNonConfigurationInstance() != null) {
            Log.i(TAG, "open usb accessory@onCreate");
            mAccessory = (UsbAccessory) getLastNonConfigurationInstance();
            openAccessory(mAccessory);
        }

        buttonLED = (ToggleButton) findViewById(R.id.nfc_btn);
        buttonLED.setBackgroundResource(buttonLED.isChecked() ? R.drawable.btn_toggle_yes : R.drawable.btn_toggle_no);
        buttonLED.setOnCheckedChangeListener(mKeyLockListener);

        tagId = (TextView) findViewById(R.id.nfc_tag);
        tagId.setText(R.string.nfc_scan_tag);

        // Avoid NetworkOnMainThreadException
        StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                .detectDiskReads().detectDiskWrites().detectNetwork()
                .penaltyLog().build());

        StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects().detectLeakedClosableObjects()
                .penaltyLog().penaltyDeath().build());

        setVolumeControlStream(AudioManager.STREAM_MUSIC);
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        if (mAccessory == null) {
            return super.onRetainNonConfigurationInstance();
        }
        return mAccessory;
    }

    @Override
    public void onResume() {
        if (DEBUG) Log.d(TAG, "onResume");
        super.onResume();
        if (mAdapter != null) {
            mAdapter.enableForegroundDispatch(this, mPendingIntent, mFilters, null);
        }

        if (mInputStream != null && mOutputStream != null) {
            return;
        }

        UsbAccessory[] accessories = mUsbManager.getAccessoryList();
        UsbAccessory accessory = (accessories == null ? null : accessories[0]);
        if (accessory != null) {
            if (mUsbManager.hasPermission(accessory)) {
                if (DEBUG) Log.i(TAG, "openAccessory @onResume");
                openAccessory(accessory);
            } else {
                synchronized (mUsbReceiver) {
                    if (!mPermissionRequestPending) {
                        mUsbManager.requestPermission(accessory, mPermissionIntent);
                        mPermissionRequestPending = true;
                    }
                }
            }
        } else {
            Log.d(TAG, "mAccessory is null");
        }
    }

    @Override
    public void onNewIntent(Intent intent) {
        if (DEBUG) Log.d(TAG, "onNewIntent");

        final String action = intent.getAction();
        if (action.equals(NfcAdapter.ACTION_TAG_DISCOVERED)) {
            if (DEBUG) Log.i(TAG, "Discovered tag with intent: " + action);
            Tag myTag = (Tag) intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);

            String nfc_tag_id = bytesToHexString(myTag.getId()).toUpperCase();
            tagId.setText("Tag ID : " + nfc_tag_id);

            if (mCheckTagInfoThread != null && mCheckTagInfoThread.isAlive()) {
                mCheckTagInfoThread.terminate();
                mCheckTagInfoThread = null;
            }
            mCheckTagInfoThread = new CheckTagInfoThread(nfc_tag_id);
            mCheckTagInfoThread.start();
        }
    }

    @Override
    public void onPause() {
        if (DEBUG) Log.d(TAG, "onPause");
        super.onPause();
        if (mAdapter != null) {
            mAdapter.disableForegroundDispatch(this);
        }
    }

    @Override
    public void onDestroy() {
        if (DEBUG) Log.d(TAG, "onDestroy");
        super.onDestroy();
        closeAccessory();
        unregisterReceiver(mUsbReceiver);

        if (mPermissionIntent != null) {
            mPermissionIntent = null;
        }

        if (mUsbManager != null) {
            mUsbManager = null;
        }

        if (mPendingIntent != null) {
            mPendingIntent = null;
        }

        if (mAdapter != null) {
            mAdapter = null;
        }
    }

    private String bytesToHexString(byte[] src) {
        StringBuilder stringBuilder = new StringBuilder("");
        if (src == null || src.length <= 0) {
            return null;
        }

        char[] buffer = new char[2];
        for (int i = 0; i < src.length; i++) {
            buffer[0] = Character.forDigit((src[i] >>> 4) & 0x0F, 16);
            buffer[1] = Character.forDigit(src[i] & 0x0F, 16);
            System.out.println(buffer);
            stringBuilder.append(buffer);
        }

        return stringBuilder.toString();
    }

    private void blinkLED(boolean isChecked) {
        if (DEBUG) Log.d(TAG, "blinkLED(" + isChecked + ")");
        byte[] buffer = new byte[1];

        if (!isChecked) {
            buffer[0] = (byte)0;  // button says on, light is off
            playSound(R.raw.doink);
        } else {
            buffer[0] = (byte)1;  // button says off, light is on
            playSound(R.raw.accept);
        }
        if (mOutputStream != null) {
            try {
                mOutputStream.write(buffer);
            } catch (IOException e) {
                Log.e(TAG, "write failed", e);
            }
        }
    }

    private void playSound(int resId) {
       // Release any resources from previous MediaPlayer
       if (mp != null) {
           mp.release();
       }

       // Create a new MediaPlayer to play this sound
       mp = MediaPlayer.create(this, resId);
       mp.setLooping(false);
       mp.start();
    }

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            // Compare whether is the same key.
            final boolean isLedBtnEnable = buttonLED.isChecked();
            if (msg.arg1 == 1) {
                buttonLED.setChecked(true);
                if (isLedBtnEnable) blinkLED(true);
            } else if (msg.arg1 == 0) {
                buttonLED.setChecked(false);
                if (!isLedBtnEnable) blinkLED(false);
            }
        }
    };

    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (DEBUG) Log.i(TAG, "mUsbReceiver receive action = " + action);
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbAccessory accessory = (UsbAccessory) intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        Log.i(TAG, "openAccessory@receive broadcast");
                        openAccessory(accessory);
                    } else {
                        Log.d(TAG, "permission denied for accessory " + accessory);
                    }
                    mPermissionRequestPending = false;
                }
            } else if (UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(action)) {
                UsbAccessory accessory = (UsbAccessory) intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
                if (accessory != null && accessory.equals(mAccessory)) {
                    closeAccessory();
                }
            }
        }
    };

    private final OnCheckedChangeListener mKeyLockListener = new OnCheckedChangeListener() {
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            Log.i(TAG, "Toggle Button onCheckedChanged(" + isChecked + ")");
            buttonLED.setChecked(isChecked);
            buttonLED.setBackgroundResource(isChecked ? R.drawable.btn_toggle_yes : R.drawable.btn_toggle_no);
            blinkLED(isChecked);
            Toast.makeText(MainActivity.this, "Toggle Button Status is " + isChecked + ".", Toast.LENGTH_SHORT).show();
        }
    };

    private void openAccessory(UsbAccessory accessory) {
        if (DEBUG) Log.i(TAG, "+ openAccesory");
        mFileDescriptor = mUsbManager.openAccessory(accessory);
        if (mFileDescriptor != null) {
            mAccessory = accessory;
            FileDescriptor fd = mFileDescriptor.getFileDescriptor();
            mInputStream = new FileInputStream(fd);
            mOutputStream = new FileOutputStream(fd);
            if (DEBUG) Log.d(TAG, "accessory opened");
        } else {
            if (DEBUG) Log.d(TAG, "accessory open fail");
        }
    }

    private void closeAccessory() {
        if (DEBUG) Log.i(TAG, "+ closeAccesory");
        try {
            if (mFileDescriptor != null) {
                mFileDescriptor.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            mFileDescriptor = null;
            mAccessory = null;
        }
    }

    private String connect(String url) {
        String result = "";
        HttpClient httpclient = new DefaultHttpClient();

        // Prepare a request object
        HttpGet httpget = new HttpGet(url);

        // Execute the request
        HttpResponse response;
        try {
            response = httpclient.execute(httpget);
            // Examine the response status
            Log.i(TAG, response.getStatusLine().toString());

            // Get hold of the response entity
            HttpEntity entity = response.getEntity();
            // If the response does not enclose an entity, there is no need
            // to worry about connection release

            if (entity != null) {

                // A Simple JSON Response Read
                InputStream instream = entity.getContent();
                result = convertStreamToString(instream);
                if (DEBUG) Log.d(TAG, "result ---> " + result);
                // now you have the string representation of the HTML request
                instream.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }

    /*
     * To convert the InputStream to String we use the BufferedReader.readLine()
     * method. We iterate until the BufferedReader return null which means
     * there's no more data to read. Each line will appended to a StringBuilder
     * and returned as String.
     */
    private String convertStreamToString(InputStream is) {

        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();

        String line = null;
        try {
            while ((line = reader.readLine()) != null) {
                if (DEBUG) Log.d(TAG, "---->" + line);
                sb.append(line + "\n");
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return sb.toString();
    }

    public class CheckTagInfoThread extends Thread {
        private boolean isContinue = true;
        String tag_id = "";

        public CheckTagInfoThread(String id) {
            tag_id = id;
        }

        public void terminate() {
            isContinue = false;
        }

        public void run() {
            if (isContinue) {
                String query = "[" + ROOM_ID + ",\"" + tag_id + "\"]";
                try {
                    String encodedUrl = HOST + URLEncoder.encode(query, "utf-8");
                    if (DEBUG) Log.d(TAG, encodedUrl);
                    String result = connect(encodedUrl);

                    if (mHandler != null && mHandler.hasMessages(MSG_CHECK_TAG_ID_COMPLETE)) {
                        mHandler.removeMessages(MSG_CHECK_TAG_ID_COMPLETE);
                    }

                    Message msg = new Message();
                    if (result.contains(ROOM_ID)) {
                        msg.arg1 = 1;
                    } else {
                        msg.arg1 = 0;
                    }
                    msg.what = MSG_CHECK_TAG_ID_COMPLETE;
                    mHandler.sendMessage(msg);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }
}

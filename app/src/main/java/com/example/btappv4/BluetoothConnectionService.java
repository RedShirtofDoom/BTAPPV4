//Code Written by Casey Conner || ID: 1000689079
//Main Class for managing bluetooth connection and sending messages

package com.example.btappv4;

import android.Manifest;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.UUID;

import static androidx.core.content.PermissionChecker.checkSelfPermission;

public class BluetoothConnectionService extends ActivityCompat{

    private static final String TAG = "BluetoothConnectionServ";

    private static final String appName = "BTAPPV4";

    private static final UUID MY_UUID_INSECURE =
            UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66");

    private static final int WRITE_EXTERNAL_STRORAGE_CODE = 1;

    private final BluetoothAdapter BA;
    Context mContext;

    private AcceptThread insecureAcceptThread;

    private ConnectThread mConnectThread;
    private BluetoothDevice mmDevice;
    private UUID deviceUUID;
    ProgressDialog mProgressDialog;

    String mText;

    private ConnectedThread mConnectedThread;

    public BluetoothConnectionService(Context context) {
        mContext = context;
        BA = BluetoothAdapter.getDefaultAdapter();
        start();
    }
    /**
     * AcceptThread Class
     * local server socket for accepting connection
     * Methods Run, Cancel
     */

    private class AcceptThread extends Thread {
        //local server socket

        private final BluetoothServerSocket mServerSocket;
        public AcceptThread(){
            BluetoothServerSocket tmp = null;

            //create a new listening server socket
            try{
                tmp = BA.listenUsingInsecureRfcommWithServiceRecord(appName, MY_UUID_INSECURE);

                Log.d(TAG, "AcceptThread: Setting up Server using: " + MY_UUID_INSECURE);

            }catch(IOException e) {
                Log.e(TAG, "AcceptThread: IOException" + e.getMessage());
            }

            mServerSocket = tmp;
        }

        public void run() {
            Log.d(TAG, "run: AcceptThread Running.");

            BluetoothSocket socket = null;

            try{
                //this is a blocking call and will only return in successful connection or exception
                Log.d(TAG, "run:RFCOM server socket start...");

                socket = mServerSocket.accept();

                Log.d(TAG, "run: RFCOM server socket accepted connection");
            }catch (IOException e){
                Log.e(TAG, "AcceptThread: IOException" + e.getMessage());
            }

            if (socket != null){
                connected(socket, mmDevice);
            }
            Log.i(TAG, "END AcceptThread ");
        }

        public void cancel(){
            Log.d(TAG, "cancel: canceling AcceptThread.");
            try{
                mServerSocket.close();
            }catch (IOException e){
                Log.e(TAG,"cancel: Close of acceptThread ServerScoket failed. " + e.getMessage() );
            }
        }
    }

    /**
     * ConnectThread Class
     * thread runs while attempting to make outgoing connection with device.
     * connection wither succeeds or fails
     * Methods: Run, Cancel, Start
     */
    private class ConnectThread extends Thread{
        private BluetoothSocket mSocket;

        public ConnectThread(BluetoothDevice device, UUID uuid){
            Log.d(TAG, "ConnectThread: started.");
            mmDevice = device;
            deviceUUID = uuid;
        }

        public void run(){
            BluetoothSocket tmp = null;
            Log.i(TAG, "RUN mConnectThread");

            try{
                Log.d(TAG, "ConnectThread: Trying to create InsecureRfCommsocket using UUID:" + MY_UUID_INSECURE);
                tmp =mmDevice.createInsecureRfcommSocketToServiceRecord(deviceUUID);
            }catch (IOException e) {
                Log.e(TAG, "ConnectThread: couldn't create InsecureRFcommsocket" + e.getMessage());
            }
            mSocket = tmp;

            try{
                mSocket.connect();
                Log.d(TAG, "Run: ConnectThread connected");
            } catch (IOException e) {
                //try to close
                try {
                    mSocket.close();
                    Log.d(TAG, "run:Closed Socket");
                }catch(IOException e1) {
                    Log.e(TAG, "run: mConnectThread: run: Unable to close connection in socket" + e1.getMessage());
                }
                Log.d(TAG, "run: ConnectThread: Couldn't connect to UUID" + MY_UUID_INSECURE);
            }

            connected(mSocket,mmDevice);

        }


        public void cancel(){
            try{
                Log.d(TAG, "cancel: closing Client Socket.");
                mSocket.close();
            }catch (IOException e){
                Log.e(TAG,"cancel: Close() of acceptThread ServerScoket failed. " + e.getMessage() );
            }
        }
    }

    /**
     * Start file exchange. specifically start acceptthread to begin
     * a session in listening(server) mode. Called by activity onResume()
     */
    public synchronized void start(){
        Log.d(TAG, "start");
        //cancel any thread attempting to make a connection
        if(mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }
        if(insecureAcceptThread == null){
            insecureAcceptThread = new AcceptThread();
            insecureAcceptThread.start();
        }
    }

    /**
     * AcceptThread starts and sits waiting for a connection
     * Then connectThread starts and attempts to make a connection with other
     * devices acceptThread
     */

    public void startClient(BluetoothDevice device, UUID uuid){
        Log.d(TAG, "startClient: Started");

        //initprogress dialog
        mProgressDialog = ProgressDialog.show(mContext, "Connect Bluetooth", "Please Wait....", true);
        mConnectThread = new ConnectThread(device, uuid);
        mConnectThread.start();
    }

    /**
     * Connected thread is responsible for maintaining BT Connection, sending data, and receiving incoming data through input/output
     * streams respectively
     * Also Responsible for sending data to text file, and creating one if none exist
     */


    private class ConnectedThread extends Thread {
        private final BluetoothSocket msocket;
        private final InputStream mInputStream;
        private final OutputStream mOutputStream;

        public ConnectedThread(BluetoothSocket socket){
            Log.d(TAG, "ConnectedThread: Starting");
            msocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            //dismiss the progressDialog when connection is established
            mProgressDialog.dismiss();
            try{
                tmpIn = msocket.getInputStream();
                tmpOut = msocket.getOutputStream();
            } catch (IOException e){
                e.printStackTrace();
            }
            mInputStream = tmpIn;
            mOutputStream = tmpOut;
        }

        public void run() {
            byte[] buffer = new byte[1024]; //buffer store for the stream
            int bytes; //bytes returned from read

            //keep listening to inputStream until an exception occus
            while (true) {
                try{
                    bytes = mInputStream.read(buffer);
                    String incomingMessage = new String(buffer, 0, bytes);
                    saveToTxtFile(incomingMessage);
                    Log.d(TAG, "InputStream:" + incomingMessage);
                } catch (IOException e){
                    Log.e(TAG, "write: Error reading input" + e.getMessage());
                    break;
                }
                /*
                //Try for file write
                try{
                    bytes = mInputStream.read(buffer);
                    String msgToWrite = new String(buffer, 0, bytes);
                    mText = msgToWrite.trim();
                    Log.d(TAG, "InputStream to TXT:" + mText);
                    if (mText.isEmpty()){
                        //Toast.makeText(MainActivity.this, "Please enter text...", Toast.LENGTH_SHORT).show();
                    }
                    else{
                        //sys os is <marshmellow
                        saveToTxtFile(mText);
                    }
                }catch (IOException e){
                    Log.e(TAG, "write to text: Error writing output");
                    break;
                }*/
            }
        }
        private void saveToTxtFile(String mText){
            //get current time for file name
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(System.currentTimeMillis());
            //ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, WRITE_EXTERNAL_STRORAGE_CODE);

            try {
                //path to storage
                File path = Environment.getExternalStorageDirectory();
                //check if dir exists
                File dir = new File(path + "/My Files/");
                if(!dir.isDirectory()){
                    dir.mkdirs();
                }
                //make file name, then check if already made
                String fileName = "MyFile.txt"; //e.g. MyFile_20190630_212222.txt
                File file = new File(dir, fileName);

                if(file.exists()){
                    FileWriter fw = new FileWriter(file.getAbsoluteFile(), true);
                    BufferedWriter bw = new BufferedWriter(fw);
                    bw.write(mText);
                    bw.newLine();
                    bw.flush();
                    bw.close();
                    Log.d(TAG, fileName+" is saved to\n"+ dir);
                } else{
                    file.createNewFile();
                    FileWriter fw = new FileWriter(file.getAbsoluteFile());
                    BufferedWriter bw = new BufferedWriter(fw);
                    bw.write(mText);
                    bw.close();

                }
            } catch (IOException e){
                Log.e(TAG, "WritetoText Attempt: Write Failed");
                e.printStackTrace();
            }

        }

        //Call this from main activity to send data to remote device
        public void write(byte[] bytes){
            String text = new String(bytes, Charset.defaultCharset());
            //String mtext = text.trim();
            Log.d(TAG, "Write: writing to outputStream: " + text);
            Log.d(TAG, "WriteToTXT: Writing to file: " +text);
            try{
                mOutputStream.write(bytes);
                saveToTxtFile(text);
            } catch (IOException e){
                Log.e(TAG, "write: Error writing to outputstream. " + e.getMessage());
            }
        }
        //call from main activity to shutdown connection
        public void cancel(){
            try{
                msocket.close();
            } catch (IOException e){}
        }

    }
    private void connected(BluetoothSocket mSocket, BluetoothDevice mDevice) {
        Log.d(TAG, "connected:Starting");

        //Start thread to manage connection & perform Transmissions
        mConnectedThread = new ConnectedThread(mSocket);
        mConnectedThread.start();

    }
    /**
     *
     * Write to connectedThread in unsynchro manner
     * @param out the bytes to write
     * @see ConnectedThread#write(byte[])
     */
    public void write(byte[] out){
        //create temp
        ConnectedThread r;

        //synchronize copy of connected thread

        Log.d(TAG, "Write: Called");
        r = mConnectedThread;
        r.write(out);
    }


}


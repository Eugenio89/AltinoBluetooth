package kr.co.saeon.www.altinobluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/**
 * Created by Deep_Learning on 2/26/2018.
 */
public class BluetoothService {
    private static final String TAG = "AltinoAppService";
    private static final boolean D = true;
    private static final String NAME = "AltinoApp";
    private static final UUID MY_UUID = UUID
            .fromString("00001101-0000-1000-8000-00805F9B34FB");
    private final BluetoothAdapter mAdapter;
    private final Handler mHandler;
    private AcceptThread mAcceptThread;
    private ConnectThread mConnectThread;
    private ConnectedThread mConnectedThread;
    private int mState;
    private byte[] tx_d = new byte[28];
    private byte[] tx_d2 = new byte[22];

    public static final int STATE_NONE = 0;
    public static final int STATE_LISTEN = 1;
    public static final int STATE_CONNECTING = 2;
    public static final int STATE_CONNECTED = 3;

    public BluetoothService(Context context, Handler handler) {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mState = STATE_NONE;
        mHandler = handler;
    }

    private synchronized void setState(int state) {
        if (D)
            Log.d(TAG, "setState() " + mState + " -> " + state);
        mState = state;
        mHandler.obtainMessage(MainActivity.MESSAGE_STATE_CHANGE, state, -1)
                .sendToTarget();
    }

    public synchronized int getState() {
        return mState;
    }

    public synchronized void start() {
        if (D)
            Log.d(TAG, "start");
        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }
        if (mAcceptThread == null) {
            mAcceptThread = new AcceptThread();
            mAcceptThread.start();
        }
        setState(STATE_LISTEN);
    }

    public synchronized void connect(BluetoothDevice device) {
        if (D)
            Log.d(TAG, "connect to: " + device);
        if (mState == STATE_CONNECTING) {
            if (mConnectThread != null) {
                mConnectThread.cancel();
                mConnectThread = null;
            }
        }
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }
        mConnectThread = new ConnectThread(device);
        mConnectThread.start();
        setState(STATE_CONNECTING);
        setState(STATE_CONNECTING);
    }

    public synchronized void connected(BluetoothSocket socket,
                                       BluetoothDevice device) {
        if (D)
            Log.d(TAG, "connected");
        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }
        if (mAcceptThread != null) {
            mAcceptThread.cancel();
            mAcceptThread = null;
        }
        mConnectedThread = new ConnectedThread(socket);
        mConnectedThread.start();
        Message msg =
                mHandler.obtainMessage(MainActivity.MESSAGE_DEVICE_NAME);
        Bundle bundle = new Bundle();
        bundle.putString(MainActivity.DEVICE_NAME, device.getName());
        msg.setData(bundle);
        mHandler.sendMessage(msg);
        setState(STATE_CONNECTED);
    }

    public synchronized void stop() {
        if (D)
            Log.d(TAG, "stop");
        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }
        if (mAcceptThread != null) {
            mAcceptThread.cancel();
            mAcceptThread = null;
        }
        setState(STATE_NONE);
    }

    public void Sendbyte(byte[] Sendbuf) {
        ConnectedThread r;
        synchronized (this) {
            if (mState != STATE_CONNECTED)
                return;
            r = mConnectedThread;
        }
        r.Sendbyte(Sendbuf);
    }

    private void connectionFailed() {
        if (D)
            Log.d(TAG, "connectionFailed");
        setState(STATE_LISTEN);
        Message msg = mHandler.obtainMessage(MainActivity.MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString(MainActivity.TOAST,
                "Unable to connect device");
        msg.setData(bundle);
        mHandler.sendMessage(msg);
    }

    private void connectionLost() {
        if (D)
            Log.d(TAG, "connectionLost");
        setState(STATE_LISTEN);
        Message msg = mHandler.obtainMessage(MainActivity.MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString(MainActivity.TOAST,
                "Device connection was lost");
        msg.setData(bundle);
        mHandler.sendMessage(msg);
        System.exit(0);
    }

    private class AcceptThread extends Thread {
        private final BluetoothServerSocket mmServerSocket;

        public AcceptThread() {
            BluetoothServerSocket tmp = null;
            try {
                tmp = mAdapter.listenUsingRfcommWithServiceRecord(NAME, MY_UUID);
            } catch (IOException e) {
                Log.e(TAG, "listen() failed", e);
            }
            mmServerSocket = tmp;
        }

        @Override
        public void run() {
            if (D)
                Log.d(TAG, "BEGIN mAcceptThread" + this);
            setName("AcceptThread");
            BluetoothSocket socket = null;

            while (mState != STATE_CONNECTED) {
                try {
                    socket = mmServerSocket.accept();
                } catch (IOException e) {
                    Log.e(TAG, "accept() failed", e);
                    break;
                }
                if (socket != null) {
                    synchronized (BluetoothService.this) {
                        switch (mState) {
                            case STATE_LISTEN:
                            case STATE_CONNECTING:
                                connected(socket, socket.getRemoteDevice());
                                break;
                            case STATE_NONE:
                            case STATE_CONNECTED:
                                try {
                                    socket.close();
                                } catch (IOException e) {
                                    Log.e(TAG, "Could not close unwanted socket", e);
                                }
                                break;
                        }
                    }
                }
            }
            if (D)
                Log.i(TAG, "END mAcceptThread");
        }

        public void cancel() {
            if (D)
                Log.d(TAG, "cancel " + this);
            try {
                mmServerSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "close() of server failed", e);
            }
        }
    }

    private class ConnectThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;

        public ConnectThread(BluetoothDevice device) {
            mmDevice = device;
            BluetoothSocket tmp = null;
            try {
                tmp = device.createRfcommSocketToServiceRecord(MY_UUID);
            } catch (IOException e) {
                Log.e(TAG, "create() failed", e);
            }
            mmSocket = tmp;
        }

        @Override
        public void run() {
            Log.i(TAG, "BEGIN mConnectThread");
            setName("ConnectThread");
            mAdapter.cancelDiscovery();
            try {
                mmSocket.connect();
            } catch (IOException e) {
                connectionFailed();
                try {
                    mmSocket.close();
                } catch (IOException e2) {
                    Log.e(TAG,
                            "unable to close() socket during connection failure",
                            e2);
                }
                BluetoothService.this.start();
                return;
            }
            synchronized (BluetoothService.this) {
                mConnectThread = null;
            }
            connected(mmSocket, mmDevice);
        }

        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "close() of connect socket failed", e);
            }
        }
    }

    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;

        public ConnectedThread(BluetoothSocket socket) {
            Log.d(TAG, "create ConnectedThread");
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "temp sockets not created", e);
            }
            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }

        @Override
        public void run() {
            Log.i(TAG, "BEGIN mConnectedThread");
            byte[] buffer2 = new byte[1024];
            int bytes;
            while (true) {
                try {
                    int nBuf = 0;
                    int nFlag = 0;
                    for (int nCnt = 0; nCnt < 31; nCnt++) {
                        nBuf = mmInStream.read();

                        buffer2[nCnt] = (byte) (nBuf);
                    }

                    mHandler.obtainMessage(MainActivity.MESSAGE_READ, nBuf,
                            -1, buffer2).sendToTarget();

                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }


                } catch (IOException e) {
                    Log.e(TAG, "disconnected", e);
                    connectionLost();
                    System.exit(0);
                    break;
                }
            }
        }

        private byte check_sum_tx_calcuration(int u16_cnt) {
            int u16_tx_check_sum = 0;
            int u16_tx_cnt;
            // u16_tx_check_sum = u8_tx_d[0];
            u16_tx_check_sum = u16_tx_check_sum + tx_d[1];
            for (u16_tx_cnt = 3; u16_tx_cnt <= u16_cnt; u16_tx_cnt++) {
                u16_tx_check_sum = u16_tx_check_sum + tx_d[u16_tx_cnt];
            }

            u16_tx_check_sum = u16_tx_check_sum % 256;
            return (byte)(u16_tx_check_sum);
        }
        private byte check_sum_tx_calcuration2(int u16_cnt) {
            int u16_tx_check_sum = 0;
            int u16_tx_cnt;
            // u16_tx_check_sum = u8_tx_d[0];
            u16_tx_check_sum = u16_tx_check_sum + tx_d2[1];
            for (u16_tx_cnt = 3; u16_tx_cnt <= u16_cnt; u16_tx_cnt++) {
                u16_tx_check_sum = u16_tx_check_sum + tx_d2[u16_tx_cnt];
            }

            u16_tx_check_sum = u16_tx_check_sum % 256;
            return (byte)(u16_tx_check_sum);
        }
        public void Sendbyte(byte[] Sendbuf) {
            try {

                mmOutStream.flush();

                tx_d[0] = 0x2;
                tx_d[1] = 28;

                tx_d[3] = 1;
                tx_d[4] = Sendbuf[4];
                tx_d[5] = Sendbuf[5];
                tx_d[6] = Sendbuf[6];
                tx_d[7] = Sendbuf[7];
                tx_d[8] = Sendbuf[8];
                tx_d[9] = Sendbuf[9];
                tx_d[10] = Sendbuf[10];
                tx_d[11] = Sendbuf[11];
                tx_d[12] = Sendbuf[12];
                tx_d[13] = Sendbuf[13];
                tx_d[14] = Sendbuf[14];// Dot1
                tx_d[15] = Sendbuf[15];// Dot2
                tx_d[16] = Sendbuf[16];// Dot3
                tx_d[17] = Sendbuf[17];// Dot4
                tx_d[18] = Sendbuf[18];// Dot5
                tx_d[19] = Sendbuf[19];// Dot6
                tx_d[20] = Sendbuf[20];// Dot7
                tx_d[21] = Sendbuf[21];// IR Out Time Value
                tx_d[22] = Sendbuf[22];
                tx_d[23] = Sendbuf[23];// LED Out
                tx_d[24] = 0;// SP
                tx_d[25] = 0;// SP
                tx_d[26] = 0;// SP
                tx_d[27] = 0x3;

                tx_d[2]=check_sum_tx_calcuration(27);
                mmOutStream.write(tx_d);

            } catch (IOException e) {
                Log.e(TAG, "Exception during write", e);
            }
        }

        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "close() of connect socket failed", e);
            }
        }
    }
}

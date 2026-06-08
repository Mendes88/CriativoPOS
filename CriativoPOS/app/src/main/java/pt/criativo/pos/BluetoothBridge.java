package pt.criativo.pos;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;

public class BluetoothBridge {

    private static final String TAG = "BTBridge";
    private static final UUID SPP_UUID =
        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final Context context;
    private final WebView webView;
    private BluetoothSocket socket;
    private OutputStream outputStream;
    private String connectedName = "";
    private boolean connected = false;

    public BluetoothBridge(Context ctx, WebView wv) {
        this.context = ctx;
        this.webView = wv;
    }

    @JavascriptInterface
    public String listPaired() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) return "[]";
            Set<BluetoothDevice> devices = adapter.getBondedDevices();
            JSONArray arr = new JSONArray();
            for (BluetoothDevice d : devices) {
                JSONObject obj = new JSONObject();
                obj.put("name",    d.getName() != null ? d.getName() : "Desconhecido");
                obj.put("address", d.getAddress());
                arr.put(obj);
            }
            return arr.toString();
        } catch (Exception e) {
            Log.e(TAG, "listPaired: " + e.getMessage());
            return "[]";
        }
    }

    @JavascriptInterface
    public boolean connect(String address) {
        try {
            disconnect();
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) return false;
            BluetoothDevice device = adapter.getRemoteDevice(address);
            adapter.cancelDiscovery();
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
            socket.connect();
            outputStream  = socket.getOutputStream();
            connectedName = device.getName() != null ? device.getName() : address;
            connected     = true;
            Log.d(TAG, "Ligado a: " + connectedName);
            notify("btConnected", connectedName);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "connect: " + e.getMessage());
            connected = false;
            notify("btError", e.getMessage());
            return false;
        }
    }

    @JavascriptInterface
    public boolean print(String base64data) {
        if (!connected || outputStream == null) return false;
        try {
            byte[] data = Base64.decode(base64data, Base64.DEFAULT);
            int CHUNK = 4096;
            for (int i = 0; i < data.length; i += CHUNK) {
                int len = Math.min(CHUNK, data.length - i);
                outputStream.write(data, i, len);
                outputStream.flush();
                Thread.sleep(20);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "print: " + e.getMessage());
            connected = false;
            notify("btError", e.getMessage());
            return false;
        }
    }

    @JavascriptInterface
    public void disconnect() {
        try {
            if (outputStream != null) { outputStream.close(); outputStream = null; }
            if (socket != null)       { socket.close();       socket = null;       }
        } catch (Exception ignored) {}
        connected     = false;
        connectedName = "";
    }

    @JavascriptInterface
    public boolean isConnected() { return connected; }

    @JavascriptInterface
    public String getConnectedName() { return connectedName; }

    private void notify(final String event, final String data) {
        webView.post(new Runnable() {
            @Override public void run() {
                webView.evaluateJavascript(
                    "if(window.onBTEvent) window.onBTEvent('" + event + "','" +
                    data.replace("'", "\\'") + "')", null);
            }
        });
    }
}

package pt.criativo.pos;

import android.app.Activity;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.util.Log;

import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreSettings;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.Timestamp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Date;

public class FirebaseBridge {
    private static final String TAG = "CaixaFB";
    private final Activity activity;
    private final WebView webView;
    private final FirebaseFirestore db;
    private ListenerRegistration mesasListener;

    public FirebaseBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView  = webView;

        FirebaseFirestoreSettings settings = new FirebaseFirestoreSettings.Builder()
            .setPersistenceEnabled(true)
            .setCacheSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
            .build();
        FirebaseFirestore instance = FirebaseFirestore.getInstance();
        instance.setFirestoreSettings(settings);
        this.db = instance;
    }

    /** Iniciar listener de mesas abertas — pedidos de hoje com estado != fechado */
    @JavascriptInterface
    public void iniciarListenerMesas() {
        if (mesasListener != null) mesasListener.remove();

        // Início do dia actual
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        Timestamp inicioDia = new Timestamp(new Date(cal.getTimeInMillis()));

        mesasListener = db.collection("mesas")
            .whereEqualTo("estado", "aguarda_pagamento")
            .addSnapshotListener((snap, e) -> {
                if (e != null) {
                    Log.e(TAG, "Mesas listener: " + e.getMessage());
                    emitir("fbMesasErro", e.getMessage() != null ? e.getMessage() : "erro");
                    return;
                }
                if (snap == null) return;
                try {
                    JSONArray arr = new JSONArray();
                    for (com.google.firebase.firestore.QueryDocumentSnapshot doc : snap) {
                        try {
                            JSONObject m = new JSONObject();
                            m.put("id",        doc.getId());
                            m.put("nome",      doc.getString("nome")        != null ? doc.getString("nome")        : "");
                            m.put("func",      doc.getString("funcionario") != null ? doc.getString("funcionario") : "");
                            m.put("estado",    doc.getString("estado")      != null ? doc.getString("estado")      : "aberta");
                            m.put("total",     doc.getDouble("total")       != null ? doc.getDouble("total")       : 0.0);
                            m.put("abertoEm",  doc.getLong("abertoEm")      != null ? doc.getLong("abertoEm")      : 0L);
                            m.put("items",     doc.getString("items")       != null ? doc.getString("items")       : "[]");
                            arr.put(m);
                        } catch (Exception ignored) {}
                    }
                    emitir("fbMesasActualizadas", arr.toString());
                } catch (Exception ex) {
                    Log.e(TAG, "Mesas parse: " + ex.getMessage());
                }
            });
        Log.d(TAG, "Listener mesas iniciado");
    }

    /** Fechar mesa — marca como fechada no Firestore */
    @JavascriptInterface
    public void fecharMesa(String mesaId, String totalStr) {
        try {
            double total = Double.parseDouble(totalStr);
            db.collection("mesas").document(mesaId)
                .update("estado", "fechada", "total", total, "fechadoEm", System.currentTimeMillis())
                .addOnSuccessListener(v -> emitir("fbMesaFechada", mesaId))
                .addOnFailureListener(ex -> emitir("fbErro", "fecharMesa: " + ex.getMessage()));
        } catch (Exception e) {
            emitir("fbErro", "fecharMesa: " + e.getMessage());
        }
    }

    /** Parar listener */
    @JavascriptInterface
    public void pararListenerMesas() {
        if (mesasListener != null) { mesasListener.remove(); mesasListener = null; }
    }

    public void destroy() {
        pararListenerMesas();
    }

    private void emitir(String evento, String detalhe) {
        String safe = detalhe != null ? detalhe.replace("\\", "\\\\").replace("'", "\\'") : "";
        String js = "window.dispatchEvent(Object.assign(new Event('" + evento + "'),{detail:'" + safe + "'}));";
        activity.runOnUiThread(() -> {
            if (webView != null) webView.evaluateJavascript(js, null);
        });
    }
}

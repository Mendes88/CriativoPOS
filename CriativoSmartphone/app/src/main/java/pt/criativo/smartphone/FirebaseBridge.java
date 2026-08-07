package pt.criativo.smartphone;

import android.app.Activity;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.content.Context;
import android.webkit.WebView;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreSettings;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.Transaction;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * FirebaseBridge v2 — ponte JS ↔ Firestore
 *
 * Métodos disponíveis no JavaScript via AndroidFB:
 *
 *   AndroidFB.abrirMesa(jsonString)
 *     → { mesaId, nome, funcionario }
 *
 *   AndroidFB.enviarPedido(jsonString)
 *     → { mesaId, mesaNome, funcionario, items: [...], notas }
 *     items: [{ nome, preco, qtd, destino }]
 *
 *   AndroidFB.enviarPedidoComFases(jsonString)
 *     → { mesaId, mesaNome, funcionario,
 *         imediatos: [...], bloqueados: [...], notas }
 *
 *   AndroidFB.libertarFase(pedidoId)
 *     → muda estado bloqueado → pendente
 *
 *   AndroidFB.actualizarEstado(pedidoId, novoEstado)
 *     → estados: pendente | em_preparacao | pronto | bloqueado
 *
 *   AndroidFB.fecharMesa(mesaId)
 *     → marca mesa como fechada
 *
 *   AndroidFB.iniciarKDS(destino)
 *     → destino: "cozinha" | "bar" | "todos"
 *
 *   AndroidFB.pararKDS()
 *
 *   AndroidFB.iniciarListenerMesas(funcionario)
 *     → listener de mesas abertas para o ecrã principal
 *
 *   AndroidFB.pararListenerMesas()
 *
 * Eventos disparados para o JS via window.dispatchEvent:
 *   fbMesaAberta          → detail: mesaId
 *   fbPedidoEnviado       → detail: pedidoId
 *   fbFaseEnviada         → detail: JSON { pedidoId, pedidoBloqId }
 *   fbEstadoActualizado   → detail: "pedidoId|novoEstado"
 *   fbMesaFechada         → detail: mesaId
 *   fbKDS_iniciado        → detail: "ok"
 *   fbKDS_novo            → detail: JSON pedido
 *   fbKDS_alterado        → detail: JSON pedido
 *   fbKDS_removido        → detail: JSON pedido
 *   fbMesas_actualizado   → detail: JSON array de mesas
 *   fbErro                → detail: mensagem
 */
public class FirebaseBridge {

    private static final String TAG = "FirebaseBridge";

    // Colecções Firestore
    private static final String COL_PEDIDOS  = "pedidos";
    private static final String COL_MESAS    = "mesas";
    private static final String COL_CONTADORES = "contadores";
    private static final String DOC_CONTADOR   = "pedidos";

    private final Activity activity;
    private final WebView  webView;
    private final FirebaseFirestore db;

    private ListenerRegistration kdsListener;
    private ListenerRegistration mesasListener;
    private ListenerRegistration pedidosListener;

    public FirebaseBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView  = webView;
        // Persistência offline — funciona sem internet, sincroniza quando voltar
        FirebaseFirestoreSettings settings = new FirebaseFirestoreSettings.Builder()
            .setPersistenceEnabled(true)
            .setCacheSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
            .build();
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        db.setFirestoreSettings(settings);
        this.db = db;
        garantirContador();
        // Listener em tempo real para modo de trabalho
        iniciarListenerModo();
    }

    private com.google.firebase.firestore.ListenerRegistration listenerModo = null;

    private void iniciarListenerModo() {
        if (listenerModo != null) listenerModo.remove();
        listenerModo = db.collection("config").document("activacao")
            .addSnapshotListener((doc, e) -> {
                if (e != null || doc == null || !doc.exists()) return;
                Object modoObj = doc.getData().get("modo_trabalho");
                String modo = modoObj != null ? modoObj.toString() : "pos";
                // Guardar localmente
                activity.getSharedPreferences("CriatvSmartphone", android.app.Activity.MODE_PRIVATE)
                    .edit().putString("modo_trabalho", modo).apply();
                Log.d(TAG, "Modo de trabalho: " + modo);
                emitir("fbModoTrabalho", modo);
            });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MESAS
    // ─────────────────────────────────────────────────────────────────────────

    @JavascriptInterface
    public void abrirMesa(String jsonString) {
        try {
            JSONObject j = new JSONObject(jsonString);
            Map<String, Object> mesa = new HashMap<>();
            mesa.put("nome",        j.optString("nome", "Mesa"));
            mesa.put("funcionario", j.optString("funcionario", ""));
            mesa.put("estado",      "aberta");
            mesa.put("aberta_em",   Timestamp.now());
            mesa.put("total",       0.0);

            db.collection(COL_MESAS)
              .add(mesa)
              .addOnSuccessListener(ref -> {
                  Log.d(TAG, "Mesa aberta: " + ref.getId());
                  emitir("fbMesaAberta", ref.getId());
              })
              .addOnFailureListener(e -> emitir("fbErro", "abrirMesa: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));

        } catch (Exception e) {
            emitir("fbErro", "abrirMesa parse: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    @JavascriptInterface
    public void fecharMesa(String mesaId) {
        if (mesaId == null || mesaId.isEmpty()) return;
        Map<String, Object> update = new HashMap<>();
        update.put("estado",    "fechada");
        update.put("fechada_em", Timestamp.now());

        db.collection(COL_MESAS).document(mesaId)
          .update(update)
          .addOnSuccessListener(v -> emitir("fbMesaFechada", mesaId))
          .addOnFailureListener(e -> emitir("fbErro", "fecharMesa: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PEDIDO NORMAL (sem fases)
    // ─────────────────────────────────────────────────────────────────────────

    @JavascriptInterface
    public void enviarPedido(String jsonString) {
        try {
            JSONObject j = new JSONObject(jsonString);
            String mesaId    = j.optString("mesaId", "");
            String mesaNome  = j.optString("mesaNome", "");
            String func      = j.optString("funcionario", "");
            String notas     = j.optString("notas", "");
            String itemsStr  = j.optString("items", "[]");
            double total     = calcularTotal(new JSONArray(itemsStr));

            obterProximoNumero(numero -> {
                Map<String, Object> pedido = buildPedido(
                    numero, mesaId, mesaNome, func,
                    itemsStr, notas, total,
                    "pendente", null, 1
                );
                db.collection(COL_PEDIDOS)
                  .add(pedido)
                  .addOnSuccessListener(ref -> {
                      actualizarTotalMesa(mesaId, total);
                      try {
                          org.json.JSONObject resp = new org.json.JSONObject();
                          resp.put("id",     ref.getId());
                          resp.put("numero", numero);
                          emitir("fbPedidoEnviado", resp.toString());
                      } catch (Exception ex) {
                          emitir("fbPedidoEnviado", ref.getId());
                      }
                  })
                  .addOnFailureListener(e -> emitir("fbErro", "enviarPedido: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
            });

        } catch (Exception e) {
            emitir("fbErro", "enviarPedido parse: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PEDIDO COM FASES
    // imediatos → estado: pendente  (KDS vê imediatamente)
    // bloqueados → estado: bloqueado (KDS ignora até libertarFase)
    // ─────────────────────────────────────────────────────────────────────────

    @JavascriptInterface
    public void enviarPedidoComFases(String jsonString) {
        try {
            JSONObject j         = new JSONObject(jsonString);
            String mesaId        = j.optString("mesaId", "");
            String mesaNome      = j.optString("mesaNome", "");
            String func          = j.optString("funcionario", "");
            String notas         = j.optString("notas", "");
            String imediatosStr  = j.optString("imediatos", "[]");
            String bloqueadosStr = j.optString("bloqueados", "[]");

            double totalImed = calcularTotal(new JSONArray(imediatosStr));
            double totalBloq = calcularTotal(new JSONArray(bloqueadosStr));
            double totalGeral = totalImed + totalBloq;

            obterProximoNumero(numero -> {
                // Pedido imediato (fase 1)
                Map<String, Object> fase1 = buildPedido(
                    numero, mesaId, mesaNome, func,
                    imediatosStr, notas, totalImed,
                    "pendente", null, 1
                );

                db.collection(COL_PEDIDOS)
                  .add(fase1)
                  .addOnSuccessListener(ref1 -> {
                      String pedidoPaiId = ref1.getId();

                      // Pedido bloqueado (fase 2) — só existe se houver itens
                      JSONArray bloqArr;
                      try { bloqArr = new JSONArray(bloqueadosStr); }
                      catch (Exception ex) { bloqArr = new JSONArray(); }

                      if (bloqArr.length() > 0) {
                          Map<String, Object> fase2 = buildPedido(
                              numero + "b", mesaId, mesaNome, func,
                              bloqueadosStr, "", totalBloq,
                              "bloqueado", pedidoPaiId, 2
                          );
                          db.collection(COL_PEDIDOS)
                            .add(fase2)
                            .addOnSuccessListener(ref2 -> {
                                actualizarTotalMesa(mesaId, totalGeral);
                                try {
                                    JSONObject r = new JSONObject();
                                    r.put("pedidoId",     pedidoPaiId);
                                    r.put("pedidoBloqId", ref2.getId());
                                    emitir("fbFaseEnviada", r.toString());
                                } catch (Exception ex) {
                                    emitir("fbFaseEnviada", pedidoPaiId);
                                }
                            })
                            .addOnFailureListener(e -> emitir("fbErro", "fase2: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
                      } else {
                          actualizarTotalMesa(mesaId, totalGeral);
                          emitir("fbPedidoEnviado", pedidoPaiId);
                      }
                  })
                  .addOnFailureListener(e -> emitir("fbErro", "fase1: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
            });

        } catch (Exception e) {
            emitir("fbErro", "enviarPedidoComFases parse: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LIBERTAR FASE BLOQUEADA → pendente
    // ─────────────────────────────────────────────────────────────────────────

    @JavascriptInterface
    public void libertarFase(String pedidoId) {
        if (pedidoId == null || pedidoId.isEmpty()) return;
        Map<String, Object> update = new HashMap<>();
        update.put("estado",    "pendente");
        update.put("criado_em", Timestamp.now()); // timestamp real de envio à cozinha

        db.collection(COL_PEDIDOS).document(pedidoId)
          .update(update)
          .addOnSuccessListener(v -> emitir("fbEstadoActualizado", pedidoId + "|pendente"))
          .addOnFailureListener(e -> emitir("fbErro", "libertarFase: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ACTUALIZAR ESTADO
    // estados válidos: pendente | em_preparacao | pronto | bloqueado
    // ─────────────────────────────────────────────────────────────────────────

    @JavascriptInterface
    public void actualizarEstado(String pedidoId, String novoEstado) {
        if (pedidoId == null || pedidoId.isEmpty()) return;
        if (novoEstado == null || novoEstado.isEmpty()) return;
        Map<String, Object> update = new HashMap<>();
        update.put("estado", novoEstado);

        // Timestamps automáticos por estado
        switch (novoEstado) {
            case "em_preparacao":
                update.put("inicio_prep_em", Timestamp.now());
                break;
            case "pronto":
                update.put("concluido_em", Timestamp.now());
                break;
        }

        db.collection(COL_PEDIDOS).document(pedidoId)
          .update(update)
          .addOnSuccessListener(v ->
              emitir("fbEstadoActualizado", pedidoId + "|" + novoEstado))
          .addOnFailureListener(e ->
              emitir("fbErro", "actualizarEstado: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // KDS LISTENER
    // Filtra por destino (cozinha | bar | todos)
    // Exclui estado "bloqueado" — invisível no KDS
    // ─────────────────────────────────────────────────────────────────────────

    @JavascriptInterface
    public void iniciarKDS(String destino) {
        if (kdsListener != null) return;

        Query query = db.collection(COL_PEDIDOS)
            .whereNotEqualTo("estado", "bloqueado")
            .orderBy("estado")
            .orderBy("criado_em", Query.Direction.ASCENDING);

        // Filtro por destino se não for "todos"
        // Nota: o Firestore não suporta OR nativo, o KDS filtra em JS
        // quando destino="todos". Para cozinha/bar usamos campo "destinos"

        kdsListener = query.addSnapshotListener((snapshots, e) -> {
            if (e != null) {
                emitir("fbErro", "KDS: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                return;
            }
            if (snapshots == null) return;

            for (DocumentChange dc : snapshots.getDocumentChanges()) {
                try {
                    Map<String, Object> data = dc.getDocument().getData();

                    // Filtrar por destino no lado cliente
                    if (!"todos".equals(destino)) {
                        String destinosStr = String.valueOf(
                            data.getOrDefault("destinos", ""));
                        if (!destinosStr.contains(destino)) continue;
                    }

                    JSONObject obj = new JSONObject();
                    obj.put("id",         dc.getDocument().getId());
                    obj.put("numero",     data.getOrDefault("numero",   "?").toString());
                    obj.put("mesa",       data.getOrDefault("mesaNome", "").toString());
                    obj.put("items",      data.getOrDefault("items",    "[]").toString());
                    obj.put("notas",      data.getOrDefault("notas",    "").toString());
                    obj.put("estado",     data.getOrDefault("estado",   "pendente").toString());
                    obj.put("destinos",   data.getOrDefault("destinos", "").toString());

                    // Tempo desde criação
                    Object criado = data.get("criado_em");
                    if (criado instanceof Timestamp) {
                        long minutos = (System.currentTimeMillis() / 1000
                            - ((Timestamp) criado).getSeconds()) / 60;
                        obj.put("minutos", minutos);
                    } else {
                        obj.put("minutos", 0);
                    }

                    String tipo;
                    switch (dc.getType()) {
                        case ADDED:    tipo = "fbKDS_novo";     break;
                        case MODIFIED: tipo = "fbKDS_alterado"; break;
                        case REMOVED:  tipo = "fbKDS_removido"; break;
                        default:       tipo = "fbKDS_evento";
                    }
                    emitir(tipo, obj.toString());

                } catch (Exception ex) {
                    Log.e(TAG, "KDS parse: " + (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
                }
            }
        });

        emitir("fbKDS_iniciado", "ok");
    }

    @JavascriptInterface
    public void pararKDS() {
        if (kdsListener != null) {
            kdsListener.remove();
            kdsListener = null;
            emitir("fbKDS_parado", "ok");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LISTENER MESAS (ecrã principal do POS)
    // ─────────────────────────────────────────────────────────────────────────

    @JavascriptInterface
    public void iniciarListenerMesas(String funcionario) {
        if (mesasListener != null) return;

        mesasListener = db.collection(COL_MESAS)
            .whereEqualTo("estado", "em_servico")
            
            .addSnapshotListener((snapshots, e) -> {
                if (e != null) {
                    emitir("fbErro", "mesas: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                    return;
                }
                if (snapshots == null) return;

                try {
                    JSONArray arr = new JSONArray();
                    for (com.google.firebase.firestore.DocumentSnapshot doc : snapshots.getDocuments()) {
                        Map<String, Object> d = doc.getData();
                        JSONObject m = new JSONObject();
                        m.put("id",          doc.getId());
                        m.put("nome",        d.getOrDefault("nome",        "").toString());
                        m.put("funcionario", d.getOrDefault("funcionario", "").toString());
                        m.put("total",       d.getOrDefault("total",       0));
                        m.put("minha",       funcionario.equals(
                            d.getOrDefault("funcionario","").toString()));

                        Object ab = d.containsKey("abertoEm") ? d.get("abertoEm") : d.get("aberta_em");
                        if (ab instanceof Timestamp) {
                            long min = (System.currentTimeMillis() / 1000
                                - ((Timestamp) ab).getSeconds()) / 60;
                            m.put("minutos", min);
                        } else {
                            m.put("minutos", 0);
                        }
                        arr.put(m);
                    }
                    emitir("fbMesas_actualizado", arr.toString());
                } catch (Exception ex) {
                    emitir("fbErro", "mesas parse: " + (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
                }
            });
    }

    @JavascriptInterface
    public void pararListenerMesas() {
        if (mesasListener != null) {
            mesasListener.remove();
            mesasListener = null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS PRIVADOS
    // ─────────────────────────────────────────────────────────────────────────

    /** Contador atómico — garante número único mesmo com múltiplos telemóveis */
    private void obterProximoNumero(NumeroCallback callback) {
        // Contador de turno - reseta quando chefe faz Fecho de Cozinha
        DocumentReference ref = db.collection(COL_CONTADORES).document("turno_actual");
        db.runTransaction((Transaction.Function<Long>) transaction -> {
            com.google.firebase.firestore.DocumentSnapshot snap = transaction.get(ref);
            long actual = snap.exists() && snap.contains("total") ?
                snap.getLong("total") : 0L;
            long proximo = actual + 1;
            if (snap.exists()) {
                transaction.update(ref, "total", proximo);
            } else {
                java.util.Map<String, Object> dados = new java.util.HashMap<>();
                dados.put("total", proximo);
                transaction.set(ref, dados);
            }
            return proximo;
        }).addOnSuccessListener(callback::onNumero)
          .addOnFailureListener(e -> {
              Log.e(TAG, "contador: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
              callback.onNumero(System.currentTimeMillis());
          });
    }

    private interface NumeroCallback {
        void onNumero(long numero);
    }

    /** Constrói o mapa de um documento de pedido */
    private Map<String, Object> buildPedido(
            Object numero, String mesaId, String mesaNome,
            String funcionario, String itemsStr, String notas,
            double total, String estado, String pedidoPaiId, int fase) {

        // Calcular destinos presentes nos items
        String destinos = calcularDestinos(itemsStr);

        Map<String, Object> p = new HashMap<>();
        p.put("numero",      numero);
        p.put("mesaId",      mesaId);
        p.put("mesaNome",    mesaNome);
        p.put("funcionario", funcionario);
        p.put("items",       itemsStr);
        p.put("notas",       notas);
        p.put("total",       total);
        p.put("estado",      estado);
        p.put("fase",        fase);
        p.put("destinos",    destinos);
        p.put("criado_em",   Timestamp.now());
        // hora formatada para o KDS
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault());
        p.put("hora", sdf.format(new java.util.Date()));

        if (pedidoPaiId != null) {
            p.put("pedidoPaiId", pedidoPaiId);
        }

        return p;
    }

    /** Calcula total a partir do array de items JSON */
    private double calcularTotal(JSONArray items) {
        double total = 0;
        try {
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.getJSONObject(i);
                total += it.optDouble("preco", 0)
                       * it.optInt("qtd", 1);
            }
        } catch (Exception e) {
            Log.e(TAG, "calcularTotal: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
        return Math.round(total * 100.0) / 100.0;
    }

    /** Extrai os destinos únicos dos items (ex: "cozinha,bar") */
    private String calcularDestinos(String itemsStr) {
        boolean temCozinha = false, temBar = false;
        try {
            JSONArray arr = new JSONArray(itemsStr);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.getJSONObject(i);
                // Aceitar tanto "destino" como "d"
                String dest = item.optString("destino", null);
                if (dest == null || dest.isEmpty()) dest = item.optString("d", "cozinha");
                if (dest == null || dest.isEmpty()) dest = "cozinha";
                if ("bar".equals(dest)) temBar = true;
                else                    temCozinha = true;
            }
        } catch (Exception e) {
            return "cozinha";
        }
        if (temCozinha && temBar) return "cozinha,bar";
        if (temBar)               return "bar";
        return "cozinha";
    }

    /** Actualiza o total acumulado da mesa */
    private void actualizarTotalMesa(String mesaId, double valor) {
        if (mesaId == null || mesaId.isEmpty()) return;
        db.collection(COL_MESAS).document(mesaId)
          .update("total", FieldValue.increment(valor));
    }

    /** Garante que o documento contador existe */
    private void garantirContador() {
        DocumentReference ref = db.collection(COL_CONTADORES)
                                   .document(DOC_CONTADOR);
        ref.get().addOnSuccessListener(snap -> {
            if (!snap.exists()) {
                Map<String, Object> init = new HashMap<>();
                init.put("total", 0L);
                ref.set(init);
                Log.d(TAG, "Contador inicializado");
            }
        });
    }

    /** Emite evento para o JavaScript */
    private void emitir(String tipo, String dados) {
        if (dados == null) dados = "";
        if (tipo  == null) tipo  = "fbErro";
        // Escapar para JS inline seguro — incluindo Unicode (evita corrupcao de acentos)
        StringBuilder sb = new StringBuilder();
        for (char c : dados.toCharArray()) {
            if (c == '\\') sb.append("\\\\");
            else if (c == '\'') sb.append("\\'");
            else if (c == '\n' || c == '\r') sb.append(' ');
            else if (c > 127) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        String esc = sb.toString();
        String js = "window.dispatchEvent(new CustomEvent('"
                  + tipo + "',{detail:'" + esc + "'}));";
        final String jsFinal = js;
        activity.runOnUiThread(() -> {
            if (webView != null && jsFinal != null) {
                webView.evaluateJavascript(jsFinal, null);
            }
        });
    }

    /** Carregar menu do Firestore */
    @JavascriptInterface
    public void carregarMenu() {
        db.collection("menu")
            .get() // ordenação feita no cliente
            .addOnSuccessListener(catSnap -> {
                try {
                    org.json.JSONObject resultado = new org.json.JSONObject();
                    org.json.JSONArray cats = new org.json.JSONArray();
                    org.json.JSONObject artigosPorCat = new org.json.JSONObject();
                    final int total = catSnap.size();
                    final int[] concluidos = {0};

                    if (total == 0) {
                        resultado.put("cats", cats);
                        resultado.put("artigos", artigosPorCat);
                        emitir("fbMenuCarregado", resultado.toString());
                        return;
                    }

                    for (com.google.firebase.firestore.QueryDocumentSnapshot catDoc : catSnap) {
                        try {
                            org.json.JSONObject cat = new org.json.JSONObject();
                            cat.put("id",    catDoc.getId());
                            cat.put("nome",  catDoc.getString("nome") != null ? catDoc.getString("nome") : catDoc.getId());
                            cat.put("ordem", catDoc.getLong("ordem") != null ? catDoc.getLong("ordem") : 0);
                            cats.put(cat);
                        } catch (Exception ignored) {}

                        final String catId = catDoc.getId();
                        catDoc.getReference().collection("artigos")
                            .get() // ordenação feita no cliente
                            .addOnSuccessListener(artSnap -> {
                                try {
                                    org.json.JSONArray artigos = new org.json.JSONArray();
                                    for (com.google.firebase.firestore.QueryDocumentSnapshot artDoc : artSnap) {
                                        try {
                                            org.json.JSONObject art = new org.json.JSONObject();
                                            art.put("id",      artDoc.getId());
                                            art.put("nome",    artDoc.getString("nome") != null ? artDoc.getString("nome") : "");
                                            // preco pode ser int64 ou double — tentar ambos
                                            Double preco = artDoc.getDouble("preco");
                                            if (preco == null) {
                                                Long precoLong = artDoc.getLong("preco");
                                                preco = precoLong != null ? precoLong.doubleValue() : 0.0;
                                            }
                                            art.put("preco", preco);
                                            art.put("destino", artDoc.getString("destino") != null ? artDoc.getString("destino") : "cozinha");
                                            art.put("ordem",   artDoc.getLong("ordem") != null ? artDoc.getLong("ordem") : 0);
                                            Object ativo = artDoc.get("ativo");
                                            art.put("ativo", ativo == null || Boolean.TRUE.equals(ativo));
                                            art.put("cat", catId); // categoria para debug
                                            artigos.put(art);
                                        } catch (Exception ignored) {}
                                    }
                                    try { artigosPorCat.put(catId, artigos); } catch (Exception ignored) {}
                                } catch (Exception ignored) {}
                                concluidos[0]++;
                                if (concluidos[0] >= total) {
                                    try {
                                        resultado.put("cats", cats);
                                        resultado.put("artigos", artigosPorCat);
                                        emitir("fbMenuCarregado", resultado.toString());
                                    } catch (Exception ignored) {}
                                }
                            })
                            .addOnFailureListener(e -> {
                                concluidos[0]++;
                                if (concluidos[0] >= total) {
                                    try {
                                        resultado.put("cats", cats);
                                        resultado.put("artigos", artigosPorCat);
                                        emitir("fbMenuCarregado", resultado.toString());
                                    } catch (Exception ignored) {}
                                }
                            });
                    }
                } catch (Exception e) {
                    emitir("fbErro", "carregarMenu: " + (e.getMessage() != null ? e.getMessage() : "erro"));
                }
            })
            .addOnFailureListener(e -> emitir("fbErro", "carregarMenu: " + (e.getMessage() != null ? e.getMessage() : "erro")));
    }

    /** Criar mesa no Firestore — estado em_servico (não visível na caixa ainda) */
    @JavascriptInterface
    public void criarMesa(String mesaId, String payloadJson) {
        try {
            org.json.JSONObject data = new org.json.JSONObject(payloadJson);
            java.util.Map<String, Object> mesa = new java.util.HashMap<>();
            mesa.put("nome",        data.optString("nome", ""));
            mesa.put("funcionario", data.optString("funcionario", ""));
            mesa.put("estado",      "em_servico"); // não aparece na caixa
            mesa.put("total",       data.optDouble("total", 0.0));
            mesa.put("items",       data.optString("items", "[]"));
            mesa.put("abertoEm",    data.optLong("abertoEm", System.currentTimeMillis()));
            mesa.put("criado_em",   com.google.firebase.Timestamp.now());

            db.collection("mesas").document(mesaId)
                .set(mesa)
                .addOnSuccessListener(v -> emitir("fbMesaCriada", mesaId))
                .addOnFailureListener(e -> emitir("fbErro", "criarMesa: " + (e.getMessage() != null ? e.getMessage() : "erro")));
        } catch (Exception e) {
            emitir("fbErro", "criarMesa parse: " + (e.getMessage() != null ? e.getMessage() : "erro"));
        }
    }

    /** Enviar conta para a caixa — muda estado para aguarda_pagamento */
    @JavascriptInterface
    public void enviarParaCaixa(String mesaId, String mesaNome, String funcionario, String totalStr, String itensJson) {
        try {
            double total = Double.parseDouble(totalStr);
            // Verificar estado actual da mesa antes de enviar para caixa
            db.collection("mesas").document(mesaId).get()
                .addOnSuccessListener(doc -> {
                    if (!doc.exists()) {
                        emitir("fbMesaJaFechada", mesaId);
                        return;
                    }
                    String estadoActual = String.valueOf(doc.getData().getOrDefault("estado", ""));
                    if ("fechado".equals(estadoActual)) {
                        // Mesa ja foi fechada pela caixa - nao reabrir
                        emitir("fbMesaJaFechada", mesaId);
                        return;
                    }
                    // Mesa ainda activa - pode enviar para caixa
                    java.util.Map<String, Object> update = new java.util.HashMap<>();
                    update.put("estado",           "aguarda_pagamento");
                    update.put("total",            total);
                    update.put("items",            itensJson);
                    update.put("nome",             mesaNome);
                    update.put("funcionario",      funcionario);
                    update.put("enviado_caixa_em", com.google.firebase.Timestamp.now());
                    db.collection("mesas").document(mesaId)
                        .update(update)
                        .addOnSuccessListener(v -> emitir("fbContaEnviada", mesaId))
                        .addOnFailureListener(e -> emitir("fbErro", "enviarParaCaixa: " + (e.getMessage() != null ? e.getMessage() : "erro")));
                })
                .addOnFailureListener(e -> emitir("fbErro", "enviarParaCaixa get: " + (e.getMessage() != null ? e.getMessage() : "erro")));
        } catch (Exception e) {
            emitir("fbErro", "enviarParaCaixa: " + (e.getMessage() != null ? e.getMessage() : "erro"));
        }
    }

    /** Actualizar items e total da mesa no Firestore */
    @JavascriptInterface
    public void actualizarMesa(String mesaId, String itemsJson, String totalStr) {
        try {
            double total = Double.parseDouble(totalStr);
            java.util.Map<String, Object> update = new java.util.HashMap<>();

            // Nunca gravar items vazios se o total for maior que zero
            // Isso causaria mesas com valor mas sem artigos listados
            boolean itemsVazios = itemsJson == null || itemsJson.equals("[]") || itemsJson.isEmpty();
            if (!itemsVazios) {
                update.put("items", itemsJson);
            } else if (total <= 0) {
                // Total zero e items vazios - pode gravar
                update.put("items", "[]");
            }
            // Se items vazios mas total > 0: nao actualizar items, manter o que esta no Firestore

            update.put("total", total);
            update.put("atualizado_em", com.google.firebase.Timestamp.now());

            if (update.size() > 1) { // pelo menos total + atualizado_em
                db.collection("mesas").document(mesaId)
                    .update(update)
                    .addOnFailureListener(e -> android.util.Log.e("CriativoFB", "actualizarMesa: " + e.getMessage()));
            }
        } catch (Exception e) {
            android.util.Log.e("CriativoFB", "actualizarMesa: " + (e.getMessage() != null ? e.getMessage() : "erro"));
        }
    }

    /** Fechar mesa no Firestore */
    @JavascriptInterface
    public void fecharMesa(String mesaId, String totalStr) {
        try {
            double total = Double.parseDouble(totalStr);
            java.util.Map<String, Object> update = new java.util.HashMap<>();
            update.put("estado",     "fechada");
            update.put("total",      total);
            update.put("fechado_em", com.google.firebase.Timestamp.now());

            db.collection("mesas").document(mesaId)
                .update(update)
                .addOnSuccessListener(v -> emitir("fbMesaFechada", mesaId))
                .addOnFailureListener(e -> emitir("fbErro", "fecharMesa: " + (e.getMessage() != null ? e.getMessage() : "erro")));
        } catch (Exception e) {
            emitir("fbErro", "fecharMesa: " + (e.getMessage() != null ? e.getMessage() : "erro"));
        }
    }

    /** Listener em tempo real para pedidos da mesa activa */
    @JavascriptInterface
    public void iniciarListenerPedidos(String mesaId) {
        pararListenerPedidos();
        if (mesaId == null || mesaId.isEmpty()) return;
        Log.d(TAG, "iniciarListenerPedidos: mesaId=" + mesaId);
        pedidosListener = db.collection(COL_PEDIDOS)
            .whereEqualTo("mesaId", mesaId)
            .addSnapshotListener((snapshots, e) -> {
                if (e != null) {
                    Log.e(TAG, "pedidosListener erro: " + e.getMessage());
                    emitir("fbErro", "pedidosListener: " + e.getMessage());
                    return;
                }
                if (snapshots == null) return;
                try {
                    org.json.JSONArray arr = new org.json.JSONArray();
                    for (com.google.firebase.firestore.QueryDocumentSnapshot doc : snapshots) {
                        try {
                            org.json.JSONObject p = new org.json.JSONObject();
                            p.put("id",       doc.getId());
                            p.put("num",      doc.getLong("numero") != null ? doc.getLong("numero") : 0);
                            p.put("numero",   doc.getLong("numero") != null ? doc.getLong("numero") : 0);
                            p.put("func",     doc.getString("funcionario") != null ? doc.getString("funcionario") : "");
                            p.put("mesaId",   doc.getString("mesaId") != null ? doc.getString("mesaId") : "");
                            p.put("mesaNome", doc.getString("mesaNome") != null ? doc.getString("mesaNome") : "");
                            p.put("hora",     doc.getString("hora") != null ? doc.getString("hora") : "");
                            p.put("estado",   doc.getString("estado") != null ? doc.getString("estado") : "pendente");
                            p.put("notas",    doc.getString("notas") != null ? doc.getString("notas") : "");
                            p.put("items",    doc.getString("items") != null ? doc.getString("items") : "[]");
                            p.put("total",    doc.getDouble("total") != null ? doc.getDouble("total") : 0);
                            p.put("bloq",     Boolean.TRUE.equals(doc.getBoolean("bloqueado")));
                            p.put("ts",       doc.getDate("criado_em") != null ? doc.getDate("criado_em").getTime() : 0);
                            arr.put(p);
                        } catch (Exception ex) {
                            Log.e(TAG, "pedidosListener doc erro: " + ex.getMessage());
                        }
                    }
                    Log.d(TAG, "pedidosListener: " + arr.length() + " pedidos");
                    emitir("fbPedidosMesa", arr.toString());
                } catch (Exception ex) {
                    Log.e(TAG, "pedidosListener: " + ex.getMessage());
                }
            });
    }

    @JavascriptInterface
    public void pararListenerPedidos() {
        if (pedidosListener != null) {
            pedidosListener.remove();
            pedidosListener = null;
            Log.d(TAG, "pedidosListener parado");
        }
    }

    /** Edita um pedido enviado - actualiza items, total e marca como alterado */
    @JavascriptInterface
    public void editarPedido(String firestoreId, String itemsJson, String total) {
        try {
            double totalVal = Double.parseDouble(total);
            java.util.Map<String, Object> update = new java.util.HashMap<>();
            update.put("items",    itemsJson);
            update.put("total",    totalVal);
            update.put("alterado", true);
            update.put("alterado_em", com.google.firebase.Timestamp.now());
            // NAO actualizar criado_em - manter original para nao sair da query do KDS
            // O KDS detecta MODIFIED instantaneamente quando criado_em nao muda
            update.put("alterado_em", com.google.firebase.Timestamp.now());
            update.put("chamar", false); // garantir que edicao nao dispara alerta
            db.collection(COL_PEDIDOS).document(firestoreId)
                .update(update)
                .addOnSuccessListener(v -> {
                    Log.d(TAG, "editarPedido OK: " + firestoreId);
                    emitir("fbPedidoEditado", firestoreId);
                })
                .addOnFailureListener(e -> Log.e(TAG, "editarPedido: " + e.getMessage()));
        } catch (Exception e) {
            Log.e(TAG, "editarPedido parse: " + e.getMessage());
        }
    }

    /** Actualiza items e total de um pedido bloqueado no Firestore */
    @JavascriptInterface
    public void actualizarBloqueado(String firestoreId, String itemsJson, String total) {
        try {
            double totalVal = Double.parseDouble(total);
            java.util.Map<String, Object> update = new java.util.HashMap<>();
            update.put("items", itemsJson);
            update.put("total", totalVal);
            db.collection(COL_PEDIDOS).document(firestoreId)
                .update(update)
                .addOnSuccessListener(v -> Log.d(TAG, "actualizarBloqueado OK: " + firestoreId))
                .addOnFailureListener(e -> Log.e(TAG, "actualizarBloqueado: " + e.getMessage()));
        } catch (Exception e) {
            Log.e(TAG, "actualizarBloqueado parse: " + e.getMessage());
        }
    }

    /** Inicia listener para detectar alertas do KDS (chamar, estado) */
    @JavascriptInterface
    public void iniciarListenerAlertas(String funcionario) {
        // Listener de pedidos do funcionario - so um whereEqualTo para evitar indice composto
        db.collection(COL_PEDIDOS)
            .whereEqualTo("funcionario", funcionario)
            .addSnapshotListener((snapshots, e) -> {
                if (e != null || snapshots == null) return;
                for (com.google.firebase.firestore.DocumentChange dc : snapshots.getDocumentChanges()) {
                    if (dc.getType() != com.google.firebase.firestore.DocumentChange.Type.MODIFIED) continue;
                    try {
                        java.util.Map<String, Object> data = dc.getDocument().getData();
                        if (data == null) continue;
                        boolean chamar = Boolean.TRUE.equals(data.get("chamar"));
                        // Ignorar se nao e chamar - nao emitir nada
                        if (!chamar) continue;
                        org.json.JSONObject obj = new org.json.JSONObject();
                        obj.put("id",          dc.getDocument().getId());
                        obj.put("firestoreId", dc.getDocument().getId());
                        obj.put("chamar",      true);
                        obj.put("chamar_func", data.getOrDefault("chamar_func", "").toString());
                        obj.put("numero",      data.getOrDefault("numero", "?").toString());
                        obj.put("mesaNome",    data.getOrDefault("mesaNome", "").toString());
                        emitir("fbPedidoAlterado", obj.toString());
                    } catch (Exception ex) {
                        Log.e(TAG, "listenerAlertas: " + ex.getMessage());
                    }
                }
            });
    }

    /** Vibrar o dispositivo */
    @JavascriptInterface
    public void vibrar(String padrao) {
        try {
            long[] pattern;
            if (padrao != null && !padrao.isEmpty()) {
                String[] parts = padrao.split(",");
                pattern = new long[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    pattern[i] = Long.parseLong(parts[i].trim());
                }
            } else {
                pattern = new long[]{0, 300, 100, 300};
            }
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                VibratorManager vm = (VibratorManager) activity.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (vm != null) {
                    Vibrator v = vm.getDefaultVibrator();
                    v.vibrate(VibrationEffect.createWaveform(pattern, -1));
                }
            } else if (android.os.Build.VERSION.SDK_INT >= 26) {
                Vibrator v = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null) v.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else {
                Vibrator v = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null) v.vibrate(pattern, -1);
            }
        } catch (Exception e) {
            Log.e(TAG, "vibrar: " + e.getMessage());
        }
    }

    /** Regista token FCM no Firestore associado ao funcionario */
    @JavascriptInterface
    public void registarTokenFCM(String funcionario) {
        com.google.firebase.messaging.FirebaseMessaging.getInstance().getToken()
            .addOnSuccessListener(token -> {
                if (token == null || token.isEmpty()) return;
                java.util.Map<String, Object> dados = new java.util.HashMap<>();
                dados.put("token",       token);
                dados.put("funcionario", funcionario);
                dados.put("updated_at",  com.google.firebase.Timestamp.now());
                db.collection("fcm_tokens").document(funcionario)
                    .set(dados)
                    .addOnSuccessListener(v -> Log.d(TAG, "Token FCM registado: " + funcionario))
                    .addOnFailureListener(e -> Log.e(TAG, "registarTokenFCM: " + e.getMessage()));
            })
            .addOnFailureListener(e -> Log.e(TAG, "getToken: " + e.getMessage()));
    }

    /** Adiciona novo pedido ao saldo de mesa com pagamento parcial */
    @JavascriptInterface
    public void adicionarPedidoAMesaParcial(String mesaId, String novoTotalPedidoStr) {
        try {
            double novoTotalPedido = Double.parseDouble(novoTotalPedidoStr);
            // Ir buscar o estado actual da mesa
            db.collection("mesas").document(mesaId).get()
                .addOnSuccessListener(doc -> {
                    if (!doc.exists()) return;
                    java.util.Map<String, Object> data = doc.getData();
                    double saldoActual = data.containsKey("total") ? ((Number) data.get("total")).doubleValue() : 0;
                    double totalPago   = data.containsKey("total_pago") ? ((Number) data.get("total_pago")).doubleValue() : 0;
                    double totalOrig   = data.containsKey("total_original") ? ((Number) data.get("total_original")).doubleValue() : 0;
                    // Novo saldo = saldo actual + novo pedido
                    double novoSaldo = saldoActual + novoTotalPedido;
                    // Novo total original = total original + novo pedido
                    double novoTotalOrig = totalOrig + novoTotalPedido;
                    java.util.Map<String, Object> update = new java.util.HashMap<>();
                    update.put("total",          novoSaldo);
                    update.put("total_original", novoTotalOrig);
                    update.put("atualizado_em",  com.google.firebase.Timestamp.now());
                    // NAO actualizar items - a caixa gere os items apos pagamento parcial
                    db.collection("mesas").document(mesaId)
                        .update(update)
                        .addOnFailureListener(e -> Log.e(TAG, "adicionarPedidoAMesaParcial: " + e.getMessage()));
                })
                .addOnFailureListener(e -> Log.e(TAG, "adicionarPedidoAMesaParcial get: " + e.getMessage()));
        } catch (Exception e) {
            Log.e(TAG, "adicionarPedidoAMesaParcial: " + e.getMessage());
        }
    }

    /** Le modo de trabalho do Firebase (pos ou pre) */
    @JavascriptInterface
    public void lerModoTrabalho() {
        // Primeiro tentar ler do SharedPreferences local
        String modoLocal = activity.getSharedPreferences("CriatvSmartphone", android.app.Activity.MODE_PRIVATE)
            .getString("modo_trabalho", "");
        if (!modoLocal.isEmpty()) {
            emitir("fbModoTrabalho", modoLocal);
        }
        // Depois actualizar do Firebase
        db.collection("config").document("activacao").get()
            .addOnSuccessListener(doc -> {
                if (doc.exists()) {
                    String modo = String.valueOf(doc.getData().getOrDefault("modo_trabalho", "pos"));
                    // Guardar localmente para próximo arranque
                    activity.getSharedPreferences("CriatvSmartphone", android.app.Activity.MODE_PRIVATE)
                        .edit().putString("modo_trabalho", modo).apply();
                    emitir("fbModoTrabalho", modo);
                }
            })
            .addOnFailureListener(e -> Log.e(TAG, "lerModoTrabalho: " + e.getMessage()));
    }

    /** Abre scanner de QR Code nativo e devolve resultado ao JS */
    @JavascriptInterface
    public void abrirScanner() {
        activity.runOnUiThread(() -> {
            try {
                // Usar ZXing Embedded - nao precisa de app externa
                com.journeyapps.barcodescanner.ScanContract scanContract =
                    new com.journeyapps.barcodescanner.ScanContract();
                // Usar IntentIntegrator do ZXing embedded
                com.google.zxing.integration.android.IntentIntegrator integrator =
                    new com.google.zxing.integration.android.IntentIntegrator(activity);
                integrator.setDesiredBarcodeFormats(
                    com.google.zxing.integration.android.IntentIntegrator.QR_CODE);
                integrator.setPrompt("Aponte para o QR Code do talao");
                integrator.setCameraId(0);
                integrator.setBeepEnabled(true);
                integrator.setBarcodeImageEnabled(false);
                integrator.setOrientationLocked(false);
                integrator.initiateScan();
            } catch (Exception e) {
                Log.e(TAG, "abrirScanner: " + e.getMessage());
                emitir("fbScannerErro", "Scanner nao disponivel: " + e.getMessage());
            }
        });
    }

    /** Busca pedido pendente no Firebase pelo numero de senha */
    @JavascriptInterface
    public void buscarPedidoPendente(String numero) {
        db.collection("pedidos_pendentes").document("senha_" + numero).get()
            .addOnSuccessListener(doc -> {
                if (!doc.exists()) {
                    emitir("fbPedidoPendente", "null");
                    return;
                }
                try {
                    java.util.Map<String, Object> data = doc.getData();

                    // Verificar se ja esta em activacao por outro funcionario
                    String estadoActual = data.getOrDefault("estado", "aguarda_activacao").toString();
                    if ("em_activacao".equals(estadoActual)) {
                        emitir("fbPedidoPendente", "em_activacao");
                        return;
                    }

                    // Marcar como em_activacao para evitar dupla leitura
                    db.collection("pedidos_pendentes").document(doc.getId())
                        .update("estado", "em_activacao");

                    org.json.JSONObject obj = new org.json.JSONObject();
                    obj.put("id",     doc.getId());
                    obj.put("numero", data.getOrDefault("numero", "").toString());
                    obj.put("mesa",   data.getOrDefault("mesa",   "").toString());
                    obj.put("estado", "em_activacao");

                    Object totalObj = data.get("total");
                    double total = 0;
                    if (totalObj instanceof Double) total = (Double) totalObj;
                    else if (totalObj instanceof Long) total = ((Long) totalObj).doubleValue();
                    else if (totalObj != null) { try { total = Double.parseDouble(totalObj.toString()); } catch(Exception ex){} }
                    obj.put("total", total);

                    Object itemsObj = data.get("items");
                    String itemsStr = "[]";
                    if (itemsObj instanceof String) {
                        itemsStr = (String) itemsObj;
                    } else if (itemsObj instanceof java.util.List) {
                        itemsStr = new org.json.JSONArray((java.util.List<?>) itemsObj).toString();
                    } else if (itemsObj != null) {
                        itemsStr = itemsObj.toString();
                    }
                    obj.put("items", itemsStr);

                    Log.d(TAG, "pedidoPendente em_activacao: " + obj.toString());
                    emitir("fbPedidoPendente", obj.toString());
                } catch (Exception e) {
                    Log.e(TAG, "buscarPedidoPendente erro: " + e.getMessage());
                    emitir("fbPedidoPendente", "null");
                }
            })
            .addOnFailureListener(e -> emitir("fbPedidoPendente", "null"));
    }

    /** Activa pedido pendente - envia para KDS */
    @JavascriptInterface
    public void activarPedido(String numero, String mesa, String funcionario) {
        String docId = "senha_" + numero;
        db.collection("pedidos_pendentes").document(docId).get()
            .addOnSuccessListener(doc -> {
                if (!doc.exists()) {
                    emitir("fbActivacaoErro", "Pedido nao encontrado");
                    return;
                }
                java.util.Map<String, Object> data = doc.getData();
                // Criar pedido activo na coleccao pedidos (vai para KDS)
                java.util.Map<String, Object> pedido = new java.util.HashMap<>(data);
                pedido.put("estado",       "pendente");
                pedido.put("mesa",         mesa);
                pedido.put("funcionario",  funcionario);
                pedido.put("activado_em",  com.google.firebase.Timestamp.now());
                pedido.put("criado_em",    com.google.firebase.Timestamp.now());
                // Calcular destinos a partir dos items para o KDS filtrar
                Object itemsObjD = data.get("items");
                String itemsStrD = itemsObjD instanceof String ? (String) itemsObjD : "[]";
                pedido.put("destinos", calcularDestinos(itemsStrD));
                db.collection("pedidos").add(pedido)
                    .addOnSuccessListener(ref -> {
                        // Apagar da coleccao pendentes
                        db.collection("pedidos_pendentes").document(docId).delete();
                        // Emitir pedido completo com ID do Firebase para o Smartphone mostrar
                        try {
                            org.json.JSONObject obj = new org.json.JSONObject();
                            obj.put("id",          ref.getId());
                            obj.put("firestoreId", ref.getId());
                            obj.put("numero",      numero);
                            obj.put("mesa",        mesa);
                            obj.put("funcionario", funcionario);
                            obj.put("estado",      "pendente");
                            obj.put("total",       data.getOrDefault("total", 0));
                            // Items - separar por destino
                            Object itemsObj = data.get("items");
                            String itemsStr = "[]";
                            if (itemsObj instanceof String) itemsStr = (String) itemsObj;
                            obj.put("items", itemsStr);
                            emitir("fbPedidoActivado", obj.toString());
                        } catch (Exception ex) {
                            emitir("fbPedidoActivado", numero);
                        }
                        Log.d(TAG, "Pedido activado: #" + numero + " -> " + ref.getId());
                    })
                    .addOnFailureListener(e -> emitir("fbActivacaoErro", e.getMessage()));
            })
            .addOnFailureListener(e -> emitir("fbActivacaoErro", "Erro ao buscar pedido"));
    }

    /** Listener de pedidos activados por este funcionario (pre-pagamento) */
    @JavascriptInterface
    public void iniciarListenerPedidosFuncionario(String funcionario) {
        db.collection("pedidos")
            .whereEqualTo("funcionario", funcionario)
            .whereNotEqualTo("estado", "tratado")
            .addSnapshotListener((snaps, e) -> {
                if (e != null || snaps == null) return;
                for (com.google.firebase.firestore.DocumentChange dc : snaps.getDocumentChanges()) {
                    try {
                        java.util.Map<String, Object> data = dc.getDocument().getData();
                        org.json.JSONObject obj = new org.json.JSONObject();
                        obj.put("id",     dc.getDocument().getId());
                        obj.put("estado", data.getOrDefault("estado", "pendente").toString());
                        obj.put("mesa",   data.getOrDefault("mesa",   "").toString());
                        obj.put("numero", data.getOrDefault("numero", "").toString());
                        emitir("fbPedidoEstadoActualizado", obj.toString());
                    } catch (Exception ex) {
                        Log.e(TAG, "listenerPedidosFuncionario: " + ex.getMessage());
                    }
                }
            });
    }

    /** Fecha mesa directamente no pre-pagamento (sem passar pela caixa) */
    @JavascriptInterface
    public void fecharMesaDirecto(String mesaId) {
        com.google.firebase.firestore.WriteBatch batch = db.batch();

        // Fechar mesa
        java.util.Map<String, Object> updateMesa = new java.util.HashMap<>();
        updateMesa.put("estado",     "fechada");
        updateMesa.put("fechado_em", com.google.firebase.Timestamp.now());
        batch.update(db.collection("mesas").document(mesaId), updateMesa);

        // Marcar pedidos da mesa como tratado
        db.collection("pedidos")
            .whereEqualTo("mesaId", mesaId)
            .get()
            .addOnSuccessListener(snaps -> {
                for (com.google.firebase.firestore.DocumentSnapshot doc : snaps.getDocuments()) {
                    String est = String.valueOf(doc.getData().getOrDefault("estado", ""));
                    if (!"tratado".equals(est)) {
                        batch.update(doc.getReference(), "estado", "tratado");
                    }
                }
                batch.commit()
                    .addOnSuccessListener(v -> {
                        emitir("fbMesaFechada", mesaId);
                        Log.d(TAG, "Mesa fechada directamente: " + mesaId);
                    })
                    .addOnFailureListener(e -> Log.e(TAG, "fecharMesaDirecto: " + e.getMessage()));
            })
            .addOnFailureListener(e -> {
                batch.commit();
                Log.e(TAG, "fecharMesaDirecto get: " + e.getMessage());
            });
    }

    /** Activa pedido na mesa - apaga de pendentes e regista activacao */
    @JavascriptInterface
    public void activarPedidoNaMesa(String numero, String mesaId, String funcionario) {
        String docId = "senha_" + numero;
        // Apagar de pedidos_pendentes
        db.collection("pedidos_pendentes").document(docId)
            .delete()
            .addOnSuccessListener(v -> Log.d(TAG, "Pedido pendente apagado: #" + numero))
            .addOnFailureListener(e -> Log.e(TAG, "activarPedidoNaMesa: " + e.getMessage()));
    }

    /** Cancela activacao - repoe pedido pendente para aguarda_activacao */
    @JavascriptInterface
    public void cancelarActivacao(String numero) {
        String docId = "senha_" + numero;
        db.collection("pedidos_pendentes").document(docId)
            .update("estado", "aguarda_activacao")
            .addOnSuccessListener(v -> Log.d(TAG, "Activacao cancelada: #" + numero))
            .addOnFailureListener(e -> Log.e(TAG, "cancelarActivacao: " + e.getMessage()));
    }

    /** Le PIN de activacao do Firebase */
    @JavascriptInterface
    public void lerPinActivacao() {
        db.collection("config").document("activacao").get()
            .addOnSuccessListener(doc -> {
                String pin = doc.exists() ?
                    String.valueOf(doc.getData().getOrDefault("pin", "")) : "";
                emitir("fbPinActivacao", pin);
            })
            .addOnFailureListener(e -> emitir("fbPinActivacao", ""));
    }

    /** Confirma entrega - limpa chamar no Firestore */
    @JavascriptInterface
    public void confirmarEntrega(String firestoreId) {
        db.collection(COL_PEDIDOS).document(firestoreId)
            .update("chamar", false)
            .addOnFailureListener(e -> Log.e(TAG, "confirmarEntrega: " + e.getMessage()));
    }

    /** Grava pedido bloqueado no Firestore para persistir entre reinicios */
    @JavascriptInterface
    public void gravarPedidoBloqueado(String payload) {
        try {
            org.json.JSONObject p = new org.json.JSONObject(payload);
            java.util.Map<String, Object> dados = new java.util.HashMap<>();
            dados.put("mesaId",      p.optString("mesaId", ""));
            dados.put("mesaNome",    p.optString("mesaNome", ""));
            dados.put("funcionario", p.optString("func", ""));
            dados.put("items",       p.optString("items", "[]"));
            dados.put("total",       p.optDouble("total", 0));
            dados.put("estado",      "bloqueado");
            dados.put("bloqueado",   true);
            dados.put("hora",        p.optString("hora", ""));
            dados.put("notas",       p.optString("notas", ""));
            dados.put("criado_em",   com.google.firebase.Timestamp.now());
            dados.put("numero",      0);
            dados.put("destinos",    "cozinha");

            String localId = p.optString("id", "");
            db.collection(COL_PEDIDOS).add(dados)
                .addOnSuccessListener(ref -> {
                    Log.d(TAG, "gravarPedidoBloqueado: " + ref.getId());
                    emitir("fbBloqueadoGravado", localId + "|" + ref.getId());
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "gravarPedidoBloqueado: " + e.getMessage());
                });
        } catch (Exception e) {
            Log.e(TAG, "gravarPedidoBloqueado parse: " + e.getMessage());
        }
    }

    public void carregarPedidosMesa(String mesaId) {
        if (mesaId == null || mesaId.isEmpty()) {
            Log.w(TAG, "carregarPedidosMesa: mesaId vazio");
            return;
        }
        Log.d(TAG, "carregarPedidosMesa: a consultar mesaId=" + mesaId);
        db.collection(COL_PEDIDOS)
            .whereEqualTo("mesaId", mesaId)
            .get()
            .addOnSuccessListener(snap -> {
                Log.d(TAG, "carregarPedidosMesa: encontrados " + snap.size() + " pedidos");
                try {
                    org.json.JSONArray arr = new org.json.JSONArray();
                    for (com.google.firebase.firestore.QueryDocumentSnapshot doc : snap) {
                        try {
                            Log.d(TAG, "carregarPedidosMesa: doc=" + doc.getId() + " estado=" + doc.getString("estado"));
                            org.json.JSONObject p = new org.json.JSONObject();
                            p.put("id",         doc.getId());
                            p.put("num",        doc.getLong("numero") != null ? doc.getLong("numero") : 0);
                            p.put("numero",     doc.getLong("numero") != null ? doc.getLong("numero") : 0);
                            p.put("func",       doc.getString("funcionario") != null ? doc.getString("funcionario") : "");
                            p.put("mesaId",     doc.getString("mesaId") != null ? doc.getString("mesaId") : "");
                            p.put("mesaNome",   doc.getString("mesaNome") != null ? doc.getString("mesaNome") : "");
                            p.put("hora",       doc.getString("hora") != null ? doc.getString("hora") : "");
                            p.put("estado",     doc.getString("estado") != null ? doc.getString("estado") : "pendente");
                            p.put("notas",      doc.getString("notas") != null ? doc.getString("notas") : "");
                            p.put("items",      doc.getString("items") != null ? doc.getString("items") : "[]");
                            p.put("total",      doc.getDouble("total") != null ? doc.getDouble("total") : 0);
                            p.put("bloq",       Boolean.TRUE.equals(doc.getBoolean("bloqueado")));
                            p.put("ts",         doc.getDate("criado_em") != null ? doc.getDate("criado_em").getTime() : 0);
                            arr.put(p);
                        } catch (Exception e2) {
                            Log.e(TAG, "carregarPedidosMesa: erro doc " + doc.getId() + ": " + e2.getMessage());
                        }
                    }
                    Log.d(TAG, "carregarPedidosMesa: a emitir " + arr.length() + " pedidos");
                    emitir("fbPedidosMesa", arr.toString());
                } catch (Exception e) {
                    Log.e(TAG, "carregarPedidosMesa: " + e.getMessage());
                    emitir("fbErro", "carregarPedidosMesa: " + (e.getMessage() != null ? e.getMessage() : "erro"));
                }
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "carregarPedidosMesa falhou: " + e.getMessage());
                emitir("fbErro", "carregarPedidosMesa: " + (e.getMessage() != null ? e.getMessage() : "erro"));
            });
    }

    /** Chamado pelo MainActivity quando o scanner devolve resultado */
    public void onScanResult(String resultado) {
        // resultado = "criativo://pedido/047" ou numero directo
        String numero = resultado;
        if (resultado.startsWith("criativo://pedido/")) {
            numero = resultado.replace("criativo://pedido/", "");
        }
        emitir("fbQRLido", numero.trim());
    }

    public void destroy() {
        if (listenerModo != null) { listenerModo.remove(); listenerModo = null; }
        pararKDS();
        pararListenerMesas();
        pararListenerPedidos();
    }
}

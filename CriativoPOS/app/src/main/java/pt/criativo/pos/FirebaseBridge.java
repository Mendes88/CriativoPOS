package pt.criativo.pos;

import android.app.Activity;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.content.SharedPreferences;
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
    public void iniciarListenerMesas() {
        if (mesasListener != null) {
            mesasListener.remove();
            mesasListener = null;
        }
        // Trazer mesas em_servico E aguarda_pagamento
        mesasListener = db.collection(COL_MESAS)
            .whereIn("estado", java.util.Arrays.asList("em_servico", "aguarda_pagamento"))
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
                        m.put("func",        d.getOrDefault("funcionario", "").toString());
                        m.put("total",          d.getOrDefault("total",          0));
                        m.put("total_original", d.getOrDefault("total_original", d.getOrDefault("total", 0)));
                        m.put("total_pago",     d.getOrDefault("total_pago",     0));
                        m.put("estado",         d.getOrDefault("estado",         "em_servico").toString());
                        m.put("items",          d.getOrDefault("items",          "[]").toString());
                        Object ab = d.containsKey("abertoEm") ? d.get("abertoEm") : d.get("aberta_em");
                        if (ab instanceof Timestamp) {
                            long min = (System.currentTimeMillis() / 1000
                                - ((Timestamp) ab).getSeconds()) / 60;
                            m.put("minutos", min);
                            m.put("enviado_caixa_em", ((Timestamp) ab).getSeconds() * 1000L);
                        } else {
                            m.put("minutos", 0);
                        }
                        arr.put(m);
                    }
                    // Emitir com o nome correcto que o JS escuta
                    emitir("fbMesasActualizadas", arr.toString());
                } catch (Exception ex) {
                    emitir("fbErro", "mesas parse: " + (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
                }
            });
    }

    // Compatibilidade com chamada antiga com argumento
    @JavascriptInterface
    public void iniciarListenerMesas(String ignorado) {
        iniciarListenerMesas();
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

    /** Contador atómico DIÁRIO — reinicia automaticamente todos os dias.
     *  Documento: contadores/pedidos_AAAAMMDD (ex: pedidos_20260630) */
    private void obterProximoNumero(NumeroCallback callback) {
        // Chave diária no fuso local do dispositivo
        String diaKey = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
                            .format(new java.util.Date());
        DocumentReference ref = db.collection(COL_CONTADORES).document("pedidos_" + diaKey);
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
                dados.put("dia", diaKey);
                transaction.set(ref, dados);
            }
            return proximo;
        }).addOnSuccessListener(callback::onNumero)
          .addOnFailureListener(e -> {
              Log.e(TAG, "contador: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
              callback.onNumero(System.currentTimeMillis()); // fallback
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
                String dest = arr.getJSONObject(i).optString("destino", "cozinha"); if (dest == null) dest = "cozinha";
                if ("bar".equals(dest))     temBar = true;
                else                         temCozinha = true;
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
        // Escapar caracteres que quebram o JS inline
        String esc = dados
            .replace("\\", "\\\\")
            .replace("'",  "\\'")
            .replace("\n", " ")
            .replace("\r", "");
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
            java.util.Map<String, Object> update = new java.util.HashMap<>();
            update.put("estado",           "aguarda_pagamento"); // agora aparece na caixa
            update.put("total",            total);
            update.put("items",            itensJson);
            update.put("nome",             mesaNome);
            update.put("funcionario",      funcionario);
            update.put("enviado_caixa_em", com.google.firebase.Timestamp.now());

            db.collection("mesas").document(mesaId)
                .update(update)
                .addOnSuccessListener(v -> emitir("fbContaEnviada", mesaId))
                .addOnFailureListener(e -> emitir("fbErro", "enviarParaCaixa: " + (e.getMessage() != null ? e.getMessage() : "erro")));
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
            update.put("items", itemsJson);
            update.put("total", total);
            update.put("atualizado_em", com.google.firebase.Timestamp.now());

            db.collection("mesas").document(mesaId)
                .update(update)
                .addOnFailureListener(e -> android.util.Log.e("CriativoFB", "actualizarMesa: " + e.getMessage()));
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

    /** Busca pedidos activos de uma mesa para o POS Caixa */
    @JavascriptInterface
    public void buscarPedidosMesa(final String mesaId) {
        db.collection("pedidos")
            .whereEqualTo("mesaId", mesaId)
            .get()
            .addOnSuccessListener(snapshots -> {
                try {
                    JSONArray resultado = new JSONArray();
                    HashMap<String, Double> precos = new HashMap<>();
                    HashMap<String, Integer> qtdMap = new HashMap<>();
                    for (com.google.firebase.firestore.DocumentSnapshot doc : snapshots.getDocuments()) {
                        Map<String, Object> docData = doc.getData();
                        if (docData == null) continue;
                        String est = String.valueOf(docData.getOrDefault("estado", "pendente"));
                        if ("tratado".equals(est) || "bloqueado".equals(est)) continue;
                        String itemsStr = docData.getOrDefault("items", "[]").toString();
                        try {
                            JSONArray arr = new JSONArray(itemsStr);
                            for (int i = 0; i < arr.length(); i++) {
                                JSONObject it = arr.getJSONObject(i);
                                String nome = it.optString("n", it.optString("nome", "?"));
                                double preco = it.optDouble("p", it.optDouble("preco", 0));
                                int qtd = it.optInt("q", it.optInt("qtd", 1));
                                String key = nome + "|" + preco;
                                precos.put(key, preco);
                                Integer prev = qtdMap.get(key);
                                qtdMap.put(key, (prev != null ? prev : 0) + qtd);
                            }
                        } catch (Exception ex2) { /* ignorar item com erro */ }
                    }
                    for (String key : qtdMap.keySet()) {
                        String[] parts = key.split("\\|");
                        String nome = parts[0];
                        Double preco = precos.get(key);
                        Integer qtd = qtdMap.get(key);
                        if (preco == null || qtd == null) continue;
                        JSONObject obj = new JSONObject();
                        obj.put("n", nome);
                        obj.put("p", preco);
                        obj.put("q", qtd);
                        resultado.put(obj);
                    }
                    emitir("fbPedidosMesaCaixa", mesaId + "|" + resultado.toString());
                } catch (Exception e) {
                    Log.e(TAG, "buscarPedidosMesa: " + e.getMessage());
                }
            })
            .addOnFailureListener(e -> Log.e(TAG, "buscarPedidosMesa fail: " + e.getMessage()));
    }

    private static final String PREFS = "CriativoPOSCaixa";

    @JavascriptInterface
    public void gravarPreferencia(String chave, String valor) {
        try {
            SharedPreferences.Editor ed = activity.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit();
            if (valor == null || valor.isEmpty()) ed.remove(chave);
            else ed.putString(chave, valor);
            ed.apply();
        } catch (Exception e) { Log.e("CriativoFB", "gravarPreferencia: " + e.getMessage()); }
    }

    @JavascriptInterface
    public String lerPreferencia(String chave) {
        try {
            return activity.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString(chave, "");
        } catch (Exception e) { return ""; }
    }

    /** Limpa pedidos e mesas fechadas do turno */
    @JavascriptInterface
    public void limparTurno() {
        // Apagar pedidos com estado tratado/bloqueado
        db.collection("pedidos")
            .whereIn("estado", java.util.Arrays.asList("tratado", "bloqueado"))
            .get()
            .addOnSuccessListener(snaps -> {
                com.google.firebase.firestore.WriteBatch batch = db.batch();
                for (com.google.firebase.firestore.DocumentSnapshot doc : snaps.getDocuments()) {
                    batch.delete(doc.getReference());
                }
                // Apagar mesas fechadas
                db.collection("mesas")
                    .whereEqualTo("estado", "fechado")
                    .get()
                    .addOnSuccessListener(snaps2 -> {
                        for (com.google.firebase.firestore.DocumentSnapshot doc : snaps2.getDocuments()) {
                            batch.delete(doc.getReference());
                        }
                        batch.commit()
                            .addOnSuccessListener(v -> emitir("fbLimpezaConcluida", "turno"))
                            .addOnFailureListener(e -> emitir("fbLimpezaErro", e.getMessage()));
                    });
            })
            .addOnFailureListener(e -> emitir("fbLimpezaErro", e.getMessage()));
    }

    /** Limpa todos os pedidos e mesas */
    @JavascriptInterface
    public void limparTodosDados() {
        com.google.firebase.firestore.WriteBatch batch = db.batch();
        // Apagar pedidos
        db.collection("pedidos").get()
            .addOnSuccessListener(snaps -> {
                for (com.google.firebase.firestore.DocumentSnapshot doc : snaps.getDocuments()) {
                    batch.delete(doc.getReference());
                }
                // Apagar mesas
                db.collection("mesas").get()
                    .addOnSuccessListener(snaps2 -> {
                        for (com.google.firebase.firestore.DocumentSnapshot doc : snaps2.getDocuments()) {
                            batch.delete(doc.getReference());
                        }
                        // Resetar contador
                        java.util.Map<String, Object> reset = new java.util.HashMap<>();
                        reset.put("total", 0);
                        reset.put("fecho_em", null);
                        batch.set(db.collection("contadores").document("turno_actual"), reset);
                        batch.commit()
                            .addOnSuccessListener(v -> emitir("fbLimpezaConcluida", "tudo"))
                            .addOnFailureListener(e -> emitir("fbLimpezaErro", e.getMessage()));
                    });
            })
            .addOnFailureListener(e -> emitir("fbLimpezaErro", e.getMessage()));
    }

    /** Verifica se ha pedidos em curso (em_preparacao ou pronto) numa mesa */
    @JavascriptInterface
    public void verificarPedidosEmCurso(String mesaId) {
        db.collection("pedidos")
            .whereEqualTo("mesaId", mesaId)
            .get()
            .addOnSuccessListener(snaps -> {
                try {
                    int emCurso = 0;
                    for (com.google.firebase.firestore.DocumentSnapshot doc : snaps.getDocuments()) {
                        java.util.Map<String, Object> data = doc.getData();
                        if (data == null) continue;
                        String estado = String.valueOf(data.getOrDefault("estado", "pendente"));
                        String estadoCoz = String.valueOf(data.getOrDefault("estado_cozinha", "pendente"));
                        String estadoBar = String.valueOf(data.getOrDefault("estado_bar", "pendente"));
                        if ("em_preparacao".equals(estado) || "pronto".equals(estado) ||
                            "em_preparacao".equals(estadoCoz) || "pronto".equals(estadoCoz) ||
                            "em_preparacao".equals(estadoBar) || "pronto".equals(estadoBar)) {
                            emCurso++;
                        }
                    }
                    org.json.JSONObject obj = new org.json.JSONObject();
                    obj.put("emCurso", emCurso > 0);
                    obj.put("count", emCurso);
                    emitir("fbPedidosEmCurso", obj.toString());
                } catch (Exception e) {
                    Log.e("CriativoFB", "verificarPedidosEmCurso: " + e.getMessage());
                }
            })
            .addOnFailureListener(e -> Log.e("CriativoFB", "verificarPedidosEmCurso: " + e.getMessage()));
    }

    /** Elimina mesa e todos os seus pedidos */
    @JavascriptInterface
    public void eliminarMesa(String mesaId) {
        com.google.firebase.firestore.WriteBatch batch = db.batch();
        // Apagar pedidos da mesa
        db.collection("pedidos")
            .whereEqualTo("mesaId", mesaId)
            .get()
            .addOnSuccessListener(snaps -> {
                for (com.google.firebase.firestore.DocumentSnapshot doc : snaps.getDocuments()) {
                    batch.delete(doc.getReference());
                }
                // Apagar documento da mesa
                batch.delete(db.collection("mesas").document(mesaId));
                batch.commit()
                    .addOnSuccessListener(v -> emitir("fbMesaEliminada", mesaId))
                    .addOnFailureListener(e -> emitir("fbErro", "eliminarMesa: " + e.getMessage()));
            })
            .addOnFailureListener(e -> emitir("fbErro", "eliminarMesa: " + e.getMessage()));
    }

    /** Grava total_original na primeira vez que a mesa e aberta para cobrar */
    @JavascriptInterface
    public void gravarTotalOriginal(String mesaId, String totalOriginalStr) {
        try {
            double totalOriginal = Double.parseDouble(totalOriginalStr);
            if (totalOriginal <= 0) return;
            // Verificar se ja tem total_original antes de gravar
            db.collection("mesas").document(mesaId).get()
                .addOnSuccessListener(doc -> {
                    if (!doc.exists()) return;
                    Object existing = doc.getData().get("total_original");
                    if (existing != null && ((Number) existing).doubleValue() > 0) return;
                    // Nao tem - gravar agora
                    db.collection("mesas").document(mesaId)
                        .update("total_original", totalOriginal)
                        .addOnFailureListener(e -> Log.e("CriativoFB", "gravarTotalOriginal: " + e.getMessage()));
                });
        } catch (Exception e) {
            Log.e("CriativoFB", "gravarTotalOriginal: " + e.getMessage());
        }
    }

    /** Grava total_pago manualmente quando introduzido pelo caixa */
    @JavascriptInterface
    public void gravarTotalPago(String mesaId, String totalPagoStr) {
        try {
            double totalPago = Double.parseDouble(totalPagoStr);
            db.collection("mesas").document(mesaId)
                .update("total_pago", totalPago)
                .addOnFailureListener(e -> Log.e("CriativoFB", "gravarTotalPago: " + e.getMessage()));
        } catch (Exception e) {
            Log.e("CriativoFB", "gravarTotalPago: " + e.getMessage());
        }
    }

    /** Actualiza mesa apos pagamento parcial com total_original e total_pago */
    @JavascriptInterface
    public void actualizarMesaParcial(String mesaId, String itemsJson, String saldoStr, String totalOriginalStr, String totalPagoStr) {
        try {
            double saldo        = Double.parseDouble(saldoStr);
            double totalOriginal = Double.parseDouble(totalOriginalStr);
            double totalPago    = Double.parseDouble(totalPagoStr);
            java.util.Map<String, Object> update = new java.util.HashMap<>();
            if (!itemsJson.equals("[]")) update.put("items", itemsJson);
            update.put("total",          saldo);
            update.put("total_original", totalOriginal);
            update.put("total_pago",     totalPago);
            update.put("atualizado_em",  com.google.firebase.Timestamp.now());
            db.collection("mesas").document(mesaId)
                .update(update)
                .addOnFailureListener(e -> Log.e("CriativoFB", "actualizarMesaParcial: " + e.getMessage()));
        } catch (Exception e) {
            Log.e("CriativoFB", "actualizarMesaParcial: " + (e.getMessage() != null ? e.getMessage() : "erro"));
        }
    }

    public void destroy() {
        pararKDS();
        pararListenerMesas();
    }
}

package br.com.papelaria.apps;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tela comum dos três aplicativos: mostra a página do aplicativo (guardada dentro do APK),
 * recebe arquivos e textos de outros aplicativos (WhatsApp) e entrega o PDF pronto ao Epson iPrint.
 */
public abstract class WebActivity extends Activity {

    static final String HOST = "appassets.androidplatform.net";
    static final String EPSON = "epson.print";
    private static final int PEDIDO_ARQUIVO = 41;

    private WebView web;
    private ValueCallback<Uri[]> escolhaPendente;
    private boolean paginaPronta = false;

    private final Object trava = new Object();
    private String arquivosPendentes = null; // JSON [{name,mime,b64}]
    private String textoPendente = null;

    /** Pasta dentro de assets com a página deste aplicativo. */
    protected abstract String pasta();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);

        web.addJavascriptInterface(new Ponte(), "Papelaria");
        web.setWebViewClient(new Cliente());
        web.setWebChromeClient(new Escolhedor());

        receber(getIntent());
        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        }
        if (savedInstanceState == null || web.getUrl() == null) {
            web.loadUrl("https://" + HOST + "/" + pasta() + "/index.html");
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null) web.saveState(outState);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        receber(intent);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    // ---------- Arquivos e textos vindos de outros aplicativos ----------

    private void receber(final Intent intent) {
        if (intent == null) return;
        final String acao = intent.getAction();
        if (acao == null) return;
        final List<Uri> uris = new ArrayList<>();
        String texto = null;
        if (Intent.ACTION_VIEW.equals(acao)) {
            if (intent.getData() != null) uris.add(intent.getData());
        } else if (Intent.ACTION_SEND.equals(acao)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
            CharSequence t = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (t != null && u == null) texto = t.toString();
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(acao)) {
            ArrayList<Uri> lista = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (lista != null) uris.addAll(lista);
        } else {
            return;
        }
        if (texto != null) {
            synchronized (trava) { textoPendente = texto; }
            entregar();
        }
        if (uris.isEmpty()) return;
        final String tipoIntent = intent.getType();
        new Thread(new Runnable() {
            @Override
            public void run() {
                JSONArray arr = new JSONArray();
                String erro = null;
                for (Uri u : uris) {
                    try {
                        byte[] dados = ler(u);
                        String tipo = getContentResolver().getType(u);
                        if (tipo == null) tipo = tipoIntent;
                        if (tipo == null || tipo.contains("*")) tipo = "application/octet-stream";
                        JSONObject o = new JSONObject();
                        o.put("name", nome(u));
                        o.put("mime", tipo);
                        o.put("b64", Base64.encodeToString(dados, Base64.NO_WRAP));
                        arr.put(o);
                    } catch (Exception e) {
                        erro = e.getMessage();
                    }
                }
                synchronized (trava) {
                    if (arr.length() > 0) arquivosPendentes = arr.toString();
                }
                final String erroFinal = erro;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (erroFinal != null) {
                            Toast.makeText(WebActivity.this, "Não consegui abrir um dos arquivos.", Toast.LENGTH_LONG).show();
                        }
                        entregar();
                    }
                });
            }
        }).start();
    }

    private void entregar() {
        if (web == null || !paginaPronta) return;
        boolean tem;
        synchronized (trava) { tem = arquivosPendentes != null || textoPendente != null; }
        if (tem) web.evaluateJavascript("window.papelariaReceber&&window.papelariaReceber()", null);
    }

    private byte[] ler(Uri u) throws IOException {
        InputStream in = getContentResolver().openInputStream(u);
        if (in == null) throw new IOException("sem dados");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private String nome(Uri u) {
        String n = null;
        Cursor c = null;
        try {
            c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) n = c.getString(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        if (n == null) n = u.getLastPathSegment();
        if (n == null || n.trim().isEmpty()) n = "arquivo";
        return n;
    }

    // ---------- Ponte com a página ----------

    private class Ponte {
        @JavascriptInterface
        public String pegarArquivos() {
            synchronized (trava) {
                String r = arquivosPendentes;
                arquivosPendentes = null;
                return r == null ? "[]" : r;
            }
        }

        @JavascriptInterface
        public String pegarTexto() {
            synchronized (trava) {
                String r = textoPendente;
                textoPendente = null;
                return r == null ? "" : r;
            }
        }

        @JavascriptInterface
        public void imprimir(String b64, String nome, String tipo) {
            enviarArquivo(b64, nome, tipo, true);
        }

        @JavascriptInterface
        public void compartilhar(String b64, String nome, String tipo) {
            enviarArquivo(b64, nome, tipo, false);
        }
    }

    private void enviarArquivo(String b64, String nome, String tipo, final boolean imprimir) {
        final Uri uri;
        try {
            byte[] dados = Base64.decode(b64, Base64.DEFAULT);
            File pasta = new File(getCacheDir(), Arquivos.PASTA);
            if (!pasta.exists()) pasta.mkdirs();
            File[] antigos = pasta.listFiles();
            if (antigos != null) for (File f : antigos) f.delete();
            String limpo = Arquivos.limparNome(nome);
            File f = new File(pasta, limpo);
            FileOutputStream out = new FileOutputStream(f);
            out.write(dados);
            out.close();
            uri = Arquivos.uriPara(this, limpo);
        } catch (final Exception e) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(WebActivity.this, "Não consegui preparar o arquivo.", Toast.LENGTH_LONG).show();
                }
            });
            return;
        }
        final String mime = (tipo == null || tipo.isEmpty()) ? "application/pdf" : tipo;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Intent envio = new Intent(Intent.ACTION_SEND);
                envio.setType(mime);
                envio.putExtra(Intent.EXTRA_STREAM, uri);
                envio.setClipData(ClipData.newRawUri("", uri));
                envio.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    if (imprimir) {
                        Intent epson = new Intent(envio);
                        epson.setPackage(EPSON);
                        if (epson.resolveActivity(getPackageManager()) != null) {
                            startActivity(epson);
                            return;
                        }
                        Toast.makeText(WebActivity.this, "Epson iPrint não encontrado. Escolha o aplicativo da impressora.", Toast.LENGTH_LONG).show();
                        startActivity(Intent.createChooser(envio, "Imprimir com"));
                    } else {
                        startActivity(Intent.createChooser(envio, "Enviar com"));
                    }
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(WebActivity.this, "Nenhum aplicativo para abrir o arquivo.", Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    // ---------- Escolher arquivos (fotos da galeria, PDFs) ----------

    private class Escolhedor extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (escolhaPendente != null) escolhaPendente.onReceiveValue(null);
            escolhaPendente = callback;
            String tipo = "*/*";
            String[] aceitos = params.getAcceptTypes();
            if (aceitos != null) {
                for (String a : aceitos) {
                    if (a == null) continue;
                    if (a.startsWith("image")) { tipo = "image/*"; break; }
                    if (a.contains("pdf")) { tipo = "application/pdf"; break; }
                }
            }
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType(tipo);
            if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
            try {
                startActivityForResult(i, PEDIDO_ARQUIVO);
            } catch (ActivityNotFoundException e) {
                escolhaPendente = null;
                Toast.makeText(WebActivity.this, "Não encontrei onde escolher arquivos.", Toast.LENGTH_LONG).show();
                return false;
            }
            return true;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != PEDIDO_ARQUIVO) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (escolhaPendente == null) return;
        Uri[] resultado = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                resultado = new Uri[clip.getItemCount()];
                for (int k = 0; k < clip.getItemCount(); k++) resultado[k] = clip.getItemAt(k).getUri();
            } else if (data.getData() != null) {
                resultado = new Uri[]{data.getData()};
            }
        }
        escolhaPendente.onReceiveValue(resultado);
        escolhaPendente = null;
    }

    // ---------- Servir as páginas guardadas no aplicativo ----------

    private class Cliente extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            if (!HOST.equals(u.getHost())) return null;
            String caminho = u.getPath();
            if (caminho == null) caminho = "/";
            while (caminho.startsWith("/")) caminho = caminho.substring(1);
            if (caminho.isEmpty() || caminho.endsWith("/")) caminho = caminho + "index.html";
            Map<String, String> cab = new HashMap<>();
            cab.put("Access-Control-Allow-Origin", "*");
            cab.put("Cache-Control", "no-cache");
            if (caminho.contains("..") || caminho.endsWith("sw.js")) {
                return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", cab, new ByteArrayInputStream(new byte[0]));
            }
            try {
                InputStream in = getAssets().open(caminho);
                String tipo = tipoDe(caminho);
                String cod = (tipo.startsWith("text/") || tipo.contains("javascript") || tipo.contains("json")) ? "utf-8" : null;
                return new WebResourceResponse(tipo, cod, 200, "OK", cab, in);
            } catch (IOException e) {
                return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", cab, new ByteArrayInputStream(new byte[0]));
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            if (HOST.equals(u.getHost())) return false;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, u));
            } catch (ActivityNotFoundException ignored) {
            }
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            paginaPronta = true;
            entregar();
        }
    }

    static String tipoDe(String caminho) {
        String c = caminho.toLowerCase();
        if (c.endsWith(".html")) return "text/html";
        if (c.endsWith(".js")) return "application/javascript";
        if (c.endsWith(".css")) return "text/css";
        if (c.endsWith(".json") || c.endsWith(".webmanifest")) return "application/json";
        if (c.endsWith(".png")) return "image/png";
        if (c.endsWith(".jpg") || c.endsWith(".jpeg")) return "image/jpeg";
        if (c.endsWith(".svg")) return "image/svg+xml";
        if (c.endsWith(".ttf")) return "font/ttf";
        if (c.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }
}

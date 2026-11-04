package br.com.papelaria.apps;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** Entrega o PDF pronto a outros aplicativos (Epson iPrint, WhatsApp) sem precisar salvar em Downloads. */
public class Arquivos extends ContentProvider {

    static final String PASTA = "compartilhar";

    static String autoridade(Context c) {
        return c.getPackageName() + ".arquivos";
    }

    static Uri uriPara(Context c, String nome) {
        return new Uri.Builder().scheme("content").authority(autoridade(c)).appendPath(nome).build();
    }

    static String limparNome(String nome) {
        if (nome == null || nome.trim().isEmpty()) nome = "documento.pdf";
        String n = nome.replaceAll("[^A-Za-z0-9._ ()\\-À-ÿ]", "_").trim();
        if (n.startsWith(".")) n = "_" + n;
        if (n.length() > 120) n = n.substring(n.length() - 120);
        return n;
    }

    private File arquivo(Uri uri) throws FileNotFoundException {
        String nome = uri.getLastPathSegment();
        if (nome == null || nome.contains("/") || nome.contains("..")) throw new FileNotFoundException();
        File f = new File(new File(getContext().getCacheDir(), PASTA), nome);
        if (!f.exists()) throw new FileNotFoundException();
        return f;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(arquivo(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f;
        try {
            f = arquivo(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] cols = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        c.addRow(new Object[]{f.getName(), f.length()});
        return c;
    }

    @Override
    public String getType(Uri uri) {
        String n = uri.getLastPathSegment();
        return n == null ? "application/octet-stream" : WebActivity.tipoDe(n);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}

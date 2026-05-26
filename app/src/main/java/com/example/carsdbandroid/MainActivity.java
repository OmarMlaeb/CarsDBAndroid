package com.example.carsdbandroid;

import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.speech.RecognizerIntent;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class MainActivity extends AppCompatActivity {

    private Button btnPickDb, btnSearch;
    private TextView tvDbPath;
    private Spinner spinnerTables, spinnerColumns;
    private EditText etQuery;
    private ImageButton btnMic;
    private RecyclerView rvResults;
    private RecyclerView rvHistory;
    private ProgressBar progress;
    private TextView tvResultSummary;
    private TextView tvEmptyState;
    private TextView tvHistoryEmpty;
    private TextView btnClearHistory;
    private View historyHeader;

    private File localDbFile;
    private SQLiteDatabase db;
    private ResultsAdapter adapter;
    private HistoryAdapter historyAdapter;
    private final List<String> searchHistory = new ArrayList<>();

    private ActivityResultLauncher<Intent> openDocLauncher;
    private ActivityResultLauncher<Intent> speechLauncher;

    private static final String PREFS_NAME = "plate_search_prefs";
    private static final String KEY_SEARCH_HISTORY = "search_history";
    private static final String KEY_CREATED_INDEXES = "created_indexes";
    private static final int MAX_HISTORY_ITEMS = 25;

    // background work
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Future<?> searchFuture;

    // keys for the list headline fields
    private String detectedPlateKey = null;
    private String detectedCodeKey  = null;
    private String requestedColumnsTable = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnPickDb = findViewById(R.id.btnPickDb);
        tvDbPath = findViewById(R.id.tvDbPath);
        spinnerTables = findViewById(R.id.spinnerTables);
        spinnerColumns = findViewById(R.id.spinnerColumns);
        etQuery = findViewById(R.id.etQuery);
        btnMic = findViewById(R.id.btnMic);
        btnSearch = findViewById(R.id.btnSearch);
        rvResults = findViewById(R.id.rvResults);
        rvHistory = findViewById(R.id.rvHistory);
        progress = findViewById(R.id.progress);
        tvResultSummary = findViewById(R.id.tvResultSummary);
        tvEmptyState = findViewById(R.id.tvEmptyState);
        tvHistoryEmpty = findViewById(R.id.tvHistoryEmpty);
        btnClearHistory = findViewById(R.id.btnClearHistory);
        historyHeader = findViewById(R.id.historyHeader);

        // RecyclerView
        rvResults.setLayoutManager(new LinearLayoutManager(this));
        rvResults.setHasFixedSize(true);
        adapter = new ResultsAdapter();
        rvResults.setAdapter(adapter);
        adapter.setOnItemClick(this::openDetails);

        rvHistory.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rvHistory.setHasFixedSize(true);
        historyAdapter = new HistoryAdapter();
        rvHistory.setAdapter(historyAdapter);
        historyAdapter.setOnHistoryClick(plate -> {
            etQuery.setText(plate);
            etQuery.setSelection(etQuery.getText().length());
            hideKeyboard();
            doSearchAsync();
        });
        btnClearHistory.setOnClickListener(v -> clearSearchHistory());
        loadSearchHistory();

        // file picker launcher (kept for future use)
        openDocLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        handlePickedDbAsync(uri);
                    }
                });

        // speech launcher
        speechLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        ArrayList<String> matches = result.getData()
                                .getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                        if (matches != null && !matches.isEmpty()) {
                            String spoken = matches.get(0);
                            etQuery.setText(parsePlateLike(spoken));
                            etQuery.setSelection(etQuery.getText().length());
                        }
                    }
                });

        // listeners
        btnPickDb.setOnClickListener(v -> pickDb());
        btnMic.setOnClickListener(v -> startSpeech());
        btnSearch.setOnClickListener(v -> {
            hideKeyboard();
            doSearchAsync();
        });

        spinnerTables.setOnItemSelectedListener(new SimpleItemSelected(() -> {
            String table = (String) spinnerTables.getSelectedItem();
            if (table != null && db != null) loadColumnsAsync(table);
        }));

        // Copy bundled DB from res/raw only the first time, then reuse the saved app-local file.
        btnPickDb.setVisibility(View.GONE);
        loadDbFromRawAsync(R.raw.cars, "cars.db");
    }

    // ---------- Details navigation ----------
    private void openDetails(Map<String, Object> row) {
        ArrayList<String> keys = new ArrayList<>();
        ArrayList<String> values = new ArrayList<>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            keys.add(e.getKey());
            values.add(e.getValue() == null ? "" : String.valueOf(e.getValue()));
        }
        String plate = getValueCaseInsensitive(row, detectedPlateKey);
        String code  = getValueCaseInsensitive(row, detectedCodeKey);

        Intent i = new Intent(this, DetailActivity.class);
        i.putStringArrayListExtra("keys", keys);
        i.putStringArrayListExtra("values", values);
        i.putExtra("plate", plate);
        i.putExtra("code", code);
        startActivity(i);
    }

    private String getValueCaseInsensitive(Map<String, Object> row, String key) {
        if (row == null || key == null) return "";
        for (String k : row.keySet()) {
            if (k.equalsIgnoreCase(key)) {
                Object v = row.get(k);
                return v == null ? "" : String.valueOf(v);
            }
        }
        return "";
    }

    // ---------- UI helpers ----------
    private void showLoading(boolean show) {
        main.post(() -> progress.setVisibility(show ? View.VISIBLE : View.GONE));
    }

    private void setSpinner(Spinner spinner, List<String> items) {
        ArrayAdapter<String> aa = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, items);
        aa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(aa);
    }

    private void toast(String msg) {
        main.post(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }

    private void hideKeyboard() {
        View v = getCurrentFocus();
        if (v == null) v = etQuery;
        if (v != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
            v.clearFocus();
        }
    }

    // ---------- Search history ----------
    private void loadSearchHistory() {
        searchHistory.clear();
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String raw = prefs.getString(KEY_SEARCH_HISTORY, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String plate = normalizeHistoryQuery(arr.optString(i, ""));
                if (!plate.isEmpty() && !containsHistoryItem(plate)) {
                    searchHistory.add(plate);
                }
            }
        } catch (JSONException ignored) {
            prefs.edit().remove(KEY_SEARCH_HISTORY).apply();
        }
        updateHistoryUi();
    }

    private void addSearchToHistory(String query) {
        String plate = normalizeHistoryQuery(query);
        if (plate.isEmpty()) return;

        for (int i = searchHistory.size() - 1; i >= 0; i--) {
            if (searchHistory.get(i).equalsIgnoreCase(plate)) {
                searchHistory.remove(i);
            }
        }
        searchHistory.add(0, plate);
        while (searchHistory.size() > MAX_HISTORY_ITEMS) {
            searchHistory.remove(searchHistory.size() - 1);
        }
        persistSearchHistory();
        updateHistoryUi();
    }

    private void clearSearchHistory() {
        searchHistory.clear();
        persistSearchHistory();
        updateHistoryUi();
    }

    private void persistSearchHistory() {
        JSONArray arr = new JSONArray();
        for (String plate : searchHistory) arr.put(plate);
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_SEARCH_HISTORY, arr.toString())
                .apply();
    }

    private void updateHistoryUi() {
        if (historyAdapter == null) return;
        historyAdapter.submit(searchHistory);
        boolean empty = searchHistory.isEmpty();
        historyHeader.setVisibility(empty ? View.GONE : View.VISIBLE);
        rvHistory.setVisibility(empty ? View.GONE : View.VISIBLE);
        tvHistoryEmpty.setVisibility(View.GONE);
        btnClearHistory.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private boolean containsHistoryItem(String plate) {
        for (String item : searchHistory) {
            if (item.equalsIgnoreCase(plate)) return true;
        }
        return false;
    }

    private static String normalizeHistoryQuery(String query) {
        if (query == null) return "";
        return query.trim().replaceAll("\\s+", " ").toUpperCase(Locale.US);
    }

    // ---------- Load DB from raw (async) ----------
    private void loadDbFromRawAsync(int rawResId, String outName) {
        io.submit(() -> {
            try {
                File dir = new File(getFilesDir(), "imported");
                if (!dir.exists()) dir.mkdirs();

                File out = new File(dir, outName);
                boolean fileAlreadySaved = out.exists() && out.length() > 0;
                boolean copiedFromRaw = false;

                if (!fileAlreadySaved) {
                    showLoading(true);
                    copyRawDatabaseToFile(rawResId, out);
                    copiedFromRaw = true;
                }
                localDbFile = out;

                try {
                    openDatabaseInternal(out);
                } catch (Exception firstOpenError) {
                    showLoading(true);
                    copyRawDatabaseToFile(rawResId, out);
                    copiedFromRaw = true;
                    openDatabaseInternal(out);
                }
                List<String> tables = loadTablesInternal();

                boolean finalCopiedFromRaw = copiedFromRaw;
                main.post(() -> {
                    tvDbPath.setText((finalCopiedFromRaw ? "Loaded once: " : "Saved locally: ")
                            + out.getName());
                    setSpinner(spinnerTables, tables);
                    if (!tables.isEmpty()) loadColumnsAsync(tables.get(0));
                    showLoading(false);
                });
            } catch (Exception e) {
                e.printStackTrace();
                toast("Failed to load database: " + e.getMessage());
                showLoading(false);
            }
        });
    }

    private void copyRawDatabaseToFile(int rawResId, File out) throws IOException {
        try (InputStream in = getResources().openRawResource(rawResId);
             FileOutputStream outStream = new FileOutputStream(out, false)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) outStream.write(buf, 0, n);
            outStream.getFD().sync();
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .remove(KEY_CREATED_INDEXES)
                .apply();
    }

    // ---------- Picker flow (optional) ----------
    private void pickDb() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        String[] mimetypes = new String[] { "application/octet-stream", "application/x-sqlite3" };
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimetypes);
        openDocLauncher.launch(intent);
    }

    private void handlePickedDbAsync(Uri uri) {
        showLoading(true);
        io.submit(() -> {
            try {
                String displayName = getDisplayName(uri);
                if (displayName == null) displayName = "database.db";

                File dir = new File(getFilesDir(), "imported");
                if (!dir.exists()) dir.mkdirs();

                File out = new File(dir, displayName);
                copyUriToFile(getContentResolver(), uri, out);
                localDbFile = out;

                openDatabaseInternal(out);
                List<String> tables = loadTablesInternal();

                String finalName = displayName;
                main.post(() -> {
                    tvDbPath.setText("Loaded: " + (finalName != null ? finalName : out.getName()));
                    setSpinner(spinnerTables, tables);
                    if (!tables.isEmpty()) loadColumnsAsync(tables.get(0));
                    showLoading(false);
                });
            } catch (Exception e) {
                e.printStackTrace();
                toast("Failed to open DB: " + e.getMessage());
                showLoading(false);
            }
        });
    }

    // ---------- DB helpers ----------
    private void openDatabaseInternal(File file) {
        closeDatabase();
        db = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        requestedColumnsTable = null;
    }

    private List<String> loadTablesInternal() {
        List<String> tables = new ArrayList<>();
        if (db == null) return tables;
        try (Cursor c = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' " +
                        "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' " +
                        "ORDER BY name", null)) {
            while (c.moveToNext()) tables.add(c.getString(0));
        }
        return tables;
    }

    private void loadColumnsAsync(String table) {
        if (TextUtils.equals(table, requestedColumnsTable)) return;
        requestedColumnsTable = table;
        io.submit(() -> {
            List<String> cols = new ArrayList<>();
            if (db != null) {
                try (Cursor c = db.rawQuery("PRAGMA table_info('" + table.replace("'", "''") + "')", null)) {
                    int idx = c.getColumnIndex("name");
                    while (c.moveToNext()) cols.add(c.getString(idx));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            // detect headline keys
            String plateKey = detectColumn(cols,
                    "ActualNB", "Plate", "Number", "No", "Matricule", "CarNumber", "ActualNo");
            String codeKey = detectColumn(cols,
                    "CodeDesc", "Code_Desc", "Code", "Description", "Desc");

            detectedPlateKey = plateKey != null ? plateKey : (cols.isEmpty() ? null : cols.get(0));
            detectedCodeKey  = codeKey  != null ? codeKey  : (cols.size() > 1 ? cols.get(1) : detectedPlateKey);

            List<String> finalCols = cols;
            main.post(() -> {
                setSpinner(spinnerColumns, finalCols);  // WHERE column picker
                adapter.setKeys(detectedPlateKey, detectedCodeKey);
            });
        });
    }

    private static String detectColumn(List<String> cols, String... candidates) {
        if (cols == null || cols.isEmpty()) return null;
        for (String cand : candidates) for (String c : cols) if (c.equalsIgnoreCase(cand)) return c;
        for (String cand : candidates) {
            String needle = cand.toLowerCase(Locale.US);
            for (String c : cols) if (c.toLowerCase(Locale.US).contains(needle)) return c;
        }
        return null;
    }

    // ---------- Search (async, index-friendly) ----------
    private void doSearchAsync() {
        if (db == null) {
            Toast.makeText(this, "Load a database first", Toast.LENGTH_SHORT).show();
            return;
        }
        final String table = (String) spinnerTables.getSelectedItem();
        final String whereCol = (String) spinnerColumns.getSelectedItem();
        final String q = etQuery.getText().toString().trim();
        if (TextUtils.isEmpty(table) || TextUtils.isEmpty(whereCol) || TextUtils.isEmpty(q)) {
            Toast.makeText(this, "Pick table/column and enter a query", Toast.LENGTH_SHORT).show();
            return;
        }

        addSearchToHistory(q);
        if (searchFuture != null) searchFuture.cancel(true);
        showLoading(true);
        btnSearch.setEnabled(false);
        tvResultSummary.setText("Searching...");
        tvEmptyState.setVisibility(View.GONE);

        searchFuture = io.submit(() -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            try {
                String trimmed = q.trim();
                boolean digitsOnly = trimmed.matches("\\d+");
                ensureSearchIndexInternal(table, whereCol);

                // FAST PATH: exact match without functions on the column (keeps indexes usable)
                String sql, sqlGuard = "", fallbackSql;
                String[] args, fallbackArgs;
                String quotedTable = quoteIdentifier(table);
                String quotedWhereCol = quoteIdentifier(whereCol);

                if (digitsOnly) {
                    sql = "SELECT * FROM " + quotedTable + " WHERE " + quotedWhereCol + " = ? LIMIT 200";
                    args = new String[]{ trimmed };
                    // optional guard (exclude '1234 A'); used only in fallback
                    sqlGuard = " AND (" + quotedWhereCol + " GLOB '[0-9]*' OR " + quotedWhereCol + " GLOB '[0-9]*.[0]*') ";
                    fallbackSql = "SELECT * FROM " + quotedTable + " WHERE TRIM(" + quotedWhereCol + ") = ? " +
                            sqlGuard + "LIMIT 200";
                    fallbackArgs = new String[]{ trimmed };
                } else {
                    sql = "SELECT * FROM " + quotedTable + " WHERE " + quotedWhereCol + " = ? COLLATE NOCASE LIMIT 200";
                    args = new String[]{ trimmed };
                    fallbackSql = "SELECT * FROM " + quotedTable + " WHERE TRIM(" + quotedWhereCol + ") = TRIM(?) " +
                            "COLLATE NOCASE LIMIT 200";
                    fallbackArgs = new String[]{ trimmed };
                }

                queryIntoRows(db, sql, args, rows);

                // Fallback for dirty trailing spaces in data (only if fast path found nothing)
                if (rows.isEmpty()) queryIntoRows(db, fallbackSql, fallbackArgs, rows);

            } catch (Exception e) {
                e.printStackTrace();
                toast("Query error: " + e.getMessage());
            }

            main.post(() -> {
                adapter.setKeys(detectedPlateKey, detectedCodeKey);
                adapter.submit(rows);
                btnSearch.setEnabled(true);
                showLoading(false);
                tvResultSummary.setText(rows.size() == 1 ? "1 match" : rows.size() + " matches");
                tvEmptyState.setText(rows.isEmpty()
                        ? "No record found for " + normalizeHistoryQuery(q) + "."
                        : "");
                tvEmptyState.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    private void ensureSearchIndexInternal(String table, String col) {
        if (localDbFile == null || table == null || col == null) return;

        String key = localDbFile.getAbsolutePath() + "|" + table + "|" + col;
        if (isIndexMarkedCreated(key)) return;

        SQLiteDatabase wdb = null;
        try {
            wdb = SQLiteDatabase.openDatabase(localDbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
            String idx = "idx_search_" + table.replaceAll("\\W+", "_")
                    + "_" + col.replaceAll("\\W+", "_");
            wdb.execSQL("CREATE INDEX IF NOT EXISTS " + quoteIdentifier(idx)
                    + " ON " + quoteIdentifier(table)
                    + "(" + quoteIdentifier(col) + ")");
            markIndexCreated(key);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (wdb != null && wdb.isOpen()) wdb.close();
        }
    }

    private boolean isIndexMarkedCreated(String key) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String raw = prefs.getString(KEY_CREATED_INDEXES, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                if (key.equals(arr.optString(i))) return true;
            }
        } catch (JSONException ignored) {
            prefs.edit().remove(KEY_CREATED_INDEXES).apply();
        }
        return false;
    }

    private void markIndexCreated(String key) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        JSONArray arr;
        try {
            arr = new JSONArray(prefs.getString(KEY_CREATED_INDEXES, "[]"));
        } catch (JSONException ignored) {
            arr = new JSONArray();
        }
        for (int i = 0; i < arr.length(); i++) {
            if (key.equals(arr.optString(i))) return;
        }
        arr.put(key);
        prefs.edit().putString(KEY_CREATED_INDEXES, arr.toString()).apply();
    }

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static void queryIntoRows(SQLiteDatabase db, String sql, String[] args, List<Map<String, Object>> out) {
        try (Cursor c = db.rawQuery(sql, args)) {
            String[] colNames = c.getColumnNames();
            while (!Thread.currentThread().isInterrupted() && c.moveToNext()) {
                Map<String, Object> map = new LinkedHashMap<>();
                for (String name : colNames) {
                    int idx = c.getColumnIndex(name);
                    if (idx >= 0) {
                        switch (c.getType(idx)) {
                            case Cursor.FIELD_TYPE_NULL:    map.put(name, null); break;
                            case Cursor.FIELD_TYPE_INTEGER: map.put(name, c.getLong(idx)); break;
                            case Cursor.FIELD_TYPE_FLOAT:   map.put(name, c.getDouble(idx)); break;
                            case Cursor.FIELD_TYPE_STRING:  map.put(name, c.getString(idx)); break;
                            case Cursor.FIELD_TYPE_BLOB:    map.put(name, "<BLOB>"); break;
                            default: map.put(name, c.getString(idx));
                        }
                    }
                }
                out.add(map);
            }
        }
    }

    // ---------- Speech ----------
    private void startSpeech() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Say car number, e.g. '234567 N'");
        try {
            speechLauncher.launch(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Speech not supported on this device", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- Generic helpers ----------
    private static void copyUriToFile(ContentResolver cr, Uri uri, File outFile) throws IOException {
        try (InputStream in = cr.openInputStream(uri);
             OutputStream out = new FileOutputStream(outFile)) {
            if (in == null) throw new IOException("Cannot open input stream");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        }
    }

    @Nullable
    private String getDisplayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        }
        return name;
    }

    /** Normalize voice like "two three four five six seven N" to "234567 N". */
    private String parsePlateLike(String spoken) {
        if (spoken == null) return "";
        String s = spoken.trim();

        Map<String,String> words = new HashMap<>();
        words.put("zero","0"); words.put("oh","0"); words.put("o","0");
        words.put("one","1"); words.put("two","2"); words.put("three","3");
        words.put("four","4"); words.put("for","4");
        words.put("five","5"); words.put("six","6"); words.put("seven","7");
        words.put("eight","8"); words.put("nine","9");

        String[] tokens = s.toLowerCase(Locale.US).split("\\s+");
        StringBuilder digits = new StringBuilder();
        String suffix = "";

        for (String t : tokens) {
            if (words.containsKey(t)) digits.append(words.get(t));
            else if (t.matches("\\d+")) digits.append(t);
            else if (t.matches("[a-zA-Z]")) suffix = t.toUpperCase(Locale.US);
        }

        if (digits.length() > 0) {
            return suffix.isEmpty() ? digits.toString() : (digits + " " + suffix);
        }
        return s;
    }

    private void closeDatabase() {
        if (db != null && db.isOpen()) db.close();
        db = null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (searchFuture != null) searchFuture.cancel(true);
        io.shutdownNow();
        closeDatabase();
    }

    // compact listener
    private static class SimpleItemSelected implements AdapterView.OnItemSelectedListener {
        private final Runnable callback;
        SimpleItemSelected(Runnable cb) { callback = cb; }
        @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { callback.run(); }
        @Override public void onNothingSelected(AdapterView<?> parent) { }
    }
}

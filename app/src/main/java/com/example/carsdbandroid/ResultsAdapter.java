package com.example.carsdbandroid;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.math.BigDecimal;
import java.util.*;

public class ResultsAdapter extends RecyclerView.Adapter<ResultsAdapter.VH> {

    public interface OnItemClick { void onClick(Map<String, Object> row); }

    private final List<Map<String, Object>> data = new ArrayList<>();
    private String plateKey = null;
    private String codeKey = null;
    private OnItemClick clickListener;

    public void setKeys(String plateKey, String codeKey) {
        this.plateKey = plateKey;
        this.codeKey = codeKey;
    }

    public void setOnItemClick(OnItemClick l) { this.clickListener = l; }

    public void submit(List<Map<String, Object>> rows) {
        data.clear();
        if (rows != null) data.addAll(rows);
        notifyDataSetChanged();
    }

    @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_result, parent, false);
        return new VH(v);
    }

    @Override public void onBindViewHolder(@NonNull VH holder, int position) {
        Map<String, Object> row = data.get(position);

        String plate = valueOf(row, plateKey);
        String code  = valueOf(row, codeKey);

        // Strip trailing .0 only for the detected plate field.
        plate = normalizeNumberStringIfNumeric(plate);

        holder.tvPlate.setText(plate.isEmpty() ? "Unknown plate" : plate);
        holder.tvCode.setText(code.isEmpty() ? "No description available" : code);

        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null) clickListener.onClick(row);
        });
    }

    @Override public int getItemCount() { return data.size(); }

    private static String valueOf(Map<String, Object> row, String key) {
        if (row == null || key == null) return "";
        // exact, else case-insensitive
        if (row.containsKey(key)) return stringOf(row.get(key));
        for (String k : row.keySet()) {
            if (k.equalsIgnoreCase(key)) return stringOf(row.get(k));
        }
        return "";
    }

    private static String stringOf(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /** If the string is a pure number (e.g., "1234.0", "1234.000", "1.0E3"), return "1234". Otherwise return as-is. */
    private static String normalizeNumberStringIfNumeric(String s) {
        if (s == null || s.isEmpty()) return "";
        // Do not touch values that clearly contain letters/spaces like "1234 A".
        if (!s.trim().matches("[+-]?[0-9]*\\.?[0-9]+([eE][+-]?[0-9]+)?")) return s;
        try {
            BigDecimal bd = new BigDecimal(s.trim());
            bd = bd.stripTrailingZeros();
            return bd.toPlainString();
        } catch (NumberFormatException ignored) {
            return s;
        }
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvPlate, tvCode;
        VH(@NonNull View itemView) {
            super(itemView);
            tvPlate = itemView.findViewById(R.id.tvPlate);
            tvCode  = itemView.findViewById(R.id.tvCode);
        }
    }
}

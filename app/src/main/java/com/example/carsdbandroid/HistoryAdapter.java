package com.example.carsdbandroid;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.VH> {

    public interface OnHistoryClick {
        void onClick(String plate);
    }

    private final List<String> plates = new ArrayList<>();
    private OnHistoryClick clickListener;

    public void setOnHistoryClick(OnHistoryClick listener) {
        clickListener = listener;
    }

    public void submit(List<String> items) {
        plates.clear();
        if (items != null) plates.addAll(items);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_history, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        String plate = plates.get(position);
        holder.tvHistoryPlate.setText(plate);
        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null) clickListener.onClick(plate);
        });
    }

    @Override
    public int getItemCount() {
        return plates.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvHistoryPlate;

        VH(@NonNull View itemView) {
            super(itemView);
            tvHistoryPlate = itemView.findViewById(R.id.tvHistoryPlate);
        }
    }
}

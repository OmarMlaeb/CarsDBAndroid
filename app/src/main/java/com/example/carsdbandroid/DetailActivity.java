package com.example.carsdbandroid;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;

public class DetailActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);

        TextView tvHeader = findViewById(R.id.tvHeader);
        TextView tvSub = findViewById(R.id.tvSub);
        LinearLayout fieldContainer = findViewById(R.id.fieldContainer);

        ArrayList<String> keys = getIntent().getStringArrayListExtra("keys");
        ArrayList<String> values = getIntent().getStringArrayListExtra("values");
        String plate = getIntent().getStringExtra("plate");
        String code = getIntent().getStringExtra("code");

        tvHeader.setText(plate == null ? "" : plate);
        tvSub.setText(code == null ? "" : code);

        if (keys != null && values != null) {
            LayoutInflater inflater = LayoutInflater.from(this);
            for (int i = 0; i < keys.size(); i++) {
                String k = keys.get(i);
                String v = values.get(i) == null ? "" : values.get(i);
                if (k != null && (k.equalsIgnoreCase("ActualNB")
                        || k.equalsIgnoreCase("Plate")
                        || k.equalsIgnoreCase("Number"))) {
                    v = normalizeNumberStringIfNumeric(v);
                }

                View row = inflater.inflate(R.layout.item_detail_field, fieldContainer, false);
                TextView label = row.findViewById(R.id.tvFieldLabel);
                TextView value = row.findViewById(R.id.tvFieldValue);
                label.setText(k == null ? "" : k.toUpperCase(java.util.Locale.US));
                value.setText(v.isEmpty() ? "-" : v);
                fieldContainer.addView(row);
            }
        }
    }

    private String normalizeNumberStringIfNumeric(String s) {
        if (s == null || s.isEmpty()) return "";
        if (!s.trim().matches("[+-]?[0-9]*\\.?[0-9]+([eE][+-]?[0-9]+)?")) return s;
        try {
            java.math.BigDecimal bd = new java.math.BigDecimal(s.trim());
            bd = bd.stripTrailingZeros();
            return bd.toPlainString();
        } catch (NumberFormatException ignored) {
            return s;
        }
    }
}

package com.quicktap.pos.ui.reviews;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.quicktap.pos.R;
import com.quicktap.pos.databinding.FragmentReviewsBinding;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class 类090 extends Fragment {
    private static final String PREFS = "quicktap_reviews";
    private static final String KEY_REVIEWS = "items";
    private FragmentReviewsBinding binding;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentReviewsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        binding.buttonSubmitReview.setOnClickListener(v -> submitReview());
        renderReviews();
    }

    private void submitReview() {
        String name = text(binding.inputReviewName);
        String review = text(binding.inputReviewText);
        int rating = Math.max(1, Math.min(5, Math.round(binding.reviewRating.getRating())));
        if (TextUtils.isEmpty(name)) { binding.inputReviewName.setError("Enter your name"); binding.inputReviewName.requestFocus(); return; }
        if (TextUtils.isEmpty(review)) { binding.inputReviewText.setError("Write your review"); binding.inputReviewText.requestFocus(); return; }
        try {
            JSONArray items = load();
            JSONArray next = new JSONArray();
            JSONObject item = new JSONObject().put("name", name).put("review", review).put("rating", rating).put("createdAt", System.currentTimeMillis());
            next.put(item);
            for (int i = 0; i < items.length(); i++) next.put(items.getJSONObject(i));
            save(next);
            binding.inputReviewName.setText("");
            binding.inputReviewText.setText("");
            binding.reviewRating.setRating(5);
            renderReviews();
            Toast.makeText(requireContext(), "Review submitted", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Could not save review", Toast.LENGTH_SHORT).show();
        }
    }

    private void renderReviews() {
        JSONArray items = load();
        binding.reviewList.removeAllViews();
        binding.textReviewCount.setText(items.length() + (items.length() == 1 ? " review" : " reviews"));
        binding.textNoReviews.setVisibility(items.length() == 0 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < items.length(); i++) {
            try { binding.reviewList.addView(reviewCard(items.getJSONObject(i))); } catch (Exception ignored) { }
        }
    }

    private View reviewCard(JSONObject item) {
        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(15, 13, 15, 13);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.WHITE); bg.setCornerRadius(18); bg.setStroke(1, Color.rgb(231, 235, 240)); card.setBackground(bg);
        TextView top = new TextView(requireContext());
        int rating = item.optInt("rating", 5);
        top.setText("★".repeat(rating) + "  " + item.optString("name", "Customer"));
        top.setTextColor(Color.rgb(20, 30, 45)); top.setTextSize(15); top.setTypeface(null, 1);
        TextView date = new TextView(requireContext());
        date.setText(formatDate(item.optLong("createdAt", 0))); date.setTextColor(Color.rgb(100, 116, 139)); date.setTextSize(11); date.setPadding(0, 3, 0, 7);
        TextView body = new TextView(requireContext());
        body.setText(item.optString("review", "")); body.setTextColor(Color.rgb(51, 65, 85)); body.setTextSize(14); body.setLineSpacing(2, 1.05f);
        card.addView(top); card.addView(date); card.addView(body);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, 0, 0, 9); card.setLayoutParams(lp);
        return card;
    }

    private JSONArray load() {
        try { return new JSONArray(requireContext().getSharedPreferences(PREFS, 0).getString(KEY_REVIEWS, "[]")); } catch (Exception e) { return new JSONArray(); }
    }
    private void save(JSONArray items) { requireContext().getSharedPreferences(PREFS, 0).edit().putString(KEY_REVIEWS, items.toString()).apply(); }
    private String text(TextInputEditText input) { return input.getText() == null ? "" : input.getText().toString().trim(); }
    private String formatDate(long time) { return time <= 0 ? "" : new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(new Date(time)); }

    @Override public void onDestroyView() { super.onDestroyView(); binding = null; }
}

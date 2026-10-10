package com.quicktap.pos.ui.dashboard;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.quicktap.pos.类001;
import com.quicktap.pos.databinding.FragmentDashboardBinding;
import com.quicktap.pos.print.类023;
import com.quicktap.pos.ui.类042;
import com.quicktap.pos.ui.billing.类062;
import com.quicktap.pos.ui.history.类071;
import com.quicktap.pos.ui.reports.类089;
import com.quicktap.pos.util.类046;
import com.quicktap.pos.util.类049;
import com.quicktap.pos.util.类053;

import java.util.ArrayList;
import java.util.Comparator;

/** Today's numbers, recent bills and quick actions. */
public class 类068 extends Fragment {

    private FragmentDashboardBinding binding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentDashboardBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        类046 prefs = 类046.get(requireContext());
        String currency = prefs.getCurrency();
        binding.textToday.setText(类049.header(System.currentTimeMillis()));

        类071 adapter = new 类071(currency, new 类071.Listener() {
            @Override public void onViewReceipt(long billId) {
                com.quicktap.pos.print.类029.loadReceipt(requireContext(), billId,
                        new com.quicktap.pos.print.类029.Callback() {
                            @Override public void onReady(android.graphics.Bitmap bitmap) {
                                if (bitmap != null) bitmap.recycle();
                            }
                            @Override public void onError(String message) { }
                        });
            }
            @Override public void onReprint(long billId) {
                类023.reprint(requireContext(), billId);
            }
            @Override public void onShare(long billId) {
                com.quicktap.pos.print.类029.share(requireContext(), billId, null);
            }
            @Override public void onDelete(long billId) {
                类001.get().repo().deleteBill(billId);
            }
        });
        binding.recyclerRecent.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recyclerRecent.setNestedScrollingEnabled(false);
        binding.recyclerRecent.setAdapter(adapter);

        类001.get().repo()
                .observeSummary(类049.startOfToday(), 类049.endOfToday())
                .observe(getViewLifecycleOwner(), summary -> {
                    if (summary == null) return;
                    binding.textSales.setText(类053.withCurrency(currency, summary.revenue));
                    binding.textOrders.setText(String.valueOf(summary.orders));
                    binding.textPaid.setText(String.valueOf(summary.paidCount));
                    binding.textUnpaid.setText(String.valueOf(summary.unpaidCount));
                    binding.textUnpaidAmount.setText(
                            "Outstanding " + 类053.withCurrency(currency, summary.unpaidAmount));
                });

        类001.get().repo().observeRecentBills(8)
                .observe(getViewLifecycleOwner(), bills -> {
                    adapter.submit(bills);
                    binding.textNoBills.setVisibility(
                            bills == null || bills.isEmpty() ? View.VISIBLE : View.GONE);
                });

        类001.get().repo().observeAllProducts()
                .observe(getViewLifecycleOwner(), products -> {
                    int tracked = 0;
                    int low = 0;
                    int out = 0;
                    if (products != null && prefs.isLowStockAlertsEnabled()) {
                        for (com.quicktap.pos.data.entity.Product product : products) {
                            if (product.stock < 0) continue;
                            tracked++;
                            if (product.stock == 0) out++;
                            else if (product.stock <= product.minimumStock) low++;
                        }
                    }
                    boolean alertsOn = prefs.isLowStockAlertsEnabled();
                    binding.cardLowStock.setVisibility(alertsOn && tracked > 0 ? View.VISIBLE : View.GONE);
                    binding.textLowStock.setText(low > 0 ? "⚠ Low Stock: " + low : "Low Stock: 0");
                    binding.textOutOfStock.setText("Out of Stock: " + out);
                });
        类001.get().repo().observeAllProducts()
                .observe(getViewLifecycleOwner(), products -> {
                    binding.containerBestSellers.removeAllViews();
                    if (products == null || products.isEmpty()) return;
                    ArrayList<com.quicktap.pos.data.entity.Product> ranked = new ArrayList<>(products);
                    ranked.sort(Comparator.comparingInt((com.quicktap.pos.data.entity.Product p) -> p.soldCount).reversed());
                    int limit = Math.min(3, ranked.size());
                    for (int i = 0; i < limit; i++) {
                        com.quicktap.pos.data.entity.Product product = ranked.get(i);
                        if (product.soldCount <= 0) continue;
                        android.widget.TextView row = new android.widget.TextView(requireContext());
                        row.setText((i + 1) + "  " + product.name + "  ·  " + product.soldCount + " sold");
                        row.setTextSize(13f);
                        row.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), com.quicktap.pos.R.color.text_primary));
                        row.setPadding(0, 6, 0, 6);
                        binding.containerBestSellers.addView(row);
                    }
                });
        binding.actionStock.setOnClickListener(v ->
                ((类042) requireActivity()).show(new com.quicktap.pos.ui.类040(), false));
        binding.actionNewBill.setOnClickListener(v ->
                ((类042) requireActivity()).show(new 类062(), false));
        binding.actionReports.setOnClickListener(v ->
                ((类042) requireActivity()).show(new 类089(), true));
        binding.actionReprint.setOnClickListener(v -> 类023.reprintLast(requireContext()));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}

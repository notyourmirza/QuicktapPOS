package com.quicktap.pos.ui.reports;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.quicktap.pos.类001;
import com.quicktap.pos.data.model.类058;
import com.quicktap.pos.data.model.类059;
import com.quicktap.pos.data.model.类061;
import com.quicktap.pos.databinding.FragmentReportsBinding;
import com.quicktap.pos.util.类045;
import com.quicktap.pos.util.类046;
import com.quicktap.pos.util.类048;
import com.quicktap.pos.util.类049;
import com.quicktap.pos.util.类053;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** Today / week / month reports with sales by product and category, plus CSV export. */
public class 类089 extends Fragment {

    private FragmentReportsBinding binding;
    private ActivityResultLauncher<String> exportPicker;

    private long from = 类049.startOfToday();
    private long to = 类049.endOfToday();
    private String title = "Today's report";
    private long customFrom;
    private long customTo;
    private final SimpleDateFormat customDateFormat = new SimpleDateFormat("dd MMM", Locale.getDefault());

    private final List<类061> productRows = new ArrayList<>();
    private final List<类058> categoryRows = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentReportsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        exportPicker = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("text/csv"), this::writeExport);

        binding.chipRange.check(binding.chipToday.getId());
        binding.chipToday.setOnClickListener(v -> {
            binding.customDateControls.setVisibility(View.GONE);
            load(类049.startOfToday(), 类049.endOfToday(), "Today's report");
        });
        binding.chipWeek.setOnClickListener(v -> {
            binding.customDateControls.setVisibility(View.GONE);
            load(类049.startOfWeek(), 类049.endOfToday(), "Weekly report");
        });
        binding.chipMonth.setOnClickListener(v -> {
            binding.customDateControls.setVisibility(View.GONE);
            load(类049.startOfMonth(), 类049.endOfToday(), "Monthly report");
        });
        binding.chipCustom.setOnClickListener(v -> {
            binding.customDateControls.setVisibility(View.VISIBLE);
            if (customFrom == 0L) showDatePicker(true);
        });
        binding.buttonFromDate.setOnClickListener(v -> showDatePicker(true));
        binding.buttonToDate.setOnClickListener(v -> showDatePicker(false));
        binding.buttonExport.setOnClickListener(v ->
                exportPicker.launch("quicktap_report_"
                        + 类049.fileStamp(System.currentTimeMillis()) + ".csv"));

        load(from, to, title);
    }

    private void showDatePicker(boolean fromPicker) {
        Calendar seed = Calendar.getInstance();
        long existing = fromPicker ? customFrom : customTo;
        if (existing > 0L) seed.setTimeInMillis(existing);
        DatePickerDialog dialog = new DatePickerDialog(requireContext(), (picker, year, month, day) -> {
            Calendar selected = Calendar.getInstance();
            selected.set(year, month, day, 0, 0, 0);
            selected.set(Calendar.MILLISECOND, 0);
            if (fromPicker) {
                customFrom = selected.getTimeInMillis();
                binding.buttonFromDate.setText("From  " + customDateFormat.format(selected.getTime()));
                if (customTo == 0L || customTo < customFrom) customTo = endOfDay(customFrom);
                binding.buttonToDate.setText("To  " + customDateFormat.format(new java.util.Date(customTo)));
                // Open the second picker immediately so a range can be selected naturally.
                showDatePicker(false);
            } else {
                customTo = endOfDay(selected.getTimeInMillis());
                if (customFrom == 0L) customFrom = startOfDay(customTo);
                if (customTo < customFrom) {
                    Toast.makeText(requireContext(), "To date must be on or after From date", Toast.LENGTH_SHORT).show();
                    return;
                }
                binding.buttonToDate.setText("To  " + customDateFormat.format(selected.getTime()));
                load(customFrom, customTo, "Custom report · " + customDateFormat.format(new java.util.Date(customFrom)) + " – " + customDateFormat.format(selected.getTime()));
            }
        }, seed.get(Calendar.YEAR), seed.get(Calendar.MONTH), seed.get(Calendar.DAY_OF_MONTH));
        dialog.show();
    }

    private long startOfDay(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private long endOfDay(long millis) { return startOfDay(millis) + 86_400_000L - 1L; }

    private void load(long start, long end, String reportTitle) {
        from = start;
        to = end;
        title = reportTitle;
        binding.textTitle.setText(reportTitle);
        if (reportTitle.startsWith("Custom report")) binding.customDateControls.setVisibility(View.VISIBLE);

        类045.io().execute(() -> {
            类059 summary = 类001.get().repo().summary(start, end);
            List<类061> products = 类001.get().repo().salesByProduct(start, end);
            List<类058> categories = 类001.get().repo().salesByCategory(start, end);
            double productCost = 类001.get().repo().productCostBetween(start, end);
            double expenses = 类001.get().repo().expenseTotalBetween(start, end);
            类045.main().post(() -> {
                if (binding == null) return;
                productRows.clear();
                productRows.addAll(products);
                categoryRows.clear();
                categoryRows.addAll(categories);
                render(summary, products, categories, productCost, expenses);
            });
        });
    }

    private void render(类059 summary, List<类061> products,
                        List<类058> categories, double productCost, double expenses) {
        String currency = 类046.get(requireContext()).getCurrency();
        binding.textRevenue.setText(类053.withCurrency(currency,
                summary == null ? 0 : summary.revenue));
        binding.textOrders.setText(String.valueOf(summary == null ? 0 : summary.orders));
        binding.textUnpaid.setText(类053.withCurrency(currency,
                summary == null ? 0 : summary.unpaidAmount));
        double revenue = summary == null ? 0 : summary.revenue;
        double netProfit = revenue - productCost - expenses;
        binding.textNetProfit.setText(类053.withCurrency(currency, netProfit));
        binding.textProfitBreakdown.setText("Revenue " + 类053.withCurrency(currency, revenue)
                + " − product cost " + 类053.withCurrency(currency, productCost)
                + " − expenses " + 类053.withCurrency(currency, expenses));

        binding.containerProducts.removeAllViews();
        if (products.isEmpty()) {
            binding.containerProducts.addView(empty("No product sales in this period"));
        } else {
            for (类061 row : products) {
                binding.containerProducts.addView(
                        row(row.name + "  x" + row.qty, 类053.withCurrency(currency, row.amount)));
            }
        }
        binding.containerCategories.removeAllViews();
        if (categories.isEmpty()) {
            binding.containerCategories.addView(empty("No category sales in this period"));
        } else {
            for (类058 row : categories) {
                binding.containerCategories.addView(
                        row(row.category + "  x" + row.qty, 类053.withCurrency(currency, row.amount)));
            }
        }
    }

    /** Placeholder row so an empty range never looks like a broken report. */
    private View empty(String message) {
        TextView text = new TextView(requireContext());
        text.setText(message);
        text.setTextSize(13f);
        text.setTextColor(com.quicktap.pos.theme.类036.textMuted(requireContext()));
        int pad = Math.round(getResources().getDisplayMetrics().density * 10);
        text.setPadding(0, pad, 0, pad);
        return text;
    }

    /** Reports must reflect bills taken since the tab was last opened. */
    @Override
    public void onResume() {
        super.onResume();
        if (binding != null) load(from, to, title);
    }

    private View row(String left, String right) {
        View view = LayoutInflater.from(requireContext())
                .inflate(com.quicktap.pos.R.layout.item_report_row, binding.containerProducts, false);
        ((TextView) view.findViewById(com.quicktap.pos.R.id.textLeft)).setText(left);
        ((TextView) view.findViewById(com.quicktap.pos.R.id.textRight)).setText(right);
        return view;
    }

    private void writeExport(Uri uri) {
        if (uri == null) return;
        类045.io().execute(() -> {
            try {
                类048.writeReport(requireContext(), uri, title, productRows, categoryRows);
                类045.main().post(() -> toast("Report exported"));
            } catch (Exception e) {
                类045.main().post(() -> toast("Export failed: " + e.getMessage()));
            }
        });
    }

    private void toast(String message) {
        if (isAdded()) Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}

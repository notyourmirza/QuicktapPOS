package com.quicktap.pos.ui.history;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.quicktap.pos.类001;
import com.quicktap.pos.data.entity.Bill;
import com.quicktap.pos.data.model.类060;
import com.quicktap.pos.databinding.FragmentHistoryBinding;
import com.quicktap.pos.print.类023;
import com.quicktap.pos.print.类029;
import com.quicktap.pos.util.类046;
import com.quicktap.pos.util.类049;
import com.quicktap.pos.util.类053;

import java.util.Calendar;
import java.util.List;

/** Database-backed Sales History search and combined filters. */
public class 类072 extends Fragment {
    private FragmentHistoryBinding binding;
    private final MutableLiveData<FilterState> filterState = new MutableLiveData<>(new FilterState());
    private LiveData<List<Bill>> filteredBills;
    private LiveData<类060> filteredSummary;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                                                   @Nullable Bundle savedInstanceState) {
        binding = FragmentHistoryBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        类046 prefs = 类046.get(requireContext());
        类071 adapter = new 类071(prefs.getCurrency(), new 类071.Listener() {
            @Override public void onViewReceipt(long id) { shareReceipt(id); }
            @Override public void onReprint(long id) { 类023.reprint(requireContext(), id); }
            @Override public void onShare(long id) { shareReceipt(id); }
            @Override public void onDelete(long id) { confirmDelete(id); }
        });
        binding.recyclerBills.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recyclerBills.setAdapter(adapter);

        filteredBills = Transformations.switchMap(filterState, state -> 类001.get().repo()
                .observeFilteredBills(state.from, state.to, state.query, state.paymentMethod,
                        state.orderType, state.paidMode, state.discountMode, 500));
        filteredSummary = Transformations.switchMap(filterState, state -> 类001.get().repo()
                .observeFilteredHistorySummary(state.from, state.to, state.query, state.paymentMethod,
                        state.orderType, state.paidMode, state.discountMode));
        filteredBills.observe(getViewLifecycleOwner(), bills -> {
            adapter.submit(bills);
            boolean empty = bills == null || bills.isEmpty();
            binding.textEmpty.setText(filterState.getValue() != null && filterState.getValue().hasCriteria()
                    ? "No Orders Found\nTry clearing a filter or changing your search."
                    : "No bills recorded yet.\nCompleted sales will show up here.");
            binding.textEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        });
        filteredSummary.observe(getViewLifecycleOwner(), summary -> {
            if (summary == null) return;
            binding.textHistorySummary.setText("Orders: " + summary.orders + " · Sales: "
                    + 类053.withCurrency(prefs.getCurrency(), summary.sales) + " · Discount: "
                    + 类053.withCurrency(prefs.getCurrency(), summary.discount));
        });
        binding.inputHistorySearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                FilterState state = copy(); state.query = s == null ? "" : s.toString().trim(); filterState.setValue(state);
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        binding.buttonHistoryFilter.setOnClickListener(v -> showFilters());
        binding.buttonReprintLast.setOnClickListener(v -> 类023.reprintLast(requireContext()));
        updateFilterButton();
    }

    private FilterState copy() { return filterState.getValue() == null ? new FilterState() : filterState.getValue().copy(); }

    private void showFilters() {
        FilterState current = copy();
        LinearLayout box = new LinearLayout(requireContext()); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(20, 4, 20, 0);
        Spinner date = spinner(new String[]{"Today", "Yesterday", "This Week", "This Month", "Custom Date", "Custom Date Range"});
        Spinner payment = spinner(new String[]{"All Payment Methods", "Cash", "PAID", "UNPAID"});
        Spinner order = spinner(new String[]{"All Order Types", "Dine In", "Delivery", "Take Away"});
        Spinner status = spinner(new String[]{"All Payment Status", "Paid", "Unpaid"});
        Spinner discount = spinner(new String[]{"All Orders", "Discounted Orders", "No Discount"});
        box.addView(label("Date range")); box.addView(date);
        box.addView(label("Payment method")); box.addView(payment);
        box.addView(label("Order type")); box.addView(order);
        box.addView(label("Payment status")); box.addView(status);
        box.addView(label("Discount")); box.addView(discount);
        Button fromButton = new Button(requireContext()); fromButton.setText("From date");
        Button toButton = new Button(requireContext()); toButton.setText("To date");
        LinearLayout dates = new LinearLayout(requireContext()); dates.setOrientation(LinearLayout.HORIZONTAL); dates.addView(fromButton, new LinearLayout.LayoutParams(0, -2, 1)); dates.addView(toButton, new LinearLayout.LayoutParams(0, -2, 1)); box.addView(dates);
        setDateSpinner(date, current);
        fromButton.setOnClickListener(v -> pickDate(fromButton, current, true));
        toButton.setOnClickListener(v -> pickDate(toButton, current, false));
        new MaterialAlertDialogBuilder(requireContext()).setTitle("Filter Sales History").setView(box)
                .setNegativeButton("Clear Filters", (d, w) -> { filterState.setValue(new FilterState()); updateFilterButton(); })
                .setPositiveButton("Apply", (d, w) -> {
                    FilterState next = current.copy(); applyDateChoice(next, date.getSelectedItemPosition());
                    next.paymentMethod = payment.getSelectedItemPosition() == 1 ? "CASH" : payment.getSelectedItemPosition() == 2 ? "PAID" : payment.getSelectedItemPosition() == 3 ? "UNPAID" : "";
                    next.orderType = order.getSelectedItemPosition() == 1 ? Bill.TYPE_DINE_IN : order.getSelectedItemPosition() == 2 ? Bill.TYPE_DELIVERY : order.getSelectedItemPosition() == 3 ? Bill.TYPE_TAKE_AWAY : "";
                    next.paidMode = status.getSelectedItemPosition() == 1 ? 1 : status.getSelectedItemPosition() == 2 ? 0 : -1;
                    next.discountMode = discount.getSelectedItemPosition(); filterState.setValue(next); updateFilterButton();
                }).show();
    }

    private TextView label(String text) { TextView v = new TextView(requireContext()); v.setText(text); v.setPadding(0, 10, 0, 2); return v; }
    private Spinner spinner(String[] values) { Spinner s = new Spinner(requireContext()); s.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, values)); return s; }
    private void setDateSpinner(Spinner s, FilterState state) { s.setSelection(state.dateMode); }
    private void applyDateChoice(FilterState state, int mode) {
        state.dateMode = mode; Calendar c = Calendar.getInstance();
        if (mode == 0) { state.from = 类049.startOfToday(); state.to = 类049.endOfToday(); }
        else if (mode == 1) { state.to = 类049.startOfToday() - 1; state.from = state.to - 86_400_000L + 1; }
        else if (mode == 2) { state.from = 类049.startOfWeek(); state.to = 类049.endOfToday(); }
        else if (mode == 3) { state.from = 类049.startOfMonth(); state.to = 类049.endOfToday(); }
        else if (mode == 4 || mode == 5) { if (state.customFrom > 0) state.from = state.customFrom; if (state.customTo > 0) state.to = state.customTo; }
    }
    private void pickDate(Button button, FilterState state, boolean from) {
        Calendar c = Calendar.getInstance(); DatePickerDialog dialog = new DatePickerDialog(requireContext(), (v, year, month, day) -> {
            Calendar chosen = Calendar.getInstance(); chosen.set(year, month, day, from ? 0 : 23, from ? 0 : 59, from ? 0 : 59); chosen.set(Calendar.MILLISECOND, from ? 0 : 999);
            if (from) state.customFrom = chosen.getTimeInMillis(); else state.customTo = chosen.getTimeInMillis(); button.setText((from ? "From: " : "To: ") + day + "/" + (month + 1) + "/" + year);
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)); dialog.show();
    }
    private void updateFilterButton() { if (binding == null) return; binding.buttonHistoryFilter.setText(copy().hasCriteria() ? "Filter · Active" : "Filter"); }

    private void shareReceipt(long id) { 类029.share(requireContext(), id, new 类029.Callback() { @Override public void onReady(android.graphics.Bitmap b) { } @Override public void onError(String m) { if (isAdded()) Toast.makeText(requireContext(), m, Toast.LENGTH_SHORT).show(); } }); }
    private void confirmDelete(long id) { new MaterialAlertDialogBuilder(requireContext()).setTitle("Delete bill?").setMessage("This removes the bill and its items from reports.").setPositiveButton("Delete", (d,w) -> 类001.get().repo().deleteBill(id)).setNegativeButton("Cancel", null).show(); }
    @Override public void onDestroyView() { super.onDestroyView(); binding = null; }

    private static class FilterState {
        long from = 类049.oneMonthAgo(), to = 类049.endOfToday(), customFrom, customTo; int dateMode = 3, paidMode = -1, discountMode = 0; String query = "", paymentMethod = "", orderType = "";
        FilterState copy() { FilterState x = new FilterState(); x.from=from; x.to=to; x.customFrom=customFrom; x.customTo=customTo; x.dateMode=dateMode; x.paidMode=paidMode; x.discountMode=discountMode; x.query=query; x.paymentMethod=paymentMethod; x.orderType=orderType; return x; }
        boolean hasCriteria() { return !query.isEmpty() || !paymentMethod.isEmpty() || !orderType.isEmpty() || paidMode != -1 || discountMode != 0 || dateMode != 3; }
    }
}

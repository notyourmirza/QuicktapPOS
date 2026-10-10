package com.quicktap.pos.ui.expenses;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.quicktap.pos.类001;
import com.quicktap.pos.data.entity.Expense;
import com.quicktap.pos.data.entity.ExpenseCategory;
import com.quicktap.pos.databinding.FragmentExpensesBinding;
import com.quicktap.pos.util.类045;
import com.quicktap.pos.util.类046;
import com.quicktap.pos.util.类049;
import com.quicktap.pos.util.类053;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class 类070 extends Fragment {
    private FragmentExpensesBinding binding;
    private 类069 adapter;
    private final List<Expense> all = new ArrayList<>();
    private final List<String> categories = new ArrayList<>();
    private long from = 类049.startOfToday();
    private long to = 类049.endOfToday();
    private long customFrom;
    private long customTo;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("hh:mm a", Locale.getDefault());
    private final SimpleDateFormat dayFormat = new SimpleDateFormat("dd MMM yyyy", Locale.getDefault());

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                                                   @Nullable Bundle savedInstanceState) {
        binding = FragmentExpensesBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        adapter = new 类069(类046.get(requireContext()).getCurrency(), new 类069.Listener() {
            @Override public void onEdit(Expense expense) { showExpenseDialog(expense); }
            @Override public void onDelete(Expense expense) { confirmDelete(expense); }
        });
        binding.recyclerExpenses.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recyclerExpenses.setAdapter(adapter);
        binding.buttonAddExpense.setOnClickListener(v -> showExpenseDialog(null));

        binding.chipExpenseRange.check(binding.chipExpenseToday.getId());
        binding.chipExpenseToday.setOnClickListener(v -> setRange(类049.startOfToday(), 类049.endOfToday()));
        binding.chipExpenseYesterday.setOnClickListener(v -> {
            long today = 类049.startOfToday();
            setRange(today - 86_400_000L, today - 1L);
        });
        binding.chipExpenseWeek.setOnClickListener(v -> setRange(类049.startOfWeek(), 类049.endOfToday()));
        binding.chipExpenseMonth.setOnClickListener(v -> setRange(类049.startOfMonth(), 类049.endOfToday()));
        binding.chipExpenseCustom.setOnClickListener(v -> chooseCustomRange());

        类001.get().repo().observeExpenses().observe(getViewLifecycleOwner(), expenses -> {
            all.clear(); if (expenses != null) all.addAll(expenses); render();
        });
        类001.get().repo().observeExpenseCategories().observe(getViewLifecycleOwner(), loaded -> {
            categories.clear();
            categories.addAll(builtInCategories());
            if (loaded != null) for (ExpenseCategory category : loaded) if (!containsIgnoreCase(categories, category.name)) categories.add(category.name);
        });
    }

    private void setRange(long start, long end) { from = start; to = end; render(); }

    private void chooseCustomRange() {
        pickDate(true, customFrom == 0L ? System.currentTimeMillis() : customFrom);
    }

    private void pickDate(boolean fromPicker, long seedMillis) {
        Calendar seed = Calendar.getInstance(); seed.setTimeInMillis(seedMillis);
        new DatePickerDialog(requireContext(), (v, year, month, day) -> {
            Calendar selected = Calendar.getInstance(); selected.set(year, month, day, 0, 0, 0); selected.set(Calendar.MILLISECOND, 0);
            if (fromPicker) { customFrom = selected.getTimeInMillis(); pickDate(false, customTo == 0L ? customFrom : customTo); }
            else { customTo = selected.getTimeInMillis() + 86_400_000L - 1L; if (customTo < customFrom) customTo = 类049.endOfToday(); setRange(customFrom, customTo); }
        }, seed.get(Calendar.YEAR), seed.get(Calendar.MONTH), seed.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void render() {
        if (binding == null) return;
        List<Expense> visible = new ArrayList<>();
        for (Expense expense : all) if (expense.date >= from && expense.date <= to) visible.add(expense);
        adapter.submit(visible);
        String currency = 类046.get(requireContext()).getCurrency();
        double selected = sum(visible);
        double today = sumBetween(类049.startOfToday(), 类049.endOfToday());
        double week = sumBetween(类049.startOfWeek(), 类049.endOfToday());
        double month = sumBetween(类049.startOfMonth(), 类049.endOfToday());
        binding.textPeriodExpenses.setText(类053.withCurrency(currency, selected));
        binding.textTotalExpenses.setText(类053.withCurrency(currency, month));
        binding.textTodayExpenses.setText("Today: " + 类053.withCurrency(currency, today));
        binding.textWeekExpenses.setText("This week: " + 类053.withCurrency(currency, week));
        binding.textExpenseEmpty.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private double sum(List<Expense> rows) { double total = 0; for (Expense e : rows) total += e.amount; return total; }
    private double sumBetween(long start, long end) { double total = 0; for (Expense e : all) if (e.date >= start && e.date <= end) total += e.amount; return total; }

    private void showExpenseDialog(@Nullable Expense existing) {
        LinearLayout form = new LinearLayout(requireContext()); form.setOrientation(LinearLayout.VERTICAL); int pad = dp(20); form.setPadding(pad, 0, pad, 0);
        EditText title = input("Expense title", InputType.TYPE_CLASS_TEXT); form.addView(title);
        Spinner category = new Spinner(requireContext()); form.addView(category, fieldParams());
        EditText amount = input("Amount", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL); form.addView(amount);
        EditText note = input("Description / note (optional)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE); form.addView(note);
        EditText date = input("Date", InputType.TYPE_CLASS_TEXT); date.setFocusable(false); form.addView(date);
        EditText time = input("Time", InputType.TYPE_CLASS_TEXT); time.setFocusable(false); form.addView(time);
        ArrayAdapter<String> categoryAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, categories);
        category.setAdapter(categoryAdapter);
        Calendar selected = Calendar.getInstance();
        if (existing != null) {
            title.setText(existing.title); amount.setText(String.valueOf(existing.amount)); note.setText(existing.description); selected.setTimeInMillis(existing.date); date.setText(dayFormat.format(new Date(existing.date))); time.setText(existing.time);
            int index = indexOf(categories, existing.category); if (index >= 0) category.setSelection(index);
        } else { date.setText(dayFormat.format(selected.getTime())); time.setText(timeFormat.format(selected.getTime())); }
        date.setOnClickListener(v -> chooseFormDate(date, selected));
        time.setOnClickListener(v -> new TimePickerDialog(requireContext(), (picker, hour, minute) -> { selected.set(Calendar.HOUR_OF_DAY, hour); selected.set(Calendar.MINUTE, minute); time.setText(String.format(Locale.getDefault(), "%02d:%02d %s", hour == 0 || hour == 12 ? 12 : hour % 12, minute, hour < 12 ? "AM" : "PM")); }, selected.get(Calendar.HOUR_OF_DAY), selected.get(Calendar.MINUTE), false).show());
        new MaterialAlertDialogBuilder(requireContext()).setTitle(existing == null ? "Add expense" : "Edit expense").setView(form)
                .setNeutralButton("New category", (d, w) -> showNewCategoryDialog())
                .setNegativeButton("Cancel", null)
                .setPositiveButton(existing == null ? "Save" : "Update", (d, w) -> {
                    String cleanTitle = title.getText().toString().trim(); String rawAmount = amount.getText().toString().trim();
                    if (cleanTitle.isEmpty() || rawAmount.isEmpty()) { toast("Title and amount are required"); return; }
                    Expense expense = existing == null ? new Expense() : existing;
                    expense.title = cleanTitle; expense.category = String.valueOf(category.getSelectedItem()); expense.amount = Double.parseDouble(rawAmount); expense.date = startOfDay(selected.getTimeInMillis()); expense.time = time.getText().toString(); expense.description = note.getText().toString().trim();
                    类001.get().repo().saveExpense(expense, null);
                }).show();
    }

    private void chooseFormDate(EditText field, Calendar selected) {
        new DatePickerDialog(requireContext(), (v, y, m, d) -> { selected.set(y, m, d); field.setText(dayFormat.format(selected.getTime())); }, selected.get(Calendar.YEAR), selected.get(Calendar.MONTH), selected.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void showNewCategoryDialog() {
        EditText input = input("Category name", InputType.TYPE_CLASS_TEXT);
        new MaterialAlertDialogBuilder(requireContext()).setTitle("New expense category").setView(input).setNegativeButton("Cancel", null).setPositiveButton("Add", (d, w) -> {
            String name = input.getText().toString().trim(); if (!name.isEmpty()) 类001.get().repo().addExpenseCategory(name, null);
        }).show();
    }

    private void confirmDelete(Expense expense) {
        new MaterialAlertDialogBuilder(requireContext()).setTitle("Delete expense?").setMessage("This expense will be removed from profit calculations.").setNegativeButton("Cancel", null).setPositiveButton("Delete", (d, w) -> 类001.get().repo().deleteExpense(expense, null)).show();
    }

    private EditText input(String hint, int type) { EditText e = new EditText(requireContext()); e.setHint(hint); e.setInputType(type); e.setSingleLine(!hint.contains("note")); e.setPadding(0, dp(8), 0, dp(8)); return e; }
    private LinearLayout.LayoutParams fieldParams() { return new LinearLayout.LayoutParams(-1, dp(52)); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private long startOfDay(long millis) { Calendar c = Calendar.getInstance(); c.setTimeInMillis(millis); c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0); return c.getTimeInMillis(); }
    private boolean containsIgnoreCase(List<String> list, String value) { return indexOf(list, value) >= 0; }
    private int indexOf(List<String> list, String value) { for (int i = 0; i < list.size(); i++) if (list.get(i).equalsIgnoreCase(value)) return i; return -1; }
    private List<String> builtInCategories() { List<String> out = new ArrayList<>(); out.add("Rent"); out.add("Electricity"); out.add("Internet"); out.add("Salary"); out.add("Transport"); out.add("Maintenance"); out.add("Purchase-related Expense"); out.add("Other"); return out; }
    private void toast(String text) { if (isAdded()) Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show(); }
    @Override public void onDestroyView() { super.onDestroyView(); binding = null; }
}

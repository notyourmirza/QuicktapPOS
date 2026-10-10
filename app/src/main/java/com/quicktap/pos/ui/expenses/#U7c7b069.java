package com.quicktap.pos.ui.expenses;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.quicktap.pos.data.entity.Expense;
import com.quicktap.pos.databinding.ItemExpenseBinding;
import com.quicktap.pos.util.类049;
import com.quicktap.pos.util.类053;

import java.util.ArrayList;
import java.util.List;

public class 类069 extends RecyclerView.Adapter<类069.VH> {
    public interface Listener {
        void onEdit(Expense expense);
        void onDelete(Expense expense);
    }

    private final List<Expense> items = new ArrayList<>();
    private final Listener listener;
    private final String currency;

    public 类069(String currency, Listener listener) {
        this.currency = currency;
        this.listener = listener;
    }

    public void submit(List<Expense> expenses) {
        items.clear();
        if (expenses != null) items.addAll(expenses);
        notifyDataSetChanged();
    }

    @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(ItemExpenseBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override public void onBindViewHolder(@NonNull VH holder, int position) {
        Expense expense = items.get(position);
        ItemExpenseBinding b = holder.binding;
        b.textExpenseItemTitle.setText(expense.title);
        b.textExpenseItemMeta.setText(expense.category + "  •  " + 类049.receipt(expense.date) + "  " + expense.time);
        b.textExpenseItemNote.setText(expense.description == null || expense.description.isEmpty() ? "No note" : expense.description);
        b.textExpenseItemAmount.setText(类053.withCurrency(currency, expense.amount));
        b.buttonEditExpense.setOnClickListener(v -> listener.onEdit(expense));
        b.buttonDeleteExpense.setOnClickListener(v -> listener.onDelete(expense));
    }

    @Override public int getItemCount() { return items.size(); }

    static final class VH extends RecyclerView.ViewHolder {
        final ItemExpenseBinding binding;
        VH(ItemExpenseBinding binding) { super(binding.getRoot()); this.binding = binding; }
    }
}

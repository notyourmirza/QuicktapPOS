package com.quicktap.pos.ui.history;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.quicktap.pos.data.entity.Bill;
import com.quicktap.pos.databinding.ItemBillBinding;
import com.quicktap.pos.util.类049;
import com.quicktap.pos.util.类053;

import java.util.ArrayList;
import java.util.List;

/** Recent bills list with reprint and delete. */
public class 类071 extends RecyclerView.Adapter<类071.VH> {

    public interface Listener {
        void onViewReceipt(long billId);
        void onReprint(long billId);
        void onShare(long billId);
        void onDelete(long billId);
    }

    private final List<Bill> bills = new ArrayList<>();
    private final Listener listener;
    private final String currency;

    public 类071(String currency, Listener listener) {
        this.currency = currency;
        this.listener = listener;
    }

    public void submit(List<Bill> items) {
        bills.clear();
        if (items != null) bills.addAll(items);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(ItemBillBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        Bill bill = bills.get(position);
        ItemBillBinding b = holder.binding;
        b.textInvoice.setText(bill.invoiceNo);
        b.textDate.setText(类049.receipt(bill.createdAt));
        b.textTotal.setText(类053.withCurrency(currency, bill.total));
        b.textStatus.setText(bill.paid ? "PAID" : "UNPAID");
        b.textType.setText(readable(bill.orderType));
        double appliedDiscount = bill.discountAmount > 0 ? bill.discountAmount : bill.discount;
        if (appliedDiscount > 0) {
            String mode = "PERCENT".equals(bill.discountType)
                    ? String.format(java.util.Locale.US, "%.2f%%", bill.discountPercentage)
                    : "Fixed";
            b.textDiscount.setText("Discount " + mode + " · " + 类053.withCurrency(currency, appliedDiscount));
            b.textDiscount.setVisibility(android.view.View.VISIBLE);
        } else {
            b.textDiscount.setText("Discount · 0");
            b.textDiscount.setVisibility(android.view.View.VISIBLE);
        }
        b.buttonViewReceipt.setOnClickListener(v -> listener.onViewReceipt(bill.id));
        b.buttonReprint.setOnClickListener(v -> listener.onReprint(bill.id));
        b.buttonShare.setOnClickListener(v -> listener.onShare(bill.id));
        b.buttonDelete.setOnClickListener(v -> listener.onDelete(bill.id));
    }

    private String readable(String type) {
        if (Bill.TYPE_TAKE_AWAY.equals(type)) return "Take Away";
        if (Bill.TYPE_DELIVERY.equals(type)) return "Delivery";
        if (Bill.TYPE_DINE_IN.equals(type)) return "Dine In";
        return type == null || type.isEmpty() ? "Dine In" : type;
    }

    @Override public int getItemCount() { return bills.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final ItemBillBinding binding;
        VH(ItemBillBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}

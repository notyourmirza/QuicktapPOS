package com.quicktap.pos.ui.billing;

import android.net.Uri;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.quicktap.pos.data.model.类057;
import com.quicktap.pos.databinding.ItemCartBinding;
import com.quicktap.pos.util.类053;

import java.util.ArrayList;
import java.util.List;

/** Live order summary rows with +, - and delete. */
public class 类064 extends RecyclerView.Adapter<类064.VH> {

    public interface Listener {
        void onQtyChanged(int index, int qty);
        void onRemove(int index);
    }

    private final List<类057> lines = new ArrayList<>();
    private final Listener listener;
    private final String currency;

    public 类064(String currency, Listener listener) {
        this.currency = currency;
        this.listener = listener;
    }

    public void submit(List<类057> items) {
        lines.clear();
        if (items != null) lines.addAll(items);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(ItemCartBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        类057 line = lines.get(position);
        ItemCartBinding b = holder.binding;
        b.textName.setText(line.name);
        if (line.imageUri == null || line.imageUri.trim().isEmpty()) {
            b.imageProduct.setImageResource(com.quicktap.pos.R.drawable.demo_product_cold_coffee);
        } else {
            try {
                b.imageProduct.setImageURI(Uri.parse(line.imageUri));
            } catch (Exception ignored) {
                b.imageProduct.setImageResource(com.quicktap.pos.R.drawable.demo_product_cold_coffee);
            }
        }
        b.textPrice.setText(类053.withCurrency(currency, line.price));
        b.textQty.setText(String.valueOf(line.qty));
        b.textTotal.setText(类053.format(line.total()));

        b.buttonPlus.setOnClickListener(v ->
                listener.onQtyChanged(holder.getBindingAdapterPosition(), line.qty + 1));
        b.buttonMinus.setOnClickListener(v ->
                listener.onQtyChanged(holder.getBindingAdapterPosition(), line.qty - 1));
        b.buttonDelete.setOnClickListener(v ->
                listener.onRemove(holder.getBindingAdapterPosition()));
    }

    @Override public int getItemCount() { return lines.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final ItemCartBinding binding;
        VH(ItemCartBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}

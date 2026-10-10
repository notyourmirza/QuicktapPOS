package com.quicktap.pos.ui.products;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.quicktap.pos.data.entity.Product;
import com.quicktap.pos.databinding.ItemProductManageBinding;
import com.quicktap.pos.util.类053;

import java.util.ArrayList;
import java.util.List;

public class 类087 extends RecyclerView.Adapter<类087.VH> {

    public interface Listener {
        void onEdit(Product product);
        void onDelete(Product product);
        void onToggleAvailable(Product product);
        void onRestock(Product product);
    }

    private final List<Product> items = new ArrayList<>();
    private final Listener listener;
    private final String currency;

    public 类087(String currency, Listener listener) {
        this.currency = currency;
        this.listener = listener;
    }

    public void submit(List<Product> products) {
        items.clear();
        if (products != null) items.addAll(products);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(ItemProductManageBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        Product product = items.get(position);
        ItemProductManageBinding b = holder.binding;
        b.textName.setText(product.name);
        b.textPrice.setText(类053.withCurrency(currency, product.price));
        if (product.stock < 0) {
            b.textStock.setText("Stock not tracked");
            b.textStock.setTextColor(ContextCompat.getColor(b.getRoot().getContext(), com.quicktap.pos.R.color.text_muted));
        } else if (product.stock == 0) {
            b.textStock.setText("Out of stock");
            b.textStock.setTextColor(ContextCompat.getColor(b.getRoot().getContext(), com.quicktap.pos.R.color.danger));
        } else if (product.stock <= product.minimumStock) {
            b.textStock.setText("⚠ Low stock — " + product.stock + " left");
            b.textStock.setTextColor(ContextCompat.getColor(b.getRoot().getContext(), com.quicktap.pos.R.color.warning));
        } else {
            b.textStock.setText("Stock: " + product.stock);
            b.textStock.setTextColor(ContextCompat.getColor(b.getRoot().getContext(), com.quicktap.pos.R.color.text_muted));
        }
        b.switchAvailable.setOnCheckedChangeListener(null);
        b.switchAvailable.setChecked(product.available);
        b.switchAvailable.setOnCheckedChangeListener((v, checked) -> {
            product.available = checked;
            listener.onToggleAvailable(product);
        });
        b.buttonRestock.setEnabled(product.stock >= 0);
        b.buttonRestock.setOnClickListener(v -> listener.onRestock(product));
        b.getRoot().setOnClickListener(v -> listener.onEdit(product));
        b.buttonDelete.setOnClickListener(v -> listener.onDelete(product));
    }

    @Override public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final ItemProductManageBinding binding;
        VH(ItemProductManageBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}

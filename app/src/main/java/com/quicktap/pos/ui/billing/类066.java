package com.quicktap.pos.ui.billing;

import android.net.Uri;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.quicktap.pos.data.entity.Product;
import com.quicktap.pos.databinding.ItemProductBinding;
import com.quicktap.pos.util.类053;
import com.quicktap.pos.util.类046;

import java.util.ArrayList;
import java.util.List;

/** Tap = add to bill, long press = edit quantity. */
public class 类066 extends RecyclerView.Adapter<类066.VH> {

    public interface Listener {
        void onTap(Product product);
        void onLongPress(Product product);
    }

    private final List<Product> items = new ArrayList<>();
    private final Listener listener;
    private final String currency;

    public 类066(String currency, Listener listener) {
        this.currency = currency;
        this.listener = listener;
        setHasStableIds(true);
    }

    public void submit(List<Product> products) {
        items.clear();
        if (products != null) items.addAll(products);
        notifyDataSetChanged();
    }

    @Override public long getItemId(int position) { return items.get(position).id; }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(ItemProductBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        Product product = items.get(position);
        ItemProductBinding b = holder.binding;

        b.textName.setText(product.name);
        b.textPrice.setText(类053.withCurrency(currency, product.price));
        b.badgeFavorite.setVisibility(product.favorite ? View.VISIBLE : View.GONE);

        boolean alertsOn = 类046.get(b.getRoot().getContext()).isLowStockAlertsEnabled();
        boolean tracked = product.stock >= 0;
        boolean outOfStock = tracked && product.stock == 0;
        boolean lowStock = tracked && product.stock > 0 && product.stock <= product.minimumStock;
        boolean showStockAlert = alertsOn && (outOfStock || lowStock);
        b.textStockAlert.setVisibility(showStockAlert ? View.VISIBLE : View.GONE);
        b.textStockAlert.setText(outOfStock ? "0 left" : product.stock + " left");
        b.textStockAlert.setBackgroundResource(outOfStock ? com.quicktap.pos.R.drawable.bg_stock_out_badge : com.quicktap.pos.R.drawable.bg_stock_warning_badge);
        b.textStockAlert.setContentDescription(outOfStock ? "Out of stock" : "Low stock: " + product.stock + " left");

        boolean soldOut = !product.available || product.stock == 0;
        boolean showStockLabel = alertsOn && (outOfStock || lowStock);
        b.textUnavailable.setVisibility(showStockLabel ? View.VISIBLE : View.GONE);
        b.textUnavailable.setText(outOfStock ? "Out of stock" : "Low stock");
        b.textUnavailable.setBackgroundResource(outOfStock ? com.quicktap.pos.R.drawable.bg_stock_out_badge : com.quicktap.pos.R.drawable.bg_stock_warning_badge);
        b.textUnavailable.setTextColor(androidx.core.content.ContextCompat.getColor(b.getRoot().getContext(), com.quicktap.pos.R.color.white));
        b.getRoot().setAlpha(soldOut ? 0.45f : 1f);
        b.getRoot().setEnabled(!soldOut);

        if (product.imageUri != null && !product.imageUri.isEmpty()) {
            if (product.imageUri.startsWith("drawable:")) {
                String name = product.imageUri.substring("drawable:".length());
                int resId = b.getRoot().getResources().getIdentifier(
                        name, "drawable", b.getRoot().getContext().getPackageName());
                if (resId != 0) b.imageProduct.setImageResource(resId);
                else b.imageProduct.setImageDrawable(null);
            } else {
                b.imageProduct.setImageURI(Uri.parse(product.imageUri));
            }
        } else {
            b.imageProduct.setImageDrawable(null);
        }
        b.textInitial.setVisibility(
                product.imageUri == null || product.imageUri.isEmpty() ? View.VISIBLE : View.GONE);
        b.textInitial.setText(product.name.isEmpty()
                ? "?" : product.name.substring(0, 1).toUpperCase());

        b.getRoot().setOnClickListener(v -> { if (!soldOut) listener.onTap(product); });
        b.getRoot().setOnLongClickListener(v -> {
            listener.onLongPress(product);
            return true;
        });
    }

    @Override public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final ItemProductBinding binding;
        VH(ItemProductBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}

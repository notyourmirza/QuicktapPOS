package com.quicktap.pos.ui.billing;

import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.quicktap.pos.databinding.ItemHeldOrderBinding;
import com.quicktap.pos.util.类050;
import com.quicktap.pos.util.类053;

import java.util.ArrayList;
import java.util.List;

/** Parked bills list: tap to resume, trash icon to discard. */
public class 类065 extends RecyclerView.Adapter<类065.VH> {

    public interface Listener {
        void onResume(类050.Held held);
        void onDelete(类050.Held held);
    }

    private final List<类050.Held> orders = new ArrayList<>();
    private final String currency;
    private final Listener listener;

    public 类065(String currency, Listener listener) {
        this.currency = currency;
        this.listener = listener;
    }

    public void submit(List<类050.Held> items) {
        orders.clear();
        if (items != null) orders.addAll(items);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(ItemHeldOrderBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        类050.Held held = orders.get(position);
        holder.binding.textHeldLabel.setText(held.label);
        String when = DateUtils.getRelativeTimeSpanString(held.createdAt,
                System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString();
        int count = held.itemCount();
        holder.binding.textHeldMeta.setText(count + (count == 1 ? " item · " : " items · ") + when);
        holder.binding.textHeldTotal.setText(类053.withCurrency(currency, held.total()));
        holder.binding.getRoot().setOnClickListener(v -> listener.onResume(held));
        holder.binding.buttonHeldDelete.setOnClickListener(v -> listener.onDelete(held));
    }

    @Override
    public int getItemCount() {
        return orders.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemHeldOrderBinding binding;

        VH(ItemHeldOrderBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}

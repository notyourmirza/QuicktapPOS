package com.quicktap.pos.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.quicktap.pos.类001;
import com.quicktap.pos.R;
import com.quicktap.pos.data.entity.Product;
import com.quicktap.pos.databinding.FragmentInventoryBinding;
import com.quicktap.pos.ui.products.类086;
import com.quicktap.pos.ui.products.类087;
import com.quicktap.pos.util.类046;

import java.util.List;

/** Dedicated inventory workspace for stock levels and quick restocking. */
public class 类040 extends Fragment {

    private FragmentInventoryBinding binding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentInventoryBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        类087 adapter = new 类087(
                类046.get(requireContext()).getCurrency(), new 类087.Listener() {
            @Override public void onEdit(Product product) { openEditor(product.id); }
            @Override public void onDelete(Product product) { confirmDelete(product); }
            @Override public void onToggleAvailable(Product product) {
                类001.get().repo().saveProduct(product, null);
            }
            @Override public void onRestock(Product product) { restock(product); }
        });

        binding.recyclerInventory.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recyclerInventory.setAdapter(adapter);
        类001.get().repo().observeAllProducts().observe(getViewLifecycleOwner(), products -> {
            adapter.submit(products);
            binding.textInventoryEmpty.setVisibility(
                    products == null || products.isEmpty() ? View.VISIBLE : View.GONE);
            renderSummary(products);
        });
    }

    private void renderSummary(List<Product> products) {
        int tracked = 0;
        int low = 0;
        int out = 0;
        if (products != null) {
            for (Product product : products) {
                if (product.stock < 0) continue;
                tracked++;
                if (product.stock == 0) out++;
                else if (product.stock <= 5) low++;
            }
        }
        binding.textInventoryTracked.setText(String.valueOf(tracked));
        binding.textInventoryLow.setText(String.valueOf(low));
        binding.textInventoryOut.setText(String.valueOf(out));
    }

    private void restock(Product product) {
        if (product.stock < 0) return;
        EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("Quantity to add");
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Restock " + product.name)
                .setView(input)
                .setPositiveButton("Add stock", (dialog, which) -> {
                    try {
                        int quantity = Integer.parseInt(input.getText().toString().trim());
                        if (quantity <= 0) return;
                        product.stock += quantity;
                        product.available = true;
                        类001.get().repo().saveProduct(product, null);
                    } catch (Exception ignored) { }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDelete(Product product) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Delete " + product.name + "?")
                .setPositiveButton("Delete", (dialog, which) ->
                        类001.get().repo().deleteProduct(product))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openEditor(long productId) {
        Intent intent = new Intent(requireContext(), 类086.class);
        intent.putExtra(类086.EXTRA_PRODUCT_ID, productId);
        startActivity(intent);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}

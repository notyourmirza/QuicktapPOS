package com.quicktap.pos.ui.products;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.quicktap.pos.类001;
import com.quicktap.pos.data.entity.Category;
import com.quicktap.pos.data.entity.Product;
import com.quicktap.pos.databinding.ActivityProductEditBinding;
import com.quicktap.pos.util.类045;

import java.util.ArrayList;
import java.util.List;

/** Add or edit a product, including its photo, price, category and stock. */
public class 类086 extends AppCompatActivity {

    public static final String EXTRA_PRODUCT_ID = "product_id";

    private ActivityProductEditBinding binding;
    private Product product = new Product();
    private final List<Category> categories = new ArrayList<>();
    private ActivityResultLauncher<String> imagePicker;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityProductEditBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        imagePicker = registerForActivityResult(
                new ActivityResultContracts.GetContent(), this::onImagePicked);

        binding.toolbar.setNavigationOnClickListener(v -> finish());
        binding.buttonPickImage.setOnClickListener(v -> imagePicker.launch("image/*"));
        binding.buttonSave.setOnClickListener(v -> save());

        long id = getIntent().getLongExtra(EXTRA_PRODUCT_ID, 0);
        loadCategories(id);
    }

    private void onImagePicked(Uri uri) {
        if (uri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // Some pickers do not offer a persistable grant; the URI still works this session.
        }
        product.imageUri = uri.toString();
        binding.imagePreview.setImageURI(uri);
    }

    private void loadCategories(long productId) {
        类045.io().execute(() -> {
            List<Category> loaded = 类001.get().repo().categoriesBlocking();
            Product loadedProduct = productId == 0
                    ? new Product()
                    : com.quicktap.pos.data.AppDatabase.get(this).productDao().byId(productId);
            类045.main().post(() -> {
                categories.clear();
                categories.addAll(loaded);
                if (loadedProduct != null) product = loadedProduct;
                bindCategorySpinner();
                bindProduct();
            });
        });
    }

    private void bindCategorySpinner() {
        List<String> names = new ArrayList<>();
        for (Category category : categories) names.add(category.name);
        binding.spinnerCategory.setAdapter(new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, names));
        for (int i = 0; i < categories.size(); i++) {
            if (categories.get(i).id == product.categoryId) {
                binding.spinnerCategory.setSelection(i);
                break;
            }
        }
    }

    private void bindProduct() {
        binding.toolbar.setTitle(product.id == 0 ? "Add product" : "Edit product");
        binding.inputName.setText(product.name);
        if (product.price > 0) binding.inputPrice.setText(String.valueOf(product.price));
        if (product.costPrice > 0) binding.inputCostPrice.setText(String.valueOf(product.costPrice));
        binding.inputBarcode.setText(product.barcode == null ? "" : product.barcode);
        binding.inputMinimumStock.setText(String.valueOf(Math.max(0, product.minimumStock)));
        binding.inputStock.setText(product.stock < 0 ? "" : String.valueOf(product.stock));
        binding.switchAvailable.setChecked(product.available);
        binding.switchFavorite.setChecked(product.favorite);
        if (product.imageUri != null && !product.imageUri.isEmpty()) {
            binding.imagePreview.setImageURI(Uri.parse(product.imageUri));
        }
    }

    private void save() {
        String name = binding.inputName.getText().toString().trim();
        if (name.isEmpty()) {
            binding.layoutName.setError("Product name is required");
            return;
        }
        binding.layoutName.setError(null);

        double price;
        try {
            price = Double.parseDouble(binding.inputPrice.getText().toString().trim());
        } catch (Exception e) {
            binding.layoutPrice.setError("Enter a price");
            return;
        }
        binding.layoutPrice.setError(null);

        if (categories.isEmpty()) {
            Toast.makeText(this, "Create a category first", Toast.LENGTH_LONG).show();
            return;
        }

        product.name = name;
        product.price = price;
        String rawCost = binding.inputCostPrice.getText() == null ? "" : binding.inputCostPrice.getText().toString().trim();
        try { product.costPrice = rawCost.isEmpty() ? 0 : Double.parseDouble(rawCost); }
        catch (NumberFormatException e) { binding.inputCostPrice.setError("Enter a valid cost"); return; }
        product.categoryId = categories.get(binding.spinnerCategory.getSelectedItemPosition()).id;
        product.barcode = binding.inputBarcode.getText().toString().trim();
        String minimum = binding.inputMinimumStock.getText() == null ? "" : binding.inputMinimumStock.getText().toString().trim();
        try { product.minimumStock = minimum.isEmpty() ? 0 : Math.max(0, Integer.parseInt(minimum)); }
        catch (NumberFormatException e) { binding.inputMinimumStock.setError("Enter a valid minimum"); return; }
        String stock = binding.inputStock.getText().toString().trim();
        product.stock = stock.isEmpty() ? -1 : Integer.parseInt(stock);
        product.available = binding.switchAvailable.isChecked();
        product.favorite = binding.switchFavorite.isChecked();

        类001.get().repo().saveProduct(product, () -> {
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Shared mechanism: banner + server-authoritative licence gate.
        com.quicktap.pos.ui.license.类075.protect(this);
    }
}

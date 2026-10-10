package com.quicktap.pos.ui.billing;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.widget.PopupMenu;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.quicktap.pos.类001;
import com.quicktap.pos.R;
import com.quicktap.pos.data.entity.Bill;
import com.quicktap.pos.data.entity.BillItem;
import com.quicktap.pos.data.entity.Category;
import com.quicktap.pos.data.entity.Product;
import com.quicktap.pos.data.entity.LedgerCustomer;
import com.quicktap.pos.data.model.类057;
import com.quicktap.pos.databinding.FragmentBillingBinding;
import com.quicktap.pos.databinding.SheetCheckoutBinding;
import com.quicktap.pos.databinding.SheetHeldOrdersBinding;
import com.quicktap.pos.print.类023;
import com.quicktap.pos.util.类045;
import com.quicktap.pos.util.类046;
import com.quicktap.pos.util.类050;
import com.quicktap.pos.util.类053;
import com.quicktap.pos.ui.类042;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pick products from a full-height grid, then checkout inside a bottom sheet. */
public class 类062 extends Fragment {

    private FragmentBillingBinding binding;
    private SheetCheckoutBinding sheetBinding;
    private BottomSheetDialog sheet;
    private 类063 viewModel;
    private 类066 productAdapter;
    private 类064 cartAdapter;
    private 类046 prefs;
    private 类050 heldOrders;

    private final Map<Long, String> categoryNames = new HashMap<>();
    private final List<Product> visibleProducts = new ArrayList<>();
    private String orderType = Bill.TYPE_DINE_IN;
    private boolean paid = true;
    private String paymentMethod = "CASH";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentBillingBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        prefs = 类046.get(requireContext());
        heldOrders = 类050.get(requireContext());
        viewModel = new ViewModelProvider(this).get(类063.class);

        setupProductGrid();
        setupSearch();
        binding.buttonHeaderMenu.setOnClickListener(v -> {
            if (requireActivity() instanceof 类042) {
                ((类042) requireActivity()).openNavigationDrawer();
            }
        });
        refreshInvoiceBadge();
        binding.buttonInvoiceBadge.setOnClickListener(v -> {
            if (requireActivity() instanceof 类042) {
                ((类042) requireActivity()).openInvoiceHistory();
            }
        });
        binding.buttonCheckout.setOnClickListener(v -> openCheckout());
        binding.buttonParked.setOnClickListener(v -> openHeldOrders());
        observe();
        refreshParkedButton();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshParkedButton();
    }

    private void refreshInvoiceBadge() {
        if (prefs == null || binding == null || binding.textInvoiceNumber == null) return;
        // Paint immediately from local state, then ask Room for the accurate
        // count off the UI thread. A synchronous DAO call here was crashing
        // the POS screen with Room's main-thread database guard.
        binding.textInvoiceNumber.setText(String.valueOf(prefs.peekNextInvoiceSeq()));
        类045.io().execute(() -> {
            int next = 类001.get().repo().nextInvoiceSequencePreview();
            类045.main().post(() -> {
                if (isAdded() && binding != null && binding.textInvoiceNumber != null) {
                    binding.textInvoiceNumber.setText(String.valueOf(next));
                }
            });
        });
    }

    private void editInvoiceNumber() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_edit_invoice, null);
        EditText input = dialogView.findViewById(R.id.inputInvoiceNumber);
        input.setText(String.valueOf(prefs.peekNextInvoiceSeq()));
        input.setSelectAllOnFocus(true);
        new MaterialAlertDialogBuilder(requireContext())
                .setView(dialogView)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog, which) -> {
                    int next = parseQty(input.getText() == null ? "" : input.getText().toString());
                    if (next > 0) {
                        prefs.setLastInvoiceSeq(next - 1);
                        refreshInvoiceBadge();
                        Toast.makeText(requireContext(), "Next invoice: " + next, Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void setupProductGrid() {
        int widthDp = getResources().getConfiguration().screenWidthDp;
        int columns = widthDp >= 600 ? 4 : 3;
        productAdapter = new 类066(prefs.getCurrency(), new 类066.Listener() {
            @Override public void onTap(Product product) {
                viewModel.add(product, categoryNames.get(product.categoryId));
            }
            @Override public void onLongPress(Product product) {
                askQuantity(product);
            }
        });
        binding.recyclerProducts.setLayoutManager(new GridLayoutManager(requireContext(), columns));
        binding.recyclerProducts.setAdapter(productAdapter);
    }

    /**
     * Typing filters the grid live. Pressing enter — which is exactly what a USB
     * or Bluetooth barcode scanner sends after the code — adds the matching
     * product straight to the cart and clears the field for the next scan.
     */
    private void setupSearch() {
        binding.inputSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                viewModel.setQuery(s.toString());
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        binding.inputSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                quickAdd(binding.inputSearch.getText() == null
                        ? "" : binding.inputSearch.getText().toString().trim());
                return true;
            }
            return false;
        });
    }

    /** Scanner / enter key: exact barcode wins, otherwise the only visible match. */
    private void quickAdd(String code) {
        if (code.isEmpty()) return;
        Product match = null;
        for (Product product : visibleProducts) {
            if (code.equalsIgnoreCase(product.barcode)) { match = product; break; }
        }
        if (match == null && visibleProducts.size() == 1) match = visibleProducts.get(0);
        if (match == null) {
            Toast.makeText(requireContext(), "No product for \"" + code + "\"",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        viewModel.add(match, categoryNames.get(match.categoryId));
        binding.inputSearch.setText("");
    }

    // ---------------- checkout sheet ----------------

    private void openCheckout() {
        if (sheet == null) buildSheet();
        renderCart(viewModel.cart().getValue());
        sheet.show();
    }

    private void buildSheet() {
        sheetBinding = SheetCheckoutBinding.inflate(getLayoutInflater());
        sheet = new BottomSheetDialog(requireContext());
        sheet.setOnDismissListener(dialog -> {
            // A swipe-down dismisses the dialog; discard the old instance so the
            // next Checkout tap always creates a fresh, expandable sheet.
            sheet = null;
            sheetBinding = null;
        });
        sheet.setContentView(sheetBinding.getRoot());
        sheet.getBehavior().setSkipCollapsed(true);
        sheet.getBehavior().setState(com.google.android.material.bottomsheet
                .BottomSheetBehavior.STATE_EXPANDED);
        sizeSheet();

        cartAdapter = new 类064(prefs.getCurrency(), new 类064.Listener() {
            @Override public void onQtyChanged(int index, int qty) { viewModel.setQty(index, qty); }
            @Override public void onRemove(int index) { viewModel.remove(index); }
        });
        sheetBinding.recyclerCart.setLayoutManager(new LinearLayoutManager(requireContext()));
        sheetBinding.recyclerCart.setNestedScrollingEnabled(false);
        sheetBinding.recyclerCart.setAdapter(cartAdapter);
        attachSwipeToDelete(sheetBinding.recyclerCart);

        sheetBinding.buttonClearCart.setOnClickListener(v -> viewModel.clear());
        sheetBinding.inputCustomerCode.setVisibility(prefs.isCustomerLedgerEnabled() ? View.VISIBLE : View.GONE);
        sheetBinding.buttonNewCustomer.setVisibility(prefs.isCustomerLedgerEnabled() ? View.VISIBLE : View.GONE);
        sheetBinding.buttonNewCustomer.setOnClickListener(v -> showNewLedgerCustomerDialog());
        sheetBinding.chipGroupDiscount.setOnCheckedStateChangeListener((group, ids) -> {
            if (ids.isEmpty()) return;
            boolean percent = ids.get(0) == R.id.chipDiscountPercent;
            viewModel.setDiscountType(percent ? "PERCENT" : "AMOUNT");
            sheetBinding.inputDiscount.setText("");
            sheetBinding.inputDiscount.setHint(percent ? "Discount percentage (0–100)" : "Discount amount");
            renderCart(viewModel.cart().getValue());
        });
        sheetBinding.inputDiscount.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                viewModel.setDiscountInput(parseDecimal(s == null ? "" : s.toString()));
                renderCart(viewModel.cart().getValue());
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        sheetBinding.buttonPrint.setOnClickListener(v -> printAndReset());
        sheetBinding.buttonHold.setOnClickListener(v -> askHoldLabel());
        sheetBinding.chipGroupPayment.setOnCheckedStateChangeListener((group, ids) -> {
            if (ids.isEmpty()) return;
            applyPaymentChoice(ids.get(0));
        });
        sheetBinding.chipGroupPayment.check(R.id.chipPaid);
        applyPaymentChoice(R.id.chipPaid);
        setupOrderTypeChips();
    }

    /** Swipe a cart row away, with an undo window so a mis-swipe costs nothing. */
    private void attachSwipeToDelete(RecyclerView recycler) {
        final Paint paint = new Paint();
        paint.setColor(Color.parseColor("#33F44336"));
        ItemTouchHelper helper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh,
                                  @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder holder, int direction) {
                int index = holder.getBindingAdapterPosition();
                List<类057> before = viewModel.snapshot();
                viewModel.remove(index);
                Snackbar.make(sheetBinding.getRoot(), R.string.item_removed, Snackbar.LENGTH_SHORT)
                        .setAction(R.string.undo, v -> viewModel.restore(before))
                        .show();
            }

            @Override
            public void onChildDraw(@NonNull Canvas canvas, @NonNull RecyclerView rv,
                                    @NonNull RecyclerView.ViewHolder holder, float dX, float dY,
                                    int actionState, boolean isActive) {
                View item = holder.itemView;
                RectF area = new RectF(item.getLeft(), item.getTop(), item.getRight(), item.getBottom());
                canvas.drawRoundRect(area, 18f, 18f, paint);
                super.onChildDraw(canvas, rv, holder, dX, dY, actionState, isActive);
            }
        });
        helper.attachToRecyclerView(recycler);
    }

    /** Lets the sheet remeasure after optional checkout fields are hidden. */
    private void sizeSheet() {
        View container = sheet.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (container == null) return;
        ViewGroup.LayoutParams params = container.getLayoutParams();
        params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        container.setLayoutParams(params);
        sheet.getBehavior().setPeekHeight(0);
    }

    /** Payment status and payment channel are selected independently. */
    private void applyPaymentChoice(int checkedId) {
        paid = checkedId == R.id.chipPaid;
    }

    private void setupOrderTypeChips() {
        ChipGroup group = sheetBinding.chipGroupOrderType;
        group.removeAllViews();
        java.util.List<String> types = prefs.getOrderTypes();
        for (int i = 0; i < types.size(); i++) {
            String label = types.get(i);
            Chip chip = new Chip(requireContext());
            chip.setId(View.generateViewId());
            chip.setText(label);
            chip.setCheckable(true);
            chip.setMinHeight(44);
            chip.setMinWidth(112);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setTextSize(14);
            chip.setOnClickListener(v -> orderType = label);
            group.addView(chip);
            if (i == 0) { chip.setChecked(true); orderType = label; }
        }
    }


    private void observe() {
        viewModel.categories().observe(getViewLifecycleOwner(), this::renderCategories);
        viewModel.products().observe(getViewLifecycleOwner(), products -> {
            visibleProducts.clear();
            if (products != null) visibleProducts.addAll(products);
            productAdapter.submit(products);
        });
        viewModel.cart().observe(getViewLifecycleOwner(), this::renderCart);
    }

    private void renderCart(List<类057> lines) {
        boolean empty = lines == null || lines.isEmpty();
        int count = 0;
        if (lines != null) for (类057 line : lines) count += line.qty;

        binding.cardCheckoutBar.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.textBarCount.setText(count + (count == 1 ? " item" : " items"));
        binding.textBarTotal.setText(类053.withCurrency(prefs.getCurrency(), viewModel.grandTotal()));

        if (sheetBinding == null) return;
        cartAdapter.submit(lines);
        sheetBinding.textSheetCount.setText(count + (count == 1 ? " item" : " items"));
        sheetBinding.textHeaderTotal.setText(类053.withCurrency(prefs.getCurrency(), viewModel.grandTotal()));
        sheetBinding.recyclerCart.setVisibility(empty ? View.GONE : View.VISIBLE);
        sheetBinding.textCartEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        sheetBinding.buttonPrint.setEnabled(!empty);
        sheetBinding.buttonHold.setEnabled(!empty);
        renderTotals();
        if (empty && sheet != null && sheet.isShowing()) sheet.dismiss();
    }

    // ---------------- parked bills ----------------

    private void refreshParkedButton() {
        if (binding == null) return;
        int count = heldOrders.count();
        binding.buttonParked.setVisibility(View.VISIBLE);
        binding.buttonParked.setText(count == 0
                ? getString(R.string.held_short)
                : getString(R.string.held_short) + " · " + count);
    }

    private void askHoldLabel() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_hold_order, null);
        EditText input = dialogView.findViewById(R.id.inputHoldName);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);

        new MaterialAlertDialogBuilder(requireContext())
                .setView(dialogView)
                .setPositiveButton("Hold Order", (d, w) -> {
                    heldOrders.hold(input.getText().toString(), viewModel.snapshot());
                    Toast.makeText(requireContext(), R.string.held_saved, Toast.LENGTH_SHORT).show();
                    resetForNextCustomer();
                    refreshParkedButton();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openHeldOrders() {
        SheetHeldOrdersBinding heldBinding = SheetHeldOrdersBinding.inflate(getLayoutInflater());
        BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
        dialog.setContentView(heldBinding.getRoot());

        类065[] holder = new 类065[1];
        holder[0] = new 类065(prefs.getCurrency(), new 类065.Listener() {
            @Override public void onResume(类050.Held held) {
                viewModel.restore(held.lines);
                heldOrders.remove(held.id);
                refreshParkedButton();
                dialog.dismiss();
                openCheckout();
            }

            @Override public void onDelete(类050.Held held) {
                heldOrders.remove(held.id);
                List<类050.Held> left = heldOrders.all();
                holder[0].submit(left);
                heldBinding.textHeldEmpty.setVisibility(left.isEmpty() ? View.VISIBLE : View.GONE);
                refreshParkedButton();
                if (left.isEmpty()) dialog.dismiss();
            }
        });
        heldBinding.recyclerHeld.setLayoutManager(new LinearLayoutManager(requireContext()));
        heldBinding.recyclerHeld.setAdapter(holder[0]);

        List<类050.Held> orders = heldOrders.all();
        holder[0].submit(orders);
        heldBinding.textHeldEmpty.setVisibility(orders.isEmpty() ? View.VISIBLE : View.GONE);
        heldBinding.buttonClearHeld.setOnClickListener(v -> {
            heldOrders.clearAll();
            refreshParkedButton();
            dialog.dismiss();
        });
        dialog.show();
    }

    private void renderCategories(List<Category> categories) {
        categoryNames.clear();
        binding.chipGroupCategories.removeAllViews();
        addChip("All", 0, true);
        if (categories == null) return;
        for (Category category : categories) {
            categoryNames.put(category.id, category.name);
            addChip(category.name, category.id, false);
        }
    }

    private void addChip(String label, long id, boolean checked) {
        Chip chip = new Chip(requireContext());
        chip.setText(label);
        chip.setCheckable(true);
        chip.setChecked(checked);
        chip.setOnClickListener(v -> {
            for (int i = 0; i < binding.chipGroupCategories.getChildCount(); i++) {
                View child = binding.chipGroupCategories.getChildAt(i);
                if (child instanceof Chip) ((Chip) child).setChecked(child == chip);
            }
            viewModel.setCategory(id);
        });
        binding.chipGroupCategories.addView(chip);
    }

    private void renderTotals() {
        if (sheetBinding == null) return;
        String currency = prefs.getCurrency();
        double sub = viewModel.subtotal();
        double discount = viewModel.discountAmount(sub);
        double tax = viewModel.taxAmount(sub - discount);
        sheetBinding.textSubtotal.setText(类053.withCurrency(currency, sub));
        sheetBinding.textDiscount.setText("-" + 类053.withCurrency(currency, discount));
        sheetBinding.textTax.setText(类053.withCurrency(currency, tax));
        sheetBinding.textGrandTotal.setText(类053.withCurrency(currency, viewModel.grandTotal()));
    }

    /** Long press on a product card: type an exact quantity or pin it as a favourite. */
    private void askQuantity(Product product) {
        EditText input = new EditText(requireContext());
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setHint("Quantity");
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(product.name)
                .setView(input)
                .setPositiveButton("Add", (d, w) -> {
                    int qty = parseQty(input.getText() == null ? "" : input.getText().toString());
                    if (qty > 0) viewModel.addQty(product, categoryNames.get(product.categoryId), qty);
                })
                .setNeutralButton(R.string.mark_favourite, (d, w) -> {
                    product.favorite = !product.favorite;
                    类001.get().repo().saveProduct(product, () -> { });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private int parseQty(String text) {
        try { return Integer.parseInt(text.trim()); } catch (Exception e) { return 0; }
    }
    private double parseDecimal(String text) {
        try { return Math.max(0d, Double.parseDouble(text.trim())); }
        catch (Exception e) { return 0d; }
    }


    /** Save, print, clear, ready for the next customer. No confirmation dialogs. */
    private void printAndReset() {
        sheetBinding.buttonPrint.setEnabled(false);
        viewModel.saveBill(
                orderType,
                "",
                "",
                "",
                "",
                text(sheetBinding.inputOrderNote),
                paid,
                paid ? "CASH" : "CREDIT",
                parseCustomerCode(sheetBinding.inputCustomerCode),
                (bill, items) -> {
                    类023.print(requireContext(), bill, items);
                    Toast.makeText(requireContext(),
                            "Bill " + bill.invoiceNo + " saved", Toast.LENGTH_SHORT).show();
                    resetForNextCustomer();
                });
    }

    private int parseCustomerCode(EditText input) {
        if (input == null || input.getText() == null) return 0;
        try { return Math.max(0, Integer.parseInt(input.getText().toString().trim())); }
        catch (Exception ignored) { return 0; }
    }

    private void showNewLedgerCustomerDialog() {
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(32, 0, 32, 0);
        EditText name = new EditText(requireContext()); name.setHint("Customer name *");
        EditText phone = new EditText(requireContext()); phone.setHint("Phone"); phone.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        EditText notes = new EditText(requireContext()); notes.setHint("Notes");
        box.addView(name); box.addView(phone); box.addView(notes);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("New customer account")
                .setMessage("A numerical customer code will be assigned automatically.")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create", (d, w) -> {
                    String customerName = name.getText() == null ? "" : name.getText().toString().trim();
                    if (customerName.isEmpty()) { Toast.makeText(requireContext(), "Customer name is required", Toast.LENGTH_SHORT).show(); return; }
                    LedgerCustomer customer = new LedgerCustomer();
                    customer.name = customerName;
                    customer.phone = phone.getText() == null ? "" : phone.getText().toString().trim();
                    customer.notes = notes.getText() == null ? "" : notes.getText().toString().trim();
                    类001.get().repo().saveLedgerCustomer(customer, () -> {
                        if (sheetBinding != null) sheetBinding.inputCustomerCode.setText(String.valueOf(customer.customerCode));
                        Toast.makeText(requireContext(), "Customer code " + customer.customerCode + " created", Toast.LENGTH_SHORT).show();
                    });
                }).show();
    }

    private void resetForNextCustomer() {
        viewModel.clear();
        refreshInvoiceBadge();
        if (sheetBinding != null) {
            sheetBinding.inputOrderNote.setText("");
            sheetBinding.inputDiscount.setText("");
            sheetBinding.chipGroupDiscount.check(R.id.chipDiscountAmount);
            sheetBinding.chipGroupPayment.check(R.id.chipPaid);
                sheetBinding.buttonPrint.setEnabled(true);
        }
        binding.inputSearch.setText("");
        if (sheet != null && sheet.isShowing()) sheet.dismiss();
        binding.recyclerProducts.smoothScrollToPosition(0);
    }

    private String text(EditText field) {
        return field.getText() == null ? "" : field.getText().toString().trim();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (sheet != null && sheet.isShowing()) sheet.dismiss();
        sheet = null;
        sheetBinding = null;
        binding = null;
    }
}

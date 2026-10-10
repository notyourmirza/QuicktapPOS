package com.quicktap.pos.ui.commercial;

import android.os.Bundle;
import androidx.appcompat.app.AlertDialog;
import android.app.DatePickerDialog;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfDocument;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import androidx.core.content.FileProvider;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Button;
import android.text.Editable;
import android.text.TextWatcher;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.card.MaterialCardView;
import com.quicktap.pos.类001;
import com.quicktap.pos.R;
import com.quicktap.pos.data.entity.LedgerCustomer;
import com.quicktap.pos.data.entity.LedgerEntry;
import com.quicktap.pos.data.entity.Bill;
import com.quicktap.pos.databinding.FragmentCommercialBinding;
import com.quicktap.pos.util.类045;
import com.quicktap.pos.util.类046;
import com.quicktap.pos.util.类053;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.json.JSONException;

/** Dedicated optional customer account / khata dashboard. */
public class 类067 extends Fragment {
    private FragmentCommercialBinding binding;
    private 类046 prefs;
    private List<LedgerCustomer> allCustomers = new ArrayList<>();

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentCommercialBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        prefs = 类046.get(requireContext());
        if (!prefs.isCustomerLedgerEnabled()) {
            binding.textModuleHint.setText("Customer Ledger is OFF. Enable it from Settings to manage customer khatas.");
            binding.buttonAddCustomer.setEnabled(false);
            binding.buttonRecordPayment.setEnabled(false);
            return;
        }
        binding.textModuleHint.setText("Numerical customer codes start at 1. Select a code at checkout to save an unpaid sale in that account.");
        binding.buttonAddCustomer.setOnClickListener(v -> customerDialog());
        binding.buttonRecordPayment.setOnClickListener(v -> paymentDialog());
        类001.get().repo().observeLedgerCustomers().observe(getViewLifecycleOwner(), customers -> { allCustomers = customers == null ? new ArrayList<>() : customers; filterCustomers(); });
        类001.get().repo().observeUnpaidCustomerOrders().observe(getViewLifecycleOwner(), this::renderUnpaidOrders);
        binding.inputLedgerSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            public void onTextChanged(CharSequence s, int st, int b, int c) { filterCustomers(); }
            public void afterTextChanged(Editable e) { }
        });
        refreshTotals();
    }

    private void renderUnpaidOrders(List<Bill> bills) {
        if (binding == null) return;
        binding.unpaidOrdersList.removeAllViews();
        if (bills == null || bills.isEmpty()) { binding.textUnpaidSummary.setText("All clear"); TextView empty = new TextView(requireContext()); empty.setText("No unpaid customer orders pending."); empty.setTextColor(getResources().getColor(R.color.success)); empty.setTextSize(12f); empty.setPadding(2, 8, 2, 4); binding.unpaidOrdersList.addView(empty); return; }
        double total = 0; for (Bill b : bills) total += b.total; binding.textUnpaidSummary.setText(bills.size() + " orders · " + 类053.withCurrency(prefs.getCurrency(), total));
        for (Bill bill : bills) {
            MaterialCardView card = new MaterialCardView(requireContext()); card.setRadius(14f); card.setCardElevation(0f); card.setStrokeWidth(1); card.setStrokeColor(getResources().getColor(R.color.app_outline_soft)); card.setCardBackgroundColor(getResources().getColor(R.color.order_summary_surface));
            LinearLayout row = new LinearLayout(requireContext()); row.setGravity(android.view.Gravity.CENTER_VERTICAL); row.setPadding(11, 9, 9, 9);
            ImageView icon = new ImageView(requireContext()); icon.setImageResource(R.drawable.ic_bill); icon.setPadding(5, 5, 5, 5); row.addView(icon, new LinearLayout.LayoutParams(38, 42));
            LinearLayout info = new LinearLayout(requireContext()); info.setOrientation(LinearLayout.VERTICAL); info.setPadding(7, 0, 7, 0); TextView title = new TextView(requireContext()); title.setText(bill.invoiceNo + "  ·  Customer #" + bill.customerCode); title.setTextColor(getResources().getColor(R.color.text_primary)); title.setTextSize(13f); title.setTypeface(null, 1); TextView sub = new TextView(requireContext()); sub.setText((bill.customerName == null || bill.customerName.isEmpty() ? "Customer account" : bill.customerName) + "  ·  " + dateText(bill.createdAt)); sub.setTextColor(getResources().getColor(R.color.text_muted)); sub.setTextSize(11f); info.addView(title); info.addView(sub); row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
            TextView amount = new TextView(requireContext()); amount.setText(类053.withCurrency(prefs.getCurrency(), bill.total)); amount.setTextColor(getResources().getColor(R.color.danger)); amount.setTextSize(13f); amount.setTypeface(null, 1); row.addView(amount, new LinearLayout.LayoutParams(-2, -2)); card.addView(row);
            com.google.android.material.button.MaterialButton paid = new com.google.android.material.button.MaterialButton(requireContext()); paid.setText("Mark as Paid"); paid.setTextSize(11f); paid.setMinHeight(40); paid.setPadding(8, 0, 8, 0); paid.setCornerRadius(11); paid.setIconResource(R.drawable.ic_receive_money); paid.setOnClickListener(v -> confirmMarkPaid(bill)); LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, 40); pp.setMargins(11, 0, 11, 10); card.addView(paid, pp); LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2); cp.setMargins(0, 0, 0, 7); binding.unpaidOrdersList.addView(card, cp);
        }
    }

    private void confirmMarkPaid(Bill bill) {
        new MaterialAlertDialogBuilder(requireContext()).setTitle("Mark order as paid?").setMessage(bill.invoiceNo + " · " + 类053.withCurrency(prefs.getCurrency(), bill.total) + "\nPayment will be recorded as cash and the customer balance will be updated.").setNegativeButton("Cancel", null).setPositiveButton("Mark as Paid", (d, w) -> 类001.get().repo().markCustomerOrderPaid(bill, () -> { toast("Order marked as paid"); refreshTotals(); })).show();
    }

    private void filterCustomers() {
        String q = binding == null || binding.inputLedgerSearch.getText() == null ? "" : binding.inputLedgerSearch.getText().toString().trim().toLowerCase();
        List<LedgerCustomer> filtered = new ArrayList<>();
        for (LedgerCustomer c : allCustomers) if (q.isEmpty() || String.valueOf(c.customerCode).contains(q) || c.name.toLowerCase().contains(q) || c.phone.toLowerCase().contains(q)) filtered.add(c);
        renderCustomers(filtered);
    }

    private void renderCustomers(List<LedgerCustomer> customers) {
        if (binding == null) return;
        binding.textCustomerCount.setText(String.valueOf(customers == null ? 0 : customers.size()));
        binding.customerList.removeAllViews();
        if (customers == null || customers.isEmpty()) {
            TextView empty = new TextView(requireContext());
            empty.setText("No customer accounts yet. Create the first account to begin.");
            empty.setTextColor(getResources().getColor(R.color.text_muted));
            empty.setPadding(4, 18, 4, 18);
            binding.customerList.addView(empty);
            return;
        }
        for (LedgerCustomer customer : customers) addCustomerCard(customer);
    }

    private void addCustomerCard(LedgerCustomer customer) {
        MaterialCardView card = new MaterialCardView(requireContext());
        card.setRadius(16f);
        card.setCardElevation(1f);
        card.setStrokeWidth(1);
        card.setStrokeColor(getResources().getColor(R.color.app_outline_soft));
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(16, 13, 16, 13);
        TextView title = new TextView(requireContext());
        title.setText("#" + customer.customerCode + "  " + customer.name);
        title.setTextSize(16f); title.setTextColor(getResources().getColor(R.color.text_primary)); title.setTypeface(null, 1);
        TextView detail = new TextView(requireContext());
        detail.setText(customer.phone == null || customer.phone.isEmpty() ? "No phone saved" : customer.phone);
        detail.setTextSize(12f); detail.setTextColor(getResources().getColor(R.color.text_muted));
        TextView balance = new TextView(requireContext());
        balance.setText("Loading balance…"); balance.setTextSize(14f); balance.setPadding(0, 7, 0, 0);
        row.addView(title); row.addView(detail); row.addView(balance); card.addView(row);
        card.setOnClickListener(v -> statementDialog(customer));
        binding.customerList.addView(card, new LinearLayout.LayoutParams(-1, -2));
        类045.io().execute(() -> {
            double value = 类001.get().repo().ledgerBalanceBlocking(customer.id);
            类045.main().post(() -> {
                if (!isAdded()) return;
                balance.setText("Outstanding: " + 类053.withCurrency(prefs.getCurrency(), value));
                balance.setTextColor(getResources().getColor(value > 0 ? R.color.danger : R.color.success));
            });
        });
    }

    private void customerDialog() {
        LinearLayout box = formBox();
        EditText name = input("Customer name *");
        EditText phone = input("Phone"); phone.setInputType(InputType.TYPE_CLASS_PHONE);
        EditText address = input("Address");
        EditText notes = input("Notes");
        LinearLayout customFields = new LinearLayout(requireContext()); customFields.setOrientation(LinearLayout.VERTICAL);
        TextView customTitle = new TextView(requireContext()); customTitle.setText("Additional fields"); customTitle.setTextSize(13f); customTitle.setPadding(0, 12, 0, 3);
        Button addField = new Button(requireContext()); addField.setText("+ Add field");
        box.addView(name); box.addView(phone); box.addView(address); box.addView(notes); box.addView(customTitle); box.addView(customFields); box.addView(addField);
        addField.setOnClickListener(v -> { LinearLayout line = new LinearLayout(requireContext()); line.setOrientation(LinearLayout.HORIZONTAL); EditText key = input("Field name"); EditText val = input("Value"); line.addView(key, new LinearLayout.LayoutParams(0, -2, 1)); line.addView(val, new LinearLayout.LayoutParams(0, -2, 1)); customFields.addView(line); });
        new MaterialAlertDialogBuilder(requireContext()).setTitle("New customer account")
                .setMessage("The next numerical customer code will be assigned automatically.")
                .setView(box).setNegativeButton("Cancel", null)
                .setPositiveButton("Create", (d, w) -> {
                    if (value(name).isEmpty()) { toast("Customer name is required"); return; }
                    LedgerCustomer row = new LedgerCustomer(); row.name = value(name); row.phone = value(phone); row.address = value(address); row.notes = value(notes);
                    JSONObject custom = new JSONObject();
                    for (int i = 0; i < customFields.getChildCount(); i++) { View child = customFields.getChildAt(i); if (child instanceof LinearLayout) { LinearLayout line = (LinearLayout) child; if (line.getChildCount() >= 2) { String k = value((EditText) line.getChildAt(0)); if (!k.isEmpty()) try { custom.put(k, value((EditText) line.getChildAt(1))); } catch (JSONException ignored) { } } } }
                    row.customFields = custom.toString();
                    类001.get().repo().saveLedgerCustomer(row, () -> { toast("Customer code " + row.customerCode + " created"); refreshTotals(); });
                }).show();
    }

    private void statementDialog(LedgerCustomer customer) {
        Calendar from = Calendar.getInstance(); from.set(Calendar.DAY_OF_MONTH, 1); startOfDay(from);
        Calendar to = Calendar.getInstance(); to.setTimeInMillis(endOfDay(to.getTimeInMillis()));
        showCustomerDashboard(customer, from.getTimeInMillis(), to.getTimeInMillis());
    }

    private void showCustomerDashboard(LedgerCustomer customer, long from, long to) {
        类045.io().execute(() -> {
            List<LedgerEntry> entries = 类001.get().repo().ledgerEntriesBlocking(customer.id, from, to);
            double balance = 类001.get().repo().ledgerBalanceBlocking(customer.id);
            类045.main().post(() -> {
                if (!isAdded()) return;
                View root = getLayoutInflater().inflate(R.layout.dialog_customer_dashboard, null);
                TextView customerText = root.findViewById(R.id.textDashboardCustomer); customerText.setText("#" + customer.customerCode + "  " + customer.name + (customer.phone == null || customer.phone.isEmpty() ? "" : "  " + customer.phone));
                ((TextView) root.findViewById(R.id.textDashboardOutstanding)).setText(类053.withCurrency(prefs.getCurrency(), balance));
                ((android.widget.Button) root.findViewById(R.id.buttonDashboardFrom)).setText(dateText(from));
                ((android.widget.Button) root.findViewById(R.id.buttonDashboardTo)).setText(dateText(to));
                double sales = 0, received = 0, deducted = 0;
                LinearLayout rows = root.findViewById(R.id.dashboardTransactions);
                for (LedgerEntry e : entries) {
                    if (LedgerEntry.SALE.equals(e.type)) sales += e.amount; else if (LedgerEntry.PAYMENT.equals(e.type)) received += e.amount; else deducted += e.amount;
                    boolean isReceived = LedgerEntry.PAYMENT.equals(e.type); boolean isDeducted = LedgerEntry.DEDUCTION.equals(e.type);
                    MaterialCardView card = new MaterialCardView(requireContext()); card.setRadius(14f); card.setCardElevation(0f); card.setStrokeWidth(1); card.setStrokeColor(getResources().getColor(isReceived ? R.color.success : isDeducted ? R.color.danger : R.color.brand_primary));
                    card.setCardBackgroundColor(getResources().getColor(isReceived ? R.color.order_summary_surface : isDeducted ? R.color.order_summary_background : R.color.order_summary_surface));
                    LinearLayout line = new LinearLayout(requireContext()); line.setGravity(android.view.Gravity.CENTER_VERTICAL); line.setPadding(12, 10, 10, 10);
                    ImageView icon = new ImageView(requireContext()); icon.setImageResource(isReceived ? R.drawable.ic_receive_money : isDeducted ? R.drawable.ic_deduct_money : R.drawable.ic_ledger_transactions); icon.setPadding(7, 7, 7, 7); line.addView(icon, new LinearLayout.LayoutParams(40, 48));
                    LinearLayout info = new LinearLayout(requireContext()); info.setOrientation(LinearLayout.VERTICAL); info.setPadding(8, 0, 6, 0); TextView top = new TextView(requireContext()); top.setText((isReceived ? "RECEIVED" : isDeducted ? "DEDUCTED" : "CREDIT SALE") + "   " + 类053.withCurrency(prefs.getCurrency(), e.amount)); top.setTextSize(14f); top.setTypeface(null, 1); top.setTextColor(getResources().getColor(R.color.text_primary)); TextView note = new TextView(requireContext()); note.setText((e.note == null || e.note.trim().isEmpty()) ? "No note" : e.note); note.setTextSize(12f); note.setTextColor(getResources().getColor(R.color.text_muted)); info.addView(top); info.addView(note); line.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
                    TextView date = new TextView(requireContext()); date.setText(new SimpleDateFormat("dd MMM\nyyyy", Locale.getDefault()).format(e.createdAt)); date.setGravity(android.view.Gravity.CENTER); date.setTextSize(11f); date.setTextColor(getResources().getColor(R.color.text_primary)); line.addView(date, new LinearLayout.LayoutParams(62, 48)); card.addView(line); LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2); cp.setMargins(0, 0, 0, 8); rows.addView(card, cp);
                }
                ((TextView) root.findViewById(R.id.textStatCredit)).setText("Period Credit\n" + 类053.withCurrency(prefs.getCurrency(), sales));
                ((TextView) root.findViewById(R.id.textStatReceived)).setText("Received\n" + 类053.withCurrency(prefs.getCurrency(), received));
                ((TextView) root.findViewById(R.id.textStatDeducted)).setText("Deducted\n" + 类053.withCurrency(prefs.getCurrency(), deducted));
                ((TextView) root.findViewById(R.id.textStatTransactions)).setText("Transactions\n" + entries.size());
                AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext()).setView(root).create();
                root.findViewById(R.id.buttonDashboardClose).setOnClickListener(v -> dialog.dismiss());
                root.findViewById(R.id.buttonDashboardCloseBottom).setOnClickListener(v -> dialog.dismiss());
                root.findViewById(R.id.buttonDashboardReceive).setOnClickListener(v -> transactionDialog(customer, LedgerEntry.PAYMENT));
                root.findViewById(R.id.buttonDashboardDeduct).setOnClickListener(v -> transactionDialog(customer, LedgerEntry.DEDUCTION));
                root.findViewById(R.id.buttonDashboardExport).setOnClickListener(v -> exportStatementPdf(customer, entries, balance, from, to));
                root.findViewById(R.id.buttonDashboardFrom).setOnClickListener(v -> pickDate(from, millis -> { dialog.dismiss(); showCustomerDashboard(customer, millis, to); }));
                root.findViewById(R.id.buttonDashboardTo).setOnClickListener(v -> pickDate(to, millis -> { dialog.dismiss(); showCustomerDashboard(customer, from, endOfDay(millis)); }));
                dialog.setOnShowListener(x -> { if (dialog.getWindow() != null) { dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent); dialog.getWindow().setLayout((int)(getResources().getDisplayMetrics().widthPixels * .94f), (int)(getResources().getDisplayMetrics().heightPixels * .90f)); } });
                dialog.show();
            });
        });
    }

    private interface DateChosen { void onDate(long millis); }
    private void pickDate(long value, DateChosen chosen) { Calendar c = Calendar.getInstance(); c.setTimeInMillis(value); new DatePickerDialog(requireContext(), (v,y,m,d) -> { Calendar selected = Calendar.getInstance(); selected.set(y,m,d); chosen.onDate(selected.getTimeInMillis()); }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show(); }
    private String dateText(long value) { return new SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(value); }
    private void startOfDay(Calendar c) { c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0); }
    private long endOfDay(long value) { Calendar c = Calendar.getInstance(); c.setTimeInMillis(value); c.set(Calendar.HOUR_OF_DAY, 23); c.set(Calendar.MINUTE, 59); c.set(Calendar.SECOND, 59); c.set(Calendar.MILLISECOND, 999); return c.getTimeInMillis(); }

    private void exportStatementPdf(LedgerCustomer customer, List<LedgerEntry> entries, double balance, long from, long to) {
        try {
            PdfDocument doc = new PdfDocument();
            final int W = 612, H = 792, left = 36, right = 576;
            int pageNo = 0, y = 0; PdfDocument.Page page = null; Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            java.text.SimpleDateFormat df = new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault());
            double credit = 0, received = 0, deducted = 0; for (LedgerEntry e : entries) { if (LedgerEntry.SALE.equals(e.type)) credit += e.amount; else if (LedgerEntry.PAYMENT.equals(e.type)) received += e.amount; else deducted += e.amount; }
            for (int i = 0; i <= entries.size(); i++) {
                if (page == null || y > 690) { if (page != null) doc.finishPage(page); page = doc.startPage(new PdfDocument.PageInfo.Builder(W, H, ++pageNo).create()); android.graphics.Canvas c = page.getCanvas(); p.setColor(0xffff5a00); c.drawRect(0, 0, W, 92, p); p.setColor(0xffffffff); p.setTextSize(23); p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); c.drawText(prefs.getStoreName(), left, 38, p); p.setTextSize(11); p.setTypeface(android.graphics.Typeface.DEFAULT); c.drawText("CUSTOMER LEDGER INVOICE", left, 66, p); p.setColor(0xff172033); p.setTextSize(10); c.drawText("Invoice: " + prefs.getInvoicePrefix() + String.format(Locale.getDefault(), "%06d", customer.customerCode), 390, 28, p); c.drawText("Issued: " + df.format(System.currentTimeMillis()), 390, 48, p); if (!prefs.getStorePhone().isEmpty()) c.drawText(prefs.getStorePhone(), 390, 68, p); p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); p.setTextSize(14); c.drawText("Customer account", left, 122, p); p.setTypeface(android.graphics.Typeface.DEFAULT); p.setTextSize(12); c.drawText("Code #" + customer.customerCode + "  " + customer.name, left, 144, p); if (customer.phone != null && !customer.phone.isEmpty()) c.drawText(customer.phone, left, 162, p); if (!prefs.getStoreAddress().isEmpty()) c.drawText(prefs.getStoreAddress(), 330, 144, p); p.setColor(0xfffff1eb); c.drawRoundRect(left, 180, right, 236, 12, 12, p); p.setColor(0xff172033); p.setTextSize(11); p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); c.drawText("STATEMENT PERIOD", left + 14, 202, p); p.setTypeface(android.graphics.Typeface.DEFAULT); c.drawText(dateText(from) + "  —  " + dateText(to), left + 14, 222, p); p.setColor(0xff172033); p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); p.setTextSize(11); c.drawText("TYPE", left, 270, p); c.drawText("DATE / NOTE", 175, 270, p); c.drawText("AMOUNT", 488, 270, p); p.setColor(0xffff5a00); c.drawRect(left, 278, right, 280, p); y = 304; }
                if (i < entries.size()) { LedgerEntry e = entries.get(i); android.graphics.Canvas c = page.getCanvas(); boolean pay = LedgerEntry.PAYMENT.equals(e.type), ded = LedgerEntry.DEDUCTION.equals(e.type); p.setColor(pay ? 0xff15803d : ded ? 0xffc2410c : 0xff172033); p.setTextSize(11); p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); c.drawText(pay ? "RECEIVED" : ded ? "DEDUCTED" : "CREDIT SALE", left, y, p); p.setTypeface(android.graphics.Typeface.DEFAULT); p.setColor(0xff475569); c.drawText(df.format(e.createdAt), 175, y, p); c.drawText((e.note == null || e.note.isEmpty()) ? "Account transaction" : e.note, 175, y + 15, p); p.setColor(0xff172033); p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); c.drawText(类053.withCurrency(prefs.getCurrency(), e.amount), 488, y, p); y += 40; }
            }
            if (page != null) { android.graphics.Canvas c = page.getCanvas(); if (y > 690) { doc.finishPage(page); page = doc.startPage(new PdfDocument.PageInfo.Builder(W, H, ++pageNo).create()); c = page.getCanvas(); y = 60; } p.setColor(0xffe2e8f0); c.drawRect(left, y, right, y + 1, p); p.setColor(0xff172033); p.setTextSize(12); p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); c.drawText("Credit sales", 350, y + 24, p); c.drawText("Received", 350, y + 44, p); c.drawText("Deducted", 350, y + 64, p); c.drawText("Closing balance", 350, y + 84, p); p.setTypeface(android.graphics.Typeface.DEFAULT); c.drawText(类053.withCurrency(prefs.getCurrency(), credit), 488, y + 24, p); c.drawText(类053.withCurrency(prefs.getCurrency(), received), 488, y + 44, p); c.drawText(类053.withCurrency(prefs.getCurrency(), deducted), 488, y + 64, p); p.setColor(balance > 0 ? 0xffc62828 : 0xff15803d); p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); c.drawText(类053.withCurrency(prefs.getCurrency(), balance), 488, y + 84, p); p.setColor(0xff64748b); p.setTypeface(android.graphics.Typeface.DEFAULT); p.setTextSize(10); c.drawText(prefs.getReceiptFooter(), left, H - 28, p); doc.finishPage(page); }
            File dir = new File(requireContext().getCacheDir(), "shared"); if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Export cache unavailable"); File file = new File(dir, "customer_invoice_" + customer.customerCode + ".pdf"); FileOutputStream out = new FileOutputStream(file); doc.writeTo(out); out.close(); doc.close(); Uri uri = FileProvider.getUriForFile(requireContext(), requireContext().getPackageName() + ".fileprovider", file); Intent send = new Intent(Intent.ACTION_SEND); send.setType("application/pdf"); send.putExtra(Intent.EXTRA_STREAM, uri); send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); send.setClipData(android.content.ClipData.newRawUri("Customer invoice", uri)); startActivity(Intent.createChooser(send, "Share customer invoice"));
        } catch (Exception e) { toast("Could not export statement"); }
    }

    private void paymentDialog() { paymentDialog(null); }
    private void paymentDialog(@Nullable LedgerCustomer preset) { transactionDialog(preset, LedgerEntry.PAYMENT); }

    private void transactionDialog(@Nullable LedgerCustomer preset, String type) {
        boolean deduction = LedgerEntry.DEDUCTION.equals(type);
        LinearLayout box = formBox();
        TextView heading = new TextView(requireContext()); heading.setText(deduction ? "Reduce this account balance with a deduction or adjustment." : "Record money received from this customer."); heading.setTextSize(13f); heading.setTextColor(getResources().getColor(R.color.text_muted)); heading.setPadding(0, 0, 0, 14); box.addView(heading);
        if (preset == null) { toast("Open a customer account first"); return; }
        TextView account = new TextView(requireContext()); account.setText("Customer #" + preset.customerCode + "  " + preset.name); account.setTextSize(16f); account.setTypeface(null, 1); account.setTextColor(getResources().getColor(R.color.text_primary)); account.setPadding(0, 0, 0, 12); box.addView(account);
        EditText amount = input(deduction ? "Deduction amount *" : "Received amount *"); amount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText note = input(deduction ? "Reason / note" : "Payment note");
        box.addView(amount); box.addView(note);
        String title = deduction ? "Deduct from customer account" : "Receive payment";
        String action = deduction ? "Save Deduction" : "Save Payment";
        new MaterialAlertDialogBuilder(requireContext()).setTitle(title).setView(box).setNegativeButton("Cancel", null).setPositiveButton(action, (d, w) -> {
            double amountValue = parse(value(amount));
            if (amountValue <= 0) { toast("Enter a valid amount"); return; }
            LedgerCustomer customer = preset;
            Runnable done = () -> { toast(deduction ? "Deduction saved" : "Payment saved"); refreshTotals(); showCustomerDashboard(customer, monthStart(), System.currentTimeMillis()); };
            if (deduction) 类001.get().repo().addLedgerDeduction(customer.id, amountValue, value(note), done); else 类001.get().repo().addLedgerPayment(customer.id, amountValue, value(note), done);
        }).show();
    }

    private long monthStart() { Calendar c = Calendar.getInstance(); c.set(Calendar.DAY_OF_MONTH, 1); startOfDay(c); return c.getTimeInMillis(); }

    private void refreshTotals() {
        if (binding == null || !isAdded()) return;
        类045.io().execute(() -> {
            double sales = 类001.get().repo().ledgerTotalCreditSalesBlocking();
            double payments = 类001.get().repo().ledgerTotalPaymentsBlocking();
            类045.main().post(() -> {
                if (binding == null || !isAdded()) return;
                binding.textCreditSales.setText("Credit sales: " + 类053.withCurrency(prefs.getCurrency(), sales));
                binding.textPayments.setText("Payments: " + 类053.withCurrency(prefs.getCurrency(), payments));
                binding.textOutstanding.setText(类053.withCurrency(prefs.getCurrency(), Math.max(0, sales - payments)));
            });
        });
    }

    private LinearLayout formBox() { LinearLayout box = new LinearLayout(requireContext()); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(28, 0, 28, 0); return box; }
    private EditText input(String hint) { EditText e = new EditText(requireContext()); e.setHint(hint); e.setSingleLine(true); return e; }
    private String value(EditText e) { return e.getText() == null ? "" : e.getText().toString().trim(); }
    private int parseInt(String s) { try { return Integer.parseInt(s); } catch (Exception e) { return 0; } }
    private double parse(String s) { try { return Double.parseDouble(s); } catch (Exception e) { return 0; } }
    private void toast(String message) { if (isAdded()) Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show(); }
    @Override public void onDestroyView() { super.onDestroyView(); binding = null; }
}

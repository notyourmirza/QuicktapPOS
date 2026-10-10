package com.quicktap.pos.ui.settings;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.graphics.BitmapFactory;
import android.view.View;
import android.widget.Toast;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.quicktap.pos.databinding.ActivityPrinterSettingsBinding;
import com.quicktap.pos.databinding.ItemPrinterBinding;
import com.quicktap.pos.print.类020;
import com.quicktap.pos.print.类023;
import com.quicktap.pos.print.类024;
import com.quicktap.pos.print.类025;
import com.quicktap.pos.print.类031;
import com.quicktap.pos.print.类028;
import com.quicktap.pos.data.entity.Bill;
import com.quicktap.pos.data.entity.BillItem;
import com.quicktap.pos.util.类046;
import com.quicktap.pos.print.类030;
import com.quicktap.pos.print.类030;

import java.util.List;

/**
 * Lets the cashier pick a Bluetooth, USB or Wi-Fi/LAN thermal printer, choose
 * the paper width, and run a test print. Whichever device is tapped becomes
 * the active transport for every future receipt.
 */
public class 类091 extends AppCompatActivity {

    private ActivityPrinterSettingsBinding binding;
    private 类046 prefs;
    private 类024 printerPrefs;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityPrinterSettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        prefs = 类046.get(this);
        printerPrefs = 类024.get(this);

        binding.toolbar.setNavigationOnClickListener(v -> finish());
        binding.radio58.setChecked(prefs.getPaperChars() == 32);
        binding.radio80.setChecked(prefs.getPaperChars() == 48);
        binding.radio58.setOnClickListener(v -> { prefs.setPaperChars(32); refreshReceiptPreview(); });
        binding.radio80.setOnClickListener(v -> { prefs.setPaperChars(48); refreshReceiptPreview(); });
        binding.buttonTestPrint.setOnClickListener(v -> 类023.testPrint(this));
        binding.buttonRefreshPreview.setOnClickListener(v -> refreshReceiptPreview());
        binding.inputReceiptTemplateCode.setText(prefs.getReceiptTemplateCode());
        binding.inputReceiptTemplateCode.setHint("Example: {STORE_NAME}\nInvoice: {INVOICE}\n{ITEMS}\nTotal: {TOTAL}");
        binding.buttonSaveReceiptTemplateCode.setOnClickListener(v -> {
            String code = binding.inputReceiptTemplateCode.getText() == null ? "" : binding.inputReceiptTemplateCode.getText().toString();
            prefs.setReceiptTemplateCode(code.trim());
            refreshReceiptPreview();
            Toast.makeText(this, code.trim().isEmpty() ? "System receipt design restored" : "Receipt code saved", Toast.LENGTH_SHORT).show();
        });
        binding.switchReceiptPreview.setChecked(prefs.isReceiptPreviewEnabled());
        binding.switchReceiptPreview.setOnCheckedChangeListener((button, checked) -> {
            prefs.setReceiptPreviewEnabled(checked);
            binding.receiptPreviewCard.setVisibility(checked ? View.VISIBLE : View.GONE);
        });
        binding.receiptPreviewCard.setVisibility(prefs.isReceiptPreviewEnabled() ? View.VISIBLE : View.GONE);
        setupReceiptDesignDropdown();

        binding.buttonRefreshBluetooth.setOnClickListener(v -> loadBluetoothDevices());
        binding.buttonRefreshUsb.setOnClickListener(v -> loadUsbDevices());
        binding.buttonSaveNetwork.setOnClickListener(v -> saveNetworkPrinter());

        refreshAll();
    }

    @Override
    protected void onResume() {
        super.onResume();
        com.quicktap.pos.ui.license.类075.protect(this);
        refreshAll();
    }

    private void refreshAll() {
        updateSummary();
        refreshReceiptPreview();
        loadBluetoothDevices();
        loadUsbDevices();
        prefillNetwork();
    }

    private void setupReceiptDesignDropdown() {
        类030.Template[] templates = 类030.all();
        String[] names = new String[templates.length];
        int selected = 0;
        for (int i = 0; i < templates.length; i++) {
            names[i] = templates[i].name;
            if (templates[i].key.equalsIgnoreCase(prefs.getReceiptTemplate())) selected = i;
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.spinnerReceiptDesign.setAdapter(adapter);
        binding.spinnerReceiptDesign.setSelection(selected, false);
        binding.spinnerReceiptDesign.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                prefs.setReceiptTemplate(templates[position].key);
                refreshReceiptPreview();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private void refreshReceiptPreview() {
        Bill sample = new Bill();
        sample.invoiceNo = "INV-000128";
        sample.createdAt = System.currentTimeMillis();
        sample.orderType = Bill.TYPE_DINE_IN;
        sample.tableNo = "04";
        sample.customerName = "Walk-in Customer";
        sample.paid = true;
        sample.paymentMethod = "CASH";
        BillItem one = new BillItem(); one.name = "Classic Burger"; one.qty = 2; one.price = 250;
        BillItem two = new BillItem(); two.name = "Cold Drink"; two.qty = 1; two.price = 120;
        sample.subtotal = one.lineTotal() + two.lineTotal();
        sample.discountAmount = 50; sample.discount = 50; sample.tax = 0; sample.total = sample.subtotal - sample.discountAmount;
        java.util.List<BillItem> items = java.util.Arrays.asList(one, two);
        binding.previewReceipt.setText(类028.render(prefs, sample, items));
        String path = prefs.getReceiptLogoPath();
        if (prefs.isReceiptLogoEnabled() && path != null && !path.isEmpty() && new java.io.File(path).exists()) {
            binding.previewLogo.setImageBitmap(BitmapFactory.decodeFile(path));
            binding.previewLogo.setVisibility(View.VISIBLE);
        } else {
            binding.previewLogo.setImageDrawable(null);
            binding.previewLogo.setVisibility(View.GONE);
        }
    }

    private void updateSummary() {
        binding.textSelectedSummary.setText(printerPrefs.describeSelected());
    }

    private void prefillNetwork() {
        String host = printerPrefs.getNetworkHost();
        if (!host.isEmpty() && binding.inputNetHost.getText() != null
                && binding.inputNetHost.getText().toString().isEmpty()) {
            binding.inputNetHost.setText(host);
        }
        if (binding.inputNetPort.getText() != null
                && binding.inputNetPort.getText().toString().trim().isEmpty()) {
            binding.inputNetPort.setText(String.valueOf(printerPrefs.getNetworkPort()));
        }
    }

    // ---------------- Bluetooth ----------------

    @SuppressLint("MissingPermission")
    private void loadBluetoothDevices() {
        binding.containerBluetoothDevices.removeAllViews();
        类020 printer = 类020.get(this);

        if (!printer.hasPermission()) {
            binding.textBluetoothHint.setText("Bluetooth permission is required to list printers.");
            return;
        }

        List<BluetoothDevice> devices = printer.pairedDevices();
        if (devices.isEmpty()) {
            binding.textBluetoothHint.setText(
                    "No paired devices. Pair your thermal printer in Android Bluetooth settings first.");
            return;
        }
        binding.textBluetoothHint.setText("Select your paired Bluetooth printer:");

        boolean isSelected = printerPrefs.getTransport() == 类025.BLUETOOTH;
        String selectedMac = printerPrefs.getPrinterMac();
        for (BluetoothDevice device : devices) {
            ItemPrinterBinding row = ItemPrinterBinding.inflate(
                    getLayoutInflater(), binding.containerBluetoothDevices, false);
            row.textName.setText(device.getName() == null ? "Unknown device" : device.getName());
            row.textMac.setText(device.getAddress());
            row.radioSelected.setChecked(isSelected && device.getAddress().equals(selectedMac));
            View.OnClickListener select = v -> {
                printerPrefs.setBluetoothPrinter(device.getAddress(), device.getName());
                printerPrefs.setTransport(类025.BLUETOOTH);
                类020.get(this).closeQuietly();
                Toast.makeText(this, "Bluetooth printer saved", Toast.LENGTH_SHORT).show();
                refreshAll();
            };
            row.getRoot().setOnClickListener(select);
            row.radioSelected.setOnClickListener(select);
            binding.containerBluetoothDevices.addView(row.getRoot());
        }
    }

    // ---------------- USB ----------------

    private void loadUsbDevices() {
        binding.containerUsbDevices.removeAllViews();
        类031 usbPrinter = 类031.get(this);
        List<UsbDevice> devices = usbPrinter.printerDevices();

        if (devices.isEmpty()) {
            binding.textUsbHint.setText(
                    "No USB printer detected. Plug it in via an OTG cable and tap Scan.");
            return;
        }
        binding.textUsbHint.setText("Select your connected USB printer:");

        boolean isSelected = printerPrefs.getTransport() == 类025.USB;
        String selectedName = printerPrefs.getUsbDeviceName();
        for (UsbDevice device : devices) {
            ItemPrinterBinding row = ItemPrinterBinding.inflate(
                    getLayoutInflater(), binding.containerUsbDevices, false);
            String label = device.getProductName() == null
                    ? "USB printer" : device.getProductName();
            row.textName.setText(label);
            row.textMac.setText(device.getDeviceName());
            row.radioSelected.setChecked(isSelected && device.getDeviceName().equals(selectedName));
            View.OnClickListener select = v -> usbPrinter.requestPermission(device,
                    new 类031.PermissionCallback() {
                        @Override public void onGranted(UsbDevice granted) {
                            printerPrefs.setUsbDevice(granted.getDeviceName());
                            printerPrefs.setTransport(类025.USB);
                            Toast.makeText(类091.this,
                                    "USB printer saved", Toast.LENGTH_SHORT).show();
                            refreshAll();
                        }
                        @Override public void onDenied() {
                            Toast.makeText(类091.this,
                                    "USB permission denied", Toast.LENGTH_SHORT).show();
                        }
                    });
            row.getRoot().setOnClickListener(select);
            row.radioSelected.setOnClickListener(select);
            binding.containerUsbDevices.addView(row.getRoot());
        }
    }

    // ---------------- Network ----------------

    private void saveNetworkPrinter() {
        String host = binding.inputNetHost.getText() == null
                ? "" : binding.inputNetHost.getText().toString().trim();
        String portText = binding.inputNetPort.getText() == null
                ? "" : binding.inputNetPort.getText().toString().trim();
        if (host.isEmpty()) {
            Toast.makeText(this, "Enter the printer's IP address", Toast.LENGTH_SHORT).show();
            return;
        }
        int port = 9100;
        if (!portText.isEmpty()) {
            try {
                port = Integer.parseInt(portText);
            } catch (NumberFormatException e) {
                Toast.makeText(this, "Invalid port", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        printerPrefs.setNetworkPrinter(host, port);
        printerPrefs.setTransport(类025.NETWORK);
        Toast.makeText(this, "Network printer saved", Toast.LENGTH_SHORT).show();
        updateSummary();
    }
}

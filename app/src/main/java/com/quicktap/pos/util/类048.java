package com.quicktap.pos.util;

import android.content.Context;
import android.net.Uri;

import com.quicktap.pos.data.model.类058;
import com.quicktap.pos.data.model.类061;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Writes a report as CSV, which Excel and Google Sheets open natively.
 */
public final class 类048 {

    private 类048() { }

    public static void writeReport(Context context, Uri target, String title,
                                   List<类061> products,
                                   List<类058> categories) throws IOException {
        OutputStream stream = context.getContentResolver().openOutputStream(target);
        if (stream == null) throw new IOException("Cannot open export destination");
        try (Writer writer = new OutputStreamWriter(stream, StandardCharsets.UTF_8)) {
            writer.write(title + "\n\n");
            writer.write("Sales by product\nProduct,Quantity,Amount\n");
            for (类061 row : products) {
                writer.write(escape(row.name) + "," + row.qty + "," + 类053.plain(row.amount) + "\n");
            }
            writer.write("\nSales by category\nCategory,Quantity,Amount\n");
            for (类058 row : categories) {
                writer.write(escape(row.category) + "," + row.qty + "," + 类053.plain(row.amount) + "\n");
            }
        }
    }

    private static String escape(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}

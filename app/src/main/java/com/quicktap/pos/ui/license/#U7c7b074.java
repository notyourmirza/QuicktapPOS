package com.quicktap.pos.ui.license;

import android.content.Context;
import android.content.Intent;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.quicktap.pos.R;
import com.quicktap.pos.license.类013;
import com.quicktap.pos.license.类014;
import com.quicktap.pos.util.类046;
import com.quicktap.pos.util.类056;

/**
 * Reusable persistent banner: "VERIFY YOUR LICENSE" / "CONTACT".
 *
 * Drop {@code <com.quicktap.pos.ui.license.类074/>} at the top of any
 * screen and call {@link #refresh()}. It hides itself only while the SERVER
 * reports an active, confirmed licence.
 */
public class 类074 extends FrameLayout {

    private TextView textStatus;
    private MaterialButton buttonVerify;
    private MaterialButton buttonContact;

    public 类074(Context context) { super(context); init(); }

    public 类074(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public 类074(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        LayoutInflater.from(getContext()).inflate(R.layout.view_license_banner, this, true);
        textStatus = findViewById(R.id.textBannerStatus);
        buttonVerify = findViewById(R.id.buttonBannerVerify);
        buttonContact = findViewById(R.id.buttonBannerContact);

        buttonVerify.setOnClickListener(v ->
                getContext().startActivity(new Intent(getContext(), 类079.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        buttonContact.setOnClickListener(v -> 类056.chat(getContext(),
                "*License activation request*" + 类056.signature(getContext())));

        applyCached();
    }

    /** Paints from the cached hint first, then asks the server for the truth. */
    public void refresh() {
        applyCached();
        类013.status(getContext(), this::apply);
    }

    public void apply(类014 state) {
        if (state == null) return;
        if (类014.ACTIVE.equals(state.state) && state.unlocked && state.confirmed) {
            setVisibility(GONE);
            return;
        }
        // OFFLINE keeps the banner visible: an unreachable server is never a licence.
        setVisibility(VISIBLE);
        textStatus.setText(state.headline());
    }

    private void applyCached() {
        类046 prefs = 类046.get(getContext());
        boolean ok = 类014.ACTIVE.equals(prefs.getLicenseState()) && prefs.isLicenseConfirmed();
        setVisibility(ok ? GONE : VISIBLE);
        if (!ok) {
            类014 cached = new 类014();
            cached.state = prefs.getLicenseState();
            textStatus.setText(cached.headline());
        }
    }

    /** Convenience for screens that just want it wired on resume. */
    public static void refresh(@Nullable View banner) {
        if (banner instanceof 类074) ((类074) banner).refresh();
    }
}

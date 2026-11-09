package com.craftstudio.cslauncher.ads;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.utils.motion.Motion;
import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdLoader;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.nativead.NativeAd;
import com.google.android.gms.ads.nativead.NativeAdOptions;
import com.google.android.gms.ads.nativead.NativeAdView;

/**
 * AdMob native ads for the launcher. One native advanced unit, rendered in the launcher's own
 * card style ({@code view_native_ad}) inside a container the page provides. The container stays
 * GONE until an ad is actually there, so a page never shows an empty slot: no network, no fill,
 * or a game process (ads are launcher-only) all mean "nothing changes".
 */
public final class CsAds {
    /** Native advanced ad unit of CS Launcher Plus (AdMob app ca-app-pub-1749761881436810~4504376190). */
    public static final String NATIVE_UNIT = "ca-app-pub-1749761881436810/9171038656";

    private static volatile boolean sInitStarted;

    private CsAds() {}

    /** Starts the SDK once, off the main thread; safe to call from every page that shows an ad. */
    public static void init(@NonNull Context context) {
        if (sInitStarted) return;
        sInitStarted = true;
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                MobileAds.initialize(app, status -> {});
            } catch (Throwable t) {
                android.util.Log.w("CsAds", "init", t);
            }
        }, "cs-ads-init").start();
    }

    /**
     * Loads one native ad into {@code container} (kept GONE until it arrives). The returned ad is
     * destroyed by the caller in {@code onDestroyView}; null when the request could not even start.
     */
    @Nullable
    public static Loaded loadNative(@NonNull Context context, @NonNull ViewGroup container) {
        init(context);
        container.setVisibility(View.GONE);
        final Loaded loaded = new Loaded();
        try {
            AdLoader loader = new AdLoader.Builder(context, NATIVE_UNIT)
                    .forNativeAd(ad -> {
                        if (loaded.destroyed) { ad.destroy(); return; }
                        if (loaded.ad != null) loaded.ad.destroy();
                        loaded.ad = ad;
                        bind(context, container, ad);
                    })
                    .withAdListener(new AdListener() {
                        @Override public void onAdFailedToLoad(@NonNull LoadAdError error) {
                            android.util.Log.i("CsAds", "no ad: " + error.getMessage());
                            container.setVisibility(View.GONE);
                        }
                    })
                    .withNativeAdOptions(new NativeAdOptions.Builder()
                            .setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT)
                            .setRequestMultipleImages(false)
                            .build())
                    .build();
            loader.loadAd(new AdRequest.Builder().build());
        } catch (Throwable t) {
            android.util.Log.w("CsAds", "load", t);
            return null;
        }
        return loaded;
    }

    private static void bind(Context context, ViewGroup container, NativeAd ad) {
        container.removeAllViews();
        NativeAdView adView = (NativeAdView) LayoutInflater.from(context).inflate(R.layout.view_native_ad, container, false);
        TextView headline = adView.findViewById(R.id.ad_headline);
        TextView body = adView.findViewById(R.id.ad_body);
        TextView advertiser = adView.findViewById(R.id.ad_advertiser);
        ImageView icon = adView.findViewById(R.id.ad_icon);
        Button cta = adView.findViewById(R.id.ad_cta);
        com.google.android.gms.ads.nativead.MediaView media = adView.findViewById(R.id.ad_media);

        headline.setText(ad.getHeadline());
        adView.setHeadlineView(headline);
        if (ad.getBody() == null) body.setVisibility(View.GONE);
        else { body.setText(ad.getBody()); body.setVisibility(View.VISIBLE); adView.setBodyView(body); }
        String adv = ad.getAdvertiser() != null ? ad.getAdvertiser() : ad.getStore();
        if (adv == null) advertiser.setVisibility(View.GONE);
        else { advertiser.setText(adv); advertiser.setVisibility(View.VISIBLE); adView.setAdvertiserView(advertiser); }
        if (ad.getIcon() == null) icon.setVisibility(View.GONE);
        else { icon.setImageDrawable(ad.getIcon().getDrawable()); icon.setVisibility(View.VISIBLE); adView.setIconView(icon); }
        if (ad.getCallToAction() == null) cta.setVisibility(View.GONE);
        else { cta.setText(ad.getCallToAction()); cta.setVisibility(View.VISIBLE); adView.setCallToActionView(cta); }
        if (ad.getMediaContent() != null) {
            media.setMediaContent(ad.getMediaContent());
            media.setVisibility(View.VISIBLE);
            adView.setMediaView(media);
        } else {
            media.setVisibility(View.GONE);
        }
        adView.setNativeAd(ad);
        container.addView(adView);
        container.setVisibility(View.VISIBLE);
        if (Motion.content(container)) Motion.riseIn(container, 0, 14);
    }

    /** Handle to the ad a page holds; destroy it with the view. */
    public static final class Loaded {
        @Nullable NativeAd ad;
        boolean destroyed;

        public void destroy() {
            destroyed = true;
            if (ad != null) { ad.destroy(); ad = null; }
        }
    }
}

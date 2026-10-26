package com.craftstudio.cslauncher.game.resources;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.modding.modpacks.imagecache.ImageReceiver;
import com.craftstudio.cslauncher.modding.modpacks.imagecache.ModIconCache;
import com.craftstudio.cslauncher.modding.modpacks.models.ContentType;
import com.craftstudio.cslauncher.modding.modpacks.models.ModItem;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cards for the in-game resource browser. Install state comes from
 * {@link ResourceInstallManager} tasks plus the on-disk installed map; both are pushed in via
 * {@link #onTaskChanged} / {@link #setInstalled} and only the affected row is rebound (payload).
 */
public final class ResourceCardAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_CARD = 0;
    private static final int TYPE_FOOTER = 1;
    private static final Object PAYLOAD_STATE = new Object();

    public enum Footer { NONE, LOADING, LOAD_MORE }

    public interface Listener {
        void onInstall(@NonNull ModItem item);
        void onCancel(@NonNull ModItem item);
        void onLoadMore();
    }

    private final List<ModItem> mItems = new ArrayList<>();
    private final Map<String, File> mInstalled = new HashMap<>();
    private final Map<String, ResourceCompatibility> mCompatCache = new HashMap<>();
    private final ModIconCache mIconCache;
    private final Listener mListener;
    private ContentType mType = ContentType.RESOURCE_PACK;
    @Nullable private ResourceProfileContext mProfile;
    private Footer mFooter = Footer.NONE;
    private boolean mInstallEnabled = true;

    public ResourceCardAdapter(@NonNull ModIconCache iconCache, @NonNull Listener listener) {
        mIconCache = iconCache;
        mListener = listener;
        setHasStableIds(false);
    }

    // ------------------------------------------------------------------ data

    public void setContext(@NonNull ContentType type, @Nullable ResourceProfileContext profile, boolean installEnabled) {
        mType = type;
        mProfile = profile;
        mInstallEnabled = installEnabled;
        mCompatCache.clear();
        notifyDataSetChanged();
    }

    public void setItems(@NonNull List<ModItem> items) {
        mItems.clear();
        mItems.addAll(items);
        mCompatCache.clear();
        notifyDataSetChanged();
    }

    public void appendItems(@NonNull List<ModItem> items) {
        if (items.isEmpty()) return;
        int start = mItems.size();
        mItems.addAll(items);
        notifyItemRangeInserted(start, items.size());
    }

    public void clear() {
        mItems.clear();
        mFooter = Footer.NONE;
        notifyDataSetChanged();
    }

    public int itemCountWithoutFooter() {
        return mItems.size();
    }

    public void setFooter(@NonNull Footer footer) {
        if (mFooter == footer) return;
        Footer old = mFooter;
        mFooter = footer;
        if (old == Footer.NONE) notifyItemInserted(mItems.size());
        else if (footer == Footer.NONE) notifyItemRemoved(mItems.size());
        else notifyItemChanged(mItems.size());
    }

    public void setInstalled(@NonNull Map<String, File> installed) {
        mInstalled.clear();
        mInstalled.putAll(installed);
        if (!mItems.isEmpty()) notifyItemRangeChanged(0, mItems.size(), PAYLOAD_STATE);
    }

    /** Called by the popup when the manager reports a change; rebinds only that card. */
    public void onTaskChanged(@NonNull ResourceInstallManager.Task task) {
        if (task.type != mType) return;
        if (task.status == ResourceInstallManager.Status.INSTALLED && task.installedFile != null) {
            mInstalled.put(task.key, task.installedFile);
        }
        for (int i = 0; i < mItems.size(); i++) {
            if (task.item.id.equals(mItems.get(i).id)) {
                notifyItemChanged(i, PAYLOAD_STATE);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ adapter

    @Override
    public int getItemViewType(int position) {
        return position < mItems.size() ? TYPE_CARD : TYPE_FOOTER;
    }

    @Override
    public int getItemCount() {
        return mItems.size() + (mFooter == Footer.NONE ? 0 : 1);
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_FOOTER) {
            return new FooterHolder(inflater.inflate(R.layout.item_resource_footer, parent, false));
        }
        return new CardHolder(inflater.inflate(R.layout.item_resource_card, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (holder instanceof CardHolder && !payloads.isEmpty()) {
            ((CardHolder) holder).bindState(mItems.get(position));
            return;
        }
        onBindViewHolder(holder, position);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof CardHolder) {
            ((CardHolder) holder).bind(mItems.get(position));
        } else {
            ((FooterHolder) holder).bind(mFooter);
        }
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof CardHolder) ((CardHolder) holder).release();
    }

    // ------------------------------------------------------------------ holders

    final class FooterHolder extends RecyclerView.ViewHolder {
        final View spinner, more;

        FooterHolder(@NonNull View itemView) {
            super(itemView);
            spinner = itemView.findViewById(R.id.rb_footer_spinner);
            more = itemView.findViewById(R.id.rb_footer_more);
            more.setOnClickListener(v -> mListener.onLoadMore());
        }

        void bind(Footer footer) {
            spinner.setVisibility(footer == Footer.LOADING ? View.VISIBLE : View.GONE);
            more.setVisibility(footer == Footer.LOAD_MORE ? View.VISIBLE : View.GONE);
        }
    }

    final class CardHolder extends RecyclerView.ViewHolder {
        final ImageView icon, iconPlaceholder;
        final TextView name, meta, description, compat, typeChip, versions, note, install;
        final View progressRoot;
        final ProgressBar progressBar;
        final TextView progressText, progressCancel;
        @Nullable ModItem bound;
        @Nullable ImageReceiver receiver;
        @Nullable String iconTag;

        CardHolder(@NonNull View v) {
            super(v);
            icon = v.findViewById(R.id.rb_card_icon);
            iconPlaceholder = v.findViewById(R.id.rb_card_icon_placeholder);
            name = v.findViewById(R.id.rb_card_name);
            meta = v.findViewById(R.id.rb_card_meta);
            description = v.findViewById(R.id.rb_card_description);
            compat = v.findViewById(R.id.rb_card_compat);
            typeChip = v.findViewById(R.id.rb_card_type);
            versions = v.findViewById(R.id.rb_card_versions);
            note = v.findViewById(R.id.rb_card_note);
            install = v.findViewById(R.id.rb_card_install);
            progressRoot = v.findViewById(R.id.rb_progress_root);
            progressBar = v.findViewById(R.id.rb_progress_bar);
            progressText = v.findViewById(R.id.rb_progress_text);
            progressCancel = v.findViewById(R.id.rb_progress_cancel);

            install.setOnClickListener(view -> {
                if (bound == null) return;
                ResourceInstallManager.Task task = ResourceInstallManager.get().getTask(ResourceInstallManager.keyFor(mType, bound));
                if (task != null && task.status.isActive()) return; // no duplicate downloads
                mListener.onInstall(bound);
            });
            progressCancel.setOnClickListener(view -> {
                if (bound != null) mListener.onCancel(bound);
            });
        }

        void bind(@NonNull ModItem item) {
            bound = item;
            Context ctx = itemView.getContext();
            name.setText(item.title == null ? "" : item.title);
            description.setText(item.description == null ? "" : item.description.trim());
            description.setVisibility(description.length() == 0 ? View.GONE : View.VISIBLE);

            StringBuilder m = new StringBuilder();
            if (item.author != null && !item.author.isEmpty()) m.append(ctx.getString(R.string.rb_by_fmt, item.author));
            if (item.downloads > 0) {
                if (m.length() > 0) m.append("  \u2022  ");
                m.append(ctx.getString(R.string.rb_downloads_fmt, compactNumber(item.downloads)));
            }
            meta.setText(m);
            meta.setVisibility(m.length() == 0 ? View.GONE : View.VISIBLE);

            typeChip.setText(mType == ContentType.SHADER_PACK ? R.string.rb_type_shader : R.string.rb_type_resource_pack);
            versions.setText(versionSummary(ctx, item));

            ResourceCompatibility c = mCompatCache.get(item.id);
            if (c == null) {
                c = ResourceCompatibility.evaluate(item, mType, mProfile);
                mCompatCache.put(item.id, c);
            }
            compat.setText(c.labelRes());
            compat.setTextColor(ContextCompat.getColor(ctx, c.colorRes()));
            ViewCompat.setBackgroundTintList(compat, ColorStateList.valueOf(ContextCompat.getColor(ctx, c.backgroundColorRes())));
            if (c.noteRes != 0) {
                note.setText(c.noteArg == null ? ctx.getString(c.noteRes) : ctx.getString(c.noteRes, c.noteArg));
                note.setTextColor(ContextCompat.getColor(ctx, c.level == ResourceCompatibility.Level.MISMATCH ? R.color.rb_bad
                        : c.level == ResourceCompatibility.Level.MISSING_DEPENDENCY ? R.color.rb_warn : R.color.rb_text_faint));
                note.setVisibility(View.VISIBLE);
            } else {
                note.setVisibility(View.GONE);
            }

            loadIcon(item);
            bindState(item);
        }

        /** Install button + progress strip only. */
        void bindState(@NonNull ModItem item) {
            Context ctx = itemView.getContext();
            String key = ResourceInstallManager.keyFor(mType, item);
            ResourceInstallManager.Task task = ResourceInstallManager.get().getTask(key);
            File installedFile = mInstalled.get(key);
            boolean installedOnDisk = installedFile != null && installedFile.isFile();

            install.setEnabled(true);
            install.setSelected(false);
            progressRoot.setVisibility(View.GONE);

            if (task != null && task.status.isActive()) {
                install.setSelected(true);
                install.setEnabled(false);
                progressRoot.setVisibility(View.VISIBLE);
                progressCancel.setVisibility(task.status == ResourceInstallManager.Status.INSTALLING ? View.INVISIBLE : View.VISIBLE);
                switch (task.status) {
                    case RESOLVING:
                        install.setText(R.string.rb_preparing);
                        setIndeterminate(true);
                        progressText.setText(R.string.rb_preparing);
                        break;
                    case DOWNLOADING: {
                        int permille = task.progressPermille();
                        if (permille < 0) {
                            install.setText(R.string.rb_downloading_indeterminate);
                            setIndeterminate(true);
                            progressText.setText(task.bytesDone > 0
                                    ? ResourceInstallManager.formatBytes(task.bytesDone) : ctx.getString(R.string.rb_downloading_indeterminate));
                        } else {
                            install.setText(ctx.getString(R.string.rb_downloading, permille / 10));
                            setIndeterminate(false);
                            progressBar.setProgress(permille);
                            progressText.setText(ctx.getString(R.string.rb_progress_bytes,
                                    ResourceInstallManager.formatBytes(task.bytesDone), ResourceInstallManager.formatBytes(task.bytesTotal)));
                        }
                        break;
                    }
                    case VERIFYING:
                        install.setText(R.string.rb_verifying);
                        setIndeterminate(true);
                        progressText.setText(R.string.rb_verifying);
                        break;
                    case AWAITING_REPLACE:
                        install.setText(R.string.rb_replace);
                        setIndeterminate(false);
                        progressBar.setProgress(1000);
                        progressText.setText(R.string.rb_replace_title);
                        break;
                    default: // INSTALLING
                        install.setText(R.string.rb_installing);
                        setIndeterminate(true);
                        progressText.setText(R.string.rb_installing);
                        break;
                }
                return;
            }

            if (task != null && task.status == ResourceInstallManager.Status.FAILED) {
                install.setText(R.string.rb_retry);
                install.setEnabled(mInstallEnabled);
                progressRoot.setVisibility(View.VISIBLE);
                progressCancel.setVisibility(View.GONE);
                setIndeterminate(false);
                progressBar.setProgress(0);
                progressText.setText(task.errorArg == null ? ctx.getString(task.errorRes) : ctx.getString(task.errorRes, task.errorArg));
                progressText.setTextColor(ContextCompat.getColor(ctx, R.color.rb_bad));
                return;
            }
            progressText.setTextColor(ContextCompat.getColor(ctx, R.color.rb_text_dim));

            boolean installed = installedOnDisk || (task != null && task.status == ResourceInstallManager.Status.INSTALLED
                    && task.installedFile != null && task.installedFile.isFile());
            if (installed) {
                install.setText(R.string.rb_installed);
                install.setSelected(true);
                install.setEnabled(false);
            } else {
                install.setText(R.string.rb_install);
                install.setEnabled(mInstallEnabled);
            }
        }

        private void setIndeterminate(boolean indeterminate) {
            if (progressBar.isIndeterminate() != indeterminate) progressBar.setIndeterminate(indeterminate);
        }

        private void loadIcon(@NonNull ModItem item) {
            releaseIcon();
            icon.setImageDrawable(null);
            iconPlaceholder.setVisibility(View.VISIBLE);
            if (item.imageUrl == null || item.imageUrl.isEmpty()) return;
            final String tag = item.getIconCacheTag();
            iconTag = tag;
            com.craftstudio.cslauncher.modding.modpacks.imagecache.AnimatedIcons.reset(icon);
            receiver = new com.craftstudio.cslauncher.modding.modpacks.imagecache.AnimatedImageReceiver() {
                @Override
                public void onImageAvailable(Bitmap image) {
                    if (image == null || !tag.equals(iconTag)) return;
                    icon.setImageBitmap(image);
                    icon.setAlpha(0f);
                    icon.animate().alpha(1f).setDuration(160).start();
                    iconPlaceholder.setVisibility(View.GONE);
                }

                @Override
                public void onAnimatedImageAvailable(@NonNull android.graphics.drawable.Drawable d) {
                    if (!tag.equals(iconTag)) return;
                    com.craftstudio.cslauncher.modding.modpacks.imagecache.AnimatedIcons.show(icon, d, 0f);
                    icon.setAlpha(0f);
                    icon.animate().alpha(1f).setDuration(160).start();
                    iconPlaceholder.setVisibility(View.GONE);
                }
            };
            mIconCache.getImage(receiver, tag, item.imageUrl);
        }

        private void releaseIcon() {
            if (receiver != null) {
                mIconCache.cancelImage(receiver);
                receiver = null;
            }
            iconTag = null;
            icon.animate().cancel();
            icon.setAlpha(1f);
        }

        void release() {
            releaseIcon();
            bound = null;
        }
    }

    // ------------------------------------------------------------------ helpers

    @NonNull
    static String compactNumber(long n) {
        if (n >= 1_000_000_000L) return String.format(Locale.ROOT, "%.1fB", n / 1e9);
        if (n >= 1_000_000L) return String.format(Locale.ROOT, n >= 10_000_000L ? "%.0fM" : "%.1fM", n / 1e6);
        if (n >= 1_000L) return String.format(Locale.ROOT, n >= 100_000L ? "%.0fK" : "%.1fK", n / 1e3);
        return String.valueOf(n);
    }

    @NonNull
    private static String versionSummary(@NonNull Context ctx, @NonNull ModItem item) {
        if (item.gameVersions == null || item.gameVersions.length == 0) return "";
        // Modrinth lists oldest → newest; show the newest release-looking version
        String newest = null;
        int count = 0;
        for (String v : item.gameVersions) {
            if (v == null || v.isEmpty()) continue;
            count++;
            if (v.matches("\\d+\\.\\d+(\\.\\d+)?")) newest = v;
        }
        if (newest == null) newest = item.gameVersions[item.gameVersions.length - 1];
        if (newest == null || newest.isEmpty()) return "";
        return count > 1 ? ctx.getString(R.string.rb_versions_more, newest, count - 1)
                : ctx.getString(R.string.rb_versions_fmt, newest);
    }
}

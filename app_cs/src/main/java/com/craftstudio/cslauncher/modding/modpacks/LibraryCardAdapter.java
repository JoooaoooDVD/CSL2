package com.craftstudio.cslauncher.modding.modpacks;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;
import androidx.recyclerview.widget.RecyclerView;

import com.craftstudio.cslauncher.R;
import com.craftstudio.cslauncher.modding.modpacks.imagecache.ImageReceiver;
import com.craftstudio.cslauncher.modding.modpacks.imagecache.ModIconCache;
import com.craftstudio.cslauncher.modding.modpacks.models.Constants;
import com.craftstudio.cslauncher.modding.modpacks.models.ContentType;
import com.craftstudio.cslauncher.modding.modpacks.models.ModItem;
import com.craftstudio.cslauncher.utils.CSAnimationEngine;
import com.craftstudio.cslauncher.utils.motion.LibraryMotion;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

/**
 * Grid adapter of the content library. Each card shows the icon, compatibility with the
 * selected profile, the description, three pills (category, loader, version line) and one
 * action button whose meaning follows the installed state: Install, Update or Uninstall.
 */
public class LibraryCardAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_CARD = 0;
    private static final int VIEW_SKELETON = 1;

    public enum CardState { INSTALL, UPDATE, UNINSTALL }

    public interface Listener {
        void onCardClicked(@NonNull ModItem item);
        void onActionClicked(@NonNull ModItem item, @NonNull CardState state);
        void onNearEnd();
    }

    private final List<ModItem> mItems = new ArrayList<>();
    private final ModIconCache mIconCache;
    private final Listener mListener;
    private InstalledContentIndex mIndex;
    private ContentType mContentType = ContentType.MOD;
    @Nullable private String mProfileVersion;
    @Nullable private String mProfileLoader;
    @Nullable private String mActiveCategory;
    private boolean mActionsEnabled = true;
    /** Positions below this were already animated in, so rebinding never replays it */
    private int mAnimatedUpTo = -1;
    /** Number of breathing placeholder cards shown instead of results, 0 when showing results */
    private int mSkeletonCount;
    private int mSpanCount = 3;
    /** Cards below this position belong to the first page and sweep in diagonally */
    private int mFirstPageSize;

    public LibraryCardAdapter(@NonNull ModIconCache iconCache, @NonNull Listener listener) {
        mIconCache = iconCache;
        mListener = listener;
        mIndex = InstalledContentIndex.empty(ContentType.MOD);
        setHasStableIds(false);
    }

    public void setContext(@NonNull ContentType type, @Nullable String profileVersion,
                           @Nullable String profileLoader, @Nullable String activeCategory) {
        mContentType = type;
        mProfileVersion = profileVersion;
        mProfileLoader = profileLoader;
        mActiveCategory = activeCategory;
    }

    /** Replaces the results with {@code count} skeleton cards while the first page loads */
    public void showSkeleton(int count) {
        mItems.clear();
        mSkeletonCount = count;
        notifyDataSetChanged();
    }

    public boolean isShowingSkeleton() {
        return mSkeletonCount > 0;
    }

    public void setSpanCount(int spanCount) {
        mSpanCount = Math.max(1, spanCount);
    }

    public void setItems(@NonNull List<ModItem> items, boolean animate) {
        mSkeletonCount = 0;
        mItems.clear();
        mItems.addAll(items);
        mFirstPageSize = animate ? items.size() : 0;
        mAnimatedUpTo = animate ? -1 : Integer.MAX_VALUE;
        notifyDataSetChanged();
    }

    public void appendItems(@NonNull List<ModItem> items) {
        if (mSkeletonCount > 0) {
            setItems(items, true);
            return;
        }
        int start = mItems.size();
        mItems.addAll(items);
        notifyItemRangeInserted(start, items.size());
    }

    public void setInstalledIndex(@NonNull InstalledContentIndex index) {
        mIndex = index;
        notifyItemRangeChanged(0, mItems.size(), Boolean.TRUE);
    }

    @NonNull
    public InstalledContentIndex getInstalledIndex() {
        return mIndex;
    }

    public void setActionsEnabled(boolean enabled) {
        if (mActionsEnabled == enabled) return;
        mActionsEnabled = enabled;
        notifyItemRangeChanged(0, mItems.size(), Boolean.TRUE);
    }

    public List<ModItem> getItems() {
        return mItems;
    }

    @NonNull
    public CardState stateFor(@NonNull ModItem item) {
        InstalledContentIndex.Entry entry = mIndex.find(item);
        if (entry == null) return CardState.INSTALL;
        boolean update = entry.updateAvailable;
        if (!update && item.apiSource == Constants.SOURCE_CURSEFORGE) {
            update = InstalledContentIndex.curseforgeHasNewer(item, entry, mProfileVersion,
                    mContentType == ContentType.MOD ? mProfileLoader : null);
        }
        return update ? CardState.UPDATE : CardState.UNINSTALL;
    }

    @Override
    public int getItemViewType(int position) {
        return mSkeletonCount > 0 ? VIEW_SKELETON : VIEW_CARD;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == VIEW_SKELETON) {
            return new SkeletonHolder(inflater.inflate(R.layout.item_library_skeleton, parent, false));
        }
        return new CardHolder(inflater.inflate(R.layout.item_library_card, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && holder instanceof CardHolder) {
            ((CardHolder) holder).bindActionAnimated(mItems.get(position));
            return;
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof SkeletonHolder) {
            ((SkeletonHolder) holder).position = position;
            return;
        }
        CardHolder card = (CardHolder) holder;
        card.bind(mItems.get(position));
        if (position > mAnimatedUpTo) {
            mAnimatedUpTo = position;
            card.animateIn(position);
        }
        if (position >= mItems.size() - 6) mListener.onNearEnd();
    }

    @Override
    public void onViewAttachedToWindow(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof SkeletonHolder) {
            LibraryMotion.startSkeleton(holder.itemView, ((SkeletonHolder) holder).position);
        }
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof SkeletonHolder) LibraryMotion.stopSkeleton(holder.itemView);
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof CardHolder) ((CardHolder) holder).recycle();
        else LibraryMotion.stopSkeleton(holder.itemView);
    }

    @Override
    public int getItemCount() {
        return mSkeletonCount > 0 ? mSkeletonCount : mItems.size();
    }

    static final class SkeletonHolder extends RecyclerView.ViewHolder {
        int position;

        SkeletonHolder(@NonNull View view) {
            super(view);
        }
    }

    public class CardHolder extends RecyclerView.ViewHolder {
        private final ImageView mIcon;
        private final ImageView mCompatIcon;
        private final TextView mCompatText;
        private final TextView mCompatRange;
        private final TextView mTitle;
        private final TextView mDescription;
        private final TextView mTagCategory;
        private final TextView mTagLoader;
        private final TextView mTagVersion;
        private final MaterialButton mAction;
        private final ImageView mSourceBadge;
        private ImageReceiver mReceiver;
        private ModItem mItem;

        CardHolder(@NonNull View view) {
            super(view);
            mIcon = view.findViewById(R.id.card_icon);
            mCompatIcon = view.findViewById(R.id.card_compat_icon);
            mCompatText = view.findViewById(R.id.card_compat_text);
            mCompatRange = view.findViewById(R.id.card_compat_range);
            mTitle = view.findViewById(R.id.card_title);
            mDescription = view.findViewById(R.id.card_description);
            mTagCategory = view.findViewById(R.id.card_tag_category);
            mTagLoader = view.findViewById(R.id.card_tag_loader);
            mTagVersion = view.findViewById(R.id.card_tag_version);
            mAction = view.findViewById(R.id.card_action);
            mSourceBadge = view.findViewById(R.id.card_source_badge);
            CSAnimationEngine.attachPressFeedback(mAction);

            view.setOnClickListener(v -> {
                if (mItem != null) mListener.onCardClicked(mItem);
            });
            mAction.setOnClickListener(v -> {
                if (mItem != null) mListener.onActionClicked(mItem, stateFor(mItem));
            });
        }

        /** Round brand badge on the icon corner: green Modrinth wrench or orange CurseForge flame */
        private void bindSourceBadge(Context context, int apiSource) {
            if (mSourceBadge == null) return;
            boolean curseforge = apiSource == Constants.SOURCE_CURSEFORGE;
            android.graphics.drawable.Drawable bg = mSourceBadge.getBackground();
            if (bg instanceof android.graphics.drawable.GradientDrawable) {
                ((android.graphics.drawable.GradientDrawable) bg.mutate()).setColor(ContextCompat.getColor(context,
                        curseforge ? R.color.lib_src_curseforge : R.color.lib_src_modrinth));
            }
            mSourceBadge.setImageResource(curseforge ? R.drawable.ic_curseforge : R.drawable.ic_modrinth);
            mSourceBadge.setImageTintList(ColorStateList.valueOf(curseforge ? 0xFFFFFFFF : 0xFF0B2A17));
            mSourceBadge.setContentDescription(context.getString(curseforge
                    ? R.string.lib_source_badge_curseforge : R.string.lib_source_badge_modrinth));
        }

        void bind(@NonNull ModItem item) {
            recycle();
            mItem = item;
            Context context = itemView.getContext();

            mTitle.setText(item.title);
            mDescription.setText(item.description);
            bindSourceBadge(context, item.apiSource);

            mIcon.setImageDrawable(null);
            mIcon.animate().cancel();
            mIcon.setAlpha(1f);
            mIcon.setScaleX(1f);
            mIcon.setScaleY(1f);
            final long boundAt = android.os.SystemClock.uptimeMillis();
            final float radius = context.getResources().getDisplayMetrics().density * 8f;
            mReceiver = new ImageReceiver() {
                @Override
                public void onImageAvailable(Bitmap image) {
                    if (mReceiver != this || image == null) return;
                    RoundedBitmapDrawable drawable = RoundedBitmapDrawableFactory.create(mIcon.getResources(), image);
                    drawable.setCornerRadius(radius);
                    drawable.setFilterBitmap(image.getWidth() >= 64);
                    mIcon.setImageDrawable(drawable);
                    // Cached icons show instantly, downloaded ones pop in
                    if (android.os.SystemClock.uptimeMillis() - boundAt > 120) LibraryMotion.iconLoaded(mIcon);
                }
            };
            if (item.imageUrl != null && !item.imageUrl.isEmpty()) {
                mIconCache.getImage(mReceiver, item.getIconCacheTag(), item.imageUrl);
            } else {
                mIcon.setImageResource(R.drawable.ic_lib_cube);
            }

            bindCompatibility(context, item);
            bindTags(context, item);
            bindAction(item);
        }

        private void bindCompatibility(Context context, ModItem item) {
            String range = LibraryCatalog.versionRange(item.gameVersions);
            boolean known = item.gameVersions != null && item.gameVersions.length > 0;
            if (!known) {
                mCompatIcon.setImageResource(R.drawable.ic_lib_check_badge);
                mCompatText.setText(R.string.lib_available);
                mCompatRange.setVisibility(View.GONE);
                return;
            }
            boolean compatible;
            if (mProfileVersion == null) {
                compatible = true;
                mCompatText.setText(R.string.lib_available);
            } else {
                compatible = LibraryCatalog.containsVersion(item.gameVersions, mProfileVersion)
                        && (mContentType != ContentType.MOD || LibraryCatalog.loaderMatches(item.loaders, mProfileLoader));
                mCompatText.setText(compatible ? R.string.lib_compatible : R.string.lib_not_compatible);
            }
            mCompatIcon.setImageResource(compatible ? R.drawable.ic_lib_check_badge : R.drawable.ic_lib_warning_badge);
            mCompatRange.setVisibility(range == null ? View.GONE : View.VISIBLE);
            mCompatRange.setText(range);
        }

        private void bindTags(Context context, ModItem item) {
            String category = LibraryCatalog.primaryCategory(mContentType, item, mActiveCategory);
            if (category != null) {
                mTagCategory.setVisibility(View.VISIBLE);
                mTagCategory.setText(LibraryCatalog.labelFor(mContentType, category));
                applyTint(context, mTagCategory, LibraryCatalog.tintFor(mContentType, category));
            } else {
                mTagCategory.setVisibility(View.GONE);
            }

            String loader = LibraryCatalog.displayLoader(mContentType, item, mProfileLoader);
            if (loader != null) {
                mTagLoader.setVisibility(View.VISIBLE);
                mTagLoader.setText(LibraryCatalog.loaderLabel(loader));
                applyTint(context, mTagLoader, LibraryCatalog.TINT_NEUTRAL);
            } else {
                mTagLoader.setVisibility(View.GONE);
            }

            String version = LibraryCatalog.versionPill(item.gameVersions, mProfileVersion);
            if (version != null) {
                mTagVersion.setVisibility(View.VISIBLE);
                mTagVersion.setText(version);
                applyTint(context, mTagVersion, LibraryCatalog.TINT_NEUTRAL);
            } else {
                mTagVersion.setVisibility(View.GONE);
            }
        }

        void bindAction(@NonNull ModItem item) {
            Context context = itemView.getContext();
            CardState state = stateFor(item);
            int textColor;
            int strokeColor;
            int background;
            switch (state) {
                case UPDATE:
                    mAction.setText(R.string.lib_update);
                    mAction.setIconResource(R.drawable.ic_lib_update);
                    textColor = ContextCompat.getColor(context, R.color.lib_update_text);
                    strokeColor = ContextCompat.getColor(context, R.color.lib_update_bg);
                    background = strokeColor;
                    break;
                case UNINSTALL:
                    mAction.setText(R.string.lib_uninstall);
                    mAction.setIconResource(R.drawable.ic_lib_trash);
                    textColor = ContextCompat.getColor(context, R.color.lib_danger);
                    strokeColor = textColor;
                    background = 0;
                    break;
                default:
                    mAction.setText(R.string.lib_install);
                    mAction.setIconResource(R.drawable.ic_lib_download);
                    textColor = ContextCompat.getColor(context, R.color.lib_install_text);
                    strokeColor = ContextCompat.getColor(context, R.color.lib_install_stroke);
                    background = 0;
                    break;
            }
            mAction.setTextColor(textColor);
            mAction.setIconTint(ColorStateList.valueOf(textColor));
            mAction.setStrokeColor(ColorStateList.valueOf(strokeColor));
            mAction.setBackgroundTintList(ColorStateList.valueOf(background));
            mAction.setRippleColor(ColorStateList.valueOf((textColor & 0x00FFFFFF) | 0x33000000));
            mAction.setEnabled(mActionsEnabled);
            mAction.setAlpha(mActionsEnabled ? 1f : 0.5f);
        }

        /** Rebinds the action after an install-state change, morphing when its meaning changes */
        void bindActionAnimated(@NonNull ModItem item) {
            CharSequence before = mAction.getText();
            bindAction(item);
            if (before != null && !before.toString().contentEquals(mAction.getText())) {
                LibraryMotion.actionMorph(mAction);
            }
        }

        void animateIn(int position) {
            LibraryMotion.cardIn(itemView, position, mSpanCount, position < mFirstPageSize);
        }

        void recycle() {
            if (mReceiver != null) {
                mIconCache.cancelImage(mReceiver);
                mReceiver = null;
            }
            itemView.animate().cancel();
            itemView.setAlpha(1f);
            itemView.setTranslationY(0f);
            itemView.setScaleX(1f);
            itemView.setScaleY(1f);
        }
    }

    /** Applies one of the LibraryCatalog tag tints to a pill */
    public static void applyTint(@NonNull Context context, @NonNull TextView view, int tint) {
        int background;
        int text;
        switch (tint) {
            case LibraryCatalog.TINT_GREEN:
                background = R.drawable.bg_lib_tag_green;
                text = R.color.lib_tag_green_text;
                break;
            case LibraryCatalog.TINT_BLUE:
                background = R.drawable.bg_lib_tag_blue;
                text = R.color.lib_tag_blue_text;
                break;
            case LibraryCatalog.TINT_SLATE:
                background = R.drawable.bg_lib_tag_slate;
                text = R.color.lib_tag_slate_text;
                break;
            case LibraryCatalog.TINT_PURPLE:
                background = R.drawable.bg_lib_tag_purple;
                text = R.color.lib_tag_purple_text;
                break;
            case LibraryCatalog.TINT_AMBER:
                background = R.drawable.bg_lib_tag_amber;
                text = R.color.lib_tag_amber_text;
                break;
            default:
                view.setBackgroundResource(R.drawable.bg_lib_tag_neutral);
                android.util.TypedValue value = new android.util.TypedValue();
                if (context.getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, value, true)) {
                    view.setTextColor(value.data);
                }
                return;
        }
        view.setBackgroundResource(background);
        view.setTextColor(ContextCompat.getColor(context, text));
    }
}

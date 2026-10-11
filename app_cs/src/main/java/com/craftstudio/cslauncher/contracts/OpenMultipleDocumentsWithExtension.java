package com.craftstudio.cslauncher.contracts;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.webkit.MimeTypeMap;

import androidx.activity.result.contract.ActivityResultContract;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Like {@link OpenDocumentWithExtension}, but the user can pick several files at once
 * (used by the content manager's Import button for .jar / .zip files).
 * File managers report .jar files with different MIME types (java-archive, x-java-archive,
 * octet-stream), so all of them are allowed; the caller still checks the real extension.
 */
public class OpenMultipleDocumentsWithExtension extends ActivityResultContract<Object, List<Uri>> {
    private final String[] mMimeTypes;

    public OpenMultipleDocumentsWithExtension(@NonNull String[] extensions) {
        ArrayList<String> mimeTypes = new ArrayList<>();
        for (String extension : extensions) {
            if ("jar".equals(extension)) {
                mimeTypes.add("application/java-archive");
                mimeTypes.add("application/x-java-archive");
            } else if ("zip".equals(extension)) {
                mimeTypes.add("application/zip");
                mimeTypes.add("application/x-zip-compressed");
            }
            String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (mimeType != null && !mimeTypes.contains(mimeType)) mimeTypes.add(mimeType);
        }
        mimeTypes.add("application/octet-stream");
        mMimeTypes = mimeTypes.toArray(new String[0]);
    }

    @NonNull
    @Override
    public Intent createIntent(@NonNull Context context, @NonNull Object input) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mMimeTypes);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        return intent;
    }

    @Nullable
    @Override
    public final SynchronousResult<List<Uri>> getSynchronousResult(@NonNull Context context,
                                                                   @NonNull Object input) {
        return null;
    }

    @NonNull
    @Override
    public final List<Uri> parseResult(int resultCode, @Nullable Intent intent) {
        if (intent == null || resultCode != Activity.RESULT_OK) return Collections.emptyList();
        ArrayList<Uri> uris = new ArrayList<>();
        ClipData clip = intent.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null && !uris.contains(uri)) uris.add(uri);
            }
        }
        Uri single = intent.getData();
        if (single != null && !uris.contains(single)) uris.add(single);
        return uris;
    }
}

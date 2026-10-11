package com.craftstudio.cslauncher.prefs;

import android.content.Context;
import android.text.InputType;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.Preference;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

/**
 * Shows the settings' list / multi-select / text preferences in {@link SettingsPopup} instead of
 * the stock AlertDialogs. Values go through {@code callChangeListener} like the stock dialogs.
 */
public final class SettingsPopups {
    private SettingsPopups() {}

    /** @return true when the preference was handled (a popup is shown) */
    public static boolean show(@NonNull Context context, @NonNull Preference preference) {
        if (preference instanceof ListPreference) {
            showList(context, (ListPreference) preference);
            return true;
        }
        if (preference instanceof MultiSelectListPreference) {
            showMulti(context, (MultiSelectListPreference) preference);
            return true;
        }
        if (preference instanceof EditTextPreference) {
            showText(context, (EditTextPreference) preference);
            return true;
        }
        return false;
    }

    @NonNull
    private static SettingsPopup base(@NonNull Context context, @NonNull Preference preference) {
        CharSequence title = preference instanceof androidx.preference.DialogPreference
                && ((androidx.preference.DialogPreference) preference).getDialogTitle() != null
                ? ((androidx.preference.DialogPreference) preference).getDialogTitle()
                : preference.getTitle();
        return new SettingsPopup(context).title(title).icon(preference.getIcon());
    }

    private static void showList(@NonNull Context context, @NonNull ListPreference preference) {
        CharSequence[] entries = preference.getEntries();
        CharSequence[] values = preference.getEntryValues();
        if (entries == null || values == null) return;
        int checked = preference.findIndexOfValue(preference.getValue());
        base(context, preference)
                .subtitle(preference.getSummaryProvider() == null ? preference.getSummary() : null)
                .singleChoice(entries, checked, index -> {
                    if (index < 0 || index >= values.length) return;
                    String value = values[index].toString();
                    if (preference.callChangeListener(value)) preference.setValue(value);
                })
                .show();
    }

    private static void showMulti(@NonNull Context context, @NonNull MultiSelectListPreference preference) {
        CharSequence[] entries = preference.getEntries();
        CharSequence[] values = preference.getEntryValues();
        if (entries == null || values == null) return;
        Set<String> current = preference.getValues();
        boolean[] checked = new boolean[values.length];
        for (int i = 0; i < values.length; i++) checked[i] = current.contains(values[i].toString());
        base(context, preference)
                .subtitle(preference.getSummaryProvider() == null ? preference.getSummary() : null)
                .multiChoice(entries, checked, result -> {
                    Set<String> selected = new HashSet<>();
                    for (int i = 0; i < result.length && i < values.length; i++) {
                        if (result[i]) selected.add(values[i].toString());
                    }
                    if (preference.callChangeListener(selected)) preference.setValues(selected);
                })
                .show();
    }

    private static void showText(@NonNull Context context, @NonNull EditTextPreference preference) {
        final EditTextPreference.OnBindEditTextListener binder = bindListenerOf(preference);
        CharSequence message = preference.getDialogMessage();
        base(context, preference)
                .message(message)
                .input(preference.getText(), null, text -> {
                    if (preference.callChangeListener(text)) preference.setText(text);
                    return true;
                })
                .inputSingleLine(true)
                .inputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
                .onInputReady(input -> {
                    // The screen's own field setup (number keyboard, limits...) still applies
                    if (binder != null) {
                        try {
                            binder.onBindEditText(input);
                        } catch (RuntimeException ignored) {}
                    }
                    input.setSelection(input.length());
                })
                .show();
    }

    /** The listener set with setOnBindEditTextListener (no public getter in androidx.preference) */
    @Nullable
    private static EditTextPreference.OnBindEditTextListener bindListenerOf(@NonNull EditTextPreference preference) {
        try {
            for (Field field : EditTextPreference.class.getDeclaredFields()) {
                if (EditTextPreference.OnBindEditTextListener.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    return (EditTextPreference.OnBindEditTextListener) field.get(preference);
                }
            }
        } catch (Exception ignored) {
            // Fall back to a plain text field
        }
        return null;
    }

    /** Text field helper for code that builds its own input popup */
    public static void selectAll(@Nullable EditText input) {
        if (input != null) input.selectAll();
    }
}

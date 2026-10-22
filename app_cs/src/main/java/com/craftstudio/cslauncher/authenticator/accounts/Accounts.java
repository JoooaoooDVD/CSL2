package com.craftstudio.cslauncher.authenticator.accounts;

import android.util.Log;

import com.craftstudio.cslauncher.Tools;
import com.craftstudio.cslauncher.authenticator.AuthType;
import com.craftstudio.cslauncher.prefs.LauncherPreferences;
import com.craftstudio.cslauncher.utils.FileUtils;
import com.craftstudio.cslauncher.utils.JSONUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class Accounts {
	private static final String PROFILE_PREF_FILE = "selected_account_file";

	public final List<Account> accounts;
	public final int selectionIndex;

	private Accounts(List<Account> accounts, int selectionIndex) {
        this.accounts = accounts;
        this.selectionIndex = selectionIndex;
    }

	public static Accounts load() throws IOException {
		File accountsDir = new File(Tools.DIR_ACCOUNT_NEW);
        synchronized (Accounts.class) {
            FileUtils.ensureDirectory(accountsDir);
        }
		File[] accountFiles = accountsDir.listFiles();
		if(accountFiles == null) throw new IOException("Failed to create account directory");
		String selectedAccount = getSelectedAccount();
		ArrayList<Account> accounts = new ArrayList<>(accountFiles.length);
		int selectedAccountIdx = 0;
		for(File accFile : accountFiles) {
			Account account = loadAccount(accFile);
			if(account == null) continue;
			accounts.add(account);
			if(accFile.getName().equals(selectedAccount)) {
				selectedAccountIdx = accounts.size() - 1;
			}
		}
		accounts.trimToSize();
		return new Accounts(Collections.unmodifiableList(accounts), selectedAccountIdx);
	}

	private static Account loadAccount(File source) {
		Account acc;
		try {
			acc = JSONUtils.readFromFile(source, Account.class);
		}catch (Exception e) {
			Log.w("Accounts", "Failed to load account", e);
			return null;
		}
        if(acc == null) return null;
		acc.mSaveLocation = source;

		if (acc.accessToken == null) {
			acc.accessToken = "0";
		}
		if (acc.profileId == null) {
			acc.profileId = "00000000-0000-0000-0000-000000000000";
		}
		if (acc.username == null) {
			acc.username = "0";
		}
		if (acc.refreshToken == null) {
			acc.refreshToken = "0";
		}
		if(acc.authType == null) {
			acc.authType = acc.isMicrosoft ? AuthType.MICROSOFT : AuthType.LOCAL;
		}
		return acc;
	}

	private static String getSelectedAccount() {
		return LauncherPreferences.DEFAULT_PREF.getString(PROFILE_PREF_FILE, "");
	}

    public static Account getCurrent() {
		String selectedAccount = getSelectedAccount();
		return loadAccount(new File(Tools.DIR_ACCOUNT_NEW, selectedAccount));
    }

	/** File name (inside the accounts dir) of the selected account, "" when none */
	public static String getCurrentFileName() {
		return getSelectedAccount();
	}

	/** Loads an account by its file name; null when missing/corrupt */
	@androidx.annotation.Nullable
	public static Account loadByFileName(@androidx.annotation.Nullable String fileName) {
		if (fileName == null || fileName.isEmpty()) return null;
		return loadAccount(new File(Tools.DIR_ACCOUNT_NEW, fileName));
	}

	/**
	 * The account the game should run with, never null when at least one account exists.
	 * Order: the file name handed over by the launcher (always fresh, even if this process has a
	 * stale preference cache), then the selected account from preferences, then the first
	 * account on disk (and it becomes the selected one).
	 */
	@androidx.annotation.Nullable
	public static Account resolveForLaunch(@androidx.annotation.Nullable String preferredFileName) {
		Account acc = loadByFileName(preferredFileName);
		if (acc != null) return acc;
		acc = getCurrent();
		if (acc != null) return acc;
		try {
			Accounts all = load();
			if (!all.accounts.isEmpty()) {
				acc = all.accounts.get(Math.max(0, Math.min(all.selectionIndex, all.accounts.size() - 1)));
				if (acc != null && acc.mSaveLocation != null) setCurrent(acc);
				return acc;
			}
		} catch (Exception e) {
			Log.w("Accounts", "resolveForLaunch: could not list accounts", e);
		}
		return null;
	}

	private static File pickAccountPath() {
		File profilePath;
		do {
			String profileName = UUID.randomUUID().toString();
			profilePath = new File(Tools.DIR_ACCOUNT_NEW, profileName);
		} while(profilePath.exists());
		return profilePath;
	}

	public static Account create(Setter setter) throws IOException {
		Account account = new Account();
		setter.writeAccount(account);
		account.mSaveLocation = pickAccountPath();
		account.save();
		return account;
	}

	public static void setCurrent(Account account) {
		LauncherPreferences.DEFAULT_PREF
				.edit().putString(PROFILE_PREF_FILE, account.mSaveLocation.getName())
				.apply();
	}

	public static void delete(Account account) {
		boolean ignored = account.mSaveLocation.delete();
	}

	public interface Setter {
		void writeAccount(Account account) throws IOException;
	}
}

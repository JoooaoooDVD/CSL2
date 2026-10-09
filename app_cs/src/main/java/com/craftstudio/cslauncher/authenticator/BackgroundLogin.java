package com.craftstudio.cslauncher.authenticator;

import androidx.annotation.NonNull;

import com.craftstudio.cslauncher.authenticator.listener.LoginListener;
import com.craftstudio.cslauncher.authenticator.accounts.Account;

public interface BackgroundLogin {
    void createAccount(@NonNull LoginListener loginListener, String code);
    void refreshAccount(@NonNull LoginListener loginListener, Account account);
    interface Creator {
        BackgroundLogin create();
    }
}

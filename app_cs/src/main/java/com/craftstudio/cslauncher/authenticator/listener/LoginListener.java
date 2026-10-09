package com.craftstudio.cslauncher.authenticator.listener;

import com.craftstudio.cslauncher.authenticator.accounts.Account;

public interface LoginListener{
    void onLoginDone(Account account);
    void onLoginError(Throwable errorMessage);
    void onLoginProgress(int step);
    void setMaxLoginProgress(int max);
}

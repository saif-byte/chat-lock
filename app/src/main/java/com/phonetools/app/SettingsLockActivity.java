package com.phonetools.app;

/** Same PIN screen, used to protect this app's own settings. Returns RESULT_OK on success. */
public class SettingsLockActivity extends LockActivity {
    @Override
    protected boolean isSettingsMode() {
        return true;
    }
}

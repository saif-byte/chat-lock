package com.chatlock.app;

/** Same PIN screen, used to protect Chat Lock's own settings. Returns RESULT_OK on success. */
public class SettingsLockActivity extends LockActivity {
    @Override
    protected boolean isSettingsMode() {
        return true;
    }
}

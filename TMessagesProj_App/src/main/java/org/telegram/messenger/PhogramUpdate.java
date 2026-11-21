package org.telegram.messenger;

/**
 * A Phogram release found on the organization's GitLab: Telegram's update (version, code, what is new) with whether
 * this device must take it before going on (its version is older than the release's minimum).
 */
public class PhogramUpdate extends BetaUpdate {

    public final boolean mandatory;

    public PhogramUpdate(String version, int versionCode, String changelog, boolean mandatory) {
        super(version, versionCode, changelog);
        this.mandatory = mandatory;
    }
}

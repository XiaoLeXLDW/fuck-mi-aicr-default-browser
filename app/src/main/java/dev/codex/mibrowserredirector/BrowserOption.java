package dev.codex.mibrowserredirector;

import java.util.Objects;

final class BrowserOption {
    final String label;
    final String packageName;

    BrowserOption(String label, String packageName) {
        this.label = label;
        this.packageName = packageName;
    }

    @Override
    public String toString() {
        return label;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof BrowserOption)) return false;
        BrowserOption that = (BrowserOption) other;
        return packageName.equals(that.packageName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(packageName);
    }
}

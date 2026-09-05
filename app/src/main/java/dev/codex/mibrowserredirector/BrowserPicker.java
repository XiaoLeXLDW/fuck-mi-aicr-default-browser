package dev.codex.mibrowserredirector;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** UI-only picker; selecting an entry never binds or reconfigures a UserService. */
final class BrowserPicker {
    private final Activity activity;
    private final FrameLayout field;
    private final View fieldRow;
    private final Consumer<BrowserOption> onSelected;
    private final List<BrowserOption> options = new ArrayList<>();
    private final Map<String, Drawable.ConstantState> iconCache = new HashMap<>();
    private BrowserOption selection;
    private String defaultPackage;
    private AlertDialog dialog;

    BrowserPicker(Activity activity, FrameLayout field, Consumer<BrowserOption> onSelected) {
        this.activity = activity;
        this.field = field;
        this.onSelected = onSelected;
        fieldRow = LayoutInflater.from(activity).inflate(R.layout.browser_option, field, false);
        fieldRow.setBackground(null);
        fieldRow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        field.addView(fieldRow);
        field.setOnClickListener(view -> showDialog());
        field.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
            }
        });
    }

    void submitOptions(List<BrowserOption> browsers, String preferredPackage, String defaultPackage) {
        close();
        options.clear();
        options.addAll(browsers);
        iconCache.clear();
        this.defaultPackage = defaultPackage;
        selection = BrowserSelection.resolve(options, preferredPackage, defaultPackage);
        renderField();
    }

    BrowserOption getSelection() {
        return selection;
    }

    void setEnabled(boolean enabled) {
        boolean available = enabled && !options.isEmpty();
        field.setEnabled(available);
        field.setAlpha(available ? 1f : 0.55f);
        if (!available) close();
    }

    void close() {
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
    }

    private void renderField() {
        TextView label = fieldRow.findViewById(R.id.browser_name);
        TextView details = fieldRow.findViewById(R.id.browser_package);
        ImageView icon = fieldRow.findViewById(R.id.browser_icon);
        ImageView mark = fieldRow.findViewById(R.id.browser_mark);
        if (selection != null) {
            bindIdentity(fieldRow, selection);
        } else {
            label.setText(options.isEmpty() ? R.string.browser_empty : R.string.browser_missing);
            details.setText(options.isEmpty() ? R.string.browser_empty_hint : R.string.browser_missing_hint);
            icon.setImageResource(R.drawable.ic_browser);
        }
        mark.setImageResource(R.drawable.ic_chevron_down);
        field.setContentDescription(activity.getString(R.string.browser_field_description,
                label.getText(), details.getText()));
    }

    private void showDialog() {
        if (!field.isEnabled() || dialog != null || activity.isFinishing()) return;
        View header = LayoutInflater.from(activity).inflate(R.layout.browser_picker_header, field, false);
        TextView subtitle = header.findViewById(R.id.browser_picker_subtitle);
        subtitle.setText(activity.getString(R.string.browser_picker_subtitle, options.size()));
        int selectedIndex = options.indexOf(selection);
        AlertDialog pickerDialog = new AlertDialog.Builder(activity, R.style.BrowserPickerDialog)
                .setCustomTitle(header)
                .setSingleChoiceItems(new OptionsAdapter(), selectedIndex, (picker, position) -> {
                    selection = options.get(position);
                    renderField();
                    onSelected.accept(selection);
                    picker.dismiss();
                })
                .setNegativeButton(R.string.browser_cancel, null)
                .create();
        dialog = pickerDialog;
        pickerDialog.setOnDismissListener(ignored -> {
            if (dialog == pickerDialog) dialog = null;
        });
        pickerDialog.show();
        Window window = pickerDialog.getWindow();
        if (window != null) {
            // Use the Activity width, not the physical display width (split-screen/foldables).
            int availableWidth = activity.getWindow().getDecorView().getWidth();
            float density = activity.getResources().getDisplayMetrics().density;
            if (availableWidth > 0) {
                int width = Math.min(availableWidth - Math.round(32 * density), Math.round(480 * density));
                window.setLayout(Math.max(1, width), ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        }
    }

    private void bindIdentity(View row, BrowserOption option) {
        TextView label = row.findViewById(R.id.browser_name);
        TextView details = row.findViewById(R.id.browser_package);
        ImageView icon = row.findViewById(R.id.browser_icon);
        label.setText(option.label);
        details.setText(option.packageName.equals(defaultPackage)
                ? activity.getString(R.string.browser_system_default, option.packageName)
                : option.packageName);
        icon.setImageDrawable(loadIcon(option.packageName));
    }

    private Drawable loadIcon(String packageName) {
        Drawable.ConstantState cached = iconCache.get(packageName);
        if (cached != null) return cached.newDrawable(activity.getResources());
        Drawable icon;
        try {
            icon = activity.getPackageManager().getApplicationIcon(packageName);
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
            icon = activity.getDrawable(R.drawable.ic_browser);
        }
        if (icon != null && icon.getConstantState() != null) {
            iconCache.put(packageName, icon.getConstantState());
        }
        return icon;
    }

    private final class OptionsAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return options.size();
        }

        @Override
        public BrowserOption getItem(int position) {
            return options.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView != null ? convertView
                    : LayoutInflater.from(activity).inflate(R.layout.browser_option, parent, false);
            BrowserOption option = getItem(position);
            boolean checked = option.equals(selection);
            bindIdentity(row, option);
            row.setActivated(checked);
            ImageView mark = row.findViewById(R.id.browser_mark);
            mark.setImageResource(checked ? R.drawable.ic_browser_selected : R.drawable.ic_browser_unselected);
            TextView details = row.findViewById(R.id.browser_package);
            row.setContentDescription(option.label + ", " + details.getText());
            row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override
                public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(RadioButton.class.getName());
                    info.setCheckable(true);
                    info.setChecked(checked);
                }
            });
            return row;
        }
    }
}

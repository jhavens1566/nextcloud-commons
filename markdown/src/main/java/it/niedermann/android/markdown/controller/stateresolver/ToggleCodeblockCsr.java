package it.niedermann.android.markdown.controller.stateresolver;

import android.content.Context;
import android.text.Spannable;

import androidx.annotation.NonNull;

import it.niedermann.android.markdown.MarkdownUtil;

public class ToggleCodeblockCsr implements CommandStateResolver {

    @Override
    public boolean isEnabled(@NonNull Context context,
                             @NonNull Spannable content,
                             int selectionStart,
                             int selectionEnd) {
        return MarkdownUtil.isSinglelineSelection(content, selectionStart, selectionEnd);
    }

    @Override
    public boolean isActive(@NonNull Context context,
                            @NonNull Spannable content,
                            int selectionStart,
                            int selectionEnd) {
        return false;
    }
}

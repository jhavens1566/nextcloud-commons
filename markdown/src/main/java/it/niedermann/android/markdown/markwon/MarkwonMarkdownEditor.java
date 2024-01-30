package it.niedermann.android.markdown.markwon;

import android.content.Context;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.EditText;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatEditText;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.nextcloud.android.sso.exceptions.NextcloudFilesAppAccountNotFoundException;
import com.nextcloud.android.sso.exceptions.NoCurrentAccountSelectedException;
import com.nextcloud.android.sso.helper.SingleAccountHelper;
import com.nextcloud.android.sso.model.SingleSignOnAccount;

import java.util.function.Consumer;

import io.noties.markwon.Markwon;
import io.noties.markwon.editor.MarkwonEditor;
import io.noties.markwon.editor.handler.EmphasisEditHandler;
import io.noties.markwon.editor.handler.StrongEmphasisEditHandler;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.image.ImagesPlugin;
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin;
import io.noties.markwon.simple.ext.SimpleExtPlugin;
import it.niedermann.android.markdown.MarkdownEditor;
import it.niedermann.android.markdown.markwon.format.ContextBasedFormattingCallback;
import it.niedermann.android.markdown.markwon.format.ContextBasedRangeFormattingCallback;
import it.niedermann.android.markdown.markwon.handler.BlockQuoteEditHandler;
import it.niedermann.android.markdown.markwon.handler.CodeBlockEditHandler;
import it.niedermann.android.markdown.markwon.handler.CodeEditHandler;
import it.niedermann.android.markdown.markwon.handler.HeadingEditHandler;
import it.niedermann.android.markdown.markwon.handler.StrikethroughEditHandler;
import it.niedermann.android.markdown.markwon.plugins.SearchHighlightPlugin;
import it.niedermann.android.markdown.markwon.plugins.ThemePlugin;
import it.niedermann.android.markdown.markwon.textwatcher.CombinedTextWatcher;
import it.niedermann.android.markdown.markwon.textwatcher.SearchHighlightTextWatcher;

public class MarkwonMarkdownEditor extends AppCompatEditText implements MarkdownEditor {

    private static final String TAG = MarkwonMarkdownEditor.class.getSimpleName();

    @Nullable
    private Consumer<CharSequence> listener;
    private final MutableLiveData<CharSequence> unrenderedText$ = new MutableLiveData<>();
    private final CombinedTextWatcher combinedWatcher;

    public MarkwonMarkdownEditor(@NonNull Context context) {
        this(context, null);
    }

    public MarkwonMarkdownEditor(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, android.R.attr.editTextStyle);
    }

    public MarkwonMarkdownEditor(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        final var markwon = createMarkwonBuilder(context).build();
        final var editor = createMarkwonEditorBuilder(markwon).build();

        combinedWatcher = new CombinedTextWatcher(editor, this);
        addTextChangedListener(combinedWatcher);
        setCustomSelectionActionModeCallback(new ContextBasedRangeFormattingCallback(this));
        setCustomInsertionActionModeCallback(new ContextBasedFormattingCallback(this));
    }

    private static Markwon.Builder createMarkwonBuilder(@NonNull Context context) {
        return Markwon.builder(context)
                .usePlugin(ThemePlugin.create(context))
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(SimpleExtPlugin.create())
                .usePlugin(ImagesPlugin.create())
                .usePlugin(MarkwonInlineParserPlugin.create())
                .usePlugin(SearchHighlightPlugin.create(context));
    }

    private static MarkwonEditor.Builder createMarkwonEditorBuilder(@NonNull Markwon markwon) {
        return MarkwonEditor.builder(markwon)
                .useEditHandler(new EmphasisEditHandler())
                .useEditHandler(new StrongEmphasisEditHandler())
                .useEditHandler(new StrikethroughEditHandler())
                .useEditHandler(new CodeEditHandler())
                .useEditHandler(new CodeBlockEditHandler())
                .useEditHandler(new BlockQuoteEditHandler())
                .useEditHandler(new HeadingEditHandler());
    }

    /**
     * @deprecated Use {@link MarkdownEditor#setCurrentSingleSignOnAccount(SingleSignOnAccount, int)}
     * @param color which will be used for highlighting. See {@link #setSearchText(CharSequence)}
     */
    @Override
    @Deprecated(forRemoval = true)
    public void setSearchColor(int color) {
        try {
            final var ssoAccount = SingleAccountHelper.getCurrentSingleSignOnAccount(getContext());
            setCurrentSingleSignOnAccount(ssoAccount, color);
        } catch (NoCurrentAccountSelectedException | NextcloudFilesAppAccountNotFoundException e) {
            setCurrentSingleSignOnAccount(null, color);
        }
    }

    @Override
    public void setCurrentSingleSignOnAccount(@Nullable SingleSignOnAccount account, @ColorInt int color) {
        final var searchHighlightTextWatcher = combinedWatcher.get(SearchHighlightTextWatcher.class);
        if (searchHighlightTextWatcher == null) {
            Log.w(TAG, SearchHighlightTextWatcher.class.getSimpleName() + " is not a registered " + TextWatcher.class.getSimpleName());
        } else {
            searchHighlightTextWatcher.setSearchColor(color);
        }
    }

    @Override
    public void setSearchText(@Nullable CharSequence searchText, @Nullable Integer current) {
        final var searchHighlightTextWatcher = combinedWatcher.get(SearchHighlightTextWatcher.class);
        if (searchHighlightTextWatcher == null) {
            Log.w(TAG, SearchHighlightTextWatcher.class.getSimpleName() + " is not a registered " + TextWatcher.class.getSimpleName());
        } else {
            searchHighlightTextWatcher.setSearchText(searchText, current);
        }
    }

    @Override
    public void setMarkdownString(CharSequence text) {
        setText(text);
        setMarkdownStringModel(text);
    }

    @Override
    public void setMarkdownString(CharSequence text, Runnable afterRender) {
        throw new UnsupportedOperationException("This is not available in " + MarkwonMarkdownEditor.class.getSimpleName() + " because the text is getting rendered all the time.");
    }

    /**
     * Updates the current model which matches the rendered state of the editor *without* triggering
     * anything of the native {@link EditText}
     */
    public void setMarkdownStringModel(CharSequence text) {
        unrenderedText$.setValue(text == null ? "" : text.toString());
        if (listener != null) {
            listener.accept(text);
        }
    }

    @Override
    public LiveData<CharSequence> getMarkdownString() {
        return unrenderedText$;
    }

    @Override
    public void setMarkdownStringChangedListener(@Nullable Consumer<CharSequence> listener) {
        this.listener = listener;
    }
}

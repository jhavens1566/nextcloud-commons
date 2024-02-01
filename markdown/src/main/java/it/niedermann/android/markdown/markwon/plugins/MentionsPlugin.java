package it.niedermann.android.markdown.markwon.plugins;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.util.Log;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.Px;
import androidx.annotation.WorkerThread;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.HttpException;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.Transition;
import com.nextcloud.android.sso.exceptions.NextcloudFilesAppAccountNotFoundException;
import com.nextcloud.android.sso.model.SingleSignOnAccount;

import org.commonmark.node.CustomNode;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.MarkwonConfiguration;
import io.noties.markwon.MarkwonPlugin;
import io.noties.markwon.MarkwonSpansFactory;
import io.noties.markwon.MarkwonVisitor;
import io.noties.markwon.Prop;
import io.noties.markwon.RenderProps;
import io.noties.markwon.SpanFactory;
import io.noties.markwon.inlineparser.InlineProcessor;
import io.noties.markwon.inlineparser.MarkwonInlineParser;
import io.noties.markwon.inlineparser.MarkwonInlineParserContext;
import it.niedermann.android.markdown.MarkdownUtil;
import it.niedermann.android.markdown.R;
import it.niedermann.nextcloud.ocs.ApiProvider;
import it.niedermann.nextcloud.ocs.OcsAPI;

public class MentionsPlugin extends AbstractMarkwonPlugin {

    private static final String TAG = MentionsPlugin.class.getSimpleName();
    /**
     * Map of existing users. Keys are the username, values are the display name
     */
    @NonNull
    private final Map<String, String> userCache = new ConcurrentHashMap<>();
    /**
     * Set of user names which are known to belong to not existing users
     */
    @NonNull
    private final Set<String> noUserCache = ConcurrentHashMap.newKeySet();
    @NonNull
    private final Context context;
    @NonNull
    private final ExecutorService executor;
    @NonNull
    private final AtomicReference<SingleSignOnAccount> ssoAccountRef = new AtomicReference<>();
    @NonNull
    private final AtomicInteger textSizeRef;

    private MentionsPlugin(@NonNull Context context,
                           @NonNull ExecutorService executor,
                           @Px int textSize) {
        this.context = context.getApplicationContext();
        this.executor = executor;
        this.textSizeRef = new AtomicInteger(textSize);
    }

    public static MarkwonPlugin create(@NonNull Context context, @Px int textSize) {
        return new MentionsPlugin(context, Executors.newCachedThreadPool(), textSize);
    }

    @Override
    public void configureParser(@NonNull Parser.Builder builder) {
        try {
            builder.inlineParserFactory(MarkwonInlineParser.factoryBuilder()
                    .addInlineProcessor(new MentionInlineProcessor(ssoAccountRef))
                    .build());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void configureVisitor(@NonNull MarkwonVisitor.Builder builder) {
        builder.on(AvatarNode.class, new AvatarVisitor(ssoAccountRef, textSizeRef));
        builder.on(DisplayNameNode.class, new DisplayNameVisitor());
    }

    @Override
    public void configureSpansFactory(@NonNull MarkwonSpansFactory.Builder builder) {
        builder.setFactory(AvatarNode.class, new AvatarSpanFactory(context));
        builder.setFactory(DisplayNameNode.class, new DisplayNameSpanFactory());
    }

    @Override
    public void beforeSetText(@NonNull TextView textView, @NonNull Spanned markdown) {
        super.beforeSetText(textView, markdown);
    }

    @Override
    public void afterSetText(@NonNull TextView textView) {
        super.afterSetText(textView);
        final var ssoAccount = ssoAccountRef.get();
        if (ssoAccount != null) {
            executor.submit(() -> {
                try {
                    final var initialSpannable = MarkdownUtil.getContentAsSpannable(textView);
                    final var spannableWithDisplayNames = replaceUserNames(textView.getContext(), initialSpannable, ssoAccount);
                    final var finalSpannable = replaceAvatars(textView.getContext(), spannableWithDisplayNames);

                    textView.post(() -> textView.setText(finalSpannable));
                } catch (InterruptedException | NextcloudFilesAppAccountNotFoundException e) {
                    e.printStackTrace();
                }
            });
        }
    }

    public void setCurrentSingleSignOnAccount(@Nullable SingleSignOnAccount ssoAccount) {
        userCache.clear();
        noUserCache.clear();
        ssoAccountRef.set(ssoAccount);
    }

    public void setTextSize(@Px int textSize) {
        textSizeRef.set(textSize);
    }

    static class MentionInlineProcessor extends InlineProcessor {

        @NonNull
        private final AtomicReference<SingleSignOnAccount> ssoAccountRef;

        private MentionInlineProcessor(@NonNull AtomicReference<SingleSignOnAccount> ssoAccountRef) {
            this.ssoAccountRef = ssoAccountRef;
        }

        private static final Pattern REGEX_MENTION = Pattern.compile("\\B@\\w+");

        @Override
        public char specialCharacter() {
            return '@';
        }

        protected Node parse() {
            if (ssoAccountRef.get() == null) {
                return null;
            }

            final String userId = match(REGEX_MENTION);
            if (userId != null) {
                return new MentionNode(super.context, userId.substring(1));
            }

            return null;
        }
    }

    static class MentionNode extends CustomNode {

        public MentionNode(@NonNull MarkwonInlineParserContext context,
                           @NonNull String userId) {
            appendChild(new AvatarNode(context, userId));
            appendChild(new DisplayNameNode(context, userId));
        }
    }

    static class AvatarNode extends CustomNode {
        public final String userId;

        public AvatarNode(@NonNull MarkwonInlineParserContext context, @NonNull String userId) {
            this.userId = userId;
            appendChild(context.text("@"));
        }
    }

    static class DisplayNameNode extends CustomNode {
        public final String userId;

        public DisplayNameNode(@NonNull MarkwonInlineParserContext context, @NonNull String userId) {
            this.userId = userId;
            appendChild(context.text(userId));
        }
    }

    private record AvatarVisitor(
            @NonNull AtomicReference<SingleSignOnAccount> ssoAccountRef,
            @NonNull AtomicInteger textSizeRef) implements MarkwonVisitor.NodeVisitor<AvatarNode> {

        @Override
        public void visit(@NonNull MarkwonVisitor visitor, @NonNull AvatarNode avatarNode) {
            final var ssoAccount = ssoAccountRef.get();

            if (ssoAccount == null) {
                Log.w(TAG, AvatarVisitor.class.getSimpleName() + " tried to process avatars, but " + SingleSignOnAccount.class.getSimpleName() + " is null");
                return;
            }

            final int start = visitor.length();
            MentionProps.MENTION_AVATAR_USER_ID_PROPS.set(visitor.renderProps(), avatarNode.userId);
            MentionProps.MENTION_AVATAR_URL_PROPS.set(visitor.renderProps(), getAvatarUrl(ssoAccount, avatarNode.userId, (int) (textSizeRef.get() * 1.5)));
            visitor.visitChildren(avatarNode);
            visitor.setSpansForNodeOptional(avatarNode, start);
        }

        private String getAvatarUrl(@NonNull SingleSignOnAccount ssoAccount, @NonNull String userId, @Px int size) {
            return ssoAccount.url + "/index.php/avatar/" + Uri.encode(userId) + "/" + size;
        }
    }

    private static class DisplayNameVisitor implements MarkwonVisitor.NodeVisitor<DisplayNameNode> {
        @Override
        public void visit(@NonNull MarkwonVisitor visitor, @NonNull DisplayNameNode displayNameNode) {
            final int start = visitor.length();
            MentionProps.MENTION_DISPLAY_NAME_USER_ID_PROPS.set(visitor.renderProps(), displayNameNode.userId);
            visitor.visitChildren(displayNameNode);
            visitor.setSpansForNodeOptional(displayNameNode, start);
        }
    }

    private static class AvatarSpanFactory implements SpanFactory {
        @NonNull
        private final Context context;

        private AvatarSpanFactory(@NonNull Context context) {
            this.context = context;
        }

        @Nullable
        @Override
        public Object getSpans(@NonNull MarkwonConfiguration configuration, @NonNull RenderProps props) {
            return new AvatarSpan(context, MentionProps.MENTION_AVATAR_USER_ID_PROPS.require(props), MentionProps.MENTION_AVATAR_URL_PROPS.require(props));
        }
    }

    private static class DisplayNameSpanFactory implements SpanFactory {
        @Nullable
        @Override
        public Object getSpans(@NonNull MarkwonConfiguration configuration, @NonNull RenderProps props) {
            return new DisplayNameSpan(MentionProps.MENTION_DISPLAY_NAME_USER_ID_PROPS.require(props));
        }
    }

    private static class AvatarSpan extends ImageSpan {
        public final String userId;
        public final String url;

        public AvatarSpan(@NonNull Context context, @NonNull String userId, @NonNull String url) {
            super(context, R.drawable.ic_baseline_account_circle_24dp);
            this.userId = userId;
            this.url = url;
        }
    }

    private record DisplayNameSpan(@NonNull String userId) {
    }

    private abstract static class MentionProps {
        public static final Prop<String> MENTION_AVATAR_URL_PROPS = Prop.of("mention-avatar-user-id");
        public static final Prop<String> MENTION_AVATAR_USER_ID_PROPS = Prop.of("mention-avatar-url");
        public static final Prop<String> MENTION_DISPLAY_NAME_USER_ID_PROPS = Prop.of("mention-display-name-user-id");

        private MentionProps() {
        }
    }

    @WorkerThread
    private Spannable replaceUserNames(@NonNull Context context, @NonNull Spannable spannable, @NonNull SingleSignOnAccount ssoAccount) throws InterruptedException, NextcloudFilesAppAccountNotFoundException {
        final var userNames = findUserNames(spannable);
        final var displayNames = fetchDisplayNames(context, ssoAccount, userNames);
        final var spannableStringBuilder = new SpannableStringBuilder(spannable);
        final var displayNameSpans = Arrays.stream(spannable.getSpans(0, spannable.length(), DisplayNameSpan.class))
                .filter(displayNameSpan -> displayNames.containsKey(displayNameSpan.userId))
                .toArray(DisplayNameSpan[]::new);

        for (final var span : displayNameSpans) {
            final var displayName = Objects.requireNonNull(displayNames.get(span.userId));
            final int start = spannable.getSpanStart(span);
            final int end = spannable.getSpanEnd(span);

            spannableStringBuilder.replace(start, end, displayName);
        }
        return spannableStringBuilder;
    }

    public Set<String> findUserNames(@NonNull Spannable spannable) {
        return Arrays.stream(spannable.getSpans(0, spannable.length(), DisplayNameSpan.class))
                .map(span -> span.userId)
                .collect(Collectors.toUnmodifiableSet());
    }

    @WorkerThread
    @NonNull
    public Map<String, String> fetchDisplayNames(@NonNull Context context,
                                                 @NonNull SingleSignOnAccount ssoAccount,
                                                 @NonNull Set<String> potentialUserNames) throws NextcloudFilesAppAccountNotFoundException, InterruptedException {
        if (potentialUserNames.isEmpty()) {
            return Collections.emptyMap();
        }

        final var result = new ConcurrentHashMap<String, String>(potentialUserNames.size());
        final var latch = new CountDownLatch(potentialUserNames.size());
        final var apiFactory = new ApiProvider.Factory();
        try (final var apiProvider = apiFactory.createApiProvider(context, ssoAccount, OcsAPI.class, "/ocs/v2.php/cloud/")) {
            for (final var potentialUsername : potentialUserNames) {
                fetchDisplayName(apiProvider.getApi(), potentialUsername).whenComplete((displayName, exception) -> {
                    if (exception == null) {
                        if (displayName != null) {
                            result.put(potentialUsername, displayName);
                        } else {
                            Log.v(TAG, "Username " + potentialUsername + " does not have a displayName");
                        }
                    } else {
                        Log.w(TAG, "Could not fetch display name for " + potentialUsername + ", " + exception.getMessage());
                    }
                    latch.countDown();
                });
            }
            latch.await();
        }
        return result;
    }

    @NonNull
    public CompletableFuture<String> fetchDisplayName(@NonNull OcsAPI ocsApi,
                                                      @NonNull String potentialUsername) {
        if (userCache.containsKey(potentialUsername)) {
            return CompletableFuture.completedFuture(userCache.get(potentialUsername));
        }

        final var future = new CompletableFuture<String>();

        if (noUserCache.contains(potentialUsername)) {
            future.complete(null);
            return future;
        }

        final var call = ocsApi.getUser(potentialUsername);

        executor.submit(() -> {
            try {
                final var response = call.execute();
                if (response.isSuccessful()) {
                    final var body = response.body();
                    if (body == null) {
                        future.completeExceptionally(new RuntimeException("Response body for " + potentialUsername + " was null."));

                    } else {
                        final var displayName = body.ocs.data.displayName;
                        userCache.putIfAbsent(potentialUsername, displayName);
                        future.complete(displayName);
                    }
                } else {
                    if (response.code() == HttpURLConnection.HTTP_NOT_FOUND) {
                        noUserCache.add(potentialUsername);
                    }

                    future.completeExceptionally(new RuntimeException("HTTP " + response.code() + ": " + response.message() + " (" + potentialUsername + ")"));
                }
            } catch (IOException e) {
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    @WorkerThread
    private Spannable replaceAvatars(@NonNull Context context, @NonNull Spannable spannable) throws InterruptedException {
        final var avatarSpans = Arrays.stream(spannable.getSpans(0, spannable.length(), AvatarSpan.class))
                .filter(span -> !noUserCache.contains(span.userId))
                .toArray(AvatarSpan[]::new);

        final var spannableStringBuilder = new SpannableStringBuilder(spannable);

        final var latch = new CountDownLatch(avatarSpans.length);
        for (final var span : avatarSpans) {
            final var spanStart = spannable.getSpanStart(span);
            final var spanEnd = spannable.getSpanEnd(span);

            Glide.with(context)
                    .asBitmap()
//                    .placeholder(R.drawable.ic_baseline_account_circle_24dp)
                    .load(span.url)
                    .apply(RequestOptions.circleCropTransform())
                    .listener(new RequestListener<>() {
                        @Override
                        public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Bitmap> target, boolean isFirstResource) {
                            latch.countDown();

                            if (e != null) {
                                final var causes = e.getRootCauses();
                                for (final var cause : causes) {
                                    if (cause instanceof HttpException httpException) {
                                        if (httpException.getStatusCode() == HttpURLConnection.HTTP_NOT_FOUND) {
                                            spannableStringBuilder.removeSpan(span); // FIXME does not work as expected, placeholder stays
                                            noUserCache.add(span.userId);
                                            return false;
                                        }
                                    }
                                }
                            }

                            spannableStringBuilder.setSpan(new ImageSpan(context, R.drawable.ic_baseline_broken_image_24), spanStart, spanEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                            return false;
                        }

                        @Override
                        public boolean onResourceReady(Bitmap resource, Object model, Target<Bitmap> target, DataSource dataSource, boolean isFirstResource) {
                            return false;
                        }
                    })
                    .into(new CustomTarget<Bitmap>() {
                        @Override
                        public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                            spannableStringBuilder.setSpan(new ImageSpan(context, resource), spanStart, spanEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                            latch.countDown();
                        }

                        @Override
                        public void onLoadCleared(@Nullable Drawable placeholder) {
                            // silence is gold
                        }
                    });
        }
        latch.await();
        return spannableStringBuilder;
    }
}

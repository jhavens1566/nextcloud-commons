package it.niedermann.android.markdown.markwon.plugins.mentions;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Px;

import com.nextcloud.android.sso.model.SingleSignOnAccount;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.noties.markwon.MarkwonVisitor;

record AvatarPlaceholderVisitor(
        @NonNull AtomicReference<SingleSignOnAccount> ssoAccountRef,
        @NonNull AtomicInteger avatarSizeRef) implements MarkwonVisitor.NodeVisitor<AvatarPlaceholderNode> {

    private static final String TAG = AvatarPlaceholderVisitor.class.getSimpleName();

    @Override
    public void visit(@NonNull MarkwonVisitor visitor, @NonNull AvatarPlaceholderNode avatarNode) {
        final var ssoAccount = ssoAccountRef.get();

        if (ssoAccount == null) {
            Log.w(TAG, AvatarPlaceholderVisitor.class.getSimpleName() + " tried to process avatars, but " + SingleSignOnAccount.class.getSimpleName() + " is null");
            return;
        }

        final int start = visitor.length();
        MentionProps.MENTION_AVATAR_USER_ID_PROPS.set(visitor.renderProps(), avatarNode.userId);
        MentionProps.MENTION_AVATAR_URL_PROPS.set(visitor.renderProps(), getAvatarUrl(ssoAccount, avatarNode.userId, avatarSizeRef.get()));
        visitor.visitChildren(avatarNode);
        visitor.setSpansForNodeOptional(avatarNode, start);
    }

    private String getAvatarUrl(@NonNull SingleSignOnAccount ssoAccount, @NonNull String userId, @Px int size) {
        return ssoAccount.url + "/index.php/avatar/" + Uri.encode(userId) + "/" + size;
    }
}
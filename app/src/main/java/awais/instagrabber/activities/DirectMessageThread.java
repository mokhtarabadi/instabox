package awais.instagrabber.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import awais.instagrabber.R;
import awais.instagrabber.adapters.MessageItemsAdapter;
import awais.instagrabber.asyncs.direct_messages.CommentAction;
import awais.instagrabber.asyncs.direct_messages.UserInboxFetcher;
import awais.instagrabber.customviews.helpers.RecyclerLazyLoader;
import awais.instagrabber.databinding.ActivityDmsBinding;
import awais.instagrabber.interfaces.FetchListener;
import awais.instagrabber.models.PostModel;
import awais.instagrabber.models.ProfileModel;
import awais.instagrabber.models.StoryModel;
import awais.instagrabber.models.direct_messages.DirectItemModel;
import awais.instagrabber.models.direct_messages.InboxThreadModel;
import awais.instagrabber.models.enums.DirectItemType;
import awais.instagrabber.models.enums.DownloadMethod;
import awais.instagrabber.models.enums.UserInboxDirection;
import awais.instagrabber.utils.Constants;
import awais.instagrabber.utils.Utils;

public final class DirectMessageThread extends BaseLanguageActivity {
    private DirectItemModel directItemModel;
    private String threadId;
    private String endCursor;
    private ActivityDmsBinding dmsBinding;
    private MessageItemsAdapter messageItemsAdapter;

    private final ProfileModel myProfileHolder = ProfileModel.getDefaultProfileModel();
    private final ArrayList<ProfileModel> users = new ArrayList<>();
    private final ArrayList<ProfileModel> leftUsers = new ArrayList<>();
    private final ArrayList<DirectItemModel> directItemModels = new ArrayList<>();
    private final FetchListener<InboxThreadModel> fetchListener = new FetchListener<InboxThreadModel>() {
        @Override
        public void doBefore() {
            dmsBinding.swipeRefreshLayout.setRefreshing(true);
        }

        @Override
        public void onResult(final InboxThreadModel result) {
            if (result == null && ("MINCURSOR".equals(endCursor) || "MAXCURSOR".equals(endCursor) || Utils.isEmpty(endCursor)))
                Toast.makeText(DirectMessageThread.this, R.string.downloader_unknown_error, Toast.LENGTH_SHORT).show();

            if (result != null) {
                endCursor = result.getPrevCursor();
                if ("MINCURSOR".equals(endCursor) || "MAXCURSOR".equals(endCursor))
                    endCursor = null;

                users.clear();
                users.addAll(Arrays.asList(result.getUsers()));

                leftUsers.clear();
                leftUsers.addAll(Arrays.asList(result.getLeftUsers()));

                threadId = result.getThreadId();
                dmsBinding.toolbar.toolbar.setTitle(result.getThreadTitle());
                String[] users = new String[result.getUsers().length];
                for (int i = 0; i < users.length; ++i) {
                    users[i] = result.getUsers()[i].getUsername();
                }
                dmsBinding.toolbar.toolbar.setSubtitle(String.join(", ", users));

                final int oldSize = directItemModels.size();
                final List<DirectItemModel> itemModels = Arrays.asList(result.getItems());
                directItemModels.addAll(itemModels);
                messageItemsAdapter.notifyItemRangeInserted(oldSize, itemModels.size());
            }

            dmsBinding.swipeRefreshLayout.setRefreshing(false);
        }
    };
    private final View.OnClickListener newCommentListener = v -> {
        if (Utils.isEmpty(dmsBinding.commentText.getText().toString())) {
            Toast.makeText(getApplicationContext(), R.string.comment_send_empty_comment, Toast.LENGTH_SHORT).show();
            return;
        }
        final CommentAction action = new CommentAction(dmsBinding.commentText.getText().toString(), threadId);
        action.setOnTaskCompleteListener(result -> {
            if (!result) {
                Toast.makeText(getApplicationContext(), R.string.downloader_unknown_error, Toast.LENGTH_SHORT).show();
                return;
            }
            dmsBinding.commentText.setText("");
            dmsBinding.commentText.clearFocus();
            directItemModels.clear();
            messageItemsAdapter.notifyDataSetChanged();
            new UserInboxFetcher(threadId, UserInboxDirection.OLDER, null, fetchListener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        });
        action.execute();
    };

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dmsBinding = ActivityDmsBinding.inflate(getLayoutInflater());
        setContentView(dmsBinding.getRoot());

        final InboxThreadModel threadModel;
        final Intent intent = getIntent();
        if (intent == null || !intent.hasExtra(Constants.EXTRAS_THREAD_MODEL) ||
                (threadModel = (InboxThreadModel) intent.getSerializableExtra(Constants.EXTRAS_THREAD_MODEL)) == null) {
            Utils.errorFinish(this);
            return;
        }

        dmsBinding.swipeRefreshLayout.setEnabled(false);
        dmsBinding.commentText.setVisibility(View.VISIBLE);
        dmsBinding.commentSend.setVisibility(View.VISIBLE);
        dmsBinding.commentSend.setOnClickListener(newCommentListener);

        final LinearLayoutManager layoutManager = new LinearLayoutManager(this, RecyclerView.VERTICAL, true);
        dmsBinding.rvDirectMessages.setLayoutManager(layoutManager);

        dmsBinding.rvDirectMessages.addOnScrollListener(new RecyclerLazyLoader(layoutManager, (page, totalItemsCount) -> {
            if (!Utils.isEmpty(endCursor)) {
                new UserInboxFetcher(threadModel.getThreadId(), UserInboxDirection.OLDER,
                        endCursor, fetchListener).execute(); // serial because we don't want messages to be randomly ordered
            }
        }));

        messageItemsAdapter = new MessageItemsAdapter(directItemModels, users, leftUsers, v -> {
            Object tag = v.getTag();
            if (tag instanceof DirectItemModel) {
                directItemModel = (DirectItemModel) tag;
                final DirectItemType itemType = directItemModel.getItemType();
                switch (itemType) {
                    case MEDIA_SHARE:
                        startActivity(new Intent(this, PostViewer.class)
                                .putExtra(Constants.EXTRAS_POST, new PostModel(directItemModel.getMediaModel().getCode(), false)));
                        break;
                    case LINK:
                        Intent linkIntent = new Intent(Intent.ACTION_VIEW);
                        linkIntent.setData(Uri.parse(directItemModel.getLinkModel().getLinkContext().getLinkUrl()));
                        startActivity(linkIntent);
                        break;
                    case TEXT:
                    case REEL_SHARE:
                        Utils.copyText(v.getContext(), directItemModel.getText());
                        Toast.makeText(v.getContext(), R.string.clipboard_copied, Toast.LENGTH_SHORT).show();
                        break;
                    case RAVEN_MEDIA:
                    case MEDIA:
                        Utils.dmDownload(this, getUser(directItemModel.getUserId()).getUsername(), DownloadMethod.DOWNLOAD_DIRECT,
                                Collections.singletonList(itemType == DirectItemType.MEDIA ? directItemModel.getMediaModel() : directItemModel.getRavenMediaModel().getMedia()));
                        Toast.makeText(v.getContext(), R.string.downloader_downloading_media, Toast.LENGTH_SHORT).show();
                        break;
                    case STORY_SHARE:
                        if (directItemModel.getReelShare() != null) {
                            StoryModel sm = new StoryModel(
                                    directItemModel.getReelShare().getReelId(),
                                    directItemModel.getReelShare().getMedia().getVideoUrl(),
                                    directItemModel.getReelShare().getMedia().getMediaType(),
                                    directItemModel.getTimestamp(),
                                    directItemModel.getReelShare().getReelOwnerName(),
                                    String.valueOf(directItemModel.getReelShare().getReelOwnerId()),
                                    false
                            );
                            sm.setVideoUrl(directItemModel.getReelShare().getMedia().getVideoUrl());
                            StoryModel[] sms = {sm};
                            startActivity(new Intent(this, StoryViewer.class)
                                    .putExtra(Constants.EXTRAS_USERNAME, directItemModel.getReelShare().getReelOwnerName())
                                    .putExtra(Constants.EXTRAS_STORIES, sms)
                            );
                        } else if (directItemModel.getText() != null && directItemModel.getText().toString().contains("@")) {
                            searchUsername(directItemModel.getText().toString().split("@")[1].split(" ")[0]);
                        }
                        break;
                    case PLACEHOLDER:
                        if (directItemModel.getText().toString().contains("@"))
                            searchUsername(directItemModel.getText().toString().split("@")[1].split(" ")[0]);
                        break;
                    default:
                        Log.d("austin_debug", "unsupported type " + itemType);
                }
            }
        }, (view, text, isHashtag) -> searchUsername(text));

        dmsBinding.rvDirectMessages.setAdapter(messageItemsAdapter);

        new UserInboxFetcher(threadModel.getThreadId(), UserInboxDirection.OLDER, null, fetchListener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @Nullable
    private ProfileModel getUser(final long userId) {
        if (users != null) {
            ProfileModel result = myProfileHolder;
            for (final ProfileModel user : users) {
                if (Long.toString(userId).equals(user.getId())) result = user;
            }
            if (leftUsers != null)
                for (final ProfileModel leftUser : leftUsers) {
                    if (Long.toString(userId).equals(leftUser.getId())) result = leftUser;
                }
            return result;
        }
        return null;
    }

    private void searchUsername(final String text) {
        startActivity(new Intent(getApplicationContext(), ProfileViewer.class).putExtra(Constants.EXTRAS_USERNAME, text));
    }
}
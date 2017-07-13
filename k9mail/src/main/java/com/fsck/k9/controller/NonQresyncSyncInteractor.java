package com.fsck.k9.controller;


import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.fsck.k9.Account;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.store.imap.ImapFolder;
import com.fsck.k9.mail.store.imap.ImapMessage;
import com.fsck.k9.mailstore.LocalFolder;
import timber.log.Timber;


class NonQresyncSyncInteractor {

    static int performSync(Account account, LocalFolder localFolder, ImapFolder imapFolder, MessagingListener listener,
            MessagingController controller, MessageDownloader messageDownloader) throws MessagingException, IOException {
        Map<String, Long> localUidMap = localFolder.getAllMessagesAndEffectiveDates();
        String folderName = localFolder.getName();
        final List<ImapMessage> remoteMessages = new ArrayList<>();
        Map<String, ImapMessage> remoteUidMap = new HashMap<>();

        int remoteMessageCount = imapFolder.getMessageCount();
        final Date earliestDate = account.getEarliestPollDate();
        int remoteStart = ImapSyncInteractor.getRemoteStart(localFolder, imapFolder);

        Timber.v("SYNC: About to get messages %d through %d for folder %s",
                remoteStart, remoteMessageCount, folderName);

        findRemoteMessagesToDownload(account, imapFolder, localUidMap, remoteMessages, remoteUidMap, remoteStart,
                listener, controller);

        /*
         * Remove any messages that are in the local store but no longer on the remote store or are too old
         */
        if (account.syncRemoteDeletions()) {
            ImapSyncInteractor.syncRemoteDeletions(account, localFolder, imapFolder,
                    findDeletedMessageUids(localUidMap, remoteUidMap), listener, controller);
        }

        // noinspection UnusedAssignment, free memory early?
        localUidMap = null;

        /*
         * Now we download the actual content of messages.
         */
        boolean useCondstore = false;
        long cachedHighestModSeq = localFolder.getHighestModSeq();
        if (cachedHighestModSeq != 0 && imapFolder.supportsModSeq()) {
            useCondstore = true;
        }
        int newMessages = messageDownloader.downloadMessages(account, imapFolder, localFolder, remoteMessages, false,
                true, !useCondstore);
        if (useCondstore) {
            Timber.v("SYNC: About to get messages having modseq greater that %d for IMAP folder %s",
                    cachedHighestModSeq, folderName);
            List<ImapMessage> syncFlagMessages = imapFolder.getChangedMessagesUsingCondstore(cachedHighestModSeq);
            newMessages += messageDownloader.downloadMessages(account, imapFolder, localFolder, syncFlagMessages,
                    false, true, true);
        }
        return newMessages;
    }

    private static void findRemoteMessagesToDownload(Account account, ImapFolder imapFolder,
            Map<String, Long> localUidMap, List<ImapMessage> remoteMessages, Map<String, ImapMessage> remoteUidMap,
            int remoteStart, MessagingListener listener, MessagingController controller)
            throws MessagingException {

        String folderName = imapFolder.getName();
        final Date earliestDate = account.getEarliestPollDate();
        long earliestTimestamp = earliestDate != null ? earliestDate.getTime() : 0L;
        final AtomicInteger headerProgress = new AtomicInteger(0);
        int remoteMessageCount = imapFolder.getMessageCount();

        for (MessagingListener l : controller.getListeners(listener)) {
            l.synchronizeMailboxHeadersStarted(account, folderName);
        }

        List<ImapMessage> remoteMessageArray = imapFolder.getMessages(remoteStart, remoteMessageCount, earliestDate, null);

        int messageCount = remoteMessageArray.size();

        for (ImapMessage thisMess : remoteMessageArray) {
            headerProgress.incrementAndGet();
            for (MessagingListener l : controller.getListeners(listener)) {
                l.synchronizeMailboxHeadersProgress(account, folderName, headerProgress.get(), messageCount);
            }
            Long localMessageTimestamp = localUidMap.get(thisMess.getUid());
            if (localMessageTimestamp == null || localMessageTimestamp >= earliestTimestamp) {
                remoteMessages.add(thisMess);
                remoteUidMap.put(thisMess.getUid(), thisMess);
            }
        }

        Timber.v("SYNC: Got %d messages for folder %s", remoteUidMap.size(), folderName);

        for (MessagingListener l : controller.getListeners(listener)) {
            l.synchronizeMailboxHeadersFinished(account, folderName, headerProgress.get(), remoteUidMap.size());
        }
    }

    private static List<String> findDeletedMessageUids(Map<String, Long> localUidMap,
            Map<String, ImapMessage> remoteUidMap) {
        List<String> deletedMessageUids = new ArrayList<>();
        for (String localMessageUid : localUidMap.keySet()) {
            if (remoteUidMap.get(localMessageUid) == null) {
                deletedMessageUids.add(localMessageUid);
            }
        }
        return deletedMessageUids;
    }
}

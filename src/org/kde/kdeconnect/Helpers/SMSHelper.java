/*
 * SPDX-FileCopyrightText: 2021 Simon Redman <simon@ergotech.com>
 * SPDX-FileCopyrightText: 2020 Aniket Kumar <anikketkumar786@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/

package org.kde.kdeconnect.Helpers;

import android.annotation.SuppressLint;
import android.content.ContentUris;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Build;
import android.os.Looper;
import android.provider.Telephony;
import android.telephony.PhoneNumberUtils;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.google.android.mms.pdu_alt.MultimediaMessagePdu;
import com.google.android.mms.pdu_alt.PduPersister;
import com.google.android.mms.util_alt.PduCache;
import com.google.android.mms.util_alt.PduCacheEntry;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.kde.kdeconnect.Plugins.SMSPlugin.MimeType;
import org.kde.kdeconnect.Plugins.SMSPlugin.SmsMmsUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import kotlin.text.Charsets;

@SuppressLint("InlinedApi")
public class SMSHelper {

    private static final int THUMBNAIL_HEIGHT = 100;
    private static final int THUMBNAIL_WIDTH = 100;

    /**
     * Get a URI for querying SMS messages
     */
    private static Uri getSMSUri() {
        // This constant was introduces with API 19 (KitKat)
        // The value it represents was used in older Android versions so it *should* work but
        // might vary between vendors.
        return Telephony.Sms.CONTENT_URI;
    }

    private static Uri getMMSUri() {
        // Same warning as getSMSUri: This constant was introduced with API 19
        return Telephony.Mms.CONTENT_URI;
    }

    public static Uri getMMSPartUri() {
        // Android says we should have Telephony.Mms.Part.CONTENT_URI. Alas, we do not.
        return Uri.parse("content://mms/part/");
    }

    /**
     * Get the base address for all message conversations
     * We only use this to fetch thread_ids because the data it returns if often incomplete or useless
     */
    private static Uri getConversationUri() {

        // Special case for Samsung
        // For some reason, Samsung devices do not support the regular SmsMms column.
        // However, according to https://stackoverflow.com/a/13640868/3723163, we can work around it this way.
        // By my understanding, "simple=true" means we can't support multi-target messages.
        // Go complain to Samsung about their annoying OS changes!
        if ("Samsung".equalsIgnoreCase(Build.MANUFACTURER)) {
            Log.i("SMSHelper", "This appears to be a Samsung device. This may cause some features to not work properly.");
        }

        return Uri.parse("content://mms-sms/conversations?simple=true");
    }

    private static Uri getCompleteConversationsUri() {
        // This glorious - but completely undocumented - content URI gives us all messages, both MMS and SMS,
        // in all conversations
        // See https://stackoverflow.com/a/36439630/3723163
        return Uri.parse("content://mms-sms/complete-conversations");
    }

    /**
     * Column used to discriminate between SMS and MMS messages
     * Unfortunately, this column is not defined for Telephony.MmsSms.CONTENT_CONVERSATIONS_URI
     * (aka. content://mms-sms/conversations)
     * which gives us the first message in every conversation, but it *is* defined for
     * content://mms-sms/conversations/<threadID> which gives us the complete conversation matching
     * that threadID, so at least it's partially useful to us.
     */
    private static String getTransportTypeDiscriminatorColumn() {
        return Telephony.MmsSms.TYPE_DISCRIMINATOR_COLUMN;
    }

    /**
     * Get some or all the messages in a requested thread, starting with the most-recent message
     *
     * @param context  android.content.Context running the request
     * @param threadID Thread to look up
     * @param numberToGet Number of messages to return. Pass null for "all"
     * @return List of all messages in the thread
     */
    public static @NonNull List<Message> getMessagesInThread(
            @NonNull Context context,
            @NonNull ThreadID threadID,
            @Nullable Long numberToGet
    ) {
        return getMessagesInRange(context, threadID, Long.MAX_VALUE, numberToGet, true);
    }

    /**
     * Get some messages in the given thread based on a start timestamp and an optional count
     *
     * @param context  android.content.Context running the request
     * @param threadID Optional ThreadID to look up. If not included, this method will return the latest messages from all threads.
     * @param startTimestamp Beginning of the range to return
     * @param numberToGet Number of messages to return. Pass null for "all"
     * @param getMessagesOlderStartTime If true, get messages with timestamps before the startTimestamp. If false, get newer messages
     * @return Some messages in the requested conversation
     */
    @SuppressLint("NewApi")
    public static @NonNull List<Message> getMessagesInRange(
            @NonNull Context context,
            @Nullable ThreadID threadID,
            @NonNull Long startTimestamp,
            @Nullable Long numberToGet,
            @NonNull Boolean getMessagesOlderStartTime
    ) {
        // The stickiness with this is that Android's MMS database has its timestamp in epoch *seconds*
        // while the SMS database uses epoch *milliseconds*.
        // I can think of no way around this other than manually querying each one with a different
        // "WHERE" statement.
        Uri smsUri = getSMSUri();
        Uri mmsUri = getMMSUri();

        List<String> allSmsColumns = new ArrayList<>(Arrays.asList(Message.smsColumns));
        List<String> allMmsColumns = new ArrayList<>(Arrays.asList(Message.mmsColumns));

        if (getSubscriptionIdSupport(smsUri, context)) {
            allSmsColumns.addAll(Arrays.asList(Message.multiSIMColumns));
        }

        if (getSubscriptionIdSupport(mmsUri, context)) {
            allMmsColumns.addAll(Arrays.asList(Message.multiSIMColumns));
        }

        String selection;

        if (getMessagesOlderStartTime) {
            selection = Message.DATE + " <= ?";
        } else {
            selection = Message.DATE + " >= ?";
        }

        List<String> smsSelectionArgs = new ArrayList<>(2);
        smsSelectionArgs.add(startTimestamp.toString());

        List<String> mmsSelectionArgs = new ArrayList<>(2);
        mmsSelectionArgs.add(Long.toString(startTimestamp / 1000));

        if (threadID != null) {
            selection += " AND " + Message.THREAD_ID + " = ?";
            smsSelectionArgs.add(threadID.toString());
            mmsSelectionArgs.add(threadID.toString());
        }

        String sortOrder = Message.DATE + " DESC";

        List<Message> allMessages = getMessages(smsUri, context, allSmsColumns, selection, smsSelectionArgs.toArray(new String[0]), sortOrder, numberToGet);
        allMessages.addAll(getMessages(mmsUri, context, allMmsColumns, selection, mmsSelectionArgs.toArray(new String[0]), sortOrder, numberToGet));

        // Need to now only return the requested number of messages:
        // Suppose we were requested to return N values and suppose a user sends only one MMS per
        // week and N SMS per day. We have requested the same N for each, so if we just return everything
        // we would return some very old MMS messages which would be very confusing.
        SortedMap<Long, Collection<Message>> sortedMessages = new TreeMap<>(Comparator.reverseOrder());
        for (Message message : allMessages) {
            Collection<Message> existingMessages = sortedMessages.computeIfAbsent(message.date,
                    key -> new ArrayList<>());
            existingMessages.add(message);
        }

        List<Message> toReturn = new ArrayList<>(allMessages.size());

        for (Collection<Message> messages : sortedMessages.values()) {
            toReturn.addAll(messages);
            if (numberToGet != null && toReturn.size() >= numberToGet) {
                break;
            }
        }

        return toReturn;
    }

    /**
     * Checks if device supports `Telephony.Sms.SUBSCRIPTION_ID` column in database with URI `uri`
     *
     * @param uri Uri indicating the messages database to check
     * @param context android.content.Context running the request.
     */
    private static boolean getSubscriptionIdSupport(@NonNull Uri uri, @NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
            return false;
        }
        // Some (Xiaomi) devices running >= Android Lollipop (SDK 22+) don't support
        // `Telephony.Sms.SUBSCRIPTION_ID`, so additional check is needed.
        // It may be possible to use "sim_id" instead of "sub_id" on these devices
        // https://stackoverflow.com/a/38152331/6509200
        try (Cursor availableColumnsCursor = context.getContentResolver().query(
                uri,
                new String[] {Telephony.Sms.SUBSCRIPTION_ID},
                null,
                null,
                null)
        ) {
            return availableColumnsCursor != null; // if we got the cursor, the query shouldn't fail
        } catch (SQLiteException | IllegalArgumentException e) {
            // With uri content://mms-sms/conversations this query throws an exception if sub_id is not supported
            return !StringUtils.contains(e.getMessage(), Telephony.Sms.SUBSCRIPTION_ID);
        }
    }

    /**
     * Gets messages which match the selection
     *
     * @param uri Uri indicating the messages database to read
     * @param context android.content.Context running the request.
     * @param fetchColumns List of columns to fetch
     * @param selection Parameterizable filter to use with the ContentResolver query. May be null.
     * @param selectionArgs Parameters for selection. May be null.
     * @param sortOrder Sort ordering passed to Android's content resolver. May be null for unspecified
     * @param numberToGet Number of things to get from the result. Pass null to get all
     * @return Returns List<Message> of all messages in the return set, either in the order of sortOrder or in an unspecified order
     */
    private static @NonNull List<Message> getMessages(
            @NonNull Uri uri,
            @NonNull Context context,
            @NonNull Collection<String> fetchColumns,
            @Nullable String selection,
            @Nullable String[] selectionArgs,
            @Nullable String sortOrder,
            @Nullable Long numberToGet
    ) {
        List<Message> toReturn = new ArrayList<>();

        // Get all the active phone numbers so we can filter the user out of the list of targets
        // of any MMSes
        List<TelephonyHelper.LocalPhoneNumber> userPhoneNumbers = TelephonyHelper.getAllPhoneNumbers(context);

        try (Cursor myCursor = context.getContentResolver().query(
                uri,
                fetchColumns.toArray(ArrayUtils.EMPTY_STRING_ARRAY),
                selection,
                selectionArgs,
                sortOrder)
        ) {
            if (myCursor != null && myCursor.moveToFirst()) {
                do {
                    int transportTypeColumn = myCursor.getColumnIndex(getTransportTypeDiscriminatorColumn());

                    TransportType transportType;
                    if (transportTypeColumn < 0) {
                        // The column didn't actually exist. See https://issuetracker.google.com/issues/134592631
                        // Try to determine using other information
                        int messageBoxColumn = myCursor.getColumnIndex(Telephony.Mms.MESSAGE_BOX);
                        // MessageBoxColumn is defined for MMS only
                        boolean messageBoxExists = !myCursor.isNull(messageBoxColumn);
                        if (messageBoxExists) {
                            transportType = TransportType.MMS;
                        } else {
                            // There is room here for me to have made an assumption and we'll guess wrong
                            // The penalty is the user will potentially get some garbled data, so that's not too bad.
                            transportType = TransportType.SMS;
                        }
                    } else {
                        String transportTypeString = myCursor.getString(transportTypeColumn);
                        if ("mms".equals(transportTypeString)) {
                            transportType = TransportType.MMS;
                        } else if ("sms".equals(transportTypeString)) {
                            transportType = TransportType.SMS;
                        } else {
                            Log.w("SMSHelper", "Skipping message with unknown TransportType: " + transportTypeString);
                            continue;
                        }
                    }

                    HashMap<String, String> messageInfo = new HashMap<>();
                    for (int columnIdx = 0; columnIdx < myCursor.getColumnCount(); columnIdx++) {
                        String colName = myCursor.getColumnName(columnIdx);
                        String body = myCursor.getString(columnIdx);
                        messageInfo.put(colName, body);
                    }

                    try {
                        switch (transportType) {
                            case SMS:
                                toReturn.add(parseSMS(context, messageInfo));
                            case MMS:
                                toReturn.add(parseMMS(context, messageInfo, userPhoneNumbers));
                        }
                    } catch (Exception e) {
                        // Swallow exceptions in case we get an error reading one message so that we
                        // might be able to read some of them
                        Log.e("SMSHelper", "Got an error reading a message of type " + transportType, e);
                    }
                } while ((numberToGet == null || toReturn.size() < numberToGet) && myCursor.moveToNext());
            }
        } catch (SQLiteException | IllegalArgumentException e) {
            String[] unfilteredColumns = {};
            try (Cursor unfilteredColumnsCursor = context.getContentResolver().query(uri, null, null, null, null)) {
                if (unfilteredColumnsCursor != null) {
                    unfilteredColumns = unfilteredColumnsCursor.getColumnNames();
                }
            }
            if (unfilteredColumns.length == 0) {
                throw new MessageAccessException(uri, e);
            } else {
                throw new MessageAccessException(unfilteredColumns, uri, e);
            }
        }

        return toReturn;
    }

    /**
     * Gets messages which match the selection
     *
     * @param uri Uri indicating the messages database to read
     * @param context android.content.Context running the request.
     * @param selection Parameterizable filter to use with the ContentResolver query. May be null.
     * @param selectionArgs Parameters for selection. May be null.
     * @param sortOrder Sort ordering passed to Android's content resolver. May be null for unspecified
     * @param numberToGet Number of things to get from the result. Pass null to get all
     * @return Returns List<Message> of all messages in the return set, either in the order of sortOrder or in an unspecified order
     */
    @SuppressLint("NewApi")
    private static @NonNull List<Message> getMessages(
            @NonNull Uri uri,
            @NonNull Context context,
            @Nullable String selection,
            @Nullable String[] selectionArgs,
            @Nullable String sortOrder,
            @Nullable Long numberToGet
    ) {
        Set<String> allColumns = new HashSet<>();
        allColumns.addAll(Arrays.asList(Message.smsColumns));
        allColumns.addAll(Arrays.asList(Message.mmsColumns));
        if (getSubscriptionIdSupport(uri, context)) {
            allColumns.addAll(Arrays.asList(Message.multiSIMColumns));
        }

        if (!uri.equals(getConversationUri())) {
            // See https://issuetracker.google.com/issues/134592631
            allColumns.add(getTransportTypeDiscriminatorColumn());
        }

        return getMessages(uri, context, allColumns, selection, selectionArgs, sortOrder, numberToGet);
    }

    /**
     * Get all messages matching the passed filter. See documentation for Android's ContentResolver
     *
     * @param context android.content.Context running the request
     * @param selection Parameterizable filter to use with the ContentResolver query. May be null.
     * @param selectionArgs Parameters for selection. May be null.
     * @param numberToGet Number of things to return. Pass null to get all
     * @return List of messages matching the filter, from newest to oldest
     */
    private static List<Message> getMessagesWithFilter(
            @NonNull Context context,
            @Nullable String selection,
            @Nullable String[] selectionArgs,
            @Nullable Long numberToGet
    ) {
        String sortOrder = Message.DATE + " DESC";

        return getMessages(getCompleteConversationsUri(), context, selection, selectionArgs, sortOrder, numberToGet);
    }

    /**
     * Get the last message from each conversation. Can use the thread_ids in those messages to look
     * up more messages in those conversations
     *
     * Returns values ordered from most-recently-touched conversation to oldest, if possible.
     * Otherwise ordering is undefined.
     *
     * @param context android.content.Context running the request
     * @return Non-blocking iterable of the first message in each conversation
     */
    public static Iterable<Message> getConversations(
            @NonNull Context context
    ) {
        Uri uri = SMSHelper.getConversationUri();

        // Used to avoid spewing logs in case there is an overall problem with fetching thread IDs
        boolean warnedForNullThreadIDs = false;

        // Used to avoid spewing logs in case the date column doesn't return anything.
        boolean warnedForUnorderedOutputs = false;

        // Step 1: Populate the list of all known threadIDs
        // This is basically instantaneous even with lots of conversations because we only make one
        // query. If someone wanted to squeeze better UI performance out of this method, they could
        // iterate over the threadIdCursor instead of getting all the threads before beginning to
        // return conversations, but I doubt anyone will ever find it necessary.
        List<ThreadID> threadIds;
        try (Cursor threadIdCursor = context.getContentResolver().query(
                uri,
                null,
                null,
                null,
                null)) {
            List<Pair<ThreadID, Long>> threadTimestampPair = new ArrayList<>();
            while (threadIdCursor != null && threadIdCursor.moveToNext()) {
                // The "_id" column returned from the `content://sms-mms/conversations?simple=true` URI
                // is actually what the rest of the world calls a thread_id.
                // In my limited experimentation, the other columns are not populated, so don't bother
                // looking at them here.
                int idColumn = threadIdCursor.getColumnIndex("_id");
                int dateColumn = threadIdCursor.getColumnIndex("date");

                ThreadID threadID = null;
                long messageDate = -1;
                if (!threadIdCursor.isNull(idColumn)) {
                    threadID = new ThreadID(threadIdCursor.getLong(idColumn));
                }
                if (!threadIdCursor.isNull(dateColumn)) {
                    // I think the presence of the "date" column depends on the specifics of the
                    // device. If it's there, we'll use it to return threads in a sorted order.
                    // If it's not there, we'll return them unsorted (maybe you get lucky and the
                    // conversations URI returns sorted anyway).
                    messageDate = threadIdCursor.getLong(dateColumn);
                }

                if (messageDate <= 0) {
                    if (!warnedForUnorderedOutputs) {
                        Log.w("SMSHelper", "Got no value for date of thread. Return order of results is undefined.");
                        warnedForUnorderedOutputs = true;
                    }
                }

                if (threadID == null) {
                    if (!warnedForNullThreadIDs) {
                        Log.w("SMSHelper", "Got null for some thread IDs. If these were valid threads, they will not be returned.");
                        warnedForNullThreadIDs = true;
                    }
                    continue;
                }

                threadTimestampPair.add(new Pair<>(threadID, messageDate));
            }

            threadIds = threadTimestampPair.stream()
                    .sorted((left, right) -> right.second.compareTo(left.second)) // Sort most-recent to least-recent (largest to smallest)
                    .map(threadTimestampPairElement -> threadTimestampPairElement.first).collect(Collectors.toList());
        }

        // Step 2: Get the actual message object from each thread ID
        // Do this in an iterator, so that the caller can choose to interrupt us as frequently as
        // desired
        return new Iterable<Message>() {
            @NonNull
            @Override
            public Iterator<Message> iterator() {
                return new Iterator<Message>() {
                    int threadIdsIndex = 0;

                    @Override
                    public boolean hasNext() {
                        return threadIdsIndex < threadIds.size();
                    }

                    @Override
                    public Message next() {
                        ThreadID nextThreadId = threadIds.get(threadIdsIndex);
                        threadIdsIndex++;

                        List<Message> firstMessage = getMessagesInThread(context, nextThreadId, 1L);

                        if (firstMessage.size() > 1) {
                            Log.w("SMSHelper", "getConversations got two messages for the same ThreadID: " + nextThreadId);
                        }

                        if (firstMessage.size() == 0)
                        {
                            Log.e("SMSHelper", "ThreadID: " + nextThreadId + " did not return any messages");
                            // This is a strange issue, but I don't know how to say what is wrong, so just continue along
                            return this.next();
                        }
                        return firstMessage.get(0);
                    }
                };
            }
        };
    }

    private static int addEventFlag(
            int oldEvent,
            int eventFlag
    ) {
        return oldEvent | eventFlag;
    }

    /**
     * Parse all parts of an SMS into a Message
     */
    private static @NonNull Message parseSMS(
            @NonNull Context context,
            @NonNull Map<String, String> messageInfo
    ) {
        int event = Message.EVENT_UNKNOWN;
        event = addEventFlag(event, Message.EVENT_TEXT_MESSAGE);

        @NonNull List<Address> address = Collections.singletonList(new Address(messageInfo.get(Telephony.Sms.ADDRESS)));
        @Nullable String maybeBody = messageInfo.getOrDefault(Message.BODY, "");
        @NonNull String body = maybeBody != null ? maybeBody : "";
        long date = NumberUtils.toLong(messageInfo.getOrDefault(Message.DATE, null));
        int type = NumberUtils.toInt(messageInfo.getOrDefault(Message.TYPE, null));
        int read = NumberUtils.toInt(messageInfo.getOrDefault(Message.READ, null));
        @NonNull ThreadID threadID = new ThreadID(NumberUtils.toLong(messageInfo.getOrDefault(Message.THREAD_ID, null), ThreadID.invalidThreadId.threadID));
        long uID = NumberUtils.toLong(messageInfo.getOrDefault(Message.U_ID, null));
        int subscriptionID = NumberUtils.toInt(messageInfo.getOrDefault(Message.SUBSCRIPTION_ID, null));

        // Examine all the required SMS columns and emit a log if something seems amiss
        boolean anyNulls = Arrays.stream(new String[] {
                        Telephony.Sms.ADDRESS,
                        Message.BODY,
                        Message.DATE,
                        Message.TYPE,
                        Message.READ,
                        Message.THREAD_ID,
                        Message.U_ID })
                .map(key -> messageInfo.getOrDefault(key, null))
                .anyMatch(Objects::isNull);
        if (anyNulls)
        {
            Log.e("parseSMS", "Some fields were invalid. This indicates either a corrupted SMS database or an unsupported device.");
        }

        return new Message(
                address,
                body,
                date,
                type,
                read,
                threadID,
                uID,
                event,
                subscriptionID,
                null
        );
    }

    /**
     * Parse all parts of the MMS message into a message
     * Original implementation from https://stackoverflow.com/a/6446831/3723163
     */
    private static @NonNull Message parseMMS(
            @NonNull Context context,
            @NonNull Map<String, String> messageInfo,
            @NonNull List<TelephonyHelper.LocalPhoneNumber> userPhoneNumbers
    ) {
        int event = Message.EVENT_UNKNOWN;

        @NonNull String body = "";
        long date;
        int type;
        int read = NumberUtils.toInt(messageInfo.get(Message.READ));
        @NonNull ThreadID threadID = new ThreadID(NumberUtils.toLong(messageInfo.getOrDefault(Message.THREAD_ID, null), ThreadID.invalidThreadId.threadID));
        long uID = NumberUtils.toLong(messageInfo.get(Message.U_ID));
        int subscriptionID = NumberUtils.toInt(messageInfo.get(Message.SUBSCRIPTION_ID));
        List<Attachment> attachments = new ArrayList<>();

        String[] columns = {
                Telephony.Mms.Part._ID,          // The content ID of this part
                Telephony.Mms.Part._DATA,        // The location in the filesystem of the data
                Telephony.Mms.Part.CONTENT_TYPE, // The mime type of the data
                Telephony.Mms.Part.TEXT,         // The plain text body of this MMS
                Telephony.Mms.Part.CHARSET,      // Charset of the plain text body
        };

        String mmsID = messageInfo.get(Message.U_ID);
        String selection = Telephony.Mms.Part.MSG_ID + " = ?";
        String[] selectionArgs = {mmsID};

        // Get text body and attachments of the message
        try (Cursor cursor = context.getContentResolver().query(
                getMMSPartUri(),
                columns,
                selection,
                selectionArgs,
                null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                int partIDColumn = cursor.getColumnIndexOrThrow(Telephony.Mms.Part._ID);
                int contentTypeColumn = cursor.getColumnIndexOrThrow(Telephony.Mms.Part.CONTENT_TYPE);
                int dataColumn = cursor.getColumnIndexOrThrow(Telephony.Mms.Part._DATA);
                int textColumn = cursor.getColumnIndexOrThrow(Telephony.Mms.Part.TEXT);
                // TODO: Parse charset (As usual, it is skimpily documented) (Possibly refer to MMS spec)

                do {
                    long partID = cursor.getLong(partIDColumn);
                    String contentType = cursor.getString(contentTypeColumn);
                    String data = cursor.getString(dataColumn);
                    if (MimeType.isTypeText(contentType)) {
                        if (data != null) {
                            // data != null means the data is on disk. Go get it.
                            body = getMmsText(context, partID);
                        } else {
                            body = cursor.getString(textColumn);
                        }
                        event = addEventFlag(event, Message.EVENT_TEXT_MESSAGE);
                    } else if (MimeType.isTypeImage(contentType)) {
                        String fileName = data.substring(data.lastIndexOf('/') + 1);

                        // Get the actual image from the mms database convert it into thumbnail and encode to Base64
                        Bitmap image = SmsMmsUtils.getMmsImage(context, partID);
                        Bitmap thumbnailImage = ThumbnailUtils.extractThumbnail(image, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT);
                        String encodedThumbnail = SmsMmsUtils.bitMapToBase64(thumbnailImage);

                        attachments.add(new Attachment(partID, contentType, encodedThumbnail, fileName));
                    } else if (MimeType.isTypeVideo(contentType)) {
                        String fileName = data.substring(data.lastIndexOf('/') + 1);

                        // Can't use try-with-resources since MediaMetadataRetriever's close method was only added in API 29
                        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                        retriever.setDataSource(context, ContentUris.withAppendedId(getMMSPartUri(), partID));
                        Bitmap videoThumbnail = retriever.getFrameAtTime();

                        String encodedThumbnail = SmsMmsUtils.bitMapToBase64(
                                Bitmap.createScaledBitmap(videoThumbnail, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT, true)
                        );

                        attachments.add(new Attachment(partID, contentType, encodedThumbnail, fileName));
                    } else if (MimeType.isTypeAudio(contentType)) {
                        String fileName = data.substring(data.lastIndexOf('/') + 1);

                        attachments.add(new Attachment(partID, contentType, null, fileName));
                    } else {
                        Log.v("SMSHelper", "Unsupported attachment type: " + contentType);
                    }
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Determine whether the message was in- our out- bound
        long messageBox = NumberUtils.toLong(messageInfo.get(Telephony.Mms.MESSAGE_BOX));
        if (messageBox == Telephony.Mms.MESSAGE_BOX_INBOX) {
            type = Telephony.Sms.MESSAGE_TYPE_INBOX;
        } else if (messageBox == Telephony.Mms.MESSAGE_BOX_SENT) {
            type = Telephony.Sms.MESSAGE_TYPE_SENT;
        } else {
            // As an undocumented feature, it looks like the values of Mms.MESSAGE_BOX_*
            // are the same as Sms.MESSAGE_TYPE_* of the same type. So by default let's just use
            // the value we've got.
            // This includes things like drafts, which are a far-distant plan to support
            type = NumberUtils.toInt(messageInfo.get(Telephony.Mms.MESSAGE_BOX));
        }

        // Get address(es) of the message
        MultimediaMessagePdu msg = getMessagePdu(context, uID);
        Address from = SmsMmsUtils.getMmsFrom(msg);
        List<Address> to = SmsMmsUtils.getMmsTo(msg);

        List<Address> addresses = new ArrayList<>();
        if (from != null) {
            boolean isLocalPhoneNumber = userPhoneNumbers.stream().anyMatch(localPhoneNumber -> localPhoneNumber.isMatchingPhoneNumber(from.address));

            if (!isLocalPhoneNumber && !from.toString().equals("insert-address-token")) {
                addresses.add(from);
            }
        }

        if (to != null) {
            for (Address toAddress : to) {
                boolean isLocalPhoneNumber = userPhoneNumbers.stream().anyMatch(localPhoneNumber -> localPhoneNumber.isMatchingPhoneNumber(toAddress.address));

                if (!isLocalPhoneNumber && !toAddress.toString().equals("insert-address-token")) {
                    addresses.add(toAddress);
                }
            }
        }

        // It looks like addresses[0] is always the sender of the message and
        // following addresses are recipient(s)
        // This usually means the addresses list is at least 2 long, but there are cases (special
        // telco service messages) where it is not (only 1 long in that case, just the "sender")

        if (addresses.size() >= 2) {
            event = addEventFlag(event, Message.EVENT_MULTI_TARGET);
        }

        // Canonicalize the date field
        // SMS uses epoch milliseconds, MMS uses epoch seconds. Standardize on milliseconds.
        long rawDate = NumberUtils.toLong(messageInfo.get(Message.DATE));
        date = rawDate * 1000;

        return new Message(
                addresses,
                body,
                date,
                type,
                read,
                threadID,
                uID,
                event,
                subscriptionID,
                attachments
        );
    }

    private static MultimediaMessagePdu getMessagePdu(Context context, long uID) {
        Uri uri = ContentUris.appendId(getMMSUri().buildUpon(), uID).build();
        MultimediaMessagePdu toReturn;
        try {
            // Work around https://bugs.kde.org/show_bug.cgi?id=434348 by querying the PduCache directly
            // Most likely, this is how we should do business anyway and we will probably see a
            // decent speedup...
            PduCache pduCache = PduCache.getInstance();
            PduCacheEntry maybePduValue;
            synchronized (pduCache) {
                 maybePduValue = pduCache.get(uri);
            }

            if (maybePduValue != null) {
                toReturn = (MultimediaMessagePdu) maybePduValue.getPdu();
            } else {
                toReturn = (MultimediaMessagePdu) PduPersister.getPduPersister(context).load(uri);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        return toReturn;
    }


    /**
     * Get a text part of an MMS message
     * Original implementation from https://stackoverflow.com/a/6446831/3723163
     */
    private static String getMmsText(@NonNull Context context, long id) {
        Uri partURI = ContentUris.withAppendedId(getMMSPartUri(), id);
        String body = "";
        try (InputStream is = context.getContentResolver().openInputStream(partURI)) {
            if (is != null) {
                // The stream is buffered internally, so buffering it separately is unnecessary.
                body = IOUtils.toString(is, Charsets.UTF_8);
            }
        } catch (IOException e) {
            throw new SMSHelper.MessageAccessException(partURI, e);
        }
        return body;
    }

    /**
     * Register a ContentObserver for the Messages database
     *
     * @param observer ContentObserver to alert on Message changes
     */
    public static void registerObserver(
            @NonNull ContentObserver observer,
            @NonNull Context context
    ) {
        context.getContentResolver().registerContentObserver(
                SMSHelper.getConversationUri(),
                true,
                observer
        );
    }

    /**
     * Represent an ID used to uniquely identify a message thread
     */
    public static class ThreadID {
        final long threadID;
        static final String lookupColumn = Telephony.Sms.THREAD_ID;

        /**
         * Define a value against which we can compare others, which should never be returned from
         * a valid thread.
         */
        public static final ThreadID invalidThreadId = new ThreadID(-1);

        public ThreadID(long threadID) {
            this.threadID = threadID;
        }

        @NonNull
        public String toString() {
            return Long.toString(threadID);
        }

        @Override
        public int hashCode() {
            return Long.hashCode(threadID);
        }

        @Override
        public boolean equals(Object other) {
            return other.getClass().isAssignableFrom(ThreadID.class) && ((ThreadID) other).threadID == this.threadID;
        }
    }

    public static class Attachment {
        final long partID;
        final String mimeType;
        final String base64EncodedFile;
        final String uniqueIdentifier;

        /**
         * Attachment object field names
         */
        public static final String PART_ID = "part_id";
        public static final String MIME_TYPE = "mime_type";
        public static final String ENCODED_THUMBNAIL = "encoded_thumbnail";
        public static final String UNIQUE_IDENTIFIER = "unique_identifier";

        public Attachment(long partID,
                          String mimeType,
                          @Nullable String base64EncodedFile,
                          String uniqueIdentifier
        ) {
            this.partID = partID;
            this.mimeType = mimeType;
            this.base64EncodedFile = base64EncodedFile;
            this.uniqueIdentifier = uniqueIdentifier;
        }

        public String getBase64EncodedFile() { return base64EncodedFile; }
        public String getMimeType() { return mimeType; }
        public String getUniqueIdentifier() { return uniqueIdentifier; }

        public JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();

            json.put(Attachment.PART_ID, this.partID);
            json.put(Attachment.MIME_TYPE, this.mimeType);

            if (this.base64EncodedFile != null) {
                json.put(Attachment.ENCODED_THUMBNAIL, this.base64EncodedFile);
            }
            json.put(Attachment.UNIQUE_IDENTIFIER, this.uniqueIdentifier);

            return json;
        }
    }

    /**
     * Converts a given JSONArray of attachments into List<Attachment>
     *
     * The structure of the input is expected to be as follows:
     * [
     *   {
     *     "fileName": <String>             // Name of the file
     *     "base64EncodedFile": <String>    // Base64 encoded file
     *     "mimeType": <String>             // File type (eg: image/jpg, video/mp4 etc.)
     *   },
     * ...
     * ]
     */
    public static @NonNull List<Attachment> jsonArrayToAttachmentsList(
            @Nullable JSONArray jsonArray) {
        if (jsonArray == null) {
            return Collections.emptyList();
        }

        List<Attachment> attachedFiles = new ArrayList<>(jsonArray.length());
        try {
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject jsonObject = jsonArray.getJSONObject(i);
                String base64EncodedFile = jsonObject.getString("base64EncodedFile");
                String mimeType = jsonObject.getString("mimeType");
                String fileName = jsonObject.getString("fileName");
                attachedFiles.add(new Attachment(-1, mimeType, base64EncodedFile, fileName));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return attachedFiles;
    }

    public static class Address {
        public final String address;

        /**
         * Address object field names
         */
        public static final String ADDRESS = "address";

        public Address(String address) {
            this.address = address;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();

            json.put(Address.ADDRESS, this.address);

            return json;
        }

        @NonNull
        @Override
        public String toString() {
            return address;
        }

        @Override
        public boolean equals(Object other){
            if (other == null) {
                return false;
            }
            if (other.getClass().isAssignableFrom(Address.class)) {
                return PhoneNumberUtils.compare(this.address, ((Address)other).address);
            }
            if (other.getClass().isAssignableFrom(String.class)) {
                return PhoneNumberUtils.compare(this.address, (String)other);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return this.address.hashCode();
        }
    }

    /**
     * converts a given JSONArray into List<Address>
     */
    public static List<Address> jsonArrayToAddressList(JSONArray jsonArray) {
        if (jsonArray == null) {
            return null;
        }

        List<Address> addresses = new ArrayList<>();
        try {
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject jsonObject = jsonArray.getJSONObject(i);
                String address = jsonObject.getString("address");
                addresses.add(new Address(address));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return addresses;
    }

    /**
     * Indicate that some error has occurred while reading a message.
     * More useful for logging than catching and handling
     */
    public static class MessageAccessException extends RuntimeException {
        MessageAccessException(Uri uri, Throwable cause) {
            super("Error getting messages from " + uri.toString(), cause);
        }

        MessageAccessException(String[] availableColumns, Uri uri, Throwable cause) {
            super("Error getting messages from " + uri.toString() + " . Available columns were: " + Arrays.toString(availableColumns), cause);
        }
    }

    /**
     * Represent all known transport types
     */
    public enum TransportType {
        SMS,
        MMS,
        // Maybe in the future there will be more TransportType, but for now these are all I know about
    }

    /**
     * Represent a message and all of its interesting data columns
     */
    public static class Message {

        public final List<Address> addresses;
        public final String body;
        public final long date;
        public final int type;
        public final int read;
        public final ThreadID threadID;
        public final long uID;
        public final int event;
        public final int subscriptionID;
        public final List<Attachment> attachments;

        /**
         * Named constants which are used to construct a Message
         * See: https://developer.android.com/reference/android/provider/Telephony.TextBasedSmsColumns.html for full documentation
         */
        static final String ADDRESSES = "addresses";   // Contact information (phone number or otherwise) of the remote
        static final String BODY = Telephony.Sms.BODY;         // Body of the message
        static final String DATE = Telephony.Sms.DATE;         // Date (Unix epoch millis) associated with the message
        static final String TYPE = Telephony.Sms.TYPE;         // Compare with Telephony.TextBasedSmsColumns.MESSAGE_TYPE_*
        static final String READ = Telephony.Sms.READ;         // Whether we have received a read report for this message (int)
        static final String THREAD_ID = ThreadID.lookupColumn; // Magic number which binds (message) threads
        static final String U_ID = Telephony.Sms._ID;          // Something which uniquely identifies this message
        static final String EVENT = "event";
        static final String SUBSCRIPTION_ID = Telephony.Sms.SUBSCRIPTION_ID; // An ID which appears to identify a SIM card
        static final String ATTACHMENTS = "attachments";       // List of files attached in an MMS

        /**
         * Event flags
         * A message should have a bitwise-or of event flags before delivering the packet
         * Any events not supported by the receiving device should be ignored
         */
        public static final int EVENT_UNKNOWN      = 0x0; // The message was of some type we did not understand
        public static final int EVENT_TEXT_MESSAGE = 0x1; // This message has a "body" field which contains
                                                          // pure, human-readable text
        public static final int EVENT_MULTI_TARGET = 0x2; // Indicates that this message has multiple recipients

        /**
         * Define the columns which are to be extracted from the Android SMS database
         */
        static final String[] smsColumns = new String[]{
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE,
                Telephony.Sms.READ,
                Telephony.Sms.THREAD_ID,
                Message.U_ID,
        };

        static final String[] mmsColumns = new String[]{
                Message.U_ID,
                Telephony.Mms.THREAD_ID,
                Telephony.Mms.DATE,
                Telephony.Mms.READ,
                Telephony.Mms.TEXT_ONLY,
                Telephony.Mms.MESSAGE_BOX, // Compare with Telephony.BaseMmsColumns.MESSAGE_BOX_*
        };

        /**
         * These columns are for determining what SIM card the message belongs to, and therefore
         * are only defined on Android versions with multi-sim capabilities
         */
        @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP_MR1)
        static final String[] multiSIMColumns = new String[]{
                Telephony.Sms.SUBSCRIPTION_ID,
        };

        Message(
                @NonNull List<Address> addresses,
                @NonNull String body,
                long date,
                @NonNull Integer type,
                int read,
                @NonNull ThreadID threadID,
                long uID,
                int event,
                int subscriptionID,
                @Nullable List<Attachment> attachments
        ) {
            this.addresses = addresses;
            this.body = body;
            this.date = date;
            if (type == null)
            {
                // To be honest, I have no idea why this happens. The docs say the TYPE field is mandatory.
                Log.w("SMSHelper", "Encountered undefined message type");
                this.type = -1;
                // Proceed anyway, maybe this is not an important problem.
            } else {
                this.type = type;
            }
            this.read = read;
            this.threadID = threadID;
            this.uID = uID;
            this.subscriptionID = subscriptionID;
            this.event = event;
            this.attachments = attachments;
        }

        public JSONObject toJSONObject() throws JSONException {
            JSONObject json = new JSONObject();

            JSONArray jsonAddresses = new JSONArray();
            for (Address address : this.addresses) {
                jsonAddresses.put(address.toJson());
            }

            json.put(Message.ADDRESSES, jsonAddresses);
            json.put(Message.BODY, body);
            json.put(Message.DATE, date);
            json.put(Message.TYPE, type);
            json.put(Message.READ, read);
            json.put(Message.THREAD_ID, threadID.threadID);
            json.put(Message.U_ID, uID);
            json.put(Message.SUBSCRIPTION_ID, subscriptionID);
            json.put(Message.EVENT, event);

            if (this.attachments != null) {
                JSONArray jsonAttachments = new JSONArray();
                for (Attachment attachment : this.attachments) {
                    jsonAttachments.put(attachment.toJson());
                }
                json.put(Message.ATTACHMENTS, jsonAttachments);
            }

            return json;
        }

        @NonNull
        @Override
        public String toString() {
            return body;
        }
    }

    /**
     * If anyone wants to subscribe to changes in the messages database, they will need a thread
     * to handle callbacks on
     * This singleton conveniently provides such a thread, accessed and used via its Looper object
     */
    public static class MessageLooper extends Thread {
        private static MessageLooper singleton = null;
        private static Looper looper = null;

        private static final Lock looperReadyLock = new ReentrantLock();
        private static final Condition looperReady = looperReadyLock.newCondition();

        private MessageLooper() {
            setName("MessageHelperLooper");
        }

        /**
         * Get the Looper object associated with this thread
         *
         * If the Looper has not been prepared, it is prepared as part of this method call.
         * Since this means a thread has to be spawned, this method might block until that thread is
         * ready to serve requests
         */
        public static Looper getLooper() {
            if (singleton == null) {
                looperReadyLock.lock();
                try {
                    singleton = new MessageLooper();
                    singleton.start();
                    while (looper == null) {
                        // Block until the looper is ready
                        looperReady.await();
                    }
                } catch (InterruptedException e) {
                    // I don't know when this would happen
                    Log.e("SMSHelper", "Interrupted while waiting for Looper", e);
                    return null;
                } finally {
                    looperReadyLock.unlock();
                }
            }

            return looper;
        }

        public void run() {
            looperReadyLock.lock();
            try {
                Looper.prepare();

                looper = Looper.myLooper();
                looperReady.signalAll();
            } finally {
                looperReadyLock.unlock();
            }

            Looper.loop();
        }
    }
}

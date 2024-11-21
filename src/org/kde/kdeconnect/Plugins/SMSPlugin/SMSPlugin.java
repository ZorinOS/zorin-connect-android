/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 * SPDX-FileCopyrightText: 2021 Simon Redman <simon@ergotech.com>
 * SPDX-FileCopyrightText: 2020 Aniket Kumar <anikketkumar786@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.Plugins.SMSPlugin;

import static androidx.core.content.ContextCompat.RECEIVER_EXPORTED;
import static org.kde.kdeconnect.Plugins.TelephonyPlugin.TelephonyPlugin.PACKET_TYPE_TELEPHONY;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.provider.Telephony;
import android.telephony.PhoneNumberUtils;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;

import androidx.annotation.WorkerThread;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.klinker.android.logger.Log;
import com.klinker.android.send_message.Transaction;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.kde.kdeconnect.Helpers.ContactsHelper;
import org.kde.kdeconnect.Helpers.SMSHelper;
import org.kde.kdeconnect.Helpers.ThreadHelper;
import org.kde.kdeconnect.NetworkPacket;
import org.kde.kdeconnect.Plugins.Plugin;
import org.kde.kdeconnect.Plugins.PluginFactory;
import org.kde.kdeconnect.Plugins.TelephonyPlugin.TelephonyPlugin;
import org.kde.kdeconnect.UserInterface.PluginSettingsFragment;
import com.zorinos.zorin_connect.BuildConfig;
import com.zorinos.zorin_connect.R;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import kotlin.sequences.Sequence;

@PluginFactory.LoadablePlugin
@SuppressLint("InlinedApi")
public class SMSPlugin extends Plugin {

    /**
     * Packet used to indicate a batch of messages has been pushed from the remote device
     * <p>
     * The body should contain the key "messages" mapping to an array of messages
     * <p>
     * For example:
     * {
     *   "version": 2                     // This is the second version of this packet type and
     *                                    // version 1 packets (which did not carry this flag)
     *                                    // are incompatible with the new format
     *   "messages" : [
     *   { "event"     : 1,               // 32-bit field containing a bitwise-or of event flags
     *                                    // See constants declared in SMSHelper.Message for defined
     *                                    // values and explanations
     *     "body"      : "Hello",         // Text message body
     *     "addresses": <List<Address>>   // List of Address objects, one for each participant of the conversation
     *                                    // The user's Address is excluded so:
     *                                    // If this is a single-target messsage, there will only be one
     *                                    // Address (the other party)
     *                                    // If this is an incoming multi-target message, the first Address is the
     *                                    // sender and all other addresses are other parties to the conversation
     *                                    // If this is an outgoing multi-target message, the sender is implicit
     *                                    // (the user's phone number) and all Addresses are recipients
     *     "date"      : "1518846484880", // Timestamp of the message
     *     "type"      : "2",   // Compare with Android's
     *                          // Telephony.TextBasedSmsColumns.MESSAGE_TYPE_*
     *     "thread_id" : 132    // Thread to which the message belongs
     *     "read"      : true   // Boolean representing whether a message is read or unread
     *   },
     *   { ... },
     *   ...
     * ]
     *
     * The following optional fields of a message object may be defined
     * "sub_id": <int> // Android's subscriber ID, which is basically used to determine which SIM card the message
     *                 // belongs to. This is mostly useful when attempting to reply to an SMS with the correct
     *                 // SIM card using PACKET_TYPE_SMS_REQUEST.
     *                 // If this value is not defined or if it does not match a valid subscriber_id known by
     *                 // Android, we will use whatever subscriber ID Android gives us as the default
     *
     * "attachments": <List<Attachment>>    // List of Attachment objects, one for each attached file in the message.
     *
     * An Attachment object looks like:
     * {
     *     "part_id": <long>                // part_id of the attachment used to read the file from MMS database
     *     "mime_type": <String>            // contains the mime type of the file (eg: image/jpg, video/mp4 etc.)
     *     "encoded_thumbnail": <String>    // Optional base64-encoded thumbnail preview of the content for types which support it
     *     "unique_identifier": <String>    // Unique name of te file
     * }
     *
     * An Address object looks like:
     * {
     *     "address": <String> // Address (phone number, email address, etc.) of this object
     * }
     */
    private final static String PACKET_TYPE_SMS_MESSAGE = "kdeconnect.sms.messages";
    private final static int SMS_MESSAGE_PACKET_VERSION = 2; // We *send* packets of this version

    /**
     * Packet sent to request a message be sent
     *
     * The body should look like so:
     * {
     *   "version": 2,                     // The version of the packet being sent. Compare to SMS_REQUEST_PACKET_VERSION before attempting to handle.
     *   "sendSms": true,                  // (Depreciated, ignored) Old versions of the desktop app used to mix phone calls, SMS, etc. in the same packet type and used this field to differentiate.
     *   "phoneNumber": "542904563213",    // (Depreciated) Retained for backwards-compatibility. Old versions of the desktop app send a single phoneNumber. Use the Addresses field instead.
     *   "addresses": <List of Addresses>, // The one or many targets of this message
     *   "messageBody": "Hi mom!",         // Plain-text string to be sent as the body of the message (Optional if sending an attachment)
     *   "attachments": <List of Attached files>,
     *   "sub_id": 3859358340534           // Some magic number which tells Android which SIM card to use (Optional, if omitted, sends with the default SIM card)
     * }
     *
     * An AttachmentContainer object looks like:
     * {
     *   "fileName": <String>             // Name of the file
     *   "base64EncodedFile": <String>    // Base64 encoded file
     *   "mimeType": <String>             // File type (eg: image/jpg, video/mp4 etc.)
     * }
     */
    private final static String PACKET_TYPE_SMS_REQUEST = "kdeconnect.sms.request";
    private final static int SMS_REQUEST_PACKET_VERSION = 2; // We *handle* packets of this version or lower. Update this number only if future packets break backwards-compatibility.

    /**
     * Packet sent to request the most-recent message in each conversations on the device
     * <p>
     * The request packet shall contain no body
     */
    private final static String PACKET_TYPE_SMS_REQUEST_CONVERSATIONS = "kdeconnect.sms.request_conversations";

    /**
     * Packet sent to request all the messages in a particular conversation
     * <p>
     * The following fields are available:
     * "threadID": <long>            // (Required) ThreadID to request
     * "rangeStartTimestamp": <long> // (Optional) Millisecond epoch timestamp indicating the start of the range from which to return messages
     * "numberToRequest": <long>     // (Optional) Number of messages to return, starting from rangeStartTimestamp.
     *                               // May return fewer than expected if there are not enough or more than expected if many
     *                               // messages have the same timestamp.
     */
    private final static String PACKET_TYPE_SMS_REQUEST_CONVERSATION = "kdeconnect.sms.request_conversation";

    /**
     * Packet sent to request an attachment file in a particular message of a conversation
     * <p>
     * The body should look like so:
     * "part_id": <long>                // Part id of the attachment
     * "unique_identifier": <String>    // This unique_identifier should come from a previous message packet's attachment field
     */
    private final static String PACKET_TYPE_SMS_REQUEST_ATTACHMENT = "kdeconnect.sms.request_attachment";

    /**
     * Packet used to send original attachment file from mms database to desktop
     * <p>
     * The following fields are available:
     * "filename": <String>     // Name of the attachment file in the database
     * "payload":               // Actual attachment file to be transferred
     */
    private final static String PACKET_TYPE_SMS_ATTACHMENT_FILE = "kdeconnect.sms.attachment_file";

    private static final String KEY_PREF_BLOCKED_NUMBERS = "telephony_blocked_numbers";

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            String action = intent.getAction();

            //Log.e("TelephonyPlugin","Telephony event: " + action);

            if (Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(action)) {

                final Bundle bundle = intent.getExtras();
                if (bundle == null) return;
                final Object[] pdus = (Object[]) bundle.get("pdus");
                ArrayList<SmsMessage> messages = new ArrayList<>();

                for (Object pdu : pdus) {
                    // I hope, but am not sure, that the pdus array is in the order that the parts
                    // of the SMS message should be
                    // If it is not, I believe the pdu contains the information necessary to put it
                    // in order, but in my testing the order seems to be correct, so I won't worry
                    // about it now.
                    messages.add(SmsMessage.createFromPdu((byte[]) pdu));
                }

                smsBroadcastReceivedDeprecated(messages);
            }
        }
    };

    /**
     * Keep track of the most-recently-seen message so that we can query for later ones as they arrive
     */
    private long mostRecentTimestamp = 0;
    // Since the mostRecentTimestamp is accessed both from the plugin's thread and the ContentObserver
    // thread, make sure that access is coherent
    private final Lock mostRecentTimestampLock = new ReentrantLock();

    /**
     * Keep track of whether we have received any packet which requested messages.
     *
     * If not, we will not send updates, since probably the user doesn't care.
     */
    private boolean haveMessagesBeenRequested = false;

    private class MessageContentObserver extends ContentObserver {

        /**
         * Create a ContentObserver to watch the Messages database. onChange is called for
         * every subscribed change
         *
         * @param handler Handler object used to make the callback
         */
        MessageContentObserver(Handler handler) {
            super(handler);
        }

        /**
         * The onChange method is called whenever the subscribed-to database changes
         *
         * In this case, this onChange expects to be called whenever *anything* in the Messages
         * database changes and simply reports those updated messages to anyone who might be listening
         */
        @Override
        public void onChange(boolean selfChange) {
            sendLatestMessage();
        }

    }

    /**
     * This receiver will be invoked only when the app will be set as the default sms app
     * Whenever the app will be set as the default, the database update alert will be sent
     * using messageUpdateReceiver and not the contentObserver class
     */
    private final BroadcastReceiver messagesUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            String action = intent.getAction();

            if (Transaction.REFRESH.equals(action)) {
                sendLatestMessage();
            }
        }
    };

    /**
     * Helper method to read the latest message from the sms-mms database and sends it to the desktop
     *
     * Should only be called after initializing the mostRecentTimestamp
     */
    private void sendLatestMessage() {
        // Lock so no one uses the mostRecentTimestamp between the moment we read it and the
        // moment we update it. This is because reading the Messages DB can take long.
        mostRecentTimestampLock.lock();

        if (!haveMessagesBeenRequested) {
            // Since the user has not requested a message, there is most likely nobody listening
            // for message updates, so just drop them rather than spending battery/time sending
            // updates that don't matter.
            mostRecentTimestampLock.unlock();
            return;
        }
        List<SMSHelper.Message> messages = SMSHelper.getMessagesInRange(context, null, mostRecentTimestamp, null, false);

        long newMostRecentTimestamp = mostRecentTimestamp;
        for (SMSHelper.Message message : messages) {
            if (message == null || message.date >= newMostRecentTimestamp) {
                  newMostRecentTimestamp = message.date;
            }
        }

        // Update the most recent counter
        mostRecentTimestamp = newMostRecentTimestamp;
        mostRecentTimestampLock.unlock();

        // Send the alert about the update
        getDevice().sendPacket(constructBulkMessagePacket(messages));
    }

    /**
     * Deliver an old-style SMS packet in response to a new message arriving
     *
     * For backwards-compatibility with long-lived distro packages, this method needs to exist in
     * order to support older desktop apps. However, note that it should no longer be used
     *
     * This comment is being written 30 August 2018. Distros will likely be running old versions for
     * many years to come...
     *
     * @param messages Ordered list of parts of the message body which should be combined into a single message
     */
    @Deprecated
    private void smsBroadcastReceivedDeprecated(ArrayList<SmsMessage> messages) {

        if (BuildConfig.DEBUG) {
            if (!(messages.size() > 0)) {
                throw new AssertionError("This method requires at least one message");
            }
        }

        NetworkPacket np = new NetworkPacket(PACKET_TYPE_TELEPHONY);

        np.set("event", "sms");

        StringBuilder messageBody = new StringBuilder();
        for (int index = 0; index < messages.size(); index++) {
            messageBody.append(messages.get(index).getMessageBody());
        }
        np.set("messageBody", messageBody.toString());

        String phoneNumber = messages.get(0).getOriginatingAddress();

        if (isNumberBlocked(phoneNumber))
            return;

        int permissionCheck = ContextCompat.checkSelfPermission(context,
                Manifest.permission.READ_CONTACTS);

        if (permissionCheck == PackageManager.PERMISSION_GRANTED) {
            Map<String, String> contactInfo = ContactsHelper.phoneNumberLookup(context, phoneNumber);

            if (contactInfo.containsKey("name")) {
                np.set("contactName", contactInfo.get("name"));
            }

            if (contactInfo.containsKey("photoID")) {
                np.set("phoneThumbnail", ContactsHelper.photoId64Encoded(context, contactInfo.get("photoID")));
            }
        }
        if (phoneNumber != null) {
            np.set("phoneNumber", phoneNumber);
        }


        getDevice().sendPacket(np);
    }

    @Override
    public int getPermissionExplanation() {
        return R.string.telepathy_permission_explanation;
    }

    @Override
    public boolean onCreate() {
        IntentFilter filter = new IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION);
        filter.setPriority(500);
        context.registerReceiver(receiver, filter);

        IntentFilter refreshFilter = new IntentFilter(Transaction.REFRESH);
        refreshFilter.setPriority(500);
        context.registerReceiver(messagesUpdateReceiver, refreshFilter, RECEIVER_EXPORTED);

        Looper helperLooper = SMSHelper.MessageLooper.getLooper();
        ContentObserver messageObserver = new MessageContentObserver(new Handler(helperLooper));
        SMSHelper.registerObserver(messageObserver, context);

        // To see debug messages for Klinker library, uncomment the below line
        //Log.setDebug(true);

        mostRecentTimestampLock.lock();
        mostRecentTimestamp = SMSHelper.getNewestMessageTimestamp(context);
        mostRecentTimestampLock.unlock();

        return true;
    }

    @Override
    public @NonNull String getDisplayName() {
        return context.getResources().getString(R.string.pref_plugin_telepathy);
    }

    @Override
    public @NonNull String getDescription() {
        return context.getResources().getString(R.string.pref_plugin_telepathy_desc);
    }

    @Override
    public boolean onPacketReceived(@NonNull NetworkPacket np) {
        long subID;

        switch (np.getType()) {
            case PACKET_TYPE_SMS_REQUEST_CONVERSATIONS:
                Runnable handleRequestAllConversationsRunnable = () -> this.handleRequestAllConversations(np);
                ThreadHelper.execute(handleRequestAllConversationsRunnable);
                return true;

            case PACKET_TYPE_SMS_REQUEST_CONVERSATION:
                Runnable handleRequestSingleConversationRunnable = () -> this.handleRequestSingleConversation(np);
                ThreadHelper.execute(handleRequestSingleConversationRunnable);
                return true;

            case PACKET_TYPE_SMS_REQUEST:
                String textMessage = np.getString("messageBody");
                subID = np.getLong("subID", -1);

                List<SMSHelper.Address> addressList = SMSHelper.jsonArrayToAddressList(context, np.getJSONArray("addresses"));
                if (addressList == null) {
                    // If the List of Address is null, then the SMS_REQUEST packet is
                    // most probably from the older version of the desktop app.
                    addressList = new ArrayList<>();
                    addressList.add(new SMSHelper.Address(context, np.getString("phoneNumber")));
                }
                List<SMSHelper.Attachment> attachedFiles = SMSHelper.jsonArrayToAttachmentsList(np.getJSONArray("attachments"));

                SmsMmsUtils.sendMessage(context, textMessage, attachedFiles, addressList, (int) subID);
                break;

            case TelephonyPlugin.PACKET_TYPE_TELEPHONY_REQUEST:
                if (np.getBoolean("sendSms")) {
                    String phoneNo = np.getString("phoneNumber");
                    String sms = np.getString("messageBody");
                    subID = np.getLong("subID", -1);

                    try {
                        SmsManager smsManager = subID == -1? SmsManager.getDefault() :
                            SmsManager.getSmsManagerForSubscriptionId((int) subID);
                        ArrayList<String> parts = smsManager.divideMessage(sms);

                        // If this message turns out to fit in a single SMS, sendMultipartTextMessage
                        // properly handles that case
                        smsManager.sendMultipartTextMessage(phoneNo, null, parts, null, null);

                        //TODO: Notify other end
                    } catch (Exception e) {
                        //TODO: Notify other end
                        Log.e("SMSPlugin", "Exception", e);
                    }
                }
                break;

            case PACKET_TYPE_SMS_REQUEST_ATTACHMENT:
                long partID = np.getLong("part_id");
                String uniqueIdentifier = np.getString("unique_identifier");

                NetworkPacket networkPacket = SmsMmsUtils.partIdToMessageAttachmentPacket(
                        context,
                        partID,
                        uniqueIdentifier,
                        PACKET_TYPE_SMS_ATTACHMENT_FILE
                );

                if (networkPacket != null) {
                    getDevice().sendPacket(networkPacket);
                }
                break;
        }

        return true;
    }

    /**
     * Construct a proper packet of PACKET_TYPE_SMS_MESSAGE from the passed messages
     *
     * @param messages Messages to include in the packet
     * @return NetworkPacket of type PACKET_TYPE_SMS_MESSAGE
     */
    private static NetworkPacket constructBulkMessagePacket(Iterable<SMSHelper.Message> messages) {
        NetworkPacket reply = new NetworkPacket(PACKET_TYPE_SMS_MESSAGE);

        JSONArray body = new JSONArray();

        for (SMSHelper.Message message : messages) {
            try {
                JSONObject json = message.toJSONObject();

                body.put(json);
            } catch (JSONException e) {
                Log.e("Conversations", "Error serializing message", e);
            }
        }

        reply.set("messages", body);
        reply.set("version", SMS_MESSAGE_PACKET_VERSION);

        return reply;
    }

    /**
     * Respond to a request for all conversations
     * <p>
     * Send one packet of type PACKET_TYPE_SMS_MESSAGE with the first message in all conversations
     */
    @WorkerThread
    private boolean handleRequestAllConversations(NetworkPacket packet) {
        haveMessagesBeenRequested = true;
        Iterator<SMSHelper.Message> conversations = SMSHelper.getConversations(this.context).iterator();

        while (conversations.hasNext()) {
            SMSHelper.Message message = conversations.next();
            NetworkPacket partialReply = constructBulkMessagePacket(Collections.singleton(message));
            getDevice().sendPacket(partialReply);
        }

        return true;
    }

    @WorkerThread
    private boolean handleRequestSingleConversation(NetworkPacket packet) {
        haveMessagesBeenRequested = true;
        SMSHelper.ThreadID threadID = new SMSHelper.ThreadID(packet.getLong("threadID"));

        long rangeStartTimestamp = packet.getLong("rangeStartTimestamp", -1);
        Long numberToGet = packet.getLong("numberToRequest", -1);

        if (numberToGet < 0) {
            numberToGet = null;
        }

        List<SMSHelper.Message> conversation;
        if (rangeStartTimestamp < 0) {
            conversation = SMSHelper.getMessagesInThread(this.context, threadID, numberToGet);
        } else {
            conversation = SMSHelper.getMessagesInRange(this.context, threadID, rangeStartTimestamp, numberToGet, true);
        }

        NetworkPacket reply = constructBulkMessagePacket(conversation);

        getDevice().sendPacket(reply);

        return true;
    }

    private boolean isNumberBlocked(String number) {
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
        String[] blockedNumbers = sharedPref.getString(KEY_PREF_BLOCKED_NUMBERS, "").split("\n");

        for (String s : blockedNumbers) {
            if (PhoneNumberUtils.compare(number, s))
                return true;
        }

        return false;
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public PluginSettingsFragment getSettingsFragment(Activity activity) {
        return PluginSettingsFragment.newInstance(getPluginKey(), R.xml.smsplugin_preferences);
    }

    @Override
    public @NonNull String[] getSupportedPacketTypes() {
        return new String[]{
                PACKET_TYPE_SMS_REQUEST,
                TelephonyPlugin.PACKET_TYPE_TELEPHONY_REQUEST,
                PACKET_TYPE_SMS_REQUEST_CONVERSATIONS,
                PACKET_TYPE_SMS_REQUEST_CONVERSATION,
                PACKET_TYPE_SMS_REQUEST_ATTACHMENT
        };
    }

    @Override
    public @NonNull String[] getOutgoingPacketTypes() {
        return new String[]{
                PACKET_TYPE_SMS_MESSAGE,
                PACKET_TYPE_SMS_ATTACHMENT_FILE
        };
    }

    @Override
    public @NonNull String[] getRequiredPermissions() {
        return new String[]{
                Manifest.permission.SEND_SMS,
                Manifest.permission.READ_SMS,
                // READ_PHONE_STATE should be optional, since we can just query the user, but that
                // requires a GUI implementation for querying the user!
                Manifest.permission.READ_PHONE_STATE,
        };
    }

    /**
     * Permissions required for sending and receiving MMs messages
     */
    public static String[] getMmsPermissions() {
        return new String[]{
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.RECEIVE_MMS,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.CHANGE_NETWORK_STATE,
                Manifest.permission.WAKE_LOCK,
        };
    }

    /**
     * With versions older than KITKAT, lots of the content providers used in SMSHelper become
     * un-documented. Most manufacturers *did* do things the same way as was done in mainline
     * Android at that time, but some did not. If the manufacturer followed the default route,
     * everything will be fine. If not, the plugin will crash. But, since we have a global catch-all
     * in Device.onPacketReceived, it will not crash catastrophically.
     * The onCreated method of this SMSPlugin complains if a version older than KitKat is loaded,
     * but it still allowed in the optimistic hope that things will "just work"
     */
    @Override
    public int getMinSdk() {
        return Build.VERSION_CODES.FROYO;
    }
}

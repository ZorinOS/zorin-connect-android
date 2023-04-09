/*
 * SPDX-FileCopyrightText: 2018 Erik Duisters <e.duisters1@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.Plugins.SharePlugin;

import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.annotation.GuardedBy;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.kde.kdeconnect.Device;
import org.kde.kdeconnect.Helpers.FilesHelper;
import org.kde.kdeconnect.Helpers.MediaStoreHelper;
import org.kde.kdeconnect.NetworkPacket;
import org.kde.kdeconnect.async.BackgroundJob;
import com.zorinos.zorin_connect.R;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A type of {@link BackgroundJob} that reads Files from another device.
 *
 * <p>
 *     We receive the requests as {@link NetworkPacket}s.
 * </p>
 * <p>
 *     Each packet should have a 'filename' property and a payload. If the payload is missing,
 *     we'll just create an empty file. You can add new packets anytime via
 *     {@link #addNetworkPacket(NetworkPacket)}.
 * </p>
 * <p>
 *     The I/O-part of this file reading is handled by {@link #receiveFile(InputStream, OutputStream)}.
 * </p>
 *
 * @see CompositeUploadFileJob
 */
public class CompositeReceiveFileJob extends BackgroundJob<Device, Void> {
    private final ReceiveNotification receiveNotification;
    private NetworkPacket currentNetworkPacket;
    private String currentFileName;
    private int currentFileNum;
    private long totalReceived;
    private long lastProgressTimeMillis;
    private long prevProgressPercentage;

    private final Object lock;                              //Use to protect concurrent access to the variables below
    @GuardedBy("lock")
    private final List<NetworkPacket> networkPacketList;
    @GuardedBy("lock")
    private int totalNumFiles;
    @GuardedBy("lock")
    private long totalPayloadSize;
    private boolean isRunning;

    CompositeReceiveFileJob(Device device, BackgroundJob.Callback<Void> callBack) {
        super(device, callBack);

        lock = new Object();
        networkPacketList = new ArrayList<>();
        receiveNotification = new ReceiveNotification(device, getId());
        currentFileNum = 0;
        totalNumFiles = 0;
        totalPayloadSize = 0;
        totalReceived = 0;
        lastProgressTimeMillis = 0;
        prevProgressPercentage = 0;
    }

    private Device getDevice() {
        return requestInfo;
    }

    boolean isRunning() { return isRunning; }

    void updateTotals(int numberOfFiles, long totalPayloadSize) {
        synchronized (lock) {
            this.totalNumFiles = numberOfFiles;
            this.totalPayloadSize = totalPayloadSize;

            receiveNotification.setTitle(getDevice().getContext().getResources()
                    .getQuantityString(R.plurals.incoming_file_title, totalNumFiles, totalNumFiles, getDevice().getName()));
        }
    }

    void addNetworkPacket(NetworkPacket networkPacket) {
        synchronized (lock) {
            if (!networkPacketList.contains(networkPacket)) {
                networkPacketList.add(networkPacket);

                totalNumFiles = networkPacket.getInt(SharePlugin.KEY_NUMBER_OF_FILES, 1);
                totalPayloadSize = networkPacket.getLong(SharePlugin.KEY_TOTAL_PAYLOAD_SIZE);

                receiveNotification.setTitle(getDevice().getContext().getResources()
                        .getQuantityString(R.plurals.incoming_file_title, totalNumFiles, totalNumFiles, getDevice().getName()));
            }
        }
    }

    @Override
    public void run() {
        boolean done;
        OutputStream outputStream = null;

        synchronized (lock) {
            done = networkPacketList.isEmpty();
        }

        try {
            DocumentFile fileDocument = null;

            isRunning = true;

            while (!done && !canceled) {
                synchronized (lock) {
                    currentNetworkPacket = networkPacketList.get(0);
                }
                currentFileName = currentNetworkPacket.getString("filename", Long.toString(System.currentTimeMillis()));
                currentFileNum++;

                setProgress((int)prevProgressPercentage);

                fileDocument = getDocumentFileFor(currentFileName, currentNetworkPacket.getBoolean("open", false));

                if (currentNetworkPacket.hasPayload()) {
                    outputStream = new BufferedOutputStream(getDevice().getContext().getContentResolver().openOutputStream(fileDocument.getUri()));
                    InputStream inputStream = currentNetworkPacket.getPayload().getInputStream();

                    long received = receiveFile(inputStream, outputStream);

                    currentNetworkPacket.getPayload().close();

                    if ( received != currentNetworkPacket.getPayloadSize()) {
                        fileDocument.delete();

                        if (!canceled) {
                            throw new RuntimeException("Failed to receive: " + currentFileName + " received:" + received + " bytes, expected: " + currentNetworkPacket.getPayloadSize() + " bytes");
                        }
                    } else {
                        publishFile(fileDocument, received);
                    }
                } else {
                    //TODO: Only set progress to 100 if this is the only file/packet to send
                    setProgress(100);
                    publishFile(fileDocument, 0);
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (currentNetworkPacket.has("lastModified")) {
                        try {
                            long lastModified = currentNetworkPacket.getLong("lastModified");
                            Files.setLastModifiedTime(Paths.get(fileDocument.getUri().getPath()), FileTime.fromMillis(lastModified));
                        } catch (Exception e) {
                            Log.e("SharePlugin", "Can't set date on file");
                            e.printStackTrace();
                        }
                    }
                }

                boolean listIsEmpty;

                synchronized (lock) {
                    networkPacketList.remove(0);
                    listIsEmpty = networkPacketList.isEmpty();
                }

                if (listIsEmpty && !canceled) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ignored) {}

                    synchronized (lock) {
                        if (currentFileNum < totalNumFiles && networkPacketList.isEmpty()) {
                            throw new RuntimeException("Failed to receive " + (totalNumFiles - currentFileNum + 1) + " files");
                        }
                    }
                }

                synchronized (lock) {
                    done = networkPacketList.isEmpty();
                }
            }

            isRunning = false;

            if (canceled) {
                receiveNotification.cancel();
                return;
            }

            int numFiles;
            synchronized (lock) {
                numFiles = totalNumFiles;
            }

            if (numFiles == 1 && currentNetworkPacket.getBoolean("open", false)) {
                receiveNotification.cancel();
                openFile(fileDocument);
            } else {
                //Update the notification and allow to open the file from it
                receiveNotification.setFinished(getDevice().getContext().getResources().getQuantityString(R.plurals.received_files_title, numFiles, getDevice().getName(), numFiles));

                if (totalNumFiles == 1 && fileDocument != null) {
                    receiveNotification.setURI(fileDocument.getUri(), fileDocument.getType(), fileDocument.getName());
                }

                receiveNotification.show();
            }
            reportResult(null);

        } catch (ActivityNotFoundException e) {
            receiveNotification.setFinished(getDevice().getContext().getString(R.string.no_app_for_opening));
            receiveNotification.show();
        } catch (Exception e) {
            isRunning = false;

            Log.e("Shareplugin", "Error receiving file", e);

            int failedFiles;
            synchronized (lock) {
                failedFiles = (totalNumFiles - currentFileNum + 1);
            }

            receiveNotification.setFinished(getDevice().getContext().getResources().getQuantityString(R.plurals.received_files_fail_title, failedFiles, getDevice().getName(), failedFiles, totalNumFiles));
            receiveNotification.show();
            reportError(e);
        } finally {
            closeAllInputStreams();
            networkPacketList.clear();
            try {
                IOUtils.close(outputStream);
            } catch (IOException ignored) {
            }
        }
    }

    private DocumentFile getDocumentFileFor(final String filename, final boolean open) throws RuntimeException {
        final DocumentFile destinationFolderDocument;

        String filenameToUse = filename;

        //We need to check for already existing files only when storing in the default path.
        //User-defined paths use the new Storage Access Framework that already handles this.
        //If the file should be opened immediately store it in the standard location to avoid the FileProvider trouble (See ReceiveNotification::setURI)
        if (open || !ShareSettingsFragment.isCustomDestinationEnabled(getDevice().getContext())) {
            final String defaultPath = ShareSettingsFragment.getDefaultDestinationDirectory().getAbsolutePath();
            filenameToUse = FilesHelper.findNonExistingNameForNewFile(defaultPath, filenameToUse);
            destinationFolderDocument = DocumentFile.fromFile(new File(defaultPath));
        } else {
            destinationFolderDocument = ShareSettingsFragment.getDestinationDirectory(getDevice().getContext());
        }
        String displayName = FilenameUtils.getBaseName(filenameToUse);
        String mimeType = FilesHelper.getMimeTypeFromFile(filenameToUse);

        if ("*/*".equals(mimeType)) {
            displayName = filenameToUse;
        }

        DocumentFile fileDocument = destinationFolderDocument.createFile(mimeType, displayName);

        if (fileDocument == null) {
            throw new RuntimeException(getDevice().getContext().getString(R.string.cannot_create_file, filenameToUse));
        }

        return fileDocument;
    }

    private long receiveFile(InputStream input, OutputStream output) throws IOException {
        byte[] data = new byte[4096];
        int count;
        long received = 0;

        while ((count = input.read(data)) >= 0 && !canceled) {
            received += count;
            totalReceived += count;

            output.write(data, 0, count);

            long progressPercentage;
            synchronized (lock) {
                progressPercentage = (totalReceived * 100 / totalPayloadSize);
            }
            long curTimeMillis = System.currentTimeMillis();

            if (progressPercentage != prevProgressPercentage &&
                    (progressPercentage == 100 || curTimeMillis - lastProgressTimeMillis >= 500)) {
                prevProgressPercentage = progressPercentage;
                lastProgressTimeMillis = curTimeMillis;
                setProgress((int)progressPercentage);
            }
        }

        output.flush();

        return received;
    }

    private void closeAllInputStreams() {
        for (NetworkPacket np : networkPacketList) {
            np.getPayload().close();
        }
    }

    private void setProgress(int progress) {
        synchronized (lock) {
            receiveNotification.setProgress(progress, getDevice().getContext().getResources()
                    .getQuantityString(R.plurals.incoming_files_text, totalNumFiles, currentFileName, currentFileNum, totalNumFiles));
        }
        receiveNotification.show();
    }

    private void publishFile(DocumentFile fileDocument, long size) {
        if (!ShareSettingsFragment.isCustomDestinationEnabled(getDevice().getContext())) {
            Log.i("SharePlugin", "Adding to downloads");
            DownloadManager manager = ContextCompat.getSystemService(getDevice().getContext(),
                    DownloadManager.class);
            manager.addCompletedDownload(fileDocument.getUri().getLastPathSegment(), getDevice().getName(), true, fileDocument.getType(), fileDocument.getUri().getPath(), size, false);
        } else {
            //Make sure it is added to the Android Gallery anyway
            Log.i("SharePlugin", "Adding to gallery");
            MediaStoreHelper.indexFile(getDevice().getContext(), fileDocument.getUri());
        }
    }

    private void openFile(DocumentFile fileDocument) {
        String mimeType = FilesHelper.getMimeTypeFromFile(fileDocument.getName());
        Intent intent = new Intent(Intent.ACTION_VIEW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            //Nougat and later require "content://" uris instead of "file://" uris
            File file = new File(fileDocument.getUri().getPath());
            Uri contentUri = FileProvider.getUriForFile(getDevice().getContext(), "com.zorinos.zorin_connect.fileprovider", file);
            intent.setDataAndType(contentUri, mimeType);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        } else {
            intent.setDataAndType(fileDocument.getUri(), mimeType);
        }

        // Open files for KDE Itinerary explicitly because Android's activity resolution sucks
        if (fileDocument.getName().endsWith(".itinerary")) {
            intent.setClassName("org.kde.itinerary", "org.kde.itinerary.Activity");
        }

        getDevice().getContext().startActivity(intent);
    }
}

/*
 * SPDX-FileCopyrightText: 2018 Erik Duisters <e.duisters1@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.Plugins.SftpPlugin;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.apache.sshd.common.file.FileSystemView;
import org.apache.sshd.common.file.SshFile;
import org.apache.sshd.common.file.nativefs.NativeSshFile;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AndroidSafFileSystemView implements FileSystemView {
    final String userName;
    final Context context;
    private final Map<String, String> roots;
    private final RootFile rootFile;

    AndroidSafFileSystemView(Map<String, String> roots, String userName, Context context) {
        this.roots = roots;
        this.userName = userName;
        this.context = context;
        this.rootFile = new RootFile( createFileList(), userName, true);
    }

    private List<SshFile> createFileList() {
        List<SshFile> list = new ArrayList<>();
        for (Map.Entry<String, String> entry : roots.entrySet()) {
            String displayName = entry.getKey();
            String uri = entry.getValue();

            Uri treeUri = Uri.parse(uri);
            Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            list.add(createAndroidSafSshFile(null, documentUri, File.separatorChar + displayName));
        }

        return list;
    }

    @Override
    public SshFile getFile(String file) {
        return getFile("/", file);
    }

    @Override
    public SshFile getFile(SshFile baseDir, String file) {
        return getFile(baseDir.getAbsolutePath(), file);
    }

    protected SshFile getFile(String dir, String file) {
        if (!dir.endsWith("/")) {
            dir = dir + "/";
        }

        if (!file.startsWith("/")) {
            file = dir + file;
        }

        String filename = NativeSshFile.getPhysicalName("/", "/", file, false);

        if (filename.equals("/")) {
            return rootFile;
        }

        for (String root : roots.keySet()) {
            if (filename.indexOf(root) == 1) {
                String nameWithoutRoot = filename.substring(root.length() + 1);
                String pathOrUri = roots.get(root);

                Uri treeUri = Uri.parse(pathOrUri);
                if (nameWithoutRoot.isEmpty()) {
                    //TreeDocument
                    Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));

                    return createAndroidSafSshFile(documentUri, documentUri, filename);
                } else {
                    /*
                        When sharing a root document tree like "Internal Storage" documentUri looks like:
                            content://com.android.externalstorage.documents/tree/primary:/document/primary:
                        For a file or folder beneath that the uri looks like:
                            content://com.android.externalstorage.documents/tree/primary:/document/primary:Folder/file.txt

                        Sharing a non root document tree the documentUri looks like:
                            content://com.android.externalstorage.documents/tree/primary:Download/document/primary:Download
                        For a file or folder beneath that the uri looks like:
                            content://com.android.externalstorage.documents/tree/primary:Download/document/primary:Download/Folder/file.txt
                     */
                    String treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
                    File nameWithoutRootFile = new File(nameWithoutRoot);
                    String parentSuffix = nameWithoutRootFile.getParent();
                    String parentDocumentId = treeDocumentId + ("/".equals(parentSuffix) ? "" : parentSuffix);

                    Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocumentId);

                    String documentId = treeDocumentId + (treeDocumentId.endsWith(":") ? nameWithoutRoot.substring(1) : nameWithoutRoot);
                    Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);

                    return createAndroidSafSshFile(parentUri, documentUri, filename);
                }
            }
        }

        //It's a file under / but not one covered by any Tree
        return new RootFile(new ArrayList<>(0), userName, false);
    }

    public AndroidSafSshFile createAndroidSafSshFile(Uri parentUri, Uri documentUri, String virtualFilename) {
        return new AndroidSafSshFile(this, parentUri, documentUri, virtualFilename);
    }

    @Override
    public FileSystemView getNormalizedView() {
        return this;
    }
}

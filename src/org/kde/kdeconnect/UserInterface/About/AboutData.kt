/*
 * SPDX-FileCopyrightText: 2021 Maxim Leshchenko <cnmaks90@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.UserInterface.About

import android.content.Context
import android.os.Parcel
import android.os.Parcelable

class AboutData(var name: String, var description: Int, var icon: Int, var versionName: String, var copyrightStatement: String? = null,
                var bugURL: String? = null, var websiteURL: String? = null, var sourceCodeURL: String? = null, var donateURL: String? = null,
                var authorsFooterText: Int? = null) : Parcelable {
    val authors: MutableList<AboutPerson> = mutableListOf()

    constructor(parcel: Parcel) : this(parcel.readString()!!, parcel.readInt(), parcel.readInt(), parcel.readString()!!, parcel.readString(),
                                       parcel.readString(), parcel.readString(), parcel.readString(), parcel.readString(),
                                       if (parcel.readByte() == 0x01.toByte()) parcel.readInt() else null) {
        parcel.readList(authors as List<*>, AboutPerson::class.java.classLoader)
    }

    fun getDescriptionString(context: Context): String = context.resources.getString(description)

    companion object CREATOR : Parcelable.Creator<AboutData> {
        override fun createFromParcel(parcel: Parcel): AboutData = AboutData(parcel)
        override fun newArray(size: Int): Array<AboutData?> = arrayOfNulls(size)
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(name)
        parcel.writeInt(description)
        parcel.writeInt(icon)
        parcel.writeString(versionName)
        parcel.writeString(copyrightStatement)
        parcel.writeList(authors.toList())

        parcel.writeString(bugURL)
        parcel.writeString(websiteURL)
        parcel.writeString(sourceCodeURL)
        parcel.writeString(donateURL)

        if (authorsFooterText == null) {
            parcel.writeByte(0x00)
        } else {
            parcel.writeByte(0x01)
            parcel.writeInt(authorsFooterText!!)
        }
    }

    override fun describeContents(): Int = 0
}
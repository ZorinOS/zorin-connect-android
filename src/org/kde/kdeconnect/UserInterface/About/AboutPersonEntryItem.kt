/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 * SPDX-FileCopyrightText: 2021 Maxim Leshchenko <cnmaks90@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.UserInterface.About

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.widget.TooltipCompat
import org.kde.kdeconnect.UserInterface.List.ListAdapter
import com.zorinos.zorin_connect.R
import com.zorinos.zorin_connect.databinding.AboutPersonListItemEntryBinding

class AboutPersonEntryItem(val person: AboutPerson) : ListAdapter.Item {
    override fun inflateView(layoutInflater: LayoutInflater): View {
        val binding = AboutPersonListItemEntryBinding.inflate(layoutInflater)

        binding.aboutPersonListItemEntryName.text = person.name

        if (person.task != null) {
            binding.aboutPersonListItemEntryTask.visibility = View.VISIBLE
            binding.aboutPersonListItemEntryTask.text = layoutInflater.context.getString(person.task)
        }

        if (person.emailAddress != null) {
            binding.aboutPersonListItemEntryEmailButton.visibility = View.VISIBLE
            TooltipCompat.setTooltipText(binding.aboutPersonListItemEntryEmailButton, layoutInflater.context.getString(R.string.email_contributor, person.emailAddress))
            binding.aboutPersonListItemEntryEmailButton.setOnClickListener {
                layoutInflater.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("mailto:" + person.emailAddress)))
            }
        }

        if (person.webAddress != null) {
            binding.aboutPersonListItemEntryVisitHomepageButton.visibility = View.VISIBLE
            TooltipCompat.setTooltipText(binding.aboutPersonListItemEntryVisitHomepageButton, layoutInflater.context.resources.getString(R.string.visit_contributors_homepage, person.webAddress))
            binding.aboutPersonListItemEntryVisitHomepageButton.setOnClickListener {
                layoutInflater.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(person.webAddress)))
            }
        }

        return binding.root
    }
}

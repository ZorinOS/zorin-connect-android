/*
 * SPDX-FileCopyrightText: 2024 TPJ Schikhof <kde@schikhof.eu>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/
package org.kde.kdeconnect.Helpers

import org.junit.Assert
import org.junit.Test

class VideoUrlsHelperTest {
    @Test
    fun checkYoutubeURL() {
        val url = "https://www.youtube.com/watch?v=ovX5G0O5ZvA&t=13"
        val formatted = VideoUrlsHelper.formatUriWithSeek(url, 51_000L)
        val expected = "https://www.youtube.com/watch?v=ovX5G0O5ZvA&t=51"
        Assert.assertEquals(expected, formatted.toString())
    }

    @Test
    fun checkYoutubeURLSubSecond() {
        val url = "https://www.youtube.com/watch?v=ovX5G0O5ZvA&t=13"
        val formatted = VideoUrlsHelper.formatUriWithSeek(url, 450L)
        val expected = "https://www.youtube.com/watch?v=ovX5G0O5ZvA&t=13"
        Assert.assertEquals(expected, formatted.toString())
    }

    @Test
    fun checkVimeoURL() {
        val url = "https://vimeo.com/347119375?foo=bar&t=13s"
        val formatted = VideoUrlsHelper.formatUriWithSeek(url, 51_000L)
        val expected = "https://vimeo.com/347119375?foo=bar&t=51s"
        Assert.assertEquals(expected, formatted.toString())
    }

    @Test
    fun checkVimeoURLSubSecond() {
        val url = "https://vimeo.com/347119375?foo=bar&t=13s"
        val formatted = VideoUrlsHelper.formatUriWithSeek(url, 450L)
        val expected = "https://vimeo.com/347119375?foo=bar&t=13s"
        Assert.assertEquals(expected, formatted.toString())
    }

    @Test
    fun checkVimeoURLParamOrderCrash() {
        val url = "https://vimeo.com/347119375?t=13s"
        val formatted = VideoUrlsHelper.formatUriWithSeek(url, 51_000L)
        val expected = "https://vimeo.com/347119375?t=51s"
        Assert.assertEquals(expected, formatted.toString())
    }

    @Test
    fun checkDailymotionURL() {
        val url = "https://www.dailymotion.com/video/xnopyt?foo=bar&start=13"
        val formatted = VideoUrlsHelper.formatUriWithSeek(url, 51_000L)
        val expected = "https://www.dailymotion.com/video/xnopyt?foo=bar&start=51"
        Assert.assertEquals(expected, formatted.toString())
    }

    @Test
    fun checkTwitchURL() {
        val url = "https://www.twitch.tv/videos/123?foo=bar&t=1h2m3s"
        val formatted = VideoUrlsHelper.formatUriWithSeek(url, 10_000_000)
        val expected = "https://www.twitch.tv/videos/123?foo=bar&t=02h46m40s"
        Assert.assertEquals(expected, formatted.toString())
    }

    @Test
    fun checkUnknownURL() {
        val url = "https://example.org/cool_video.mp4"
        val formatted = VideoUrlsHelper.formatUriWithSeek(url, 51_000L)
        val expected = "https://example.org/cool_video.mp4"
        Assert.assertEquals(expected, formatted.toString())
    }

    @Test
    fun checkPeerTubeURL() {
        val validUrls = mapOf(
            "https://video.blender.org/w/472h2s5srBFmAThiZVw96R?start=01m27s" to "https://video.blender.org/w/472h2s5srBFmAThiZVw96R?start=01m30s",
            "https://video.blender.org/w/mDyZP2TrdjjjNRMoVUgPM2?start=01m27s" to "https://video.blender.org/w/mDyZP2TrdjjjNRMoVUgPM2?start=01m30s",
            "https://video.blender.org/w/evhMcVhvK6VeAKJwCSuHSe?start=01m27s" to "https://video.blender.org/w/evhMcVhvK6VeAKJwCSuHSe?start=01m30s",
            "https://video.blender.org/w/54tzKpEguEEu26Hi8Lcpna?start=01m27s" to "https://video.blender.org/w/54tzKpEguEEu26Hi8Lcpna?start=01m30s",
            "https://video.blender.org/w/o5VtGNQaNpFNNHiJbLy4eM?start=01m27s" to "https://video.blender.org/w/o5VtGNQaNpFNNHiJbLy4eM?start=01m30s",
        )
        for ((from, to) in validUrls) {
            val formatted = VideoUrlsHelper.formatUriWithSeek(from, 90_000L)
            Assert.assertEquals(to, formatted.toString())
        }
        val invalidUrls = listOf(
            "https://video.blender.org/w/472h2s5srBFmAOhiZVw96R?start=01m27s", // invalid character (O)
            "https://video.blender.org/w/mDyZP2TrdjjjNIMoVUgPM2?start=01m27s", // invalid character (I)
            "https://video.blender.org/w/evhMcVhvK6VeAlJwCSuHSe?start=01m27s", // invalid character (l)
            "https://video.blender.org/w/54tzKpEguEEu20Hi8Lcpna?start=01m27s", // invalid character (0)
            "https://video.blender.org/w/o5VtGNQaNpFNHiJbLy4eM?start=01m27s", // invalid length (21)
            "https://video.blender.org/w/hb43bRmBzNpHd4sW74Y4cyAB?start=01m27s", // invalid length (23)
        )
        for (url in invalidUrls) {
            val formatted = VideoUrlsHelper.formatUriWithSeek(url, 90_000L)
            Assert.assertEquals(url, formatted.toString()) // should not modify the URL
        }
    }
}

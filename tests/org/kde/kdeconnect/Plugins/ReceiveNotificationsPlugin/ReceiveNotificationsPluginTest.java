/*
 * SPDX-FileCopyrightText: 2025 Contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.Plugins.ReceiveNotificationsPlugin;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ReceiveNotificationsPluginTest {

    @Test
    public void fromHtml_plainTextNoAngleBrackets_returnsSameString() {
        String input = "Hello world";
        CharSequence result = ReceiveNotificationsPlugin.fromHtml(input);
        assertEquals("Hello world", result.toString());
    }

    @Test
    public void fromHtml_plainTextWithNumbers_returnsSameString() {
        String input = "Battery level 85%";
        CharSequence result = ReceiveNotificationsPlugin.fromHtml(input);
        assertEquals("Battery level 85%", result.toString());
    }

    @Test
    public void fromHtml_boldTag_stripsTag() {
        String input = "<b>Important</b> message";
        CharSequence result = ReceiveNotificationsPlugin.fromHtml(input);
        assertEquals("Important message", result.toString());
    }

    @Test
    public void fromHtml_italicTag_stripsTag() {
        String input = "<i>emphasis</i> here";
        CharSequence result = ReceiveNotificationsPlugin.fromHtml(input);
        assertEquals("emphasis here", result.toString());
    }

    @Test
    public void fromHtml_brTag_convertsToNewline() {
        String input = "Line1<br>Line2";
        CharSequence result = ReceiveNotificationsPlugin.fromHtml(input);
        assertEquals("Line1\nLine2", result.toString());
    }

    @Test
    public void fromHtml_brSlashTag_convertsToNewline() {
        String input = "Line1<br/>Line2";
        CharSequence result = ReceiveNotificationsPlugin.fromHtml(input);
        assertEquals("Line1\nLine2", result.toString());
    }

    @Test
    public void fromHtml_multipleTags_stripsAll() {
        String input = "<b>Bold</b> and <i>italic</i><br>new line";
        CharSequence result = ReceiveNotificationsPlugin.fromHtml(input);
        assertEquals("Bold and italic\nnew line", result.toString());
    }

    @Test
    public void fromHtml_htmlEntities_decodesThem() {
        String input = "5 &lt; 10 &amp; 10 &gt; 5";
        CharSequence result = ReceiveNotificationsPlugin.fromHtml(input);
        assertEquals("5 < 10 & 10 > 5", result.toString());
    }

    @Test
    public void fromHtml_lessThanSymbol_treatedAsHtml() {
        String input = "5 < 10";
        CharSequence result = ReceiveNotificationsPlugin.fromHtml(input);
        assertEquals("5 < 10", result.toString());
    }

    @Test
    public void fromHtml_emptyString_returnsEmpty() {
        String input = "";
        CharSequence result = ReceiveNotificationsPlugin.fromHtml(input);
        assertEquals("", result.toString());
    }

    @Test
    public void fromHtml_pTags_stripsTags() {
        String input = "<p>Paragraph</p>";
        CharSequence result = ReceiveNotificationsPlugin.fromHtml(input);
        assertEquals("Paragraph", result.toString().trim());
    }

    @Test
    public void fromHtml_malformedHtml_handlesGracefully() {
        String input = "<b>unclosed bold";
        CharSequence result = ReceiveNotificationsPlugin.fromHtml(input);
        assertEquals("unclosed bold", result.toString());
    }

    @Test
    @Config(sdk = 23)
    public void fromHtml_preApi24_handlesHtml() {
        String input = "<b>Bold text</b>";
        CharSequence result = ReceiveNotificationsPlugin.fromHtml(input);
        assertEquals("Bold text", result.toString());
    }
}

/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settingslib.spa.widget.preference

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsOpacity.alphaForEnabled
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.widget.ui.SettingsTitle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import android.util.Log;
@Composable
internal fun BaseLayout(
    title: String,
    subTitle: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    enabled: () -> Boolean = { true },
    paddingStart: Dp = SettingsDimension.itemPaddingStart,
    paddingEnd: Dp = SettingsDimension.itemPaddingEnd,
    paddingVertical: Dp = SettingsDimension.itemPaddingVertical,
    widget: @Composable () -> Unit = {},
) {
    Log.w("BaseLayout"," title "+title + ",paddingVertical "+paddingVertical + ",paddingStart "+paddingStart + ",paddingEnd: "+paddingEnd);
    var horizontalPad = paddingEnd;
    //if(title.equals("开发者选项") || title.equals("重置蓝牙和WLAN")){
    if(title.contains("开发者") || title.contains("重置蓝牙")){
        horizontalPad = 0.dp;
    }
    
    Log.w("BaseLayout"," title "+title + ",horizontalPad "+horizontalPad + ",paddingStart "+paddingStart + ",paddingEnd: "+paddingEnd);
    Card (
            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp, horizontal = horizontalPad),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(0.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        )  
        {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(end = horizontalPad),
            verticalAlignment = Alignment.CenterVertically,
        ) {
        val alphaModifier = Modifier.alphaForEnabled(enabled())
        BaseIcon(icon, alphaModifier, paddingStart)
        Titles(
            title = title,
            subTitle = subTitle,
            modifier = alphaModifier
                .weight(1f)
                .padding(vertical = 6.dp),
        )
        widget()
    }}
}

@Composable
internal fun BaseIcon(
    icon: @Composable (() -> Unit)?,
    modifier: Modifier,
    paddingStart: Dp,
) {
    if (icon != null) {
        Box(
            modifier = modifier.size(SettingsDimension.itemIconContainerSize),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
    } else {
        Spacer(modifier = Modifier.width(width = 16.dp))
    }
}

// Extracts a scope to avoid frequent recompose outside scope.
@Composable
private fun Titles(title: String, subTitle: @Composable () -> Unit, modifier: Modifier) {
    Column(modifier) {
        SettingsTitle(title)
        subTitle()
    }
}

@Preview
@Composable
private fun BaseLayoutPreview() {
    SettingsTheme {
        BaseLayout(
            title = "Title",
            subTitle = {
                HorizontalDivider(thickness = 10.dp)
            }
        )
    }
}

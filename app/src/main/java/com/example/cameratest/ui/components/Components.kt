package com.example.cameratest.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.cameratest.data.NetScope
import com.example.cameratest.ui.theme.Green
import com.example.cameratest.ui.theme.Muted

@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = Green, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
            }
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabeledTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    placeholder: String = ""
) {
    Column(modifier.padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Muted)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier.fillMaxWidth(),
            singleLine = singleLine,
            placeholder = { if (placeholder.isNotEmpty()) Text(placeholder, color = Muted) },
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Green,
                unfocusedBorderColor = Muted.copy(alpha = 0.5f),
                cursorColor = Green
            )
        )
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = MaterialTheme.colorScheme.onPrimary)
    ) {
        Text(text, modifier = Modifier.padding(vertical = 4.dp), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ScopeBadge(scope: NetScope) {
    val (text, color) = when (scope) {
        NetScope.LAN -> "内网" to Green
        NetScope.WAN -> "外网" to com.example.cameratest.ui.theme.Warn
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun StatusDot(ok: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(10.dp),
        shape = RoundedCornerShape(50),
        color = if (ok) Green else com.example.cameratest.ui.theme.Danger
    ) {}
}

@Composable
fun RowScope.SpacerW(width: Int = 8) {
    Spacer(Modifier.width(width.dp))
}

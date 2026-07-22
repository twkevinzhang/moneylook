package tw.kevinzhang.moneylook.ui.analysis

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tw.kevinzhang.moneylook.ui.home.formatCurrencyAmount
import kotlin.math.max

/**
 * Embeddable third tab for the global ledger. Date, account, tag, and currency filters are owned
 * by that parent screen; this composable renders the resulting safe reporting presentation.
 */
@Composable
fun AnalysisContent(
    presentation: AnalysisPresentation,
    selectedDirection: AnalysisDirection = AnalysisDirection.EXPENSE,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SectionHeading("${presentation.periodLabel}總收支", "以 ${presentation.currency} 統計")
        SummaryCard(presentation)

        SectionHeading("本期分類", "錢花在哪裡？")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                CategoryDonut(
                    slices = presentation.categorySlices(selectedDirection),
                    currency = presentation.currency,
                    direction = selectedDirection,
                    periodLabel = presentation.periodLabel,
                )
            }
        }

        SectionHeading("近半年趨勢", "收入與支出的月度變化")
        TrendCard(presentation)
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SummaryCard(presentation: AnalysisPresentation) {
    val summary = presentation.summary
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SummaryLine("結餘", formatCurrencyAmount(summary.balance, presentation.currency), emphasized = true)
            SummaryLine("收入", formatCurrencyAmount(summary.income, presentation.currency), accent = true)
            SummaryLine("支出", "-${formatCurrencyAmount(summary.expense, presentation.currency)}")
        }
    }
}

@Composable
private fun SummaryLine(label: String, value: String, emphasized: Boolean = false, accent: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = if (emphasized) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge)
        Text(
            value,
            style = if (emphasized) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun CategoryDonut(
    slices: List<AnalysisCategorySlice>,
    currency: String,
    direction: AnalysisDirection,
    periodLabel: String,
) {
    val total = slices.sumOf(AnalysisCategorySlice::amount)
    val description = if (total == 0.0) {
        "$periodLabel${if (direction == AnalysisDirection.INCOME) "收入" else "支出"}沒有可分析的交易"
    } else {
        "$periodLabel${if (direction == AnalysisDirection.INCOME) "收入" else "支出"}分類總計 ${formatCurrencyAmount(total, currency)}；" +
            slices.joinToString { "${it.name} ${formatCurrencyAmount(it.amount, currency)}" }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(220.dp)) {
            Canvas(modifier = Modifier.size(200.dp)) {
                val strokeWidth = size.minDimension * .24f
                val bounds = Size(size.width - strokeWidth, size.height - strokeWidth)
                val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)
                if (total <= 0.0) {
                    drawArc(
                        color = Color(0xFFE1E3E6),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = bounds,
                        style = Stroke(strokeWidth, cap = StrokeCap.Butt),
                    )
                } else {
                    var start = -90f
                    slices.forEachIndexed { index, slice ->
                        val sweep = (slice.amount / total * 360.0).toFloat()
                        drawArc(
                            color = slice.color.asChartColor(index),
                            startAngle = start,
                            sweepAngle = sweep,
                            useCenter = false,
                            topLeft = topLeft,
                            size = bounds,
                            style = Stroke(strokeWidth, cap = StrokeCap.Butt),
                        )
                        start += sweep
                    }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("總計", style = MaterialTheme.typography.labelMedium)
                Text(formatCurrencyAmount(total, currency), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
        if (slices.isEmpty()) {
            Text("尚無可分析的交易", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            slices.forEachIndexed { index, slice ->
                CategoryLegendRow(slice, total, index, currency)
            }
        }
    }
}

@Composable
private fun CategoryLegendRow(slice: AnalysisCategorySlice, total: Double, index: Int, currency: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(
            Modifier.size(10.dp).background(slice.color.asChartColor(index), CircleShape),
        )
        Text(slice.name, modifier = Modifier.padding(start = 8.dp).weight(1f))
        Text(
            "${(slice.amount / total * 100).toInt()}%  ${formatCurrencyAmount(slice.amount, currency)}",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun TrendCard(presentation: AnalysisPresentation) {
    val points = presentation.trend
    val incomeColor = MaterialTheme.colorScheme.primary
    val expenseColor = MaterialTheme.colorScheme.tertiary
    val description = points.joinToString(prefix = "近半年趨勢：") { point ->
        "${point.month.shortLabel}收入 ${formatCurrencyAmount(point.income, presentation.currency)}、支出 ${formatCurrencyAmount(point.expense, presentation.currency)}"
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TrendLegend("收入", MaterialTheme.colorScheme.primary)
                TrendLegend("支出", MaterialTheme.colorScheme.tertiary)
            }
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
                    .padding(top = 18.dp)
                    .testTag("analysis-trend-chart")
                    .semantics { contentDescription = description },
            ) {
                val maxValue = max(1.0, points.maxOfOrNull { max(it.income, it.expense) } ?: 1.0).toFloat()
                val left = 8.dp.toPx()
                val right = size.width - 8.dp.toPx()
                val top = 8.dp.toPx()
                val bottom = size.height - 8.dp.toPx()
                repeat(4) { index ->
                    val y = top + (bottom - top) * index / 3f
                    drawLine(Color(0xFFE5E7EB), Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
                }
                fun coordinate(index: Int, value: Double): Offset = Offset(
                    x = if (points.size <= 1) size.width / 2 else left + (right - left) * index / (points.size - 1),
                    y = bottom - (bottom - top) * (value.toFloat() / maxValue),
                )
                fun drawSeries(values: List<Double>, color: Color) {
                    values.zipWithNext().forEachIndexed { index, (a, b) ->
                        drawLine(color, coordinate(index, a), coordinate(index + 1, b), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                    }
                    values.forEachIndexed { index, value ->
                        drawCircle(color, radius = 4.dp.toPx(), center = coordinate(index, value))
                    }
                }
                drawSeries(points.map(AnalysisTrendPoint::income), incomeColor)
                drawSeries(points.map(AnalysisTrendPoint::expense), expenseColor)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                points.forEach { point ->
                    Text(point.month.shortLabel, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun TrendLegend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.size(10.dp).background(color, CircleShape))
        Text(label, modifier = Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelMedium)
    }
}

private fun String?.asChartColor(index: Int): Color {
    val fallback = listOf(0xFF20A4B4, 0xFFF6A623, 0xFF64B5F6, 0xFF66BB6A, 0xFFAB47BC)
    val parsed = runCatching { AndroidColor.parseColor(this) }.getOrNull()
    return parsed?.let(::Color) ?: Color(fallback[index % fallback.size])
}

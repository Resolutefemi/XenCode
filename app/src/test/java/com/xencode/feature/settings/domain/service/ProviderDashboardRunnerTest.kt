package com.xencode.feature.settings.domain.service

import com.xencode.feature.settings.domain.model.BadgeElement
import com.xencode.feature.settings.domain.model.CardColor
import com.xencode.feature.settings.domain.model.ColumnSetElement
import com.xencode.feature.settings.domain.model.ContainerElement
import com.xencode.feature.settings.domain.model.ContainerStyle
import com.xencode.feature.settings.domain.model.DividerElement
import com.xencode.feature.settings.domain.model.FactSetElement
import com.xencode.feature.settings.domain.model.MetricElement
import com.xencode.feature.settings.domain.model.ProgressBarElement
import com.xencode.feature.settings.domain.model.RowElement
import com.xencode.feature.settings.domain.model.StatusDotElement
import com.xencode.feature.settings.domain.model.TextBlockElement
import com.xencode.feature.settings.domain.model.TextSize
import com.xencode.feature.settings.domain.model.TextWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderDashboardRunnerTest {

    private val subscriptionCardJson = """
        {
          "type": "AdaptiveCard",
          "version": "1.5",
          "compact": {
            "type": "ColumnSet",
            "spacing": "Medium",
            "columns": [
              {
                "type": "Column",
                "width": "stretch",
                "items": [
                  { "type": "TextBlock", "text": "5h 80%", "size": "Small", "weight": "Bolder", "color": "Good" },
                  { "type": "ProgressBar", "value": 80, "color": "Good", "height": 3 }
                ]
              },
              {
                "type": "Column",
                "width": "stretch",
                "items": [
                  { "type": "TextBlock", "text": "7d 65%", "size": "Small", "weight": "Bolder", "color": "Accent" },
                  { "type": "ProgressBar", "value": 65, "color": "Accent", "height": 3 }
                ]
              }
            ]
          },
          "body": [
            {
              "type": "ColumnSet",
              "columns": [
                {
                  "type": "Column",
                  "width": "stretch",
                  "items": [
                    { "type": "TextBlock", "text": "5h window", "size": "Small", "isSubtle": true },
                    { "type": "TextBlock", "text": "80%", "size": "Medium", "weight": "Bolder", "color": "Good" },
                    { "type": "ProgressBar", "value": 80, "color": "Good" },
                    { "type": "TextBlock", "text": "4.0 / 5.0 hours", "size": "Small", "isSubtle": true }
                  ]
                },
                {
                  "type": "Column",
                  "width": "stretch",
                  "separator": true,
                  "items": [
                    { "type": "TextBlock", "text": "7d window", "size": "Small", "isSubtle": true },
                    { "type": "TextBlock", "text": "65%", "size": "Medium", "weight": "Bolder", "color": "Accent" },
                    { "type": "ProgressBar", "value": 65, "color": "Accent" },
                    { "type": "TextBlock", "text": "4.6 / 7.0 days", "size": "Small", "isSubtle": true }
                  ]
                }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun testParseSubscriptionCard() {
        val result = ProviderDashboardRunner.parseDashboardJson(subscriptionCardJson)
        val card = result.card

        assertEquals("1.5", card.version)

        // Verify compact
        assertNotNull(card.compact)
        val compactCols = card.compact as ColumnSetElement
        assertEquals(2, compactCols.columns.size)

        // Verify the body ColumnSet
        assertEquals(1, card.body.size)
        val columnSet = card.body[0] as ColumnSetElement
        assertEquals(2, columnSet.columns.size)

        val col1 = columnSet.columns[0]
        assertEquals(4, col1.items.size)
        val text1 = col1.items[0] as TextBlockElement
        assertEquals("5h window", text1.text)
        assertEquals(TextSize.SMALL, text1.size)
        assertTrue(text1.isSubtle)

        val progress1 = col1.items[2] as ProgressBarElement
        assertEquals(80f, progress1.value, 0.01f)
        assertEquals(CardColor.Good, progress1.color)

        val col2 = columnSet.columns[1]
        assertTrue(col2.separator)
        val progress2 = col2.items[2] as ProgressBarElement
        assertEquals(65f, progress2.value, 0.01f)
        assertEquals(CardColor.Accent, progress2.color)
    }

    @Test
    fun testParseContainerAndBadge() {
        val alertJson = """
            {
              "type": "AdaptiveCard",
              "body": [
                {
                  "type": "Container",
                  "style": "Attention",
                  "items": [
                    { "type": "Badge", "text": "Quota exhausted", "style": "Attention" },
                    { "type": "TextBlock", "text": "Please top up promptly", "weight": "Bolder", "color": "Attention" }
                  ]
                },
                {
                  "type": "Metric",
                  "label": "Current balance",
                  "value": "$0.00",
                  "color": "Attention"
                }
              ]
            }
        """.trimIndent()

        val result = ProviderDashboardRunner.parseDashboardJson(alertJson)
        val card = result.card
        assertEquals(2, card.body.size)

        val container = card.body[0] as ContainerElement
        assertEquals(ContainerStyle.ATTENTION, container.style)
        assertEquals(2, container.items.size)

        val badge = container.items[0] as BadgeElement
        assertEquals("Quota exhausted", badge.text)
        assertEquals(ContainerStyle.ATTENTION, badge.style)

        val metric = card.body[1] as MetricElement
        assertEquals("Current balance", metric.label)
        assertEquals("$0.00", metric.value)
        assertEquals(CardColor.Attention, metric.color)
    }

    @Test
    fun testFineGrainedLayoutAndTypography() {
        val fineGrainedJson = """
            {
              "type": "AdaptiveCard",
              "body": [
                {
                  "type": "ColumnSet",
                  "gap": "10dp",
                  "padding": [8, 16],
                  "margin": 4,
                  "columns": [
                    {
                      "width": "80dp",
                      "minHeight": "60dp",
                      "verticalAlignment": "Center",
                      "items": [
                        {
                          "type": "TextBlock",
                          "text": "80%",
                          "size": 24,
                          "lineHeight": "28sp",
                          "align": "center"
                        }
                      ]
                    },
                    {
                      "width": "stretch",
                      "items": [
                        {
                          "type": "ProgressBar",
                          "value": 80,
                          "cornerRadius": 3,
                          "showPercent": true,
                          "text": "80% used"
                        }
                      ]
                    }
                  ]
                },
                {
                  "type": "Container",
                  "cornerRadius": 8,
                  "gap": 6,
                  "padding": { "top": 6, "right": 12, "bottom": 6, "left": 12 },
                  "items": [
                    {
                      "type": "TextBlock",
                      "text": "Custom padding and line-height note",
                      "fontSize": 13.5,
                      "lineHeight": 18
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val result = ProviderDashboardRunner.parseDashboardJson(fineGrainedJson)
        val card = result.card
        assertEquals(2, card.body.size)

        // Verify the ColumnSet
        val columnSet = card.body[0] as ColumnSetElement
        assertEquals(10, columnSet.gapDp)
        assertEquals(8, columnSet.padding?.top)
        assertEquals(16, columnSet.padding?.right)
        assertEquals(8, columnSet.padding?.bottom)
        assertEquals(16, columnSet.padding?.left)
        assertEquals(4, columnSet.margin?.top)

        val col1 = columnSet.columns[0]
        assertEquals(80, (col1.width as com.xencode.feature.settings.domain.model.ColumnWidth.Fixed).dp)
        assertEquals(60, col1.minHeightDp)
        assertEquals("Center", col1.verticalContentAlignment)

        val text1 = col1.items[0] as TextBlockElement
        assertEquals("80%", text1.text)
        assertEquals(24f, text1.fontSizeSp)
        assertEquals(28f, text1.lineHeightSp)
        assertEquals("center", text1.horizontalAlignment)

        val col2 = columnSet.columns[1]
        val progress = col2.items[0] as ProgressBarElement
        assertEquals(80f, progress.value, 0.01f)
        assertEquals(3, progress.cornerRadiusDp)
        assertTrue(progress.showPercent)
        assertEquals("80% used", progress.text)

        // Verify the Container
        val container = card.body[1] as ContainerElement
        assertEquals(8, container.cornerRadiusDp)
        assertEquals(6, container.gapDp)
        assertEquals(6, container.padding?.top)
        assertEquals(12, container.padding?.right)

        val text2 = container.items[0] as TextBlockElement
        assertEquals(13.5f, text2.fontSizeSp)
        assertEquals(18f, text2.lineHeightSp)
    }
}

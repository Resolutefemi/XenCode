# Token Stats and Costs

"Settings → Token Stats" records the consumption of every AI call so you can review token usage, costs, and cache hits.

Recording starts from when the feature shipped; earlier historical calls have no details. The page refreshes in real time.

## Period Switching and Dimension Filters

Four tabs at the top: Today / Last 7 days / Last 30 days / All. Switching updates every card, chart, and list below in sync. The "Today" trend chart is hourly; the others are daily.

Below the period tabs is a row of dimension filters:
- **Provider filter**: Choose "All providers" or a specific provider from the dropdown.
- **Model filter**: Choose "All models" or a specific model from the dropdown.
- **Clear filters**: Appears once a filter is active; tap it to reset everything back to the full data in one go.
- Additionally, tapping any row in "Provider stats" or "Model stats" quickly sets that item as a filter; tap it again to clear it.

## Overview

Four cards: total consumption (input plus output), request count, cost, and cache hit rate.

Cost is an **estimate**. Custom prices you entered under "Providers → Models" take priority; models without them are priced from models.dev's public rates: cache reads use the cache price, or an estimated 10% of the input price when no cache price exists; cache writes use the cache-write price, or 1.25× the input price when none exists. Total cost applies prices per "provider + model" pair and then sums them, so the same model called through different providers is priced separately. The cache hit rate is cache reads divided by total input, with this period's cache-read and cache-write token counts shown below the card.

::: tip Costs are for reference only
This is an estimate based on public unit prices, not a bill. If a relay changed its pricing, offers discounts, or your model has no price data, the estimate will differ from the actual charge. Your provider's billing dashboard is the source of truth.
:::

## Usage Trends

The line chart shows input, output, and cache-hit lines over the period (blue for input, orange for output, purple for cache), with translucent fill below each line to make overlapping areas easier to tell apart. The left axis is the token count (abbreviated with k / M).

The time axis spans the full period, with days or hours that have no records filled in as 0, so the trend remains readable even when calls are concentrated on a single day.

## Provider and Model Stats

**Provider stats** aggregate by provider, showing the provider name, the number of configured key credentials, a success-rate percentage with a horizontal progress bar, and the request count and token consumption highlighted on the right, sorted by total consumption in descending order.

**Model stats** aggregate by model; each row shows the model name, input/output consumption, call count, and success rate, sorted by total consumption in descending order.

Tap any provider or model row to instantly filter the whole page by it.

## Call Log

A detail table with 10 rows per page, paginated at the bottom; swipe left or right to see all fields:

| Field | Description |
| --- | --- |
| Call time | Year-month-day hour:minute |
| Provider / Model | Which provider and model were used |
| Reasoning effort | The tier used for this call; models without tiers show `-` |
| Status | success (succeeded) / error (failed) / cancelled (cancelled) |
| Input / Output / Cache read / Cache write | Token counts for each category |
| Cache hit rate | Cache-read input divided by total input |
| First-token latency | Time from the request being sent until the first content event (text, reasoning, or a tool call result) arrives |
| Total duration | Total elapsed time of the call |
| Estimated cost | Models without price data show `-` |

First-token latency is also recorded for rounds that are pure tool calls.

## Reset

The trash icon in the top-right corner clears all call records and resets the stats to zero. A confirmation dialog appears — **this cannot be undone once cleared**.

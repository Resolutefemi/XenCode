#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
XenCode subscription quota example script (demo_subscription.py) - subscription Adaptive Card template

This script demonstrates returning quota data in declarative Adaptive Card format for a XenCode provider.
Supports custom layouts for the collapsed state (compact) and the expanded state (body).
"""

import json
import os
import sys

def main():
    # Simulated subscription quotas across the 5h / 7d / 1m windows
    data = {
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
                },
                {
                    "type": "Column",
                    "width": "stretch",
                    "items": [
                        { "type": "TextBlock", "text": "1m 42%", "size": "Small", "weight": "Bolder", "color": "#8B5CF6" },
                        { "type": "ProgressBar", "value": 42, "color": "#8B5CF6", "height": 3 }
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
                            { "type": "TextBlock", "text": "5h window", "size": "Small", "isSubtle": True },
                            { "type": "TextBlock", "text": "80%", "size": "Medium", "weight": "Bolder", "color": "Good" },
                            { "type": "ProgressBar", "value": 80, "color": "Good" },
                            { "type": "TextBlock", "text": "4.0 / 5.0 hours", "size": "Small", "isSubtle": True }
                        ]
                    },
                    {
                        "type": "Column",
                        "width": "stretch",
                        "separator": True,
                        "items": [
                            { "type": "TextBlock", "text": "7d window", "size": "Small", "isSubtle": True },
                            { "type": "TextBlock", "text": "65%", "size": "Medium", "weight": "Bolder", "color": "Accent" },
                            { "type": "ProgressBar", "value": 65, "color": "Accent" },
                            { "type": "TextBlock", "text": "4.6 / 7.0 days", "size": "Small", "isSubtle": True }
                        ]
                    },
                    {
                        "type": "Column",
                        "width": "stretch",
                        "separator": True,
                        "items": [
                            { "type": "TextBlock", "text": "1m window", "size": "Small", "isSubtle": True },
                            { "type": "TextBlock", "text": "42%", "size": "Medium", "weight": "Bolder", "color": "#8B5CF6" },
                            { "type": "ProgressBar", "value": 42, "color": "#8B5CF6" },
                            { "type": "TextBlock", "text": "12.6 / 30 days", "size": "Small", "isSubtle": True }
                        ]
                    }
                ]
            }
        ]
    }

    print(json.dumps(data, ensure_ascii=False))

if __name__ == "__main__":
    main()

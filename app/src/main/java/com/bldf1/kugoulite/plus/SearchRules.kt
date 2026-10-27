package com.bldf1.kugoulite.plus

/** SearchRules：UI 净化规则集（从 UiCleaner.kt 拆出，行为不变）。 */
internal val searchRules: List<UiRule> = listOf(
    UiRule(Settings.KEY_UI_SEARCH_CHANNEL, "搜索页频道推荐",
            listOf(), listOf(), idNames = listOf("gub", "gue"),
            ancestorIdNames = listOf("e1k"),
            requireActivityName = "Search"),
    UiRule(Settings.KEY_UI_SEARCH_CLOSE, "搜索推广关闭钮",
            listOf(), listOf(), idNames = listOf("gu3"), minYRatio = 0.15f,
            hideParentLevels = 1),
    UiRule(Settings.KEY_UI_SEARCH_INDICATOR, "搜索推广指示器",
            listOf(), listOf(), idNames = listOf("gu4"), minYRatio = 0.15f),
)

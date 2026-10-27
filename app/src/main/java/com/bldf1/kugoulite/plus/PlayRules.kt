package com.bldf1.kugoulite.plus

/** PlayRules：UI 净化规则集（从 UiCleaner.kt 拆出，行为不变）。 */
internal val playRules: List<UiRule> = listOf(
    UiRule(Settings.KEY_UI_PLAY_BOOKMARK, "播放页书签",
            // R139：dt7 改用 **INVISIBLE 占位**（见 scan 里的处理），不再用规则 GONE ——
            // GONE 会让布局少一格，标题整体右移；INVISIBLE 保留占位，标题自然居中，
            // 也不需要 translationX 补偿（那种补偿会压制切歌动画，之前踩过）。
            listOf(), listOf(), idNames = listOf("elq"), maxYRatio = 0.12f,
            alphaShield = false),
        // ⚠️ R79 同上：「去每周福利条」也是死开关，实际由「去播放页广告」控制。已改回自己的 key。
    UiRule(Settings.KEY_UI_PLAY_DISCIPLINE, "咱一起自律入口",
            listOf(), listOf(), idNames = listOf("dq4"),
            minYRatio = 0.60f, maxYRatio = 0.75f),
        // ⚠️ R66：本规则关键词只有「广告/推广」两个**极通用的短词**，且**既无页面门禁也无 ID 门禁**
        // ⇒ 会在**其它页面**的正文里误命中。真机实锤（flashscan）：冷启留在发现页时，
        // feed 的**推荐语槽位 b5w** 文本为「7 推荐 - 你是那场电影 而我只是那场电影中的那段广告」
        // ⇒ 命中「广告」⇒ 把推荐语 TextView(794x41) 藏掉，且**藏的时候它已经上过屏**
        // （`严格=是`）＝用户看到推荐语闪一下。同一视图一次会话被藏 3 次。
        // 处方同「频道推荐/免费听区块」那两处历史坑（memory：**推荐语是 b5w，无门禁会误伤**）：
        // 排除推荐语槽位。（更彻底的"只在搜索页生效"需要搜索页祖先 ID，留待后续。）
    UiRule(Settings.KEY_UI_FREE_LISTEN_ENTRY, "免费听入口",
            listOf(), listOf(), idNames = listOf("dka")),
    UiRule(Settings.KEY_UI_FREE_LISTEN_ICON, "免费听图标",
            listOf(), listOf(), idNames = listOf("dg0"), maxYRatio = 0.12f),
            // ⚠️ 不再连父藏 dg1（2026-10-15 真机 A/B 实锤）：dg1(HnVisualEffectLayout)
            // 虽然只有 84×84（免图标位），但酷狗效果引擎内部联动——dg1 被藏后
            // **发现页整页背景渐变（含顶栏）消失变纯白**（用户报「顶栏背景不见了」）。
            // R54 会话曾误归因给 unclipContentPadding 并用顶栏玻璃遮住，玻璃移除后
            // 问题重现才定位到真因。只藏 dg0 自身，dg1 残留与否真机验证后再定。
    UiRule(Settings.KEY_UI_TAB_BADGE, "TAB角标",
            listOf(), listOf(), idNames = listOf("at6", "at7"), minYRatio = 0.85f),
    UiRule(Settings.KEY_UI_PLAY_PUBLISH_TIP, "发布提示",
            listOf(), listOf("使用此音乐发布", "使用此应用发布")),
    UiRule(Settings.KEY_UI_CHANNEL, "频道推荐区块",
            listOf(), listOf("频道推荐"), sectionMode = true, useCardHide = true),
    UiRule(Settings.KEY_UI_FREE_LISTEN_BLOCK, "免费听区块",
            listOf(), listOf("免费听"), sectionMode = true, useCardHide = true,
            excludeIdNames = listOf("dka")),
)

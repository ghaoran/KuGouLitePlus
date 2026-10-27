package com.bldf1.kugoulite.plus

/** AdsRules：UI 净化规则集（从 UiCleaner.kt 拆出，行为不变）。 */
internal val adsRules: List<UiRule> = listOf(
    UiRule(Settings.KEY_UI_SPLASH, "开屏广告",
            listOf("SplashAd", "OpenAd", "SplashAdvert", "GdtAd", "tangramsplash"), listOf()),
    UiRule(Settings.KEY_UI_BANNER, "首页Banner",
            // ⚠️ SlowSmoothSwipeViewPager 从 classKw 移除（真机验证发现误杀）：
            // 该类是通用轮播控件——发现页 feed 中部的运营推荐卡轮播
            // (ew, 1112px≈0.91 屏宽, 3 张正常运营图) 也用它，宽度>minWRatio 0.88
            // 被 scan 命中 → climbParent(1) 把整个 feed item GONE → feed 底部
            // 一大片空白占位（13:07 真机实锤）。广告轮播由 PERSISTENT_HIDE 的
            // Discovery*AdsView 出生拦截 + 「发现页Banner槽位」ela ID 制导覆盖，
            // 不需要类名兜底。与 PERSISTENT_HIDE 注释的历史决定（该类非广告
            // 专属，加入会连累乐库 Banner）一致化
            listOf("MosaicView"),
            listOf(),
            minWRatio = 0.88f, minYRatio = 0.25f, hideParentLevels = 1),
        // 发现页 feed 顶部的淘宝/天猫运营推广位（Round 51+，用户要求隐藏）：
        // 淘宝倒计时条（bnr=FrameLayout：「上淘宝更优惠」+「立即查看」+倒计时）
        // 与天猫活动大卡（fmx=FrameLayout：含 fn1「天猫双11」角标），都在发现页
        // feed RecyclerView(b7e) 里。活动文案随档期变化（双11/618/年货节…），
        // 固定文本会过期 → 用「淘宝/天猫」语义词触发 + 爬升白名单（bnr/fmx）
        // 双门禁：文本命中后只有直接父是 bnr/fmx 才连父隐藏；ID 跨页复用时
        // 退化为只藏文本，绝不牵连同层。
    UiRule(Settings.KEY_UI_TAOBAO_BAR, "发现页淘宝推广条",
            listOf(), listOf("淘宝"), hideParentLevels = 1,
            climbParentIdNames = listOf("bnr")),
    UiRule(Settings.KEY_UI_TMALL_CARD, "发现页天猫活动卡",
            listOf(), listOf("天猫"), hideParentLevels = 1,
            climbParentIdNames = listOf("fmx")),
    UiRule(Settings.KEY_UI_PLAY_AD, "播放页广告",
            listOf("PlayAd", "PlayerAd", "SongAd", "PlayPageAd"), listOf()),
        // ⚠️ R79 修正：本条原先挂在 `KEY_UI_PLAY_AD`（去播放页广告）上 —— 结果面板上
        // 「去播放页书签」这个开关**没有任何实现引用 = 死开关**，而书签的实际隐藏被
        // 「去播放页广告」顺带控制（用户点书签开关毫无反应）。现改回它自己的 key。
    UiRule(Settings.KEY_UI_SEARCH_AD, "搜索推广",
            listOf("SearchAd", "SearchPromo"), listOf("广告", "推广"),
            excludeIdNames = listOf("b5w"),
            // ⭐ R148 治本：限定在搜索类 Activity 内生效。之前没有任何页面门禁，
            // "广告/推广"两个短词在设置页命中了「定向广告推送」「互动广告管理」
            // 「广告应用下载清单」三行标题（用户实锤：关掉本开关这三行才恢复）。
            requireActivityName = "Search",
            // ⭐ R146：搜索推广按"广告/推广"匹配，会连隐私设置里的开关标题一起藏
            // （「定向广告推送」「互动广告管理」），导致设置页出现只剩开关的空白行。
            // 真机日志实锤：UI净化[搜索推广] 即时预隐藏 text="定向广告推送" id=hsb
            excludeTextKeywords = listOf(
                "定向广告推送", "互动广告管理", "广告数量", "广告与您的相关度", "互动广告",
                // 设置页第三处：关闭搜索推广后这一行标题才回来（真机实锤）
                "广告应用下载清单"
            )),
    UiRule(Settings.KEY_UI_SEARCH_BANNER, "搜索推广Banner",
            listOf(), listOf(), idNames = listOf("gu6"),
            minYRatio = 0.20f, hideParentLevels = 2,
            requireActivityName = "Search"),
        // ⭐ R100：搜索页默认页下方的**频道推荐区**（推荐/周榜/次元/AI… tab + 卡片列表）。
        // 祖先链真机实锤（模块内遍历，adb 的 uiautomator/dumpsys 都被该页动画挡住）：
        //   KGUIFrameLayout/gu7 < FrameLayout < ViewPager/**gue** < FrameLayout/**gub**
        //     < LinearLayout < TouchScrollView/**e1k** < RelativeLayout/gtz
        // `gub` 包住整个频道区（tab 栏 + 内容页），`gue` 是频道 tab 的 pager。
        // 用 ancestorIdNames=["e1k"] 限定**只在搜索页**生效 —— gub/gue 是短混淆 id，
        // 别处可能有同名节点（铁律：同类同 id 跨页复用是最大误伤源）。
    UiRule(Settings.KEY_UI_DISCOVER_BANNER_SLOT, "发现页Banner槽位",
            listOf(), listOf(), idNames = listOf("ela")),
)

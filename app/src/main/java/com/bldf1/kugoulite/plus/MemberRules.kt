package com.bldf1.kugoulite.plus

/** MemberRules：UI 净化规则集（从 UiCleaner.kt 拆出，行为不变）。 */
internal val memberRules: List<UiRule> = listOf(
    UiRule(Settings.KEY_UI_WEEKLY_WELFARE, "每周福利条",
            listOf(), listOf("每周福利"), hideParentLevels = 1),
        // 咱一起自律入口（Round 51，用户要求移除）：播放页歌词下方左侧的
        // 情绪/场景标签胶囊（dq4=LinearLayout 图标+文字容器），文本随歌变化
        // （L-O-V-E 歌显示「咱一起自律」、另曲显示「伤感」）→ 不能按文本匹配，
        // 用 ID 制导 + 高度区间门禁（真机 y≈1833/2712≈0.68）限制到播放页该位置。
    UiRule(Settings.KEY_UI_MEMBER_BAR, "会员栏",
            listOf("VipBar", "MemberBar", "VipBanner"), listOf("开通会员", "开通概念版", "即将过期", "自动续费")),
    UiRule(Settings.KEY_UI_MEMBER_WIDGET, "会员控件",
            listOf("VipWidget", "MemberWidget", "VipEntry", "TmeMaskAdDialog"),
            listOf("立即开通", "领取会员")),
    UiRule(
            Settings.KEY_UI_PLUS, "右下角加号",
            listOf("PlusEntry", "FloatEntry", "PlusButton", "FabEntry", "KGTransImageView"),
            listOf(),
            floatArrayOf(0.78f, 0.60f)
        ),
    UiRule(Settings.KEY_UI_INVITE, "邀请提示",
            listOf("InviteTip", "InviteView"), listOf("邀请好友", "三个月会员", "邀好友", "组队领"),
            // ⚠️ ID 门禁（真机实锤 10-12）：feed 推荐语槽位 b5w 的文案可能包含
            // "组队领…VIP"（用户推荐语），无门禁会把**正常推荐卡文案**当成邀请
            // 提示隐藏，且发生在已绘制之后（闪现）。同 b6b/b5w 门禁思路。
            excludeIdNames = listOf("b5w")),
    UiRule(Settings.KEY_UI_LAB, "概念实验室",
            listOf("ConceptLab", "Laboratory", "LabEntry"), listOf("概念实验室"),
            sectionMode = true, useCardHide = true),
    UiRule(Settings.KEY_UI_LOGIN_GUIDE, "登录引导",
            listOf("LoginGuide", "GuideLogin", "LoginTip"), listOf("登录后畅听", "立即登录")),
    UiRule(Settings.KEY_UI_SEARCH_VIP_BAR, "搜索结果VIP条",
            listOf(), listOf(), idNames = listOf("ezd"), minYRatio = 0.10f),
    UiRule(Settings.KEY_UI_VIP_EXPIRE, "VIP过期胶囊",
            listOf(), listOf(), idNames = listOf("c6a")),
    UiRule(Settings.KEY_UI_VIP_LOOK_BAR, "VIP去看看残条",
            listOf(), listOf(), idNames = listOf("f96")),
)

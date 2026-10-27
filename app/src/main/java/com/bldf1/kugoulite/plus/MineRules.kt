package com.bldf1.kugoulite.plus

/** MineRules：UI 净化规则集（从 UiCleaner.kt 拆出，行为不变）。 */
internal val mineRules: List<UiRule> = listOf(
    UiRule(Settings.KEY_UI_MINE_TAB_BAR, "我的页分类栏",
            listOf("MineSpecialTabView"), listOf()),
    UiRule(Settings.KEY_UI_MINE_EARN, "我的页赚钱入口",
            listOf(), listOf(), idNames = listOf("dk_"),
            // ⭐ R72 定稿：只靠 `ancestorClass` + `requireMinePage` 两道门禁。
            //   真机 dump 实锤（v5.2.9）：发现页顶栏的 dk_（乐库 tab）与我的页顶栏的 dk_
            //   （赚钱入口）**同类同 ID**（都是 MainTopImageView），但**祖先链不同**——
            //   发现页那支是 ConstraintLayout→FrameLayout→MainFragmentViewPage（**无** TwoViewSwitchLayout），
            //   我的页那支挂在 TwoViewSwitchLayout 下。尺寸旁证：102x54 vs 102x144。
            //   ⚠️ R66 曾在此加 `excludeClassNames=["MainTopImageView"]`，是**误诊**：
            //      R66 那条 `严格=是 102x144` 其实是**赚钱入口自己被藏晚了 1 帧**，
            //      被误判成"误伤乐库 tab" ⇒ 按类排除 = 把真目标一起废掉
            //      （用户实锤"我的页顶栏赚钱又出现了"）。已回退。
            //   ⚠️ 同时**去掉 `requireSiblingIdName="dk5"`**：已被 ancestorClass 覆盖，且
            //      **挂载瞬间兄弟 dk5 尚未 addView ⇒ 门禁必失败 ⇒ 只能等扫描才藏 ⇒ 闪现**。
            ancestorClass = "TwoViewSwitchLayout"),
            // ⭐ R74：**去掉 `requireMinePage`**。R72 的 dump 已证明 `ancestorClass` 足以区分
            //   （发现页那支祖先链里没有 TwoViewSwitchLayout）；而页面门禁的副作用是：
            //   **切回发现页时会把它当"门禁失败"还原** ⇒ 赚钱入口被画出来（严格=是 102x144）
            //   ⇒ 下次扫描再藏 ⇒ 闪现。去掉后它挂载即藏、跨页保持，不再有还原动作。
        // 我的页白卡内的两行推广（Round 18 实锤：ist=福利与活动/天天签到兑免费
        // VIP，bsb=免费听/全天会员免费领/领会员；关键词规则只隐了 bsa/c6n 两个
        // TextView，残留「免费听」文字+进度条 = 半隐藏残迹）——按行容器整条塌缩
    UiRule(Settings.KEY_UI_MINE_SIGN_BAR, "我的页签到/领会员条",
            listOf(), listOf(), idNames = listOf("ist", "bsb"),
            collapseHeight = true),
        // 会员角标与入口：⚠️ 只藏角标图片 c6u，**不能藏 iv5**——iv5 是白卡
        // 右上角的白色底板（GONE 后露出页面底色 = 白卡右上角缺口，Round 19
        // 用户实锤）；apj=昵称右侧「会员中心」图标（KGTransImageView，desc
        // 命中但不走 setText/TextView 分支，必须 ID 制导）
    UiRule(Settings.KEY_UI_MINE_MEMBER_BADGE, "我的页会员角标",
            listOf(), listOf(), idNames = listOf("c6u", "apj")),
        // 账号徽章：c6b=Lv.1 等级容器（iue/iug），ivf=「勋章」胶囊
    UiRule(Settings.KEY_UI_MINE_MEDAL, "我的页等级勋章",
            listOf(), listOf(), idNames = listOf("c6b", "ivf")),
        // 我的页右上角搜索图标（Round 26，用户要求移除）：apg 是我的页顶栏
        // 四图标容器（dg0/f5i/c67/f5j = 免|搜索|信息中心|设置），只摘 f5i。
        // ⚠️ 用**父容器 ID 门禁**而非页面门禁：父容器在挂载瞬间就确定 → 绘制
        // 前即时预隐藏，不会像 requireMinePage 那样翻页时先闪一下再藏。
    UiRule(Settings.KEY_UI_MINE_SEARCH, "我的页搜索图标",
            listOf(), listOf(), idNames = listOf("f5i"),
            parentIdNames = listOf("apg")),
        // 我的页右上角「信息中心/设置」图标的红点（Round 46，真机 dump 实锤）：
        // 信息中心 c67 的红点 = 无 ID FrameLayout → c6_(红点底 ImageView)+ip9(数字
        // TextView)；设置 f5j 的红点 = f5k(15x15 小圆点)。只藏叶子，图标本体保留。
        // ⚠️ 必须带 apg **祖先**门禁（新 ancestorIdNames）：c6_/ip9/f5k 是通用混淆
        // ID（红点数字/小圆点随处可能复用），且直接父容器都无 ID（父门禁够不着）——
        // 无门禁会全局误伤。
    UiRule(Settings.KEY_UI_MINE_BADGE, "我的页顶栏红点",
            listOf(), listOf(), idNames = listOf("c6_", "ip9", "f5k"),
            ancestorIdNames = listOf("apg")),
        // ⚠️ R87：底栏有**两套**角标容器（都在 kg 导航栏布局 res/z/ae5.xml，
        // 5 个 id 均只此一处使用、零复用）：
        //   at6 = 红点/数字角标（at8 红点图 + at5 数字）
        //   at7 = **文字标签角标**（at4 带红底 drawable + padding 3dp）← 「即将过期」
        // 原先只列 at6 ⇒ 「即将过期」这类文字标签整块裸露（用户实锤：
        // 胶囊右上角红色「即将过期」，07:49 首启截图 g10 实证）。
        // at7 在 XML 里默认 GONE、由代码决定 visible，只在"要弹推广角标"时才出现。
)

package com.bldf1.kugoulite.plus

/**
 * UI 净化规则（数据定义）。
 *
 * 从 UiCleaner 中拆出：22 个字段的语义与注释是长期真机调试的沉淀，原样保留。
 * UiCleaner 只负责「按规则扫描并隐藏」，规则数据集中在本包各 *Rules 文件中。
 */
internal class UiRule(
    val key: String, val name: String,
    val classKeywords: List<String>, val textKeywords: List<String>,
    val region: FloatArray? = null,
    val minWRatio: Float = 0f,
    val minYRatio: Float = 0f,
    val maxYRatio: Float = 0f,
    val idNames: List<String> = listOf(),
    val ancestorClass: String? = null,
    val hideParentLevels: Int = 0,
    val sectionMode: Boolean = false,
    val useCardHide: Boolean = false,
    val excludeIdNames: List<String> = listOf(),
    // 文本排除门禁：命中这些词就整条规则跳过。
    // 真机实锤（设置页两处空白）：搜索推广规则按"广告/推广"匹配，把隐私设置里
    // 「定向广告推送」「互动广告管理」这两个开关的标题也藏了，只剩开关没有文字。
    val excludeTextKeywords: List<String> = listOf(),
    // Activity 名门禁（治本）：只在该类名包含指定片段的 Activity 里生效。
    // 「搜索推广」就用它 —— 这条规则本该只管搜索页，之前靠"广告/推广"短词
    // 全 app 命中，把设置页三行标题都藏了（真机实锤）。
    val requireActivityName: String? = null,
    // ⭐ R168「规则页面归属」：本规则**只在**这些页面生效。
    // 空集合 = 所有页面（向后兼容，现有规则不标注即保持原行为）。
    // 页面 id 见 computePageId()：search / player / discovery / main /
    // fav / recent / profile …
    // 目的：把"全 app 跑一遍再靠门禁排除"改成"页面 → 规则集合"，
    // 无规则的页面连判定都不做（性能 + 防误伤）。
    val pageIds: Set<String> = emptySet(),
    // 类名排除门禁（真机实锤 15:0x）：同一资源 ID 在首页顶栏与我的页是
    // 完全不同的控件——「我的页赚钱入口」(dk_) 的祖先判定
    // TwoViewSwitchLayout 在首页顶栏同样成立，会把顶栏"乐库"入口一起
    // 隐藏（用户实锤：顶栏只剩"发现"）。按类名排除顶栏图标控件。
    val excludeClassNames: List<String> = listOf(),
    // 高度塌缩隐藏：GONE + layoutParams.height=0 + savedHeights
    // （我的页白卡内的整行推广条用——只 GONE 会留 180px 白洞）
    val collapseHeight: Boolean = false,
    // 兄弟 ID 门禁：同父容器内必须存在该 ID 的兄弟节点才命中
    // （赚钱 tab dk_ 与「我的」tab dk5 成对出现，凭此与其它页面同名
    // 顶栏图标区分，替代一刀切的 excludeClassNames）
    val requireSiblingIdName: String? = null,
    // 页面语义门禁（真机实锤 Round 19）：同一资源 ID 在主 pager 两页
    // 复用——页 0(发现) 时 dk_ 是「乐库」入口，页 1(我的) 时才是
    // 「赚钱」入口。结构判定不可靠（uiautomator 省略中间节点），
    // 必须按"我的页是否在屏幕上"判定（见 minePageOnScreen）
    val requireMinePage: Boolean = false,
    /**
     * ⭐ R85b「alpha 保险」开关（**按规则选择加入**，默认关）。
     *
     * 仅用于"宿主会把已隐藏视图**复活**、其它手段都拦不住"的规则（目前只有「播放页书签」）。
     * 置 true 后隐藏时会把 `alpha` 同时置 0 —— 宿主复活只改 visibility、碰不到 alpha，
     * 所以即使被复活也是全透明。
     *
     * ⚠️ 为什么默认关、且必须**按规则**开：置过 alpha 的视图在**每一条还原路径**上都要还原
     * （全仓有 8 处 `hiddenByUs.remove`），漏一处该视图就会**永久透明**。
     * 把范围收到单条规则，风险面才可控。（还原统一走 `restoreAlphaShield()` helper）
     */
    val alphaShield: Boolean = false,
    // ⚠️ 爬升门禁（真机实锤 Round 26，用户报「我的页右上角 搜索/信息中心/
    // 设置 图标被误伤」）：hideParentLevels="连父容器一起藏" 只有在父容器
    // **确属本规则的专属底板**时才成立。混淆 ID 跨页复用时父容器可能是
    // 完全无关的功能容器——dg0(LottieAnimationView 免费听动画) 在发现页的
    // 父是 dg1(HnVisualEffectLayout，免费听图标专属胶囊底板，连父藏正确)，
    // 在我的页的父却是 apg(LinearLayout 顶栏图标容器，内含 dg0/f5i/c67/f5j
    // 四个图标，即「免|搜索|信息中心|设置」) → 无条件爬升 = 把整个顶栏
    // 图标区带走（日志实锤 `UI净化[免费听图标] 隐藏: LinearLayout`；
    // 关掉 ui_free_listen 后三图标全部回来）。
    // 列入此表后：直接父 ID 命中 → 按 hideParentLevels 爬升；否则退化为只
    // 藏自身（levels=0），绝不牵连同层兄弟。
    val climbParentIdNames: List<String> = listOf(),
    // ⭐ 父容器 ID 门禁（Round 26）：**直接父容器**的 resourceName 必须命中
    // 白名单才算命中。比 requireMinePage 稳定——挂载瞬间父容器就已确定，
    // 可在**绘制之前**即时预隐藏（零闪现）；而页面语义门禁在挂载期仍反映
    // 旧页，会退化成"先绘制后隐藏"（Task #26 抖动）。父缺失/无 ID=不命中。
    val parentIdNames: List<String> = listOf(),
    // ⭐ 祖先 ID 门禁（Round 46）：祖先链（限深 12）任一级 resourceName 命中
    // 白名单才算命中。用于目标视图本身带通用混淆 ID（如红点数字 ip9）、
    // 但其**直接父容器无 ID** 的场景（父容器门禁够不着）——例如我的页顶栏
    // apg 内信息中心红点（c6_/ip9 挂在无 ID 的 FrameLayout 下）。
    val ancestorIdNames: List<String> = listOf()
)

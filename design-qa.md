# 自動分類規則 UI Design QA

## 比對目標與證據

- Source visual truth: `/Users/zhangzhenlong/.codex/generated_images/019fb325-0e44-7a92-b38a-05aa324c1fee/call_meqFQTTM6oCDLmyWmnFjpluD.png`
- Implementation screenshot: `/tmp/moneylook-rules-qa-SGHXes/final-v2.png`
- Final side-by-side comparison: `/tmp/moneylook-rules-qa-SGHXes/comparison-v2.png`
- Source pixels: `852 x 1846`（視為約 `426 x 923 @2x`）
- Implementation pixels: `1080 x 2400`；ASUS I002D 實機為 `1080 x 2400 @420 dpi`、font scale `1.0`，約 `411 x 914 dp`
- Density normalization: 移除實機 status/navigation bar 後，以來源畫布 `852 x 1846` 正規化；比對圖左右各 `852 x 1846`
- State: 深色主題、依分類、未搜尋、未篩選、第一個分類展開

來源 mock 使用示意分類、122 筆規則且展開兩組；實作使用實機既有 121 筆資料，並依已確認產品行為只預設展開第一組。這些是資料與需求差異，不列為視覺缺陷。

## Findings

最終比對沒有仍待修正的 P0、P1 或 P2。

- Fonts and typography: Android 系統中文字型與 Material 3 typography hierarchy 清楚；標題、群組標題、規則名稱、摘要與輔助文字層級一致，沒有截斷主要操作文字。
- Spacing and layout rhythm: 搜尋、chips、計數、群組卡片與 Extended FAB 在約 411 dp 寬度沒有重疊或裁切；卡片、列高、分隔線與觸控區符合 Material 3 節奏。
- Colors and tokens: 深色背景、neutral container、primary switches、selected/unselected chips 與分類色圓形圖示均使用 Material theme token；對比可讀。
- Image quality and asset fidelity: 畫面沒有照片、插圖或品牌 raster asset；所有圖示均使用 Material Icons，沒有 emoji、手繪 SVG 或 placeholder 圖形。
- Copy and content: 搜尋提示涵蓋名稱、條件與分類；「依分類／依來源／停用」、規則計數、空結果及 IF/THEN 詳情文案與確認需求一致。
- Interaction and accessibility: 搜尋、分組切換、停用篩選、展開／收合、Switch、overflow、詳情 bottom sheet 與 Extended FAB 均有語意標籤或標準 Material 控件觸控區。

## Focused Region Comparison

不另建局部裁切：`comparison-v2.png` 以原始 `852 x 1846` 高度並排，搜尋列、chips、群組 header、規則列、switch、overflow 與 FAB 均可直接辨識，沒有需要放大才能判斷的品牌字標或細節資產。

## Comparison History

### Iteration 1

- Evidence: `/tmp/moneylook-rules-qa-SGHXes/rules.png` 與 `/tmp/moneylook-rules-qa-SGHXes/comparison.png`
- [P2] 搜尋框使用 outlined treatment，而來源是 filled search surface。
- [P2] 分類卡整片套用高彩度分類色，來源以 neutral surface 搭配局部分類色。
- [P2] 分類與篩選圖示語意未充分貼近來源。
- Fixes:
  - 搜尋改為無 indicator 的 Material 3 filled `TextField`。
  - 群組卡改用 `surfaceContainerHigh`，分類色集中在圓形 icon container 與列側色條。
  - 「依分類／依來源／停用」改用標籤、帳戶來源與禁止圖示；交通群組使用水平交換圖示。

### Iteration 2

- Evidence: `/tmp/moneylook-rules-qa-SGHXes/final-v2.png` 與 `/tmp/moneylook-rules-qa-SGHXes/comparison-v2.png`
- Post-fix result: 搜尋 surface、neutral card、分類色、icon semantics、switch、overflow 與 FAB 的階層已對齊方案 3；沒有剩餘 P0/P1/P2。
- P3 follow-up polish: 若未來建立正式分類 icon 對照表，可讓每個分類都有專屬語意圖示；目前未對應分類使用 Material `Category`，不影響可讀性或操作。

## Runtime Verification

- ASUS 實機 12 個 Compose interaction tests 全數通過。
- 實際 App 已驗證：兩組同時展開、規則詳情、搜尋 YouBike、切換依來源、疊加停用空結果。
- ASUS 橫向寬螢幕已驗證 list-detail：未選取時顯示空白詳情提示，選取規則後在右側顯示 IF/THEN 詳情，未誤開 bottom sheet。
- force-stop / relaunch 後搜尋與篩選回到預設值，第一組展開，沒有 crash；logcat 未見本 App fatal exception。

## Implementation Checklist

- [x] Filled Material 3 search
- [x] 依分類／依來源互斥與停用 additive filter
- [x] 多組展開、每組先顯示五項
- [x] 規則詳情與不可編輯說明
- [x] Material icons、switch、overflow、Extended FAB
- [x] 手機與 ASUS 橫向寬螢幕 list-detail
- [x] 實機視覺與互動驗證

final result: passed

# 网络请求

在侧边栏底部卡片中，浏览器和设置之间有「Network Request」入口，用来手动发送 HTTP 请求并查看响应。

## 打开页面

- **手机**：点击入口后全屏打开，按返回键回到聊天。
- **平板**：同样从侧边栏进入独立页面，顶栏左侧返回，右侧三点菜单可清空当前请求、导入 / 导出 cURL，或打开历史。

## 发送请求

页面底部是悬浮芯片导航，可在 Request 和 Response 之间滑动切换。Request 页本身可以整页滚动，弹出键盘时编辑区会滑到悬浮底栏后面，不会被挤扁。

Request 页从上到下：

1. 输入 URL。没有写 `http://` 或 `https://` 时，发送时会自动补上 `https://`。
2. 横向选择方法：GET、POST、PUT、PATCH、DELETE、HEAD、OPTIONS。
3. 点击瘦高的 Send 按钮发送。发送中按钮会显示进度圈。
4. Send 按钮下方是 Query、Headers、Body、Authorization、Settings 五个标签。

Query、Headers、Body、Authorization 都可以在 JSON、Tree、Tabled Input 三种视图之间切换：

- **JSON**：用和文件编辑器相同的代码编辑器，可直接改 JSON，带语法高亮。
- **Tree**：只读的细字号嵌套树，用来快速查看结构。非法 JSON 会提示无法解析。
- **Tabled Input**：两列表格。左侧勾选框决定这一行会不会写进请求 JSON；中间是 Key，右侧是 Value；行末减号删除该行。表格下方有「Add row」按钮可继续加行。取消勾选的行不会出现在 JSON 里，切回 JSON 视图时也能看到同样的结果。

Settings 里可以开关跟随重定向、校验证书，以及设置超时秒数。

发送成功后会自动滑到 Response 页。失败时 Request 页会显示错误信息。每次发送都会在应用主目录写下一条历史 JSON，终端里也能看到这些文件。

## 查看响应

Response 页顶部是标签栏（没有关闭按钮）：

- **Headers (数量)**：响应头。默认用表格查看，可切换成 JSON。
- **Body**：响应正文。默认格式化为带语法高亮的 JSON，可切换成表格。JSON 视图会拉高到悬浮底栏后面，右上角有复制按钮。
- **Metrics**：请求 ID、状态码、协议、耗时、DNS / 连接 / TLS 时间、请求与响应大小等。这一页没有视图切换。

点标签下方的「Table」「JSON」或「Tabled Input」文字会弹出底栏，选择当前标签的展示方式。表格和 JSON 都放在同一套圆角卡片里。体积很大的响应会截断展示，避免卡住页面。

## 导入和导出 cURL

点顶栏右侧三点菜单：

- **Import cURL**：弹出接近全屏的底栏，标题是 Import cURL。把命令贴进编辑器后点悬浮按钮，会填好方法、URL、Query、Headers、Body 和 Authorization。
- **Export cURL**：同样的全屏底栏，只读显示当前请求对应的 cURL。点 Export cURL 会复制到剪贴板。

解析失败时会留在底栏并提示无法解析。

## 历史记录

点顶栏右侧三点菜单里的 History，会弹出底栏列出本次及以往保存的请求。当前载入的那一条右侧有对勾，点任意一条会把 URL、方法、Query、Headers、Body、Authorization 和 Settings 填回去。

这些历史文件保存在应用主目录的 `network-history` 下，容器终端里对应 `~/.aicode/network-history/`。也可以到「设置 → 存储空间」展开「AI 配置与产物」，勾选「Network request history」清理。
